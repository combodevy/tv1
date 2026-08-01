package com.example.cctvofficialnavigator;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.net.URI;
import java.util.Locale;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * A remote-first navigator for official CCTV pages. It deliberately has no stream extraction code.
 *
 * Fullscreen strategy (verified against tv.cctv.com/live/cctv* on 2026-07-31, 2nd iteration):
 *  CCTV 的"网页全屏"按钮 click() 在系统 WebView + Android TV 上不稳定:
 *  - 有些频道 click 一次就生效(CCTV-1, CCTV-9)
 *  - 有些频道 click 一次不生效(CCTV-5, CCTV-5+),页面不切布局,左右白边,顶部"体育频道直播"标题还在
 *  - 有些频道根本没有 video 元素(频道下线了)
 *
 *  解决:不依赖 click(),直接用 CSS 强制把 #player 容器和 video 元素拉成 100vw/100vh,
 *  并隐藏所有装饰元素(.video_right 频道列表 / .bg_top_h_tile 顶部条 / .vspace / .column_wrapper 等)。
 *  FastLoading 每 200ms 跑一次,持续 30 秒,即使视频元素已出现,装饰元素也持续清空。
 *  AutoFullscreen 每 300ms 跑一次,持续 8 秒,持续把 video 拉成 position:fixed 全屏。
 *  白屏(8 秒后还没看到 video)→ 自动跳下一个频道,不等用户手动按。
 */
public final class MainActivity extends Activity {
    private static final String SAVED_CHANNEL_INDEX = "channel_index";
    private static final long CHANNEL_HINT_DURATION_MS = 1800L;
    private static final long WHITE_SCREEN_CHECK_DELAY_MS = 15000L;
    // 桌面 UA:仅用于 CCTV-3/6/8。版权敏感频道在移动端 UA 下会被直接 302 重定向到
    // https://m.yangshipin.cn/static/empty.html(刻意空白页,引导用户装央视频 APP)。
    // 其他频道用系统默认移动 UA(桌面 UA 会让 CCTV-9 等频道黑屏无法播放)。
    private static final String DESKTOP_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36";

    private WebView webView;
    private TextView channelHint;
    private TextView debugPanel;
    private FrameLayout rootContainer;
    private int channelIndex;
    private final Runnable hideChannelHint = () -> channelHint.setVisibility(View.GONE);
    private final Handler handler = new Handler(Looper.getMainLooper());
    // 倒计时线程:用 background thread 跑,避免被 WebView 加载/JS 阻塞 main thread 导致 postDelayed 永不执行
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
    // 拦截到的 m3u8 URL(从 shouldInterceptRequest 捕获,用于 hls.js 兜底播放)
    private volatile String capturedM3u8Url;
    // hls.js 是否已注入(避免重复注入)
    private volatile boolean hlsPlayerInjected;
    private java.util.List<ScheduledFuture<?>> pendingChecks = new java.util.ArrayList<>();
    private int loadGeneration = 0;
    // HTML5 全屏自定义视图:WebView 进入全屏时(video.webkitRequestFullscreen)会传入一个包含 SurfaceView 的 View,
    // 把它放到 rootContainer 顶层全屏显示,可解决 CSS 硬拉 video 导致的"有声音没画面"问题。
    // 注意:MuMu(x86)模拟器无硬件H.264解码器,此方案在MuMu上同样无画面;真实ARM电视盒子正常。
    private View customFullscreenView;
    private WebChromeClient.CustomViewCallback customFullscreenCallback;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        Window window = getWindow();
        window.setStatusBarColor(Color.TRANSPARENT);
        window.setNavigationBarColor(Color.TRANSPARENT);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            WindowManager.LayoutParams lp = window.getAttributes();
            lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
            window.setAttributes(lp);
        }

        setContentView(R.layout.activity_main);
        webView = findViewById(R.id.live_web_view);
        channelHint = findViewById(R.id.channel_hint);
        debugPanel = findViewById(R.id.debug_panel);
        rootContainer = findViewById(R.id.root_container);
        webView.setBackgroundColor(Color.BLACK);
        channelIndex = savedInstanceState == null ? 0 : savedInstanceState.getInt(SAVED_CHANNEL_INDEX, 0);
        configureWebView();
        enterImmersiveMode();
        loadChannel(channelIndex);
    }

    @SuppressLint("SetJavaScriptEnabled")
    private void configureWebView() {
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        // 注意:这里把 UA 设为默认值。CCTV-3/6/8 这三个频道在 loadChannel 里会临时切成 DESKTOP_UA,
        // 其他频道保持默认移动 UA。不能全频道统一桌面 UA(会导致 CCTV-9 等频道黑屏无法播放)。
        settings.setUserAgentString(null);
        settings.setLoadsImagesAutomatically(true);
        settings.setBlockNetworkImage(false);
        settings.setMediaPlaybackRequiresUserGesture(false);
        settings.setLoadWithOverviewMode(true);
        settings.setUseWideViewPort(true);

        // CCTV 页面内部有 http/https 混合资源(如某些统计/广告),允许加载避免资源缺失
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            settings.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        }

        // 启用 WebView 硬件加速(默认开启,显式确保);MSE/blob URL 视频需要硬件合成
        // 注意:MuMu 等 x86 模拟器无硬件 H.264 解码器,会导致"有声音没画面",这是模拟器限制,
        // 真实 ARM 电视盒子有硬件解码器,不受影响。
        webView.setLayerType(View.LAYER_TYPE_HARDWARE, null);
        // 允许在 file: 协议下访问内容(某些缓存/本地资源场景需要)
        settings.setAllowFileAccess(true);
        settings.setAllowContentAccess(true);

        // 用自定义的 WebChromeClient 拦截 console 输出和加载进度(CCTV 内部的 JS 报错能反映到 logcat/面板)
        // 同时处理 HTML5 全屏(onShowCustomView),让 video 用 WebView 自己的全屏机制渲染,避免 CSS 硬拉导致黑屏
        webView.setWebChromeClient(new LoggingWebChromeClient(this));
        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                return !isOfficialCctvUrl(request.getUrl().toString());
            }

            @Override
            public void onPageStarted(WebView view, String url, Bitmap favicon) {
                super.onPageStarted(view, url, favicon);
                updateDebugPanel("onPageStarted → " + shortenUrl(url), null);
                // 兜底防御:如果 CCTV 重定向到了移动端空白页(尽管我们已设置桌面 UA),
                // 立刻重新加载当前频道的官方桌面 URL,并附加 User-Agent HTTP 头(强上双保险)。
                // 典型恶意重定向:m.yangshipin.cn/static/empty.html(版权敏感频道 CCTV-3/6/8)
                if (url != null && url.contains("yangshipin.cn/static/empty")) {
                    Channel cur = ChannelCatalog.CHANNELS.get(channelIndex);
                    Log.w("CCTV-TV", "被 CCTV 重定向到 " + url + " → 重新加载桌面版 " + cur.officialUrl);
                    updateDebugPanel("REDIRECT_BLOCKED", "检测到被重定向到 mobile 空页\n正在以桌面 UA 重载:" + shortenUrl(cur.officialUrl));
                    java.util.Map<String, String> headers = new java.util.HashMap<>();
                    headers.put("User-Agent",
                            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36");
                    headers.put("Referer", "https://tv.cctv.com/");
                    view.stopLoading();
                    view.loadUrl(cur.officialUrl, headers);
                    return; // 不要跑下面的 CSS/补丁注入,等重新 load 的 onPageStarted
                }
                // 1) 最早期:document.write polyfill(必须在任何页面 JS 之前注入,否则晚了)
                //    Chromium 53+ 起对"parser-blocking + cross-site + document.write 插入的<script>"
                //    在 2G/慢网下直接不执行(2G Intervention)。CCTV 的播放器启动链里有用
                //    document.write('<script src="https://r.img.cctvpics.com/.../gray*.js">') 加载
                //    公共库(不同 eTLD+1,cctv.com vs cctvpics.com),命中这个规则后脚本被丢弃,
                //    后续 createLivePlayer() 永远不跑→CCTV-3/6/8 白屏。
                //    修复:在最早时机 hook document.write,把 <script src=...> 转成
                //    createElement('script') 异步插入,绕开 Chromium 干预。
                injectDocumentWritePatch(view);
                // 1.5) hook XMLHttpRequest 捕获 m3u8 URL
                //   HLSP2P 在 Web Worker 里用 XHR 请求 m3u8,shouldInterceptRequest 可能拦不到 Worker 请求。
                //   但 VDN API(返回 m3u8 URL 的接口)是从主线程发的,可以 hook 拦截。
                //   VDN 响应 JSON 里 streamUrl 字段包含 m3u8 URL,用正则提取。
                injectM3u8Capture(view);
                // 2) CSS 拉满容器 + 隐藏装饰(顺序必须在补丁之后,不能影响 document.write 覆盖)
                injectFastLoading(view);
                // 3) 立即启动 AutoFullscreen 轮询(不等 onPageFinished,因为 CCTV 页面的
                //    onPageFinished 可能因持续心跳永不触发)。轮询会持续 30 秒,
                //    即使 video 元素还没创建,一旦被 JS 动态插入就能立即拉满全屏。
                injectAutoFullscreen(view);
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                updateDebugPanel("onPageFinished → " + shortenUrl(url), null);
                // 再次注入确保覆盖(onPageStarted 注入的可能因为页面 JS 重写 DOM 而失效)
                injectAutoFullscreen(view);
            }

            // 抓底层资源错误(直接显示到面板,不需要等 evaluateJavascript)
            // 拦截所有网络请求,捕获 m3u8 URL(HLSP2P 在 Web Worker 里发 XHR,
            // JS 层 hook 不到,只能在 Android 层面拦截)
            @Override
            public android.webkit.WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                String url = request.getUrl().toString();
                if (url != null && url.contains(".m3u8") && capturedM3u8Url == null) {
                    capturedM3u8Url = url;
                    Log.i("CCTV-TV", "拦截到 m3u8: " + url);
                }
                return null; // 不拦截,让请求正常发出
            }

            @Override
            public void onReceivedError(WebView view, WebResourceRequest request, android.webkit.WebResourceError error) {
                super.onReceivedError(view, request, error);
                if (request.isForMainFrame()) {
                    String msg = "NET_ERR: " + error.getErrorCode() + " " + error.getDescription()
                            + "\nURL=" + request.getUrl();
                    Log.e("CCTV-TV", msg);
                    updateDebugPanel("MAIN_FRAME_ERROR", msg);
                }
            }

            @Override
            public void onReceivedHttpError(WebView view, WebResourceRequest request, android.webkit.WebResourceResponse errorResponse) {
                super.onReceivedHttpError(view, request, errorResponse);
                String url = request.getUrl().toString();
                int code = errorResponse.getStatusCode();
                // 只把 4xx/5xx 的关键请求显示出来(直播流 API / 播放器 JS)
                boolean key = url.contains("liveplayer") || url.contains("h5_live_index")
                        || url.contains("hls2p") || url.contains("getstream") || url.contains("m3u8")
                        || url.contains("vdn") || url.contains("api/timestamp");
                if (request.isForMainFrame() || (key && code >= 400)) {
                    String msg = "HTTP " + code + " for " + shortenUrl(url);
                    Log.w("CCTV-TV", msg);
                    updateDebugPanel("HTTP_ERROR " + code, msg);
                }
            }
        });
    }

    /**
     * 判断 console error 是否为已知的、不影响播放的错误。
     * 这些错误会被记录到 logcat,但不会覆盖 debug 面板上的白屏诊断信息。
     */
    private static boolean isKnownHarmlessError(String msg) {
        if (msg == null) return true;
        String m = msg.toLowerCase(Locale.ROOT);
        // Aegis 前端监控 SDK 不支持当前域名/路径(腾讯监控库,与播放无关)
        if (m.contains("aegis") && (m.contains("not support") || m.contains("not suport"))) return true;
        // CCTV 自己的 trace 库抛出的非致命异常
        if (m.contains("cctv5-trace") || m.contains("aegis")) return true;
        // 百度统计/广告 SDK 域名错误
        if (m.contains("bdns") || m.contains("bdydns")) return true;
        return false;
    }

    /**
     * onPageStarted 最早期注入:hook document.write,把
     * document.write('<script src="跨站URL">...</script>') 转成
     * document.createElement('script') 异步插入。
     *
     * 原因:Chromium 53+ 的"2G Intervention"会在慢网/2G时直接丢弃
     * "parser-blocking + cross-site + document.write 插入"的脚本,
     * cctv.com 页面会通过 document.write 从 r.img.cctvpics.com(不同 eTLD+1)
     * 加载 gray*.js / DEPA 公共脚本,被丢弃后 createLivePlayer() 不执行,
     * 导致 CCTV-3/6/8 白屏(此问题只在"页面自己通过 document.write 注入跨站脚本"时出现,
     * CCTV-1/9 的启动顺序不同,侥幸没被拦)。
     */
    private void injectDocumentWritePatch(WebView view) {
        String js =
                "(function(){" +
                "  if(window.__cctvDwPatched)return;" +
                "  window.__cctvDwPatched=true;" +
                // 关键:不再调用原始 document.write/writeln。
                // 异步加载的外部脚本调用 document.write 时,原始 write 会抛出
                // "It isn't possible to write into a document from an asynchronously-loaded
                //  external script unless it is explicitly opened",导致后续播放器初始化 JS 中断。
                // 我们只做一件事:把 write 字符串里的 <script> 标签提取出来,用 createElement 异步插入,
                // 其余 HTML/文本内容在 document 已关闭时无法也不应再写入,直接丢弃。
                "  function extractScripts(html){" +
                "    if(!html||typeof html!=='string')return [];" +
                "    var out=[];" +
                "    var re=/<script([^>]*)>([\\s\\S]*?)<\\/script>/gi;" +
                "    var m;" +
                "    while((m=re.exec(html))!==null){" +
                "      var attrs=m[1]||'';" +
                "      var body=m[2]||'';" +
                "      var srcM=attrs.match(/src\\s*=\\s*['\\\"]([^'\\\"]+)['\\\"]/i);" +
                "      var asyncM=attrs.match(/async/i);" +
                "      var deferM=attrs.match(/defer/i);" +
                "      out.push({src:srcM?srcM[1]:'',inline:body,async:!!asyncM,defer:!!deferM});" +
                "    }" +
                "    return out;" +
                "  }" +
                "  function insertScript(s){" +
                "    try{" +
                "      var el=document.createElement('script');" +
                "      if(s.src){el.src=s.src;el.async=s.async||s.defer||true;}" +
                "      else if(s.inline){el.text=s.inline;}" +
                "      else{return;}" +
                "      var p=document.currentScript&&document.currentScript.parentNode;" +
                "      p=p||document.head||document.documentElement;" +
                "      p.appendChild(el);" +
                "    }catch(e){console.log('[CCTV-DW] insert err: '+e.message);}" +
                "  }" +
                "  function handleWrite(htmlStr){" +
                "    var scripts=extractScripts(htmlStr);" +
                "    for(var i=0;i<scripts.length;i++){insertScript(scripts[i]);}" +
                "  }" +
                "  document.write=function(){var s='';for(var i=0;i<arguments.length;i++)s+=arguments[i];handleWrite(s);};" +
                "  document.writeln=function(){var s='';for(var i=0;i<arguments.length;i++)s+=arguments[i];s+='\\n';handleWrite(s);};" +
                "})()";
        view.evaluateJavascript(js, null);
    }

    /**
     * hook XMLHttpRequest,捕获 m3u8 URL。
     * HLSP2P 的 m3u8 请求在 Web Worker 里发(shouldInterceptRequest 可能拦不到),
     * 但 VDN API(返回 m3u8 URL 的接口)是从主线程发的 XHR。
     * VDN 响应 JSON 的 streamUrl 字段包含 m3u8 URL,用正则提取存到 window.__cctvM3u8Url。
     * 白屏检测时如果 shouldInterceptRequest 没拦到,会从 window.__cctvM3u8Url 获取。
     */
    private void injectM3u8Capture(WebView view) {
        String js =
                "(function(){" +
                "  if(window.__cctvM3u8Hook)return;" +
                "  window.__cctvM3u8Hook=true;" +
                "  window.__cctvM3u8Url=null;" +
                "  var origOpen=XMLHttpRequest.prototype.open;" +
                "  var origSend=XMLHttpRequest.prototype.send;" +
                "  XMLHttpRequest.prototype.open=function(method,url){" +
                "    this.__cctvReqUrl=url||'';" +
                "    return origOpen.apply(this,arguments);" +
                "  };" +
                "  XMLHttpRequest.prototype.send=function(){" +
                "    var self=this;" +
                "    var reqUrl=self.__cctvReqUrl||'';" +
                // 直接拦截 m3u8 请求(主线程发的)
                "    if(reqUrl.indexOf('.m3u8')>=0&&!window.__cctvM3u8Url){" +
                "      window.__cctvM3u8Url=reqUrl;" +
                "      console.log('[CCTV-M3U8] captured from XHR: '+reqUrl);" +
                "    }" +
                // 拦截 VDN API 响应,从 JSON 中提取 m3u8 URL
                "    if(reqUrl.indexOf('vdn/live')>=0||reqUrl.indexOf('getstream')>=0){" +
                "      var origRSC=self.onreadystatechange;" +
                "      self.onreadystatechange=function(){" +
                "        if(self.readyState===4&&self.responseText&&!window.__cctvM3u8Url){" +
                "          try{" +
                "            var m=self.responseText.match(/https?:\\/\\/[^\\s\"'<>]+\\.m3u8[^\\s\"'<>]*/);" +
                "            if(m){" +
                "              window.__cctvM3u8Url=m[0];" +
                "              console.log('[CCTV-M3U8] captured from VDN API: '+m[0]);" +
                "            }" +
                "          }catch(e){}" +
                "        }" +
                "        if(origRSC)return origRSC.apply(self,arguments);" +
                "      };" +
                "    }" +
                "    return origSend.apply(this,arguments);" +
                "  };" +
                // 也 hook fetch(部分新版播放器可能用 fetch 而非 XHR)
                "  if(window.fetch){" +
                "    var origFetch=window.fetch;" +
                "    window.fetch=function(input,init){" +
                "      var url=typeof input==='string'?input:(input&&input.url||'');" +
                "      if(url.indexOf('.m3u8')>=0&&!window.__cctvM3u8Url){" +
                "        window.__cctvM3u8Url=url;" +
                "        console.log('[CCTV-M3U8] captured from fetch: '+url);" +
                "      }" +
                "      return origFetch.apply(this,arguments);" +
                "    };" +
                "  }" +
                "})()";
        view.evaluateJavascript(js, null);
    }

    /**
     * 页面一开始加载就注入 FastLoading(每 200ms 跑一次):
     *  1. 注入强力 CSS,强制让播放器容器占满 100vw/100vh。兼容 3 种布局:
     *     - 移动端默认布局(除 CCTV-3/6/8): .video_left / .video_flash / #player / .video_box
     *     - 桌面版布局(CCTV-3/6/8 用桌面 UA): .video_left / .video_flash / #player + iframe[youtube/yangshipin 嵌套]
     *     - 兜底: 任何 div 下的 <video>、#player_container、.video_box 新布局
     *  2. 隐藏所有非播放器装饰元素(顶部条/底部版权/右侧频道列表/广告等)
     *  3. 不依赖 click "网页全屏"按钮(在某些频道上不可靠)
     *  4. 不删任何脚本:之前"删脚本"曾多次误删 h5_live_index.js/liveplayer.js 导致播放器不初始化(CCTV-3/6/8 白屏根因)
     *  5. 保留图片正常加载(CCTV 某些频道的播放器依赖图片 onload 触发 video 元素插入)
     */
    private void injectFastLoading(WebView view) {
        String js =
                "(function(){" +
                "  if(window.__cctvFastLoadingInjected)return;" +
                "  window.__cctvFastLoadingInjected=true;" +
                // CSS: 强力覆盖, 同时兼容 移动/桌面 两种布局, iframe 嵌套播放器也要拉满
                "  var css=" +
                "    'html,body{width:100%!important;height:100%!important;margin:0!important;padding:0!important;background:#000!important;overflow:hidden!important}'+" +
                "    '.jiemuguanwang18950_zhibo_ind01,.zhibo19629_ind01,.playingVideo{width:100vw!important;height:100vh!important;margin:0!important;padding:0!important;position:absolute!important;left:0!important;top:0!important}'+" +
                // 容器层: 所有常见的 CCTV 播放器容器 id/class + iframe 内嵌播放器
                "    '.video_left,.video_right_main,.video_flash,.video_box,#player,#player_container,#live_player{width:100vw!important;height:100vh!important;margin:0!important;padding:0!important;position:absolute!important;left:0!important;top:0!important;background:#000!important;border:0!important}'+" +
                // iframe: 全部隐藏。CCTV 页面的 iframe 是广告(yangshipin.cn)而非播放器,
                // 之前把所有 iframe 拉成 100vw/100vh + z-index:99999 会盖住 video 导致黑屏。
                // video 元素(#h5player_player)是 JS 直接创建在主文档里的,不在 iframe 内。
                "    'iframe{display:none!important}'+" +
                // video 元素: 固定全屏 + 最高 z-index,确保在所有元素之上
                // 加 transform/translateZ 强制触发 GPU 合成层,修复某些 WebView 上有声无画问题
                "    'video{position:fixed!important;display:block!important;visibility:visible!important;opacity:1!important;width:100vw!important;height:100vh!important;min-width:100vw!important;min-height:100vh!important;left:0!important;top:0!important;z-index:999999!important;object-fit:contain!important;background:#000!important;transform:translateZ(0)!important;backface-visibility:hidden!important}'+" +
                // #h5player_player 是 CCTV 播放器创建的 video 元素 ID
                "    '#h5player_player{position:fixed!important;display:block!important;visibility:visible!important;opacity:1!important;width:100vw!important;height:100vh!important;min-width:100vw!important;min-height:100vh!important;left:0!important;top:0!important;z-index:999999!important;object-fit:contain!important;background:#000!important;transform:translateZ(0)!important;backface-visibility:hidden!important}'+" +
                // 播放器容器: 确保尺寸不为 0,overflow 不裁剪 video
                "    '#player,#player_container,.video_box,.video_flash,.video_left{overflow:visible!important;width:100vw!important;height:100vh!important}'+" +
                // 装饰元素: 隐藏 (桌面版的顶部 CCTV 大导航栏也必须隐藏)
                "    '.video_right,.video_btnBar,.bg_top_h_tile,.bg_top_owner,.bg_bottom_h_tile,header,footer,nav,.vspace,.column_wrapper,.nav,.topbar,.sitemap,.shares{display:none!important}';" +
                "  function applyCss(){" +
                "    if(document.getElementById('cctv-tv-style'))return;" +
                "    var s=document.createElement('style');" +
                "    s.id='cctv-tv-style';" +
                "    s.textContent=css;" +
                "    (document.head||document.documentElement).appendChild(s);" +
                "  }" +
                "  function FastLoading(){" +
                "    applyCss();" +
                "    if(window.__cctvFlStart===undefined)window.__cctvFlStart=Date.now();" +
                "    if(Date.now()-window.__cctvFlStart<30000)setTimeout(FastLoading,200);" +
                "  }" +
                "  if(document.readyState==='complete'||document.readyState==='interactive'){" +
                "    FastLoading();" +
                "  }else{" +
                "    document.addEventListener('DOMContentLoaded',FastLoading);" +
                "  }" +
                "})()";
        view.evaluateJavascript(js, null);
    }

    /**
     * 页面加载完注入 AutoFullscreen:
     *  不再依赖 click "网页全屏" 按钮(在某些频道上 CCTV 自己的切换是异步的,点一次不一定生效)。
     *  直接用 CSS 把 video 元素拉成 position:fixed 100vw/100vh,完全占满屏幕。
     *  同时设音量为 1,尝试调用 play() 自动播放。
     */
    private void injectAutoFullscreen(WebView view) {
        String js =
                "(function(){" +
                "  function ForceFullscreen(){" +
                // 优先用 #h5player_player(CCTV 播放器创建的 video 元素 ID),兜底用 video 标签
                "    var v=document.getElementById('h5player_player')||document.querySelector('video');" +
                "    if(v){" +
                "      try{v.volume=1;}catch(e){}" +
                // 自动播放策略修复:CCTV 的 HLSP2P 播放器创建了 video 元素并加载了流(960x540),
                // 但因 muted=false + 自动播放策略,video.play() 被 reject,导致 paused=true → 黑屏。
                // 修复:先 muted=true 触发 play(),播放成功后延迟 2 秒取消 muted 恢复声音。
                // 用 __cctvAutoplayStarted 防止重复触发。
                "      try{" +
                "      if(v.paused&&!v.__cctvAutoplayStarted){" +
                "        v.__cctvAutoplayStarted=true;" +
                "        v.muted=true;" +
                "        var p=v.play();" +
                "        if(p&&p.then){" +
                "          p.then(function(){" +
                "            setTimeout(function(){v.muted=false;},2000);" +
                "            try{v.webkitRequestFullscreen();}catch(e){}" +
                "          }).catch(function(e){v.__cctvAutoplayStarted=false;});" +
                "        }else{" +
                "          setTimeout(function(){v.muted=false;},2000);" +
                "          try{v.webkitRequestFullscreen();}catch(e){}" +
                "        }" +
                "      }" +
                "    }catch(e){}" +
                "    try{if(v.webkitRequestFullscreen&&!v.__cctvFsRequested){v.__cctvFsRequested=true;v.webkitRequestFullscreen();}}catch(e){}" +
                "    v.style.position='fixed';" +
                "      v.style.display='block';" +
                "      v.style.visibility='visible';" +
                "      v.style.opacity='1';" +
                "      v.style.left='0';" +
                "      v.style.top='0';" +
                "      v.style.width='100vw';" +
                "      v.style.height='100vh';" +
                "      v.style.minWidth='100vw';" +
                "      v.style.minHeight='100vh';" +
                "      v.style.zIndex='999999';" +
                "      v.style.objectFit='contain';" +
                "      v.style.background='#000';" +
                "    }" +
                "    var p=document.getElementById('player');" +
                "    if(p){" +
                "      p.style.position='fixed';" +
                "      p.style.left='0';" +
                "      p.style.top='0';" +
                "      p.style.width='100vw';" +
                "      p.style.height='100vh';" +
                "      p.style.zIndex='999998';" +
                "      p.style.background='#000';" +
                "    }" +
                // 隐藏所有 iframe(广告等),确保不盖住 video
                "    var ifs=document.querySelectorAll('iframe');" +
                "    for(var i=0;i<ifs.length;i++){ifs[i].style.display='none';}" +
                // 修复父容器可能裁剪/遮挡 video: 向上遍历所有父元素,强制 overflow:visible 且尺寸不为 0
                "    if(v){" +
                "      var node=v.parentNode;" +
                "      while(node&&node!==document.body&&node!==document.documentElement){" +
                "        node.style.overflow='visible';" +
                "        node.style.width='100vw';" +
                "        node.style.height='100vh';" +
                "        node=node.parentNode;" +
                "      }" +
                "    }" +
                // 隐藏所有可能盖住 video 的 z-index 极高的兄弟/遮罩层(广告弹窗等)
                "    var all=document.querySelectorAll('*');" +
                "    for(var i=0;i<all.length;i++){" +
                "      var el=all[i];" +
                "      if(el===v||el.contains(v)||v.contains(el))continue;" +
                "      var rect=el.getBoundingClientRect();" +
                "      if(rect.width>0&&rect.height>0){" +
                "        var z=parseInt(window.getComputedStyle(el).zIndex)||0;" +
                "        if(z>=999990){el.style.display='none';}" +
                "      }" +
                "    }" +
                "  }" +
                "  ForceFullscreen();" +
                "  var count=0;" +
                "  function loop(){" +
                "    ForceFullscreen();" +
                "    count++;" +
                // 100 次 x 300ms = 30 秒,匹配 FastLoading 的持续时间
                "    if(count<100)setTimeout(loop,300);" +
                "  }" +
                "  setTimeout(loop,300);" +
                "})()";
        view.evaluateJavascript(js, null);
    }

    /**
     * hls.js 兜底播放器:当 CCTV 自带的 HLSP2P 播放器在 WebView 上无法播放时
     * (WebRTC/P2P 不支持、WASM 解码失败、DRM license 获取失败等),
     * 用开源 hls.js 库直接播放拦截到的 m3u8 URL。
     * hls.js 纯 MSE 实现,不依赖 WebRTC/WASM/DRM,兼容性最好。
     *
     * 关键修复:CCTV-3/6/8 的 .ts 流解析出的视频 codec 是 avc1.64011f
     * (H.264 High profile + constraint_set1_flag),MediaSource.addSourceBuffer
     * 拒绝这个 codec 字符串。通过 hook addSourceBuffer,把 avc1.64XXXX 替换成
     * avc1.640028(已知被广泛支持的 codec),只影响 SourceBuffer 类型声明,不影响实际解码。
     * 验证:桌面 Chrome 上替换后 hls.js 成功播放 CCTV-3 m3u8 流。
     */
    private void injectHlsPlayer(String m3u8Url) {
        Log.i("CCTV-TV", "注入 hls.js 播放器, m3u8=" + m3u8Url);
        // 转义 URL 中的特殊字符,防止 JS 注入
        String safeUrl = m3u8Url.replace("\\", "\\\\").replace("'", "\\'").replace("\n", "");
        String js =
                "(function(){" +
                "  if(window.__cctvHlsPlayer)return;" +
                "  window.__cctvHlsPlayer=true;" +
                "  var m3u8Url='" + safeUrl + "';" +
                // hook addSourceBuffer:修复 codec 字符串
                // CCTV .ts 流的 avc1.64011f 被 MediaSource 拒绝,替换成 avc1.640028
                "  if(!window.__cctvCodecFixed){" +
                "    window.__cctvCodecFixed=true;" +
                "    var origASB=MediaSource.prototype.addSourceBuffer;" +
                "    MediaSource.prototype.addSourceBuffer=function(type){" +
                "      var fixed=type.replace(/avc1\\.64[0-9a-fA-F]{4}/g,'avc1.640028');" +
                "      if(fixed!==type)console.log('[CCTV-HLS] codec fix: '+type+' -> '+fixed);" +
                "      return origASB.call(this,fixed);" +
                "    };" +
                "  }" +
                // 隐藏 CCTV 原播放器的 video(如果有)
                "  var origV=document.getElementById('h5player_player');" +
                "  if(origV)origV.style.display='none';" +
                // 创建我们的 video 元素
                "  var video=document.createElement('video');" +
                "  video.id='cctv-hls-player';" +
                "  video.style.cssText='position:fixed;left:0;top:0;width:100vw;height:100vh;z-index:999999;object-fit:contain;background:#000;';" +
                "  video.autoplay=true;" +
                "  video.playsInline=true;" +
                "  document.body.appendChild(video);" +
                // 视频事件诊断(输出到 console,会被 LoggingWebChromeClient 捕获到 logcat)
                "  video.addEventListener('playing',function(){console.log('[CCTV-HLS] PLAYING');});" +
                "  video.addEventListener('error',function(){console.log('[CCTV-HLS] VIDEO_ERR code='+video.error.code);});" +
                // 加载 hls.js(jsdelivr CDN,国内可访问)
                "  var script=document.createElement('script');" +
                "  script.src='https://cdn.jsdelivr.net/npm/hls.js@1.5.15/dist/hls.min.js';" +
                "  script.onload=function(){" +
                "    console.log('[CCTV-HLS] hls.js loaded');" +
                "    if(!window.Hls){" +
                "      console.log('[CCTV-HLS] Hls undefined, try native');" +
                "      video.src=m3u8Url;video.play();return;" +
                "    }" +
                "    if(!Hls.isSupported()){" +
                "      console.log('[CCTV-HLS] MSE not supported, try native');" +
                "      video.src=m3u8Url;video.play();return;" +
                "    }" +
                // enableWorker:false — Worker 内的 console 日志不会输出到主线程,
                // 且某些 Android WebView 的 Worker 实现有兼容性问题
                "    var hls=new Hls({enableWorker:false,lowLatencyMode:true});" +
                "    hls.loadSource(m3u8Url);" +
                "    hls.attachMedia(video);" +
                "    hls.on(Hls.Events.MANIFEST_PARSED,function(){" +
                "      console.log('[CCTV-HLS] MANIFEST_PARSED');" +
                "      video.play();" +
                "    });" +
                "    hls.on(Hls.Events.ERROR,function(e,data){" +
                "      if(data.fatal)console.log('[CCTV-HLS] FATAL: '+data.type+' '+data.details);" +
                "    });" +
                "  };" +
                "  script.onerror=function(){" +
                // jsdelivr 失败,尝试 unpkg 备用 CDN
                "    console.log('[CCTV-HLS] jsdelivr failed, try unpkg');" +
                "    var s2=document.createElement('script');" +
                "    s2.src='https://unpkg.com/hls.js@1.5.15/dist/hls.min.js';" +
                "    s2.onload=script.onload;" +
                "    s2.onerror=function(){" +
                "      console.log('[CCTV-HLS] unpkg also failed, try native');" +
                "      video.src=m3u8Url;video.play();" +
                "    };" +
                "    document.head.appendChild(s2);" +
                "  };" +
                "  document.head.appendChild(script);" +
                "})()";
        webView.evaluateJavascript(js, null);
    }

    /**
     * 判断频道是否需要用桌面 UA。
     * CCTV-3 综艺 / CCTV-6 电影 / CCTV-8 电视剧:移动端 UA 会被 CCTV 服务器端直接 302 重定向
     * 到 https://m.yangshipin.cn/static/empty.html(刻意空白的版权引导页),必须用桌面 UA 才能加载
     * 正确的带播放器的桌面版页面。其他频道(如 CCTV-9)用桌面 UA 会黑屏播放不了。
     */
    private static boolean needsDesktopUA(String officialUrl) {
        if (officialUrl == null) return false;
        String u = officialUrl.toLowerCase(Locale.ROOT);
        return u.contains("/cctv3") || u.contains("/cctv6") || u.contains("/cctv8");
    }

    private void loadChannel(int requestedIndex) {
        handler.removeCallbacksAndMessages(null);
        loadGeneration++;
        capturedM3u8Url = null;
        hlsPlayerInjected = false;
        // 如果当前处于 WebView HTML5 全屏(custom view),先退出,否则切台后画面仍停留在旧视频
        if (customFullscreenView != null) {
            if (customFullscreenCallback != null) customFullscreenCallback.onCustomViewHidden();
            rootContainer.removeView(customFullscreenView);
            customFullscreenView = null;
            customFullscreenCallback = null;
            webView.setVisibility(View.VISIBLE);
        }
        int count = ChannelCatalog.CHANNELS.size();
        channelIndex = ((requestedIndex % count) + count) % count;
        Channel channel = ChannelCatalog.CHANNELS.get(channelIndex);
        // 关键:在加载前切 UA
        //  CCTV-3/6/8 → 桌面 Chrome 126(否则服务器端跳空页)
        //  其他     → 系统默认移动 UA(桌面 UA 会让 CCTV-9 等频道黑屏)
        WebSettings settings = webView.getSettings();
        if (needsDesktopUA(channel.officialUrl)) {
            settings.setUserAgentString(DESKTOP_UA);
        } else {
            settings.setUserAgentString(null); // null = 回退到系统默认移动 UA
        }
        // 切换频道时先清掉诊断面板,并显示"加载中"占位
        String uaTag = needsDesktopUA(channel.officialUrl) ? " [桌面UA]" : " [移动UA]";
        debugPanel.setText("加载中..." + uaTag + " 频道=" + channel.name + "\nURL=" + channel.officialUrl);
        debugPanel.setVisibility(View.VISIBLE);
        webView.loadUrl(channel.officialUrl);
        showChannelHint(channel.name);
        // 立即开始白屏倒计时,不依赖 onPageFinished
        // (CCTV 页面有持续心跳,onPageFinished 在某些频道永远不触发)
        scheduleWhiteScreenCheck();
    }

    private void showChannelHint(String channelName) {
        channelHint.removeCallbacks(hideChannelHint);
        channelHint.setText(channelName + "  ·  上下键切换频道  ·  菜单键显示提示");
        channelHint.setVisibility(View.VISIBLE);
        channelHint.postDelayed(hideChannelHint, CHANNEL_HINT_DURATION_MS);
    }

    /**
     * 5/10/15/20/30 秒各检查一次页面里到底有没有 video 元素。
     * 没有 → 显示诊断面板(用户一定能看见,不再依赖 Toast)
     * 有但暂停 → 调 play() 强制播放
     *
     * 用 ScheduledExecutorService 替代 handler.postDelayed:
     *   CCTV 页面有持续心跳,WebView 在 main thread 疯狂 load + parse + 跑 JS,
     *   handler.postDelayed 任务被压在队列里没机会跑(用户看到"加载中"永远不变)。
     *   background thread 的倒计时不被 main thread 阻塞,到时间后 post 回 main thread 更新 UI。
     */
    private void scheduleWhiteScreenCheck() {
        // 先取消之前还在排队的任务
        for (ScheduledFuture<?> f : pendingChecks) f.cancel(false);
        pendingChecks.clear();
        final int gen = loadGeneration;
        long[] delays = {5000L, 10000L, 15000L, 20000L, 30000L};
        for (long delay : delays) {
            ScheduledFuture<?> future = scheduler.schedule(() -> {
                if (gen != loadGeneration) return;
                // 切回 main thread 更新 UI(WebView 必须在 main thread 调)
                handler.post(() -> doWhiteScreenCheck(gen, delay));
            }, delay, TimeUnit.MILLISECONDS);
            pendingChecks.add(future);
        }
    }

    private void doWhiteScreenCheck(int gen, long elapsedMs) {
        if (gen != loadGeneration) return;
        // 即使 evaluateJavascript 回调永远不触发(如 JS 死循环或 WebView 挂),也先在屏幕上打"诊断中"
        updateDebugPanel("诊断中 已等" + (elapsedMs / 1000) + "秒", null);
        // 兜底:2 秒后如果 JS 回调还没触发,强制显示"JS 卡住了"(让用户知道不是白屏而是 evaluateJavascript 无响应)
        final java.util.concurrent.atomic.AtomicBoolean callbackFired = new java.util.concurrent.atomic.AtomicBoolean(false);
        scheduler.schedule(() -> {
            if (gen != loadGeneration || callbackFired.get()) return;
            handler.post(() -> updateDebugPanel("JS_TIMEOUT_" + (elapsedMs/1000) + "s",
                    "evaluateJavascript 2 秒无响应,WebView 可能死循环或崩溃\nURL=" + shortenUrl(webView.getUrl())));
        }, 2, TimeUnit.SECONDS);
        String js =
                "(function(){" +
                "  var v=document.getElementById('h5player_player')||document.querySelector('video');" +
                "  var m3u8=window.__cctvM3u8Url||'';" +
                "  if(v){" +
                "    var r=v.getBoundingClientRect();" +
                "    var cs=window.getComputedStyle(v);" +
                "    var diag='x='+Math.round(r.left)+' y='+Math.round(r.top)+' w='+Math.round(r.width)+' h='+Math.round(r.height)+" +
                "             ' display='+cs.display+' visibility='+cs.visibility+' opacity='+cs.opacity+" +
                "             ' zIndex='+cs.zIndex+' objectFit='+cs.objectFit+' muted='+v.muted+' paused='+v.paused;" +
                "    return 'OK:'+(v.paused?'PAUSED':'PLAYING')+' src='+(v.src||v.currentSrc||'none').substring(0,60)+'|M3U8='+m3u8+'|'+diag;" +
                "  }" +
                "  var txt=(document.body&&document.body.innerText||'').replace(/\\s+/g,' ').trim();" +
                "  var info=[];" +
                "  info.push('M3U8='+m3u8);" +
                "  info.push('已等='+" + (elapsedMs/1000) + ");" +
                "  info.push('URL='+location.href);" +
                "  info.push('TITLE='+document.title);" +
                "  info.push('RS='+document.readyState);" +
                "  info.push('videos='+document.getElementsByTagName('video').length);" +
                "  info.push('imgs='+document.getElementsByTagName('img').length);" +
                "  info.push('scripts='+document.getElementsByTagName('script').length);" +
                "  info.push('MediaKeys='+(window.MediaKeys?'YES':'NO'));" +
                "  info.push('MSE='+(window.MediaSource?'YES':'NO'));" +
                "  info.push('WASM='+(typeof WebAssembly==='object'?'YES:'+(typeof WebAssembly))+' Worker='+(typeof Worker==='function'?'YES':'NO')+' WebRTC='+(typeof RTCPeerConnection==='function'?'YES':'NO'));" +
                "  info.push('HLSP2P='+(typeof HLSP2P)+' createLivePlayer='+(typeof createLivePlayer));" +
                "  info.push('cntvPlayer='+(typeof cntvPlayer)+' playerObj='+(typeof playerObj));" +
                "  var player=document.getElementById('player');" +
                "  if(player){info.push('playerChildren='+player.children.length);info.push('playerHTML='+player.innerHTML.substring(0,200));}else{info.push('player=null');}" +
                "  var h5p=document.getElementById('h5player');" +
                "  if(h5p){info.push('h5playerHTML='+h5p.innerHTML.substring(0,200));}else{info.push('h5player=null');}" +
                "  info.push('BODY='+txt.substring(0,120));" +
                "  info.push('UA='+navigator.userAgent.substring(0,60));" +
                "  return 'NO_VIDEO|'+info.join('\\n');" +
                "})()";
        webView.evaluateJavascript(js, value -> {
            callbackFired.set(true);
            if (gen != loadGeneration) return;
            if (value == null) {
                updateDebugPanel("JS_NULL_RETURN_" + (elapsedMs/1000) + "s", "evaluateJavascript 返回 null,页面可能未初始化");
                return;
            }
            String state = value.toString();
            // 从返回值中解析 JS hook 捕获的 m3u8 URL
            // (shouldInterceptRequest 可能拦不到 HLSP2P Worker 内的 XHR,
            //  但 injectM3u8Capture 在主线程 hook 了 VDN API 响应,能获取 m3u8 URL)
            if (capturedM3u8Url == null) {
                int m3u8Idx = state.indexOf("M3U8=");
                if (m3u8Idx >= 0) {
                    int start = m3u8Idx + 5;
                    int end = state.indexOf("\n", start);
                    if (end < 0) end = state.indexOf("\\n", start);
                    if (end < 0) end = state.length();
                    String jsM3u8 = state.substring(start, end).trim();
                    if (!jsM3u8.isEmpty() && jsM3u8.startsWith("http")) {
                        capturedM3u8Url = jsM3u8;
                        Log.i("CCTV-TV", "从 JS hook 获取到 m3u8: " + jsM3u8);
                    }
                }
            }
            if (state.startsWith("NO_VIDEO")) {
                Log.e("CCTV-TV", "=== 白屏诊断(" + elapsedMs + "ms) ===\n" + state);
                String detail = state.substring("NO_VIDEO|".length())
                        .replace("\\n", "\n")
                        .replace("|", "\n");
                updateDebugPanel("NO_VIDEO", detail);
                // 如果 10 秒后还是没有 video 元素,且已拦截到 m3u8,用 hls.js 兜底播放
                if (elapsedMs >= 10000 && capturedM3u8Url != null && !hlsPlayerInjected) {
                    hlsPlayerInjected = true;
                    updateDebugPanel("HLS_FALLBACK", "无video元素,切换hls.js直连\nm3u8=" + shortenUrl(capturedM3u8Url));
                    injectHlsPlayer(capturedM3u8Url);
                }
            } else if (state.contains("PAUSED")) {
                // video 元素存在但暂停 → 自动播放策略阻止。用 muted + play() 策略
                webView.evaluateJavascript(
                        "(function(){var v=document.getElementById('h5player_player')||document.querySelector('video');if(v&&!v.__cctvAutoplayStarted){v.__cctvAutoplayStarted=true;v.muted=true;var p=v.play();if(p&&p.then){p.then(function(){setTimeout(function(){v.muted=false;},2000);}).catch(function(e){v.__cctvAutoplayStarted=false;});}else{setTimeout(function(){v.muted=false;},2000);}}return true;})()",
                        null);
                // 如果 10 秒后视频还是暂停的,说明 HLSP2P 播放器在 WebView 上跑不起来,
                // 用 hls.js 兜底直接播放 m3u8
                if (elapsedMs >= 10000 && capturedM3u8Url != null && !hlsPlayerInjected) {
                    hlsPlayerInjected = true;
                    updateDebugPanel("HLS_FALLBACK", "HLSP2P播放失败,切换hls.js直连\nm3u8=" + shortenUrl(capturedM3u8Url));
                    injectHlsPlayer(capturedM3u8Url);
                }
            } else {
                // OK:视频播放中,隐藏面板
                debugPanel.setVisibility(View.GONE);
            }
        });
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (event.getAction() == KeyEvent.ACTION_DOWN) {
            if (event.getKeyCode() == KeyEvent.KEYCODE_DPAD_UP) {
                loadChannel(channelIndex - 1);
                return true;
            }
            if (event.getKeyCode() == KeyEvent.KEYCODE_DPAD_DOWN) {
                loadChannel(channelIndex + 1);
                return true;
            }
            if (event.getKeyCode() == KeyEvent.KEYCODE_MENU) {
                // 菜单键:重试当前频道(CCTV-3/6/8 等白屏时很有用)
                loadChannel(channelIndex);
                return true;
            }
        }
        return super.dispatchKeyEvent(event);
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) enterImmersiveMode();
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        outState.putInt(SAVED_CHANNEL_INDEX, channelIndex);
        super.onSaveInstanceState(outState);
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        channelHint.removeCallbacks(hideChannelHint);
        webView.destroy();
        super.onDestroy();
    }

    private void enterImmersiveMode() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            getWindow().setDecorFitsSystemWindows(false);
            WindowInsetsController controller = getWindow().getInsetsController();
            if (controller != null) {
                controller.hide(WindowInsets.Type.statusBars() | WindowInsets.Type.navigationBars());
                controller.setSystemBarsBehavior(
                        WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            }
        } else {
            getWindow().getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                            | View.SYSTEM_UI_FLAG_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
        }
    }

    private boolean isOfficialCctvUrl(String url) {
        try {
            String host = new URI(url).getHost();
            if (host == null) return false;
            host = host.toLowerCase(Locale.ROOT);
            return host.equals("cctv.com") || host.endsWith(".cctv.com")
                    || host.equals("cntv.cn") || host.endsWith(".cntv.cn");
        } catch (Exception ignored) {
            return false;
        }
    }

    private static String shortenUrl(String url) {
        if (url == null) return "null";
        if (url.length() <= 90) return url;
        return url.substring(0, 70) + "..." + url.substring(url.length() - 18);
    }

    /**
     * 把状态信息写进中央诊断面板(用户看得见,不再错过 Toast)。
     * 优先级:NET_ERR > HTTP_ERR > CONSOLE_ERR > PROGRESS > NO_VIDEO dump > 加载中。
     * 新信息比旧信息重要时才覆盖,避免 LOADING 覆盖掉 ERROR。
     */
    private void updateDebugPanel(String title, String extra) {
        int priority;
        if (title == null) return;
        String tl = title.toUpperCase(Locale.ROOT);
        if (tl.startsWith("MAIN_FRAME_ERROR") || tl.startsWith("NET_ERR")) priority = 100;
        else if (tl.startsWith("HTTP_ERROR")) priority = 90;
        else if (tl.startsWith("CONSOLE_ERR")) priority = 80;
        else if (tl.startsWith("NO_VIDEO")) priority = 70;
        else if (tl.startsWith("诊断中")) priority = 60;
        else if (tl.startsWith("onPageFinished")) priority = 50;
        else if (tl.startsWith("PROGRESS")) priority = 30;
        else if (tl.startsWith("onPageStarted")) priority = 20;
        else priority = 10;
        // 如果面板已显示更高优先级的内容(如错误),不要用低级的"加载中"覆盖它
        CharSequence existing = debugPanel.getText();
        if (existing != null && existing.length() > 0) {
            String ex = existing.toString().toUpperCase(Locale.ROOT);
            int exPriority;
            if (ex.startsWith("MAIN_FRAME_ERROR") || ex.startsWith("NET_ERR")) exPriority = 100;
            else if (ex.startsWith("HTTP_ERROR")) exPriority = 90;
            else if (ex.startsWith("CONSOLE_ERR")) exPriority = 80;
            else if (ex.startsWith("NO_VIDEO")) exPriority = 70;
            else if (ex.startsWith("诊断中")) exPriority = 60;
            else if (ex.startsWith("ONPAGEFINISHED")) exPriority = 50;
            else if (ex.startsWith("PROGRESS")) exPriority = 30;
            else if (ex.startsWith("ONPAGESTARTED") || ex.startsWith("加载中")) exPriority = 20;
            else exPriority = 0;
            if (priority < exPriority) return;
        }
        String line2 = extra == null ? (webView.getUrl() != null ? "URL=" + shortenUrl(webView.getUrl()) : "") : extra;
        debugPanel.setText(title + "\n" + line2);
        debugPanel.setVisibility(View.VISIBLE);
    }

    /**
     * 把 WebView 内部的 console 输出(尤其是 error/warn)写到 logcat,严重的 JS 错误直接显示到面板。
     * 用法:在 MuMu 模拟器/真机上,运行 `adb logcat -s CCTV-TV` 即可看到 CCTV 内部 JS 的报错。
     * 配合 scheduleWhiteScreenCheck 的 body dump,能定位到白屏的真实原因。
     */
    private static class LoggingWebChromeClient extends WebChromeClient {
        private final MainActivity activity;
        private int lastProgressShown = -1;

        LoggingWebChromeClient(MainActivity a) {
            this.activity = a;
        }

        @Override
        public void onProgressChanged(WebView view, int newProgress) {
            super.onProgressChanged(view, newProgress);
            // 10/25/50/75/100 各显示一次(太多反而闪)
            int bucket = newProgress == 100 ? 100 : (newProgress / 25) * 25;
            if (bucket != lastProgressShown && activity != null) {
                lastProgressShown = bucket;
                activity.updateDebugPanel("PROGRESS " + newProgress + "%", null);
            }
        }

        @Override
        public void onShowCustomView(View view, WebChromeClient.CustomViewCallback callback) {
            super.onShowCustomView(view, callback);
            if (activity == null) return;
            if (activity.customFullscreenView != null) {
                callback.onCustomViewHidden();
                return;
            }
            activity.customFullscreenCallback = callback;
            activity.customFullscreenView = view;
            activity.rootContainer.addView(view, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            activity.webView.setVisibility(View.GONE);
            activity.updateDebugPanel("FULLSCREEN", "WebView 进入全屏模式");
        }

        @Override
        public void onHideCustomView() {
            super.onHideCustomView();
            if (activity == null) return;
            if (activity.customFullscreenView == null) return;
            activity.rootContainer.removeView(activity.customFullscreenView);
            activity.customFullscreenView = null;
            activity.customFullscreenCallback = null;
            activity.webView.setVisibility(View.VISIBLE);
        }

        @Override
        public boolean onConsoleMessage(android.webkit.ConsoleMessage cm) {
            String msg = cm.message();
            int level = cm.messageLevel().ordinal();
            // 浏览器自身的 document.write 跨站警告:不是致命 JS 异常,降为 INFO,别霸住面板
            // 典型内容:"A parser-blocking, cross site ... is invoked via document.write ... MAY be blocked"
            if (msg.contains("parser-blocking") && msg.contains("document.write")) {
                Log.d("CCTV-TV", "[I-DW] " + msg);
                return true;
            }
            // yangshipin iframe 跨域访问 window.parent/top:完全正常的 CORS 拦截,不影响播放,降级
            // 典型内容:"getWTTop error: Blocked a frame with origin 'https://ydh5.yangshipin.cn'
            //         from accessing a cross-origin frame."
            if ((msg.contains("Blocked a frame") || msg.contains("cross-origin frame")
                    || msg.contains("getWTTop") || msg.contains("SecurityError"))
                    && msg.contains("origin")) {
                Log.d("CCTV-TV", "[I-CORS] " + msg);
                return true;
            }
            // LOG_LEVEL_ERROR=2, LOG_LEVEL_WARNING=1, LOG_LEVEL_LOG=0
            switch (level) {
                case 2:
                    Log.e("CCTV-TV", "[E] " + msg + "  @ " + cm.sourceId() + ":" + cm.lineNumber());
                    // 严重 JS 错误直接显示到面板(白屏时用户看得清)
                    // 但过滤掉已知的非致命错误,避免覆盖白屏诊断信息
                    if (activity != null && !isKnownHarmlessError(msg)) {
                        String shortMsg = msg.length() > 260 ? msg.substring(0, 260) : msg;
                        activity.updateDebugPanel("CONSOLE_ERR", shortMsg);
                    }
                    break;
                case 1:
                    Log.w("CCTV-TV", "[W] " + msg + "  @ " + cm.sourceId() + ":" + cm.lineNumber());
                    break;
                default:
                    Log.d("CCTV-TV", "[I] " + msg);
                    break;
            }
            return true;
        }
    }
}
