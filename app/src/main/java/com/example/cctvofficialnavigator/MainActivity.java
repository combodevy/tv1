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
import android.view.GestureDetector;
import android.view.KeyEvent;
import android.view.MotionEvent;
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
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.mozilla.geckoview.GeckoRuntime;
import org.mozilla.geckoview.GeckoRuntimeSettings;
import org.mozilla.geckoview.GeckoSession;
import org.mozilla.geckoview.GeckoSessionSettings;
import org.mozilla.geckoview.GeckoView;

import java.net.URI;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
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
    private TextView progressHint;
    private ScrollView channelListScroll;
    private LinearLayout channelListItems;
    private TextView numberInputHint;
    private FrameLayout rootContainer;
    private int channelIndex;
    private final Runnable hideChannelHint = () -> channelHint.setVisibility(View.GONE);
    private final Runnable hideProgressHint = () -> progressHint.setVisibility(View.GONE);
    private final Handler handler = new Handler(Looper.getMainLooper());
    // 手势检测:上滑=下一个频道,下滑=上一个频道(手机触屏操作)
    private GestureDetector gestureDetector;
    // 频道列表状态
    private boolean channelListVisible = false;
    private int selectedListIndex = 0;
    private List<Integer> sortedChannelIndices;
    // 数字输入:按数字键直接跳频道,3秒延迟支持多位数
    private final StringBuilder pendingNumber = new StringBuilder();
    private Runnable numberInputTimeoutRunnable;
    // CCTV-3/6/8 用 GeckoView(Firefox 引擎,内嵌完整浏览器引擎)
    private GeckoView geckoView;
    private GeckoRuntime geckoRuntime;
    private GeckoSession geckoSession;
    private boolean geckoReady = false;
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
        progressHint = findViewById(R.id.progress_hint);
        channelListScroll = findViewById(R.id.channel_list_scroll);
        channelListItems = findViewById(R.id.channel_list_items);
        numberInputHint = findViewById(R.id.number_input_hint);
        rootContainer = findViewById(R.id.root_container);
        webView.setBackgroundColor(Color.BLACK);
        channelIndex = savedInstanceState == null ? 0 : savedInstanceState.getInt(SAVED_CHANNEL_INDEX, 0);
        buildSortedChannelList();
        // 初始化手势检测器:
        //  - 单击 → 显示/隐藏频道列表
        //  - 上滑/下滑 → 频道列表可见时导航列表,否则切换频道
        gestureDetector = new GestureDetector(this, new GestureDetector.SimpleOnGestureListener() {
            private static final int SWIPE_THRESHOLD = 100;
            private static final int SWIPE_VELOCITY_THRESHOLD = 100;
            @Override
            public boolean onSingleTapConfirmed(MotionEvent e) {
                if (channelListVisible) {
                    // 列表可见时单击 = 选中当前高亮项
                    selectChannelFromList();
                } else {
                    showChannelList();
                }
                return true;
            }
            @Override
            public boolean onFling(MotionEvent e1, MotionEvent e2, float velocityX, float velocityY) {
                if (e1 == null || e2 == null) return false;
                float diffY = e2.getY() - e1.getY();
                if (Math.abs(diffY) > SWIPE_THRESHOLD && Math.abs(velocityY) > SWIPE_VELOCITY_THRESHOLD) {
                    if (channelListVisible) {
                        // 频道列表可见时:上滑→下一项,下滑→上一项
                        if (diffY < 0) {
                            selectedListIndex = Math.min(sortedChannelIndices.size() - 1, selectedListIndex + 1);
                        } else {
                            selectedListIndex = Math.max(0, selectedListIndex - 1);
                        }
                        updateListHighlight();
                    } else {
                        if (diffY < 0) {
                            loadChannel(channelIndex + 1);
                        } else {
                            loadChannel(channelIndex - 1);
                        }
                    }
                    return true;
                }
                return false;
            }
        });
        configureWebView();
        initGeckoView(); // 初始化 GeckoView(用于 CCTV-3/6/8)
        enterImmersiveMode();
        loadChannel(channelIndex);
    }

    @SuppressLint("SetJavaScriptEnabled")
    private void configureWebView() {
        // 开启 WebView 远程调试:手机 Chrome 地址栏输入 chrome://inspect 可远程连接查看 console/DOM/网络
        WebView.setWebContentsDebuggingEnabled(true);
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
                // 0) 最最早期:浏览器环境伪装(必须在所有其他注入之前)
                //    WebView 原生没有 window.chrome,CCTV 播放器检测到缺失就激活 video_protect(只放声音不放画面)
                //    必须比 injectDocumentWritePatch/injectM3u8Capture/injectFastLoading/injectAutoFullscreen 都早
                injectBrowserEnvironment(view, needsDesktopUA(url));
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
     * 浏览器环境伪装(核心修复"有声音无画面"):
     *  ① 注入 window.chrome 对象 — WebView 原生没有,CCTV 播放器检测缺失后激活 video_protect(纯音频模式)
     *  ② 桌面 UA 频道覆盖 navigator.platform → "Win32" — UA 说 Windows 但 platform 还是 Linux,不一致会降级
     *  ③ 覆盖 navigator.webdriver → false — 防止被检测为自动化测试工具
     *  ④ 覆盖 navigator.vendor → "Google Inc." + 添加 navigator.userAgentData(Client Hints)
     *  ⑤ 覆盖 MediaSource.isTypeSupported() 对视频 codec 一律返回 true + 修复 addSourceBuffer codec
     *    WebView 可能对某些 H.264 profile 返回 false,导致播放器认为不支持视频降级为纯音频
     */
    private void injectBrowserEnvironment(WebView view, boolean isDesktopUA) {
        String platform = isDesktopUA ? "Win32" : "Linux armv8l";
        String uaDataPlatform = isDesktopUA ? "Windows" : "Android";
        String uaDataMobile = isDesktopUA ? "false" : "true";
        String js =
                "(function(){" +
                "  if(window.__cctvEnvPatched)return;" +
                "  window.__cctvEnvPatched=true;" +
                // ① 注入 window.chrome 对象(最关键)
                "  if(!window.chrome){" +
                "    window.chrome={};" +
                "    window.chrome.runtime={};" +
                "    window.chrome.app={isInstalled:false};" +
                "    window.chrome.csi=function(){return{}};" +
                "    window.chrome.loadTimes=function(){return{}};" +
                "  }" +
                // ② 覆盖 navigator.platform
                "  try{Object.defineProperty(navigator,'platform',{get:function(){return '" + platform + "';},configurable:true});}catch(e){}" +
                // ③ 覆盖 navigator.webdriver → false
                "  try{Object.defineProperty(navigator,'webdriver',{get:function(){return false;},configurable:true});}catch(e){}" +
                // ④ 覆盖 navigator.vendor + 添加 navigator.userAgentData
                "  try{Object.defineProperty(navigator,'vendor',{get:function(){return 'Google Inc.';},configurable:true});}catch(e){}" +
                "  try{if(!navigator.userAgentData){" +
                "    Object.defineProperty(navigator,'userData',{get:function(){return undefined;},configurable:true});" +
                "    Object.defineProperty(navigator,'userAgentData',{" +
                "      get:function(){return {brands:[{brand:'Chromium',version:'126'},{brand:'Google Chrome',version:'126'}],mobile:" + uaDataMobile + ",platform:'" + uaDataPlatform + "'};" +
                "      },configurable:true" +
                "    });" +
                "  }}catch(e){}" +
                // ⑤ 覆盖 MediaSource.isTypeSupported() + 修复 addSourceBuffer codec
                "  if(window.MediaSource){" +
                "    var origITS=MediaSource.isTypeSupported.bind(MediaSource);" +
                "    MediaSource.isTypeSupported=function(type){" +
                "      if(/video/i.test(type)&&/avc1|hvc1|hev1|mp4|webm/i.test(type))return true;" +
                "      return origITS(type);" +
                "    };" +
                "    var origASB=MediaSource.prototype.addSourceBuffer;" +
                "    MediaSource.prototype.addSourceBuffer=function(type){" +
                "      var fixed=String(type).replace(/avc1\\.64[0-9a-fA-F]{4}/g,'avc1.640028');" +
                "      return origASB.call(this,fixed);" +
                "    };" +
                "  }" +
                "})()";
        view.evaluateJavascript(js, null);
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
     * 三路捕获 m3u8 URL(黑屏兜底的关键前置):
     *  1) XHR:hook open 记 URL,hook send 后用 onload + addEventListener('load'/'readystatechange') 三重拦截
     *     (原来只 hook onreadystatechange,但 liveplayer.js 用 onload,VDN 返回的 m3u8 从来没被捕获)
     *  2) fetch:拦截 VDN fetch 的 json()/text() 响应,从 body 中正则提取 m3u8
     *  3) livePlayerObjs 轮询:每 500ms 从播放器对象内部直接读 m3u8 URL(兜底兜底)
     * VDN URL 匹配从 'vdn/live' 扩大为 'vdn'(覆盖 vdnx/vdnxbk/vdn 三个端点)。
     */
    private void injectM3u8Capture(WebView view) {
        String js =
                "(function(){" +
                "  if(window.__cctvM3u8Hook)return;" +
                "  window.__cctvM3u8Hook=true;" +
                "  window.__cctvM3u8Url=null;" +
                // ---- 工具函数:从任意文本中提取 m3u8 URL ----
                "  function extractM3u8(text){" +
                "    if(!text||typeof text!=='string')return null;" +
                "    var m=text.match(/https?:\\/\\/[^\\s\"'<>|]+\\.m3u8[^\\s\"'<>|]*/);" +
                "    return m?m[0]:null;" +
                "  }" +
                "  function saveM3u8(url,source){" +
                "    if(url&&!window.__cctvM3u8Url){" +
                "      window.__cctvM3u8Url=url;" +
                "      console.log('[CCTV-M3U8] '+source+': '+url);" +
                "    }" +
                "  }" +
                // ---- 1) XHR hook ----
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
                "    if(reqUrl.indexOf('.m3u8')>=0){" +
                "      saveM3u8(reqUrl,'XHR direct');" +
                "    }" +
                // VDN API 响应拦截(扩大匹配:vdn 覆盖 vdnx/vdnxbk/vdn)
                "    var isVdn=reqUrl.indexOf('vdn')>=0||reqUrl.indexOf('getstream')>=0;" +
                "    if(isVdn){" +
                // 三重拦截:onload + addEventListener('load') + addEventListener('readystatechange')
                // (liveplayer.js 用 onload,不用 onreadystatechange,原来只 hook onreadystatechange 导致漏捕)
                "      var origOnload=self.onload;" +
                "      self.onload=function(){" +
                "        var m=extractM3u8(self.responseText);" +
                "        if(m)saveM3u8(m,'XHR onload');" +
                "        if(origOnload)return origOnload.apply(self,arguments);" +
                "      };" +
                "      self.addEventListener('load',function(){" +
                "        var m=extractM3u8(self.responseText);" +
                "        if(m)saveM3u8(m,'XHR load evt');" +
                "      });" +
                "      self.addEventListener('readystatechange',function(){" +
                "        if(self.readyState===4){" +
                "          var m=extractM3u8(self.responseText);" +
                "          if(m)saveM3u8(m,'XHR rsc evt');" +
                "        }" +
                "      });" +
                // 保留原来的 onreadystatechange hook(兼容直接赋值的写法)
                "      var origRSC=self.onreadystatechange;" +
                "      self.onreadystatechange=function(){" +
                "        if(self.readyState===4){" +
                "          var m=extractM3u8(self.responseText);" +
                "          if(m)saveM3u8(m,'XHR rsc hook');" +
                "        }" +
                "        if(origRSC)return origRSC.apply(self,arguments);" +
                "      };" +
                "    }" +
                "    return origSend.apply(self,arguments);" +
                "  };" +
                // ---- 2) fetch hook ----
                "  if(window.fetch){" +
                "    var origFetch=window.fetch;" +
                "    window.fetch=function(input,init){" +
                "      var url=typeof input==='string'?input:(input&&input.url||'');" +
                "      if(url.indexOf('.m3u8')>=0)saveM3u8(url,'fetch direct');" +
                // VDN fetch:拦截 response.json()/text()
                "      var isVdn=url.indexOf('vdn')>=0||url.indexOf('getstream')>=0;" +
                "      if(isVdn){" +
                "        return origFetch.apply(this,arguments).then(function(resp){" +
                "          var origJson=resp.json.bind(resp);" +
                "          var origText=resp.text.bind(resp);" +
                "          resp.json=function(){return origJson().then(function(data){" +
                "            var m=extractM3u8(JSON.stringify(data));" +
                "            if(m)saveM3u8(m,'fetch json');" +
                "            return data;" +
                "          });};" +
                "          resp.text=function(){return origText().then(function(text){" +
                "            var m=extractM3u8(text);" +
                "            if(m)saveM3u8(m,'fetch text');" +
                "            return text;" +
                "          });};" +
                "          return resp;" +
                "        });" +
                "      }" +
                "      return origFetch.apply(this,arguments);" +
                "    };" +
                "  }" +
                // ---- 3) livePlayerObjs 轮询:每 500ms 从播放器对象内部读 m3u8 ----
                "  if(!window.__cctvPlayerPoll){" +
                "    window.__cctvPlayerPoll=true;" +
                "    function pollPlayerObjs(){" +
                "      if(window.__cctvM3u8Url){return;}" +
                // 遍历已知的播放器全局对象
                "      var objs=[window.playerObj,window.cntvPlayer,window.liveplayer,window.livePlayer,window.HLSP2P];" +
                "      for(var i=0;i<objs.length;i++){" +
                "        var o=objs[i];" +
                "        if(!o)continue;" +
                "        var m3u8=o.m3u8||o.m3u8Url||o.streamUrl||o.url||o.src||" +
                "                 (o.options&&o.options.m3u8)||(o.config&&o.config.m3u8);" +
                "        if(m3u8&&typeof m3u8==='string'&&m3u8.indexOf('.m3u8')>=0){" +
                "          saveM3u8(m3u8,'playerObj poll');" +
                "          return;" +
                "        }" +
                // 深度遍历一层属性
                "        for(var k in o){" +
                "          try{" +
                "            var v=o[k];" +
                "            if(typeof v==='string'&&v.indexOf('.m3u8')>=0){" +
                "              saveM3u8(v,'playerObj.'+k);" +
                "              return;" +
                "            }" +
                "          }catch(e){}" +
                "        }" +
                "      }" +
                "      setTimeout(pollPlayerObjs,500);" +
                "    }" +
                "    setTimeout(pollPlayerObjs,500);" +
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
                "    try{if(v.webkitRequestFullscreen&&!v.__cctvFsRequested&&v.videoWidth>0){v.__cctvFsRequested=true;v.webkitRequestFullscreen();}}catch(e){}" +
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
                // 停止并隐藏 CCTV 原播放器的所有 video(避免和 hls.js 的音频重叠)
                "  var allVideos=document.getElementsByTagName('video');" +
                "  for(var i=0;i<allVideos.length;i++){" +
                "    var ov=allVideos[i];" +
                "    if(ov.id==='cctv-hls-player')continue;" +
                "    try{ov.pause();ov.muted=true;ov.volume=0;}catch(e){}" +
                "    ov.style.display='none';" +
                "  }" +
                // 尝试 stop/destroy HLSP2P 播放器对象,释放 P2P 资源
                "  try{if(typeof playerObj!=='undefined'&&playerObj){if(playerObj.stop)playerObj.stop();if(playerObj.destroy)playerObj.destroy();}}catch(e){}" +
                "  try{if(typeof cntvPlayer!=='undefined'&&cntvPlayer){if(cntvPlayer.stop)cntvPlayer.stop();if(cntvPlayer.destroy)cntvPlayer.destroy();}}catch(e){}" +
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

    /**
     * 初始化 GeckoView(Firefox 引擎),用于 CCTV-3/6/8。
     * GeckoView 自带:
     *  - Widevine DRM(解密加密视频流)
     *  - 完整 MediaSource(支持 MSE blob URL)
     *  - 完整视频解码器(不依赖系统 WebView)
     *  - UA 自定义(桌面 UA)
     *  - HTML5 全屏(CCTV 播放器的全屏按钮直接可用)
     *  - 自动播放(不需要 muted hack)
     */
    @SuppressLint("SetJavaScriptEnabled")
    private void initGeckoView() {
        geckoView = findViewById(R.id.gecko_view);
        try {
            // 创建 GeckoRuntime,配置 JS + DRM(GeckoView 130 的 UA 覆盖和自动播放通过 SessionSettings 设置)
            GeckoRuntimeSettings.Builder builder = new GeckoRuntimeSettings.Builder()
                    .javaScriptEnabled(true)
                    .configFilePath(getFilesDir().getAbsolutePath() + "/geckoview-config.json");
            geckoRuntime = GeckoRuntime.create(this, builder.build());
            if (geckoRuntime == null) {
                Log.e("CCTV-TV", "GeckoRuntime 创建失败,回退到 WebView");
                geckoReady = false;
                return;
            }
            // 创建 GeckoSession
            geckoSession = new GeckoSession();
            geckoSession.getSettings().setUserAgentMode(GeckoSessionSettings.USER_AGENT_MODE_DESKTOP);
            geckoSession.getSettings().setViewportMode(GeckoSessionSettings.VIEWPORT_MODE_DESKTOP);
            geckoSession.getSettings().setUserAgentOverride(DESKTOP_UA);
            geckoSession.getSettings().setAllowJavascript(true);

            // GeckoView 全尺寸 + 移除动态工具栏高度(防止挤压内容区域)
            geckoView.setDynamicToolbarMaxHeight(0);
            // Session 设置: DISPLAY_MODE_FULLSCREEN 告诉 GeckoView 这是全屏应用模式
            // 影响 Gecko 引擎内部的 viewport 和 滚动条行为
            geckoSession.getSettings().setDisplayMode(GeckoSessionSettings.DISPLAY_MODE_FULLSCREEN);
            geckoSession.getSettings().setSuspendMediaWhenInactive(false);

            // Content delegate: 处理全屏 + 页面标题(带诊断回传)
            geckoSession.setContentDelegate(new GeckoSession.ContentDelegate() {
                @Override
                public void onFullScreen(GeckoSession session, boolean fullScreen) {
                    Log.i("CCTV-TV", "GeckoView HTML5 全屏回调: " + fullScreen);
                    if (fullScreen) {
                        enterImmersiveMode();
                    }
                }

                // JS 里通过 document.title 把诊断信息回传到 Java:
                // 格式: "[DIAG] ..." 开头的就是诊断信息
                // 双路输出: 1) 打到 logcat; 2) 显示到屏幕右上角 progressHint,用户无需adb即可查看
                @Override
                public void onTitleChange(GeckoSession session, String title) {
                    if (title != null && title.startsWith("[DIAG]")) {
                        String diag = title.substring(7);
                        Log.i("CCTV-TV", "JS诊断 → " + diag);
                        // 显示到屏幕右上角(不自动消失,方便看;新的诊断会覆盖)
                        handler.removeCallbacks(hideProgressHint);
                        progressHint.setVisibility(View.VISIBLE);
                        progressHint.setText("诊断: " + diag);
                    }
                }

                @Override
                public void onFirstComposite(GeckoSession session) {
                    Log.i("CCTV-TV", "GeckoView onFirstComposite → 触发注入");
                    scheduleGeckoAutoPlay();
                }

                @Override
                public void onFirstContentfulPaint(GeckoSession session) {
                    Log.i("CCTV-TV", "GeckoView onFirstContentfulPaint → 触发注入");
                    scheduleGeckoAutoPlay();
                }

                @Override
                public void onCloseRequest(GeckoSession session) {
                }
            });

            // Progress delegate: 页面加载进度
            geckoSession.setProgressDelegate(new GeckoSession.ProgressDelegate() {
                @Override
                public void onPageStart(GeckoSession session, String url) {
                    updateDebugPanel("GeckoView加载", shortenUrl(url));
                    Log.i("CCTV-TV", "GeckoView onPageStart → " + url);
                }

                @Override
                public void onPageStop(GeckoSession session, boolean success) {
                    updateDebugPanel("GeckoView就绪", success ? "加载完成" : "加载失败");
                    Log.i("CCTV-TV", "GeckoView onPageStop → success=" + success + ", 触发注入");
                    scheduleGeckoAutoPlay();
                }
            });

            // Navigation delegate: 拦截非 CCTV URL
            geckoSession.setNavigationDelegate(new GeckoSession.NavigationDelegate() {
                @Override
                public void onLocationChange(GeckoSession session, String url,
                        java.util.List<org.mozilla.geckoview.GeckoSession.PermissionDelegate.ContentPermission> perms,
                        Boolean hasChanged) {
                    if (url != null && url.contains("yangshipin.cn/static/empty")) {
                        Log.w("CCTV-TV", "GeckoView 被重定向到空页,重新加载");
                        Channel cur = ChannelCatalog.CHANNELS.get(channelIndex);
                        geckoSession.loadUri(cur.officialUrl);
                    }
                }
            });

            geckoSession.open(geckoRuntime);
            geckoView.setSession(geckoSession);
            geckoReady = true;
            Log.i("CCTV-TV", "GeckoView 初始化成功");
        } catch (Exception e) {
            Log.e("CCTV-TV", "GeckoView 初始化失败: " + e.getMessage(), e);
            geckoReady = false;
        }
    }

    /**
     * 用 Java Handler 多次延迟注入自动播放+全屏脚本(仅 GeckoView/CCTV-3/6/8)。
     * CCTV 的 video 元素是流连接后才动态插入的,需要多次尝试。
     * 用 Java 层轮询比 JS setTimeout 更可靠(不受页面 JS 心跳阻塞)。
     */
    private void scheduleGeckoAutoPlay() {
        final int gen = loadGeneration;
        long[] delays = {1500L, 3000L, 5000L, 7000L, 10000L, 15000L, 20000L};
        for (long delay : delays) {
            handler.postDelayed(() -> {
                if (gen != loadGeneration || !geckoReady || geckoSession == null) return;
                injectGeckoAutoPlay();
            }, delay);
        }
    }

    /**
     * 给 GeckoView 注入自动播放+全屏脚本(仅 CCTV-3/6/8)。
     *
     * 关键改动(2026-08-01 针对用户反馈"顶部导航栏+分类栏还在+视频区域黑屏"):
     *  1. CSS 暴力隐藏: 直接用 body>*:not(...) 把非播放器的所有元素隐藏,
     *     不再用逐个 class 选择器(之前的选择器匹配不到 CCTv 新版 DOM)。
     *     用 !important 提高优先级。
     *  2. document.title 诊断回传: 每个关键步骤都把结果写入 document.title,
     *     Java 层通过 onTitleChange 捕获并打到 logcat。用这个机制确认 JS 注入是否执行。
     *  3. 每步用 try/catch 防止局部错误导致后续代码不执行。
     */
    private void injectGeckoAutoPlay() {
        if (geckoSession == null) return;
        String js =
                "(function(){" +
                "  try{" +
                // 诊断: 写入 title,Java 层 onTitleChange 捕获
                "    var r=[];" +
                "    r.push('JS注入OK');" +
                // 1. 暴力 CSS: 隐藏 body 下所有非播放器元素,隐藏导航/广告/iframe
                "    if(!document.getElementById('cctv-gv-style')){" +
                "      var s=document.createElement('style');" +
                "      s.id='cctv-gv-style';" +
                "      s.textContent=" +
                "        'html,body{width:100%!important;height:100%!important;margin:0!important;padding:0!important;background:#000!important;overflow:hidden!important}'+" +
                "        'body>*{display:none!important}'+" +
                "        'body>#player,body>#player_container,body>#h5player_player,body>#m_CmContentPlayer,body>#CMVideoPlayer,body>div:first-child{display:block!important}'+" +
                "        '#player,#player_container,#h5player_player,.video_box,.video_flash,.video_left,.video_main,#CMVideoPlayer{display:block!important;width:100%!important;height:100%!important;margin:0!important;padding:0!important;position:fixed!important;left:0!important;top:0!important;z-index:1!important;background:#000!important}'+" +
                "        'video{display:block!important;width:100vw!important;height:100vh!important;margin:0!important;padding:0!important;position:fixed!important;left:0!important;top:0!important;z-index:999999!important;background:#000!important;object-fit:contain!important}'+" +
                "        'iframe:not([id*=player]):not([src*=player]):not([src*=live]),header,nav,footer,.nav,.nav_wrap,.topnav,.sitemap,.shares,.topbar,.column_wrapper,.bg_top_h_tile,.bg_bottom_h_tile,.video_right,.video_btnBar,.vspace{display:none!important;visibility:hidden!important;height:0!important;width:0!important;overflow:hidden!important}';" +
                "      (document.head||document.documentElement).appendChild(s);" +
                "      r.push('CSS已注入');" +
                "    } else { r.push('CSS已存在'); }" +
                // 2. 诊断: video 元素状态
                "    var v=document.querySelector('video');" +
                "    r.push(v?'找到video':'未找到video');" +
                // 3. 自动播放
                "    if(v){" +
                "      r.push('video状态:paused='+v.paused+',muted='+v.muted+',readyState='+v.readyState+',videoWidth='+v.videoWidth);" +
                "      if(v.paused&&!v.__gvAp){" +
                "        v.__gvAp=true;" +
                "        v.muted=true;" +
                "        try{" +
                "          var p=v.play();" +
                "          if(p&&p.then){" +
                "            p.then(function(){r.push('play()成功');setTimeout(function(){v.muted=false;r.push('取消muted');document.title='[DIAG] '+r.join(' | ');},2000);}).catch(function(e){v.__gvAp=false;r.push('play失败:'+e.message);});" +
                "          } else {" +
                "            r.push('play()已执行(非Promise)');setTimeout(function(){v.muted=false;r.push('取消muted');document.title='[DIAG] '+r.join(' | ');},2000);" +
                "          }" +
                "        }catch(e){ r.push('play异常:'+e.message); v.__gvAp=false; }" +
                "      }" +
                // 4. CSS 兜底: video 强制显示和定位
                "      try{" +
                "        v.style.setProperty('display','block','important');" +
                "        v.style.setProperty('position','fixed','important');" +
                "        v.style.setProperty('left','0','important');" +
                "        v.style.setProperty('top','0','important');" +
                "        v.style.setProperty('width','100vw','important');" +
                "        v.style.setProperty('height','100vh','important');" +
                "        v.style.setProperty('z-index','999999','important');" +
                "        v.style.setProperty('visibility','visible','important');" +
                "        v.style.setProperty('opacity','1','important');" +
                "      }catch(e2){ r.push('video CSS异常:'+e2.message); }" +
                "    }" +
                // 5. 点击 CCTV 网页全屏按钮(所有可能选择器都试一下)
                "    var btn=null;" +
                "    var allSelectors=['#player_fullscreen_no_player','#player_pagefullscreen_yes_player','.videoFull','.fs_btn','[id*=fullscreen][id*=no]','[id*=fullscreen][id*=yes]'];" +
                "    for(var i=0;i<allSelectors.length;i++){" +
                "      try{ var el=document.querySelector(allSelectors[i]); if(el){btn=el;r.push('全屏按钮:'+allSelectors[i]);break;} }catch(e3){}" +
                "    }" +
                "    if(!btn){ r.push('未找到全屏按钮'); }" +
                "    if(btn&&!btn.__gvClicked){" +
                "      btn.__gvClicked=true;" +
                "      try{" +
                "        btn.click();" +
                "        r.push('全屏按钮已点击');" +
                "      }catch(e4){ r.push('点击全屏异常:'+e4.message); }" +
                "    }" +
                // 最终: 诊断写入 document.title
                "    document.title='[DIAG] '+r.join(' | ');" +
                "  }catch(e5){" +
                "    try{ document.title='[DIAG] JS顶层异常:'+e5.message; }catch(z){}" +
                "  }" +
                "})()";
        // 重要: javascript: 后面不空格,直接接编码后的脚本
        geckoSession.loadUri("javascript:" + js);
        Log.i("CCTV-TV", "GeckoView 注入自动播放+全屏(暴力CSS+诊断回传)");
    }

    /**
     * 用 GeckoView 加载 CCTV-3/6/8 频道。
     */
    private void loadGeckoChannel(String url) {
        if (!geckoReady) {
            Log.w("CCTV-TV", "GeckoView 未就绪,重新初始化");
            initGeckoView();
        }
        if (geckoReady && geckoSession != null) {
            // 显示 GeckoView,隐藏 WebView
            webView.setVisibility(View.GONE);
            geckoView.setVisibility(View.VISIBLE);
            geckoView.requestFocus();
            Log.i("CCTV-TV", "GeckoView 加载: " + url);
            geckoSession.loadUri(url);
            // 直接调度自动播放(onPageStop 可能因 CCTV 心跳不触发,这里做主调用)
            scheduleGeckoAutoPlay();
        } else {
            // GeckoView 不可用,回退到 WebView(带桌面 UA)
            Log.w("CCTV-TV", "GeckoView 不可用,回退到 WebView");
            WebSettings settings = webView.getSettings();
            settings.setUserAgentString(DESKTOP_UA);
            webView.setVisibility(View.VISIBLE);
            webView.loadUrl(url);
            scheduleWhiteScreenCheck();
        }
    }

    /**
     * 切换到 WebView 模式(非 CCTV-3/6/8 频道)。
     */
    private void switchToWebView() {
        if (geckoView != null) {
            geckoView.setVisibility(View.GONE);
        }
        webView.setVisibility(View.VISIBLE);
        // 停止 GeckoView 加载(节省资源)
        if (geckoSession != null) {
            geckoSession.stop();
        }
    }

    private void loadChannel(int requestedIndex) {
        handler.removeCallbacksAndMessages(null);
        // 切频道时关闭频道列表和数字输入提示
        if (channelListVisible) hideChannelList();
        if (numberInputHint.getVisibility() == View.VISIBLE) {
            numberInputHint.setVisibility(View.GONE);
            pendingNumber.setLength(0);
        }
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
        // 注意:MuMu(x86)模拟器无硬件 H.264 解码器,桌面 UA 的 HLSP2P 播放器(MSE blob URL)无法解码,
        // 所以 MuMu 上 CCTV-3/6/8 会有声音没画面。真实 ARM 电视盒子有硬件解码器,不受影响。
        WebSettings settings = webView.getSettings();
        if (needsDesktopUA(channel.officialUrl)) {
            // CCTV-3/6/8:用 GeckoView(Firefox 引擎,内嵌完整浏览器)
            // GeckoView 自带 Widevine DRM + MediaSource + 完整视频解码器
            // 不需要安装 Chrome,直接内嵌在 APK 里
            settings.setUserAgentString(DESKTOP_UA);
            updateDebugPanel("GeckoView", channel.name + " [桌面UA]");
            showChannelHint(channel.name);
            loadGeckoChannel(channel.officialUrl);
            return;
        } else {
            // 其他频道:用 WebView(移动 UA),先切回 WebView 模式
            switchToWebView();
            settings.setUserAgentString(null); // null = 回退到系统默认移动 UA
        }
        // 切换频道时先显示"加载中"进度提示
        String uaTag = needsDesktopUA(channel.officialUrl) ? " [桌面UA]" : " [移动UA]";
        updateDebugPanel("加载中", channel.name + uaTag);
        webView.loadUrl(channel.officialUrl);
        showChannelHint(channel.name);
        // 立即开始白屏倒计时,不依赖 onPageFinished
        // (CCTV 页面有持续心跳,onPageFinished 在某些频道永远不触发)
        scheduleWhiteScreenCheck();
    }

    private void showChannelHint(String channelName) {
        channelHint.removeCallbacks(hideChannelHint);
        channelHint.setText(channelName + "  ·  上下键切台  ·  OK键频道列表  ·  数字键直跳");
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
        long[] delays = {3000L, 5000L, 8000L, 12000L, 20000L};
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
                "             ' zIndex='+cs.zIndex+' objectFit='+cs.objectFit+' muted='+v.muted+' paused='+v.paused+" +
                "             ' vw='+v.videoWidth+' vh='+v.videoHeight;" +
                "    var st=v.paused?'PAUSED':((v.videoWidth===0||v.videoHeight===0)?'BLACK_SCREEN':'PLAYING');" +
                "    return 'OK:'+st+' src='+(v.src||v.currentSrc||'none').substring(0,60)+'|M3U8='+m3u8+'|'+diag;" +
                "  }" +
                "  var allVideos=document.querySelectorAll('video');" +
                "  var videoInfo=[];" +
                "  for(var i=0;i<allVideos.length;i++){" +
                "    var vv=allVideos[i];" +
                "    var rr=vv.getBoundingClientRect();" +
                "    videoInfo.push('video['+i+'] id='+vv.id+' w='+Math.round(rr.width)+' h='+Math.round(rr.height)+' paused='+vv.paused+' src='+(vv.src||vv.currentSrc||'none').substring(0,40));" +
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
                    if (end < 0) end = state.indexOf("|", start);
                    if (end < 0) end = state.length();
                    String jsM3u8 = state.substring(start, end).trim();
                    // 去掉首尾引号(BLACK_SCREEN 状态用 | 分隔字段,URL 可能带引号)
                    while (jsM3u8.startsWith("\"") || jsM3u8.startsWith("'")) {
                        jsM3u8 = jsM3u8.substring(1);
                    }
                    while (jsM3u8.endsWith("\"") || jsM3u8.endsWith("'")) {
                        jsM3u8 = jsM3u8.substring(0, jsM3u8.length() - 1);
                    }
                    jsM3u8 = jsM3u8.trim();
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
                // 如果 5 秒后还是没有 video 元素,且已拦截到 m3u8,用 hls.js 兜底播放
                if (elapsedMs >= 5000 && capturedM3u8Url != null && !hlsPlayerInjected) {
                    hlsPlayerInjected = true;
                    updateDebugPanel("HLS_FALLBACK", "无video元素,切换hls.js直连\nm3u8=" + shortenUrl(capturedM3u8Url));
                    injectHlsPlayer(capturedM3u8Url);
                }
            } else if (state.contains("BLACK_SCREEN")) {
                // 有声音无画面:HLSP2P 的 P2P 视频失败,音频正常
                Log.w("CCTV-TV", "=== 有声音无画面(" + elapsedMs + "ms) ===\n" + state);
                updateDebugPanel("BLACK_SCREEN", "有声音无画面:HLSP2P的P2P视频失败\n正在切换hls.js兜底...");
                // 如果 capturedM3u8Url 还是 null,直接从 JS 读一次 window.__cctvM3u8Url
                if (elapsedMs >= 5000 && capturedM3u8Url == null && !hlsPlayerInjected) {
                    webView.evaluateJavascript("window.__cctvM3u8Url", m3u8Val -> {
                        if (gen != loadGeneration) return;
                        if (m3u8Val != null && !"null".equals(m3u8Val)) {
                            String url = m3u8Val.toString().trim();
                            // 去掉首尾引号
                            while (url.startsWith("\"") || url.startsWith("'")) url = url.substring(1);
                            while (url.endsWith("\"") || url.endsWith("'")) url = url.substring(0, url.length() - 1);
                            if (url.startsWith("http") && url.contains(".m3u8")) {
                                capturedM3u8Url = url;
                                Log.i("CCTV-TV", "从 window.__cctvM3u8Url 读到: " + url);
                            }
                        }
                        // 读到后立刻触发 hls.js
                        if (capturedM3u8Url != null && !hlsPlayerInjected) {
                            hlsPlayerInjected = true;
                            updateDebugPanel("HLS_FALLBACK", "有声音无画面,切换hls.js直连\nm3u8=" + shortenUrl(capturedM3u8Url));
                            injectHlsPlayer(capturedM3u8Url);
                        }
                    });
                } else if (elapsedMs >= 5000 && capturedM3u8Url != null && !hlsPlayerInjected) {
                    hlsPlayerInjected = true;
                    updateDebugPanel("HLS_FALLBACK", "有声音无画面,切换hls.js直连\nm3u8=" + shortenUrl(capturedM3u8Url));
                    injectHlsPlayer(capturedM3u8Url);
                }
            } else if (state.contains("PAUSED")) {
                // video 元素存在但暂停 → 自动播放策略阻止。用 muted + play() 策略
                webView.evaluateJavascript(
                        "(function(){var v=document.getElementById('h5player_player')||document.querySelector('video');if(v&&!v.__cctvAutoplayStarted){v.__cctvAutoplayStarted=true;v.muted=true;var p=v.play();if(p&&p.then){p.then(function(){setTimeout(function(){v.muted=false;},2000);}).catch(function(e){v.__cctvAutoplayStarted=false;});}else{setTimeout(function(){v.muted=false;},2000);}}return true;})()",
                        null);
                // 如果 5 秒后视频还是暂停的,说明 HLSP2P 播放器在 WebView 上跑不起来,
                // 用 hls.js 兜底直接播放 m3u8
                if (elapsedMs >= 5000 && capturedM3u8Url != null && !hlsPlayerInjected) {
                    hlsPlayerInjected = true;
                    updateDebugPanel("HLS_FALLBACK", "HLSP2P播放失败,切换hls.js直连\nm3u8=" + shortenUrl(capturedM3u8Url));
                    injectHlsPlayer(capturedM3u8Url);
                }
            } else {
                // OK:视频播放中,隐藏进度提示
                progressHint.setVisibility(View.GONE);
            }
        });
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent ev) {
        // 手势检测优先:上滑/下滑切换频道
        if (gestureDetector.onTouchEvent(ev)) {
            return true;
        }
        return super.dispatchTouchEvent(ev);
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (event.getAction() == KeyEvent.ACTION_DOWN) {
            // 数字键 0-9: 直接跳频道(3秒延迟,支持两位数)
            if (event.getKeyCode() >= KeyEvent.KEYCODE_0 && event.getKeyCode() <= KeyEvent.KEYCODE_9) {
                int digit = event.getKeyCode() - KeyEvent.KEYCODE_0;
                handleNumberInput(digit);
                return true;
            }
            // OK/Enter: 频道列表可见时=选中,不可见时=显示列表
            if (event.getKeyCode() == KeyEvent.KEYCODE_DPAD_CENTER
                    || event.getKeyCode() == KeyEvent.KEYCODE_ENTER) {
                if (channelListVisible) {
                    selectChannelFromList();
                } else {
                    showChannelList();
                }
                return true;
            }
            // 返回键: 频道列表可见时=关闭列表
            if (event.getKeyCode() == KeyEvent.KEYCODE_BACK && channelListVisible) {
                hideChannelList();
                return true;
            }
            // 上/下: 频道列表可见时=导航列表,否则=切换频道
            if (event.getKeyCode() == KeyEvent.KEYCODE_DPAD_UP) {
                if (channelListVisible) {
                    selectedListIndex = Math.max(0, selectedListIndex - 1);
                    updateListHighlight();
                } else {
                    loadChannel(channelIndex - 1);
                }
                return true;
            }
            if (event.getKeyCode() == KeyEvent.KEYCODE_DPAD_DOWN) {
                if (channelListVisible) {
                    selectedListIndex = Math.min(sortedChannelIndices.size() - 1, selectedListIndex + 1);
                    updateListHighlight();
                } else {
                    loadChannel(channelIndex + 1);
                }
                return true;
            }
            // 菜单键: 显示频道列表
            if (event.getKeyCode() == KeyEvent.KEYCODE_MENU) {
                if (channelListVisible) {
                    hideChannelList();
                } else {
                    showChannelList();
                }
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
        // 清理 GeckoView
        if (geckoSession != null) {
            try {
                geckoSession.close();
            } catch (Exception ignored) {
            }
        }
        webView.destroy();
        super.onDestroy();
    }

    // ==================== 频道列表 ====================

    /**
     * 从频道名提取频道号: "CCTV-1 综合"→1, "CCTV-5+ 体育赛事"→5, "CCTV-9 纪录"→9
     */
    private static int extractChannelNumber(String name) {
        if (name == null) return 999;
        int idx = name.indexOf("CCTV-");
        if (idx < 0) return 999;
        int start = idx + 5;
        int end = start;
        while (end < name.length() && Character.isDigit(name.charAt(end))) end++;
        if (end == start) return 999;
        try {
            return Integer.parseInt(name.substring(start, end));
        } catch (NumberFormatException e) {
            return 999;
        }
    }

    /**
     * 按频道号正序排序(CCTV-1, CCTV-2, ..., CCTV-17),同号保持原序。
     */
    private void buildSortedChannelList() {
        sortedChannelIndices = new ArrayList<>();
        for (int i = 0; i < ChannelCatalog.CHANNELS.size(); i++) {
            sortedChannelIndices.add(i);
        }
        Collections.sort(sortedChannelIndices, (a, b) -> {
            int numA = extractChannelNumber(ChannelCatalog.CHANNELS.get(a).name);
            int numB = extractChannelNumber(ChannelCatalog.CHANNELS.get(b).name);
            return Integer.compare(numA, numB);
        });
    }

    /**
     * 显示频道列表(左半屏纵向),高亮当前频道。
     */
    private void showChannelList() {
        if (sortedChannelIndices == null) buildSortedChannelList();
        channelListItems.removeAllViews();
        selectedListIndex = 0;
        for (int i = 0; i < sortedChannelIndices.size(); i++) {
            int origIdx = sortedChannelIndices.get(i);
            Channel ch = ChannelCatalog.CHANNELS.get(origIdx);
            int chNum = extractChannelNumber(ch.name);
            TextView tv = new TextView(this);
            tv.setText(chNum + ". " + ch.name);
            tv.setTextSize(16);
            tv.setPadding(24, 16, 16, 16);
            tv.setTextColor(Color.WHITE);
            if (origIdx == channelIndex) {
                selectedListIndex = i;
            }
            channelListItems.addView(tv);
        }
        channelListScroll.setVisibility(View.VISIBLE);
        channelListVisible = true;
        updateListHighlight();
        updateDebugPanel("频道列表", "OK键选择/返回键关闭");
    }

    private void hideChannelList() {
        channelListScroll.setVisibility(View.GONE);
        channelListVisible = false;
    }

    private void updateListHighlight() {
        for (int i = 0; i < channelListItems.getChildCount(); i++) {
            TextView tv = (TextView) channelListItems.getChildAt(i);
            if (i == selectedListIndex) {
                tv.setBackgroundColor(Color.parseColor("#CCFF8800"));
                tv.setTextColor(Color.BLACK);
                // 滚动到可见位置
                channelListScroll.smoothScrollTo(0, Math.max(0, tv.getTop() - 50));
            } else {
                tv.setBackgroundColor(Color.TRANSPARENT);
                tv.setTextColor(Color.WHITE);
            }
        }
    }

    /**
     * 选中频道列表中当前高亮项:加载频道并隐藏列表。
     */
    private void selectChannelFromList() {
        if (sortedChannelIndices == null || selectedListIndex >= sortedChannelIndices.size()) {
            hideChannelList();
            return;
        }
        int origIdx = sortedChannelIndices.get(selectedListIndex);
        hideChannelList();
        loadChannel(origIdx);
    }

    // ==================== 数字输入 ====================

    /**
     * 按数字键直接跳频道:3秒延迟,支持输入两位数(如 12 → CCTV-12)。
     * 延迟期间在屏幕中央显示已输入的数字。
     */
    private void handleNumberInput(int digit) {
        // 取消之前的定时器
        if (numberInputTimeoutRunnable != null) {
            handler.removeCallbacks(numberInputTimeoutRunnable);
        }
        // 最多两位数
        if (pendingNumber.length() < 2) {
            pendingNumber.append(digit);
        }
        String numStr = pendingNumber.toString();
        numberInputHint.setText(numStr);
        numberInputHint.setVisibility(View.VISIBLE);
        // 3秒后跳转
        final String finalNum = numStr;
        numberInputTimeoutRunnable = () -> {
            numberInputHint.setVisibility(View.GONE);
            pendingNumber.setLength(0);
            try {
                int chNum = Integer.parseInt(finalNum);
                jumpToChannelNumber(chNum);
            } catch (NumberFormatException ignored) {
            }
        };
        handler.postDelayed(numberInputTimeoutRunnable, 3000);
    }

    /**
     * 按频道号跳转:找到第一个匹配的频道并加载。
     */
    private void jumpToChannelNumber(int num) {
        if (sortedChannelIndices == null) buildSortedChannelList();
        for (int i = 0; i < sortedChannelIndices.size(); i++) {
            int origIdx = sortedChannelIndices.get(i);
            Channel ch = ChannelCatalog.CHANNELS.get(origIdx);
            if (extractChannelNumber(ch.name) == num) {
                loadChannel(origIdx);
                return;
            }
        }
        updateDebugPanel("未找到", "频道 " + num);
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
     * 右上角进度提示:只显示简短状态(tag),3秒后自动消失。
     * 详细诊断信息仍写入 logcat(adb logcat -s CCTV-TV)。
     */
    private void updateDebugPanel(String title, String extra) {
        if (title == null) return;
        // 屏幕上只显示简短 tag(第一行),避免大段诊断文字挡住视频
        String display = title;
        if (extra != null && !extra.isEmpty()) {
            int nl = extra.indexOf('\n');
            display = nl > 0 ? title + " " + extra.substring(0, nl) : title + " " + extra;
        }
        if (display.length() > 80) display = display.substring(0, 80);
        progressHint.setText(display);
        progressHint.setVisibility(View.VISIBLE);
        // 自动消失:3秒后隐藏
        handler.removeCallbacks(hideProgressHint);
        handler.postDelayed(hideProgressHint, 3000);
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
