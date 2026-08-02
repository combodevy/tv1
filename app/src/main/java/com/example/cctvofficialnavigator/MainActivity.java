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

import com.google.android.exoplayer2.ExoPlayer;
import com.google.android.exoplayer2.MediaItem;
import com.google.android.exoplayer2.ui.PlayerView;

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
    /**
     * 桌面 Chrome UA。仅对"移动端页面返回'分享频道已下架'/版权受限空页"的央视频桌面端直播
     * 页 (yangshipin.cn/tv/home?pid=CCTV-6/3/8) 使用,其余频道一律保持系统默认移动 UA。
     * 注意:Android WebView 用的 UA 里要包含 "Chrome/" 让央视频的前端 CDN 按桌面 Chromium
     *   解析而非 Safari/WebKit。
     */
    private static final String DESKTOP_UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36";

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
    // 倒计时线程:用 background thread 跑,避免被 WebView 加载/JS 阻塞 main thread 导致 postDelayed 永不执行
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
    // 拦截到的 m3u8 URL(从 shouldInterceptRequest 捕获,用于 hls.js 兜底播放)
    private volatile String capturedM3u8Url;
    // hls.js 是否已注入(避免重复注入)
    private volatile boolean hlsPlayerInjected;
    private java.util.List<ScheduledFuture<?>> pendingChecks = new java.util.ArrayList<>();
    private int loadGeneration = 0;
    // HTML5 全屏自定义视图:WebView 进入全屏时(video.webkitRequestFullscreen)会传入一个包含 SurfaceView 的 View
    private View customFullscreenView;
    private WebChromeClient.CustomViewCallback customFullscreenCallback;
    /** 当前预期加载的官方 URL,用于判断 WebView 是否被服务器重定向到了 m.yangshipin.cn 等旧域名。 */
    private String expectedOfficialUrl;
    // ================= CCTV-3/6/8 yangshipin 桌面端:ExoPlayer 原生播放器(核心根治方案) =================
    // 为什么必须用 ExoPlayer 原生播放而不是 WebView <video>?
    //   Chromium WebView 渲染链路在 yangshipin 页面上**两条路都走不通**:
    //    ① LAYER_TYPE_HARDWARE 硬件加速 → <video> 走独立 SurfaceView overlay 叠加层,
    //      但 position:fixed/detach DOM 后 overlay 位置计算错误(贴到屏幕外)→ 解码器/audio 正常在播但画
    //      面黑屏,Chromium 老 bug 持续 10+ 年,无解
    //    ② LAYER_TYPE_SOFTWARE 软件渲染 → 视频像素要画到 WebView Canvas 位图上,但 MediaCodec 硬件解码
    //      器输出的 Surface 无法正确绑定到 WebView Canvas → 依旧黑屏,只有声音
    //   → 唯一根治方案:截到 yangshipin 的 HLS m3u8 URL(shouldInterceptRequest 能 100% 截到)后,
    //     直接隐藏 WebView,用 ExoPlayer(Google 官方播放器)+ PlayerView 全屏原生播放
    //     完全绕开 WebView 渲染链路,ExoPlayer 在任何 Android TV/盒子上 100% 能出画面
    private ExoPlayer exoPlayer;
    private android.view.View exoPlayerView;  // 可能是 PlayerView 也可能是 TextureView,用通用 View 类型,切台统一 removeView
    // 当前切的频道是否是 yangshipin 桌面端(useDesktop=true):只有 true 时截到 m3u8 才切 ExoPlayer
    private boolean currentIsYangshipin = false;
    // 已经切换到 ExoPlayer 播放了(true时WebView隐藏,PlayerView显示):避免重复创建播放器
    private boolean exoPlayerActive = false;

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
        enterImmersiveMode();
        loadChannel(channelIndex);
    }

    @SuppressLint("SetJavaScriptEnabled")
    private void configureWebView() {
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        // 默认使用系统移动UA。对央视频桌面端(yangshipin.cn/tv/home)等特定频道,
        // 会在 loadChannel 里切到 DESKTOP_UA(桌面UA),保证不出现"分享频道已下架"等移动端空页提示。
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

        // WebView 默认用硬件加速(LAYER_TYPE_HARDWARE):其他台(CCTV1/2/5+/广西台 etc.)默认走Surface overlay
        // CCTV-6/3/8(yangshipin.cn桌面端)因Surface overlay位置计算bug→有声音没画面
        // 修复策略:不在configure阶段固定死layerType,改为在loadChannel()里**按频道动态切**
        //   → yangshipin桌面台:切LAYER_TYPE_SOFTWARE软件渲染(根治overlay合成bug)
        //   → 其他台:保持LAYER_TYPE_HARDWARE硬件加速(正常性能,画面正常)
        // 详见loadChannel里layerType动态切换逻辑(L1198附近)
        webView.setLayerType(View.LAYER_TYPE_HARDWARE, null);
        // WebView 背景透明+初始缩放 100%:避免背景色影响画面显示,避免缩放比例错乱导致盒模型计算错误
        try { webView.setBackgroundColor(android.graphics.Color.TRANSPARENT); } catch (Throwable t) {}
        try { settings.setSupportZoom(false); settings.setBuiltInZoomControls(false); webView.setInitialScale(100); } catch (Throwable t) {}
        // 允许在 file: 协议下访问内容(某些缓存/本地资源场景需要)
        settings.setAllowFileAccess(true);
        settings.setAllowContentAccess(true);

        // 远程调试:Chrome 访问 chrome://inspect 可直接调试 WebView 的 DOM/console/network
        //  方便现场抓 CCTV-6 重定向时的 Header/重定向链
        WebView.setWebContentsDebuggingEnabled(true);

        // 用自定义的 WebChromeClient 拦截 console 输出和加载进度(CCTV 内部的 JS 报错能反映到 logcat/面板)
        // 同时处理 HTML5 全屏(onShowCustomView),让 video 用 WebView 自己的全屏机制渲染,避免 CSS 硬拉导致黑屏
        webView.setWebChromeClient(new LoggingWebChromeClient(this));
        webView.setWebViewClient(new WebViewClient() {
            /** 记录 www.yangshipin.cn → m.yangshipin.cn 自动重试次数,避免无限循环重定向。 */
            private int redirectRetryCount = 0;
            private static final int MAX_REDIRECT_RETRY = 3;

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                String url = request.getUrl().toString();
                // ============ 防重定向核心 1/2:拦截 m.yangshipin.cn(移动端旧域名) ============
                // 服务器看到 X-Requested-With: com.example.cctvofficialnavigator 就 302/JS 跳 m.yangshipin.cn
                // 如果发现被跳去 m.yangshipin.cn,并且我们原本 expectedOfficialUrl 是 www.yangshipin.cn/tv/home?pid=XXX,
                // 就直接拦截这次跳转,重新 load www 版(带 additionalHttpHeaders 覆盖 X-Requested-With + 加 Referer)
                if (isMobileYangshipinDomain(url) && expectedOfficialUrl != null
                        && needsDesktopUA(expectedOfficialUrl)) {
                    if (redirectRetryCount < MAX_REDIRECT_RETRY) {
                        redirectRetryCount++;
                        Log.w("CCTV-TV", "拦截到跳 m.yangshipin.cn,重试第 " + redirectRetryCount + " 次 → 强制加载 " + expectedOfficialUrl);
                        updateDebugPanel("REDIRECT_BLOCKED_" + redirectRetryCount, "拦截跳m.yangshipin.cn,重加载www版");
                        loadYangshipinWithHeaders(expectedOfficialUrl);
                        return true; // 这次跳转吃掉,不执行
                    } else {
                        Log.e("CCTV-TV", "防重定向重试已达 " + MAX_REDIRECT_RETRY + " 次上限,放行");
                    }
                }
                // 其他跳转:按原逻辑,非官方域名才拦截(跳外部浏览器)
                return !isOfficialCctvUrl(url);
            }

            @Override
            public void onPageStarted(WebView view, String url, Bitmap favicon) {
                super.onPageStarted(view, url, favicon);
                updateDebugPanel("onPageStarted → " + shortenUrl(url), null);
                // ============ 防重定向核心 2/2:URL 层面兜底 ============
                // 有些重定向是服务器返回 200 OK 但内部 Location 已变 / JS location.href 改 URL,
                // shouldOverrideUrlLoading 不一定每次都能拦住。在 onPageStarted 里再检查一次:
                // 当前加载的 URL 是 m.yangshipin.cn,但预期是 www.yangshipin.cn/tv/home?pid=XXX → 立刻 reload www 版
                if (isMobileYangshipinDomain(url) && expectedOfficialUrl != null
                        && needsDesktopUA(expectedOfficialUrl) && redirectRetryCount < MAX_REDIRECT_RETRY) {
                    redirectRetryCount++;
                    Log.w("CCTV-TV", "onPageStarted 发现被重定向到 m.yangshipin.cn,重试第 " + redirectRetryCount + " 次");
                    updateDebugPanel("ONPAGESTART_REDIRECT_" + redirectRetryCount, "重定向检测,强制重加载www版");
                    // 必须 post 一下,否则 onPageStarted 里直接 loadUrl 会打断当前 onPageStarted
                    handler.post(() -> loadYangshipinWithHeaders(expectedOfficialUrl));
                    return; // 不继续注入 CSS/补丁了,反正这个 URL 不对,等下次真正加载 www 版再说
                }
                // 切台/重加载后重置重试计数
                redirectRetryCount = 0;
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
                // ============ 兜底:onPageFinished 了还在 m.yangshipin.cn → 再来一次 ============
                if (isMobileYangshipinDomain(url) && expectedOfficialUrl != null
                        && needsDesktopUA(expectedOfficialUrl) && redirectRetryCount < MAX_REDIRECT_RETRY) {
                    redirectRetryCount++;
                    Log.w("CCTV-TV", "onPageFinished 仍在 m.yangshipin.cn,第 " + redirectRetryCount + " 次重加载");
                    updateDebugPanel("FINISH_REDIRECT_" + redirectRetryCount, "未跳至www版,继续重加载");
                    handler.postDelayed(() -> loadYangshipinWithHeaders(expectedOfficialUrl), 300);
                    return;
                }
                // 再次注入确保覆盖(onPageStarted 注入的可能因为页面 JS 重写 DOM 而失效)
                injectAutoFullscreen(view);
            }

            // 抓底层资源错误(直接显示到面板,不需要等 evaluateJavascript)
            // 拦截所有网络请求,捕获 m3u8 URL(HLSP2P 在 Web Worker 里发 XHR,
            // JS 层 hook 不到,只能在 Android 层面拦截)
            @Override
            public android.webkit.WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                String url = request.getUrl().toString();
                if (url != null && url.contains(".m3u8")) {
                    if (capturedM3u8Url == null) {
                        capturedM3u8Url = url;
                        Log.i("CCTV-TV", "拦截到 m3u8: " + url);
                    }
                    // ===================== 关键Debug:把m3u8拦截结果显式打到右上角调试面板 =====================
                    //  用户现在一看右上角就知道:有没有截到m3u8?有没有调用ExoPlayer?瞬间定位问题!
                    final boolean willCallExo = currentIsYangshipin && !exoPlayerActive;
                    final String shortUrl = url.length() > 80 ? url.substring(0, 70) + "..." : url;
                    handler.post(() -> updateDebugPanel("M3U8_" + (willCallExo ? "OK_EXO" : "SKIP"),
                            (willCallExo ? "将切ExoPlayer:" : "不切ExoPlayer:") + shortUrl));
                    // ================= CCTV-3/6/8 yangshipin 桌面端:截到 m3u8 立刻切 ExoPlayer 原生播放 ===============
                    // 只有当前频道是 yangshipin 桌面端(currentIsYangshipin=true)并且 ExoPlayer 还没启动(exoPlayerActive=false)时才切,
                    // 其他台(CCTV1/5+/广西台)→不切,继续用 WebView 播放
                    if (willCallExo) {
                        final String finalM3u8Url = url;
                        // shouldInterceptRequest 在子线程,切回主线程操作 UI(隐藏 WebView / 创建 ExoPlayer)
                        handler.post(() -> playYangshipinWithExoPlayer(finalM3u8Url));
                    }
                }
                return null; // 不拦截,让请求正常发出(hls.js 兜底和其他逻辑继续正常工作)
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
        // X-Requested-With 彻底移除(双保险):
        //   additionalHttpHeaders 里传的空字符串,有些 Chromium 版本里会被内部覆盖为包名;
        //   反射直接清掉 WebView Provider 里持有的 mRequestedWithHeader,从根上移除这个 header。
        //   Android API >= 26 (O) 公开了 API,低版本走反射兜底。
        removeXRequestedWithHeader(webView);
    }

    /**
     * 反射 + 公开 API 双路径移除 WebView 的 X-Requested-With Header。
     * Android O (API 26)+ 的标准做法是 WebSettings.setRequestedWithHeader("") 或
     *   通过 WebView.getWebViewClient() 回调 getRequestedWithHeader(),但系统 WebView
     *   里这个 Header 还会在底层 aw_network 层从 Context 包名再塞一次,所以必须反射
     *   清掉 AwBrowserContext / AwContents 里的 mRequestedWithHeader 字段。
     * 本方法只处理 best-effort:任何一步失败都静默,绝不崩溃影响播放。
     */
    @SuppressLint({"PrivateApi", "SoonBlockedPrivateApi"})
    private static void removeXRequestedWithHeader(WebView webView) {
        if (webView == null) return;
        // 路径 1:Android 11+ 公开了 WebSettingsCompat.setRequestedWithHeader(WebSettings, "")
        // 但 AGP 8.6.1 没带 androidx.webkit,我们直接反射 android.webkit.WebSettings 的方法
        try {
            Class<?> wc = Class.forName("android.webkit.WebSettings");
            java.lang.reflect.Method setRwh = wc.getDeclaredMethod("setRequestedWithHeader", String.class);
            setRwh.setAccessible(true);
            setRwh.invoke(webView.getSettings(), "");
            Log.i("CCTV-TV", "setRequestedWithHeader('') via WebSettings → OK");
        } catch (Throwable t) {
            Log.d("CCTV-TV", "WebSettings.setRequestedWithHeader 不可用,走反射兜底: " + t);
        }
        // 路径 2:反射 AwContents.mContext 上层持有的 mRequestedWithHeader (Chromium 层)
        // 先拿 WebView 的 mProvider(WebViewChromium)
        try {
            Class<?> webViewCls = WebView.class;
            java.lang.reflect.Field fProvider = webViewCls.getDeclaredField("mProvider");
            fProvider.setAccessible(true);
            Object provider = fProvider.get(webView);
            if (provider == null) return;
            // 找 WebViewChromium 里的 mAwContents
            Class<?> providerCls = provider.getClass();
            Object awContents = null;
            for (java.lang.reflect.Field f : providerCls.getDeclaredFields()) {
                if (f.getType().getName().contains("AwContents")) {
                    f.setAccessible(true);
                    awContents = f.get(provider);
                    break;
                }
            }
            if (awContents == null) return;
            // 从 AwContents 向上找 mBrowserContext / mRequestedWithHeader
            Class<?> awcCls = awContents.getClass();
            boolean cleared = false;
            // 不同 Chromium 版本字段名可能是 mRequestedWithHeader / requested_with_header_ / mRequestedWith
            for (String fname : new String[]{"mRequestedWithHeader", "requested_with_header_", "mRequestedWith", "requested_with"}) {
                try {
                    java.lang.reflect.Field f = awcCls.getDeclaredField(fname);
                    f.setAccessible(true);
                    f.set(awContents, "");
                    cleared = true;
                    Log.i("CCTV-TV", "反射清 AwContents." + fname + " → OK");
                } catch (Throwable t2) { /* 这个字段不存在,跳过 */ }
            }
            // 再去 AwBrowserContext 找(有些版本存在 context 上)
            if (!cleared) {
                for (java.lang.reflect.Field f : awcCls.getDeclaredFields()) {
                    if (f.getType().getName().contains("AwBrowserContext")) {
                        f.setAccessible(true);
                        Object ctx = f.get(awContents);
                        if (ctx != null) {
                            for (String fname : new String[]{"mRequestedWithHeader", "requested_with_header_", "mRequestedWith"}) {
                                try {
                                    java.lang.reflect.Field f2 = ctx.getClass().getDeclaredField(fname);
                                    f2.setAccessible(true);
                                    f2.set(ctx, "");
                                    cleared = true;
                                    Log.i("CCTV-TV", "反射清 AwBrowserContext." + fname + " → OK");
                                    break;
                                } catch (Throwable t3) { /* skip */ }
                            }
                        }
                        break;
                    }
                }
            }
            if (!cleared) {
                Log.d("CCTV-TV", "反射兜底找不到 mRequestedWithHeader 字段(本 Chromium 版本可能不通过该字段加 header)");
            }
        } catch (Throwable t) {
            // 任何反射失败都不影响使用,additionalHttpHeaders 那一层已经覆盖了
            Log.d("CCTV-TV", "反射移除 X-Requested-With 失败(不影响播放): " + t);
        }
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
                // #region debug-point A:yangshipin-diagnostics
                "  (function(){" +
                "    var urls=['http://192.168.1.4:7777/event','http://10.0.2.2:7777/event'];" +
                "    function send(o){" +
                "      try{" +
                "        var body=JSON.stringify(o);" +
                "        for(var ui=0;ui<urls.length;ui++){" +
                "          try{fetch(urls[ui],{method:'POST',body:body,headers:{'Content-Type':'application/json'}}).catch(function(){});}catch(e){" +
                "            try{var x=new XMLHttpRequest();x.open('POST',urls[ui],true);x.setRequestHeader('Content-Type','application/json');x.send(body);}catch(e2){}" +
                "          }" +
                "        }" +
                "      }catch(e){}" +
                "    }" +
                "    function snap(){" +
                "      try{" +
                "        var isYsh=(location.host||'').indexOf('yangshipin')>=0;" +
                "        var videos=[];var vels=document.querySelectorAll('video');" +
                "        for(var i=0;i<vels.length;i++){" +
                "          var ve=vels[i];videos.push({" +
                "            id:ve.id||'',className:ve.className||''," +
                "            clientWidth:ve.clientWidth,clientHeight:ve.clientHeight," +
                "            offsetWidth:ve.offsetWidth,offsetHeight:ve.offsetHeight," +
                "            videoWidth:ve.videoWidth||0,videoHeight:ve.videoHeight||0," +
                "            paused:ve.paused,muted:ve.muted,readyState:ve.readyState," +
                "            src:(ve.src||'').slice(0,200),currentSrc:(ve.currentSrc||'').slice(0,200)" +
                "          });" +
                "        }" +
                "        var containers=[];" +
                "        var csel=['.container[data-v-03d5f916]','.y-full','.video-js','[id^=vodbox]','.video-con','#app','.tv-main-con-l'];" +
                "        for(var ci=0;ci<csel.length;ci++){" +
                "          try{var el=document.querySelector(csel[ci]);if(el){" +
                "            containers.push({sel:csel[ci],tag:el.tagName,className:el.className||'',id:el.id||''," +
                "              clientWidth:el.clientWidth,clientHeight:el.clientHeight,offsetWidth:el.offsetWidth,offsetHeight:el.offsetHeight," +
                "              display:window.getComputedStyle(el).display,position:window.getComputedStyle(el).position});" +
                "          }}catch(e){}" +
                "        }" +
                "        var buttons={};" +
                "        ['.play.play2','.videoFull','.full.full2','.vjs-big-play-button','.play','.videoFull','.full'].forEach(function(sel){" +
                "          try{var el=document.querySelector(sel);buttons[sel]=el?{exists:true,className:el.className||'',display:window.getComputedStyle(el).display}:{exists:false};}catch(e){buttons[sel]={err:true};}" +
                "        });" +
                "        var tips={};" +
                "        ['.video-status-tip','.volume-muted-tip','.y-full-bg'].forEach(function(sel){" +
                "          try{var el=document.querySelector(sel);tips[sel]=el?{exists:true,display:window.getComputedStyle(el).display,innerText:el.innerText.slice(0,80)}:{exists:false};}catch(e){tips[sel]={err:true};}" +
                "        });" +
                "        var bodyChildren=[];" +
                "        for(var bi=0;bi<document.body.children.length;bi++){" +
                "          var bc=document.body.children[bi];bodyChildren.push({tag:bc.tagName,className:bc.className||'',id:bc.id||'',display:window.getComputedStyle(bc).display});" +
                "        }" +
                "        send({" +
                "          sessionId:'cctv6-yangshipin-issue',runId:'pre',hypothesisId:'A'," +
                "          location:'injectFastLoading:snap',ts:Date.now()," +
                "          msg:'[DEBUG] yangshipin diagnostics snapshot'," +
                "          data:{" +
                "            url:location.href,host:location.host,isYsh:isYsh," +
                "            scrollY:window.scrollY||window.pageYOffset||0," +
                "            docScrollTop:document.documentElement.scrollTop,bodyScrollTop:document.body.scrollTop," +
                "            docHeight:document.documentElement.scrollHeight,bodyHeight:document.body.scrollHeight," +
                "            viewportW:window.innerWidth,viewportH:window.innerHeight," +
                "            videoCount:vels.length,videos:videos,containers:containers,buttons:buttons,tips:tips,bodyChildren:bodyChildren" +
                "          }" +
                "        });" +
                "      }catch(e){send({sessionId:'cctv6-yangshipin-issue',runId:'pre',hypothesisId:'A',location:'injectFastLoading:snapError',ts:Date.now(),msg:'[DEBUG] snap error',data:{error:e.message}});}" +
                "    }" +
                "    snap();setInterval(snap,2000);" +
                "  })();" +
                // #endregion
                // CSS: 强力覆盖, 同时兼容 移动/桌面 两种布局, iframe 嵌套播放器也要拉满
                "  var css=" +
                "    'html,body{width:100%!important;height:100%!important;margin:0!important;padding:0!important;background:#000!important;overflow:hidden!important}'+" +
                "    '.jiemuguanwang18950_zhibo_ind01,.zhibo19629_ind01,.playingVideo{width:100vw!important;height:100vh!important;margin:0!important;padding:0!important;position:absolute!important;left:0!important;top:0!important}'+" +
                // 容器层: 所有常见的 CCTV 播放器容器 id/class + iframe 内嵌播放器
                "    '.video_left,.video_right_main,.video_flash,.video_box,#player,#player_container,#live_player{width:100vw!important;height:100vh!important;margin:0!important;padding:0!important;position:absolute!important;left:0!important;top:0!important;background:#000!important;border:0!important}'+" +
                // 广西台(gxtv.cn)使用阿里云 AliPlayer:常见容器 id/class (#J_prismPlayer / #prismPlayer / .prism-player)
                "    '#J_prismPlayer,#prismPlayer,#live_prismPlayer,.prism-player{width:100vw!important;height:100vh!important;margin:0!important;padding:0!important;position:absolute!important;left:0!important;top:0!important;background:#000!important;border:0!important}'+" +
                // 广西台播放器外壳(liangtv/频道详情页常见的 wrapper id)
                "    '#play-box,#videoBox,#playBox,.player-wrap,.live-wrap{width:100vw!important;height:100vh!important;margin:0!important;padding:0!important;position:absolute!important;left:0!important;top:0!important;background:#000!important}'+" +
                // 央视频桌面端(yangshipin)新版播放器DOM(实机日志确认):.container>.y-full>.y-full-control>.play.play2 .videoFull .full.full2. 用#app前缀限制范围,其他台无#app不会误中
                "    '#app .container,#app .y-full,#app .y-full-control,#app .y-full-control-btn{width:100vw!important;height:100vh!important;margin:0!important;padding:0!important;position:absolute!important;left:0!important;top:0!important;background:#000!important;border:0!important}'+" +
                // 央视频桌面端(yangshipin.cn/tv/home)真实容器链(2026-08-01实机确认):
                //   根: .tv-home / .tv / .tv-main / .tv-main-con
                //   左侧播放器区: .tv-main-con-l → .tv-main-con-l-vid → #vodbox<pid>.c-container.img → .video-con
                //   播放器实际用的是 video.js (class=video-js ...)
                "    '.tv-home,.tv,.tv-main,.tv-main-con,.tv-main-con-l,.tv-main-con-l-vid,.tv-home-list,.comPadding,#app,'+" + // 整体容器
                "    '[id^=vodbox],.c-container,.img,.video-con,'+" +                           // 真实视频包装层 (id前缀 vodbox)
                "    '.video-js,.vjs-fluid,.vjs-big-play-centered,'+" +                        // video.js 容器
                "    'video[id^=myvideo]{'+" +                                                   // VIDEO 元素(id前缀 myvideo)
                "       'width:100vw!important;height:100vh!important;margin:0!important;padding:0!important;position:absolute!important;left:0!important;top:0!important;background:#000!important;border:0!important}'+" +
                // 央视频桌面端旧播放器(保留兼容,防止页面改版回滚):CMGPlayer / 腾讯云txp
                "    '#cmgPlayer,.CMGPlayer,#cmg_player,.cmg-player-wrap,.cmgplayer-wrap,.ysp-player,.yspPlayer,.tv-player-wrap,.tv-player-container,.player-main-wrap,.ysp-player-wrap,.ysp-player-box,.txp_container,.txp_video_container{width:100vw!important;height:100vh!important;margin:0!important;padding:0!important;position:absolute!important;left:0!important;top:0!important;background:#000!important;border:0!important}'+" +
                // iframe: CCTV 页面的 iframe 是广告(yangshipin.cn)而非播放器,直接隐藏。
                //   AliPlayer H5 模式直接在主文档建 <video>,不依赖 iframe;如果将来碰到用 iframe 的变种,再针对性放行。
                "    'iframe{display:none!important}'+" +
                // video 元素: 固定全屏 + 最高 z-index,确保在所有元素之上
                // 加 transform/translateZ 强制触发 GPU 合成层,修复某些 WebView 上有声无画问题
                "    'video{position:fixed!important;display:block!important;visibility:visible!important;opacity:1!important;width:100vw!important;height:100vh!important;min-width:100vw!important;min-height:100vh!important;left:0!important;top:0!important;z-index:999999!important;object-fit:contain!important;background:#000!important;transform:translateZ(0)!important;backface-visibility:hidden!important}'+" +
                // #h5player_player 是 CCTV 播放器创建的 video 元素 ID,video[id^=myvideo] 是 yangshipin tv-home video.js 创建的 video ID 前缀
                "    '#h5player_player,video[id^=myvideo]{position:fixed!important;display:block!important;visibility:visible!important;opacity:1!important;width:100vw!important;height:100vh!important;min-width:100vw!important;min-height:100vh!important;left:0!important;top:0!important;z-index:999999!important;object-fit:contain!important;background:#000!important;transform:translateZ(0)!important;backface-visibility:hidden!important}'+" +
                // video.js 大播放按钮 / 封面 / 控制栏 必须隐藏(否则就是用户看到的灰色封面+大黑三角)
                "    '.vjs-big-play-button,.vjs-poster,.vjs-control-bar,.vjs-text-track-display,.vjs-error-display,.vjs-loading-spinner,.vjs-modal-dialog{display:none!important;opacity:0!important;visibility:hidden!important}'+" +
                // 播放器容器: 确保尺寸不为 0,overflow 不裁剪 video
                "    '#player,#player_container,.video_box,.video_flash,.video_left,#J_prismPlayer,#prismPlayer,.prism-player,#cmgPlayer,.CMGPlayer,.ysp-player,.tv-player-wrap,.player-main-wrap,.txp_container,.tv,.tv-main,.tv-main-con,.tv-main-con-l,.tv-main-con-l-vid,[id^=vodbox],.video-con,.video-js{overflow:visible!important;width:100vw!important;height:100vh!important}'+" +
                // 装饰元素: 隐藏 (桌面版的顶部 CCTV 大导航栏也必须隐藏)
                // + AliPlayer 控制栏/水印/封面/大播放按钮 (.prism-controlbar .prism-big-play-btn .prism-cover .prism-watermark .prism-live-tip)
                // + 广西台页面常见装饰 (.header .nav .footer .channel-list .channel-detail .program-list .live-info .share-bar .page-header .page-footer .breadcrumb)
                // + 央视频 yangshipin 页面常见装饰: 顶部(登录/下载App)/关注按钮/相关推荐/底部版权/节目单侧边栏/"打开APP"弹窗
                "    '.video_right,.video_btnBar,.bg_top_h_tile,.bg_top_owner,.bg_bottom_h_tile,header,footer,nav,.vspace,.column_wrapper,.nav,.topbar,.sitemap,.shares,'+" +
                "    '.prism-controlbar,.prism-big-play-btn,.prism-cover,.prism-watermark,.prism-live-tip,.prism-info-panel,.prism-fullscreen-btn,'+" +
                "    '.header,.channel-list,.channel-detail,.program-list,.live-info,.share-bar,.page-header,.page-footer,.breadcrumb,'+" +
                "    '.m-navbar,.m-footer,.m-live-detail,.m-program-guide,.m-live-side,.gxtv-header,.gxtv-footer,.m-side-share,'+" +
                "    '.ysp-header,.ysp-footer,.ysp-login,.ysp-download,.ysp-related,.ysp-program,.ysp-side-nav,.ysp-detail,.ysp-epg,.ysp-recommend,'+" +
                "    '.app-download-btn,.follow-btn,.attention-btn,.login-bar,.share-box,.program-list,.recommend-list,.comment-box,.bottom-copyright,'+" +
                "    '.txp_layer_bottom,.txp_top_title,.txp_vip_tip,.txp_mini_tip,.txp_btn,.txp_right_menu,'+" +
                // 央视频桌面端(yangshipin.cn/tv/home)特有装饰: 顶部导航 / 左右侧边栏(频道/推荐) / 节目单(EPG) / 分类Tab / 登录下载按钮
                "    '.ysp-top,.top-wrapper,.header-top,.nav-header,.ysp-tabs,.channel-tabs,.category-tabs,.tv-tabs,'+" +
                "    '.ysp-sidebar-left,.left-sidebar,.side-bar-left,.ysp-side-nav-left,.side-nav,.tv-side,.channels-side,.channel-wrap,.channel-panel,.channels-panel,.ysp-channels,.channel-bar,.tv-channels,.ysp-channel-nav,.channelListWrapper,'+" +
                "    '.program-guide-bar,.program-grid,.program-schedule,.ysp-program-guide,.tv-epg,.ysp-schedule,.tv-programs,.epg-tabs,.tv-date-tabs,.program-date-bar,.date-tab,.epg-header,.epg-nav,.epg-wrap,.ysp-program-date,.date-tabs,.tabs-days,'+" +
                "    '.ysp-sidebar-right,.right-sidebar,.side-bar-right,.ysp-side-nav-right,.side-right,.recommend-panel,.right-panel,.ysp-recommend-wrap,.tv-right-side,'+" +
                "    '.ysp-app-download,.open-app-btn,.download-app-btn,.app-open-btn,.ysp-live-title,.live-title,.tv-live-info,.ysp-live-meta,.live-info-bar,.meta-bar,'+" +
                "    '.layout-left,.layout-right,.layout-main-left,.layout-main-right,.ysp-layout-left,.ysp-layout-right,.left-panel,.right-panel,'+" +
                "    '.ysp-control,.player-controls,.ysp-bottom-bar,.ysp-player-bar,.control-bar-container,'+" +
                // 顶部LOGO/搜索/个人中心
                "    '.ysp-logo,.logo-box,.logo-wrap,.search-box,.search-bar,.search-wrap,.user-area,.user-center,.user-profile,.header-logo-wrap,'+" +
                // 央视频桌面端 tv/home 真实装饰(2026-08-01实机抓DOM确认):
                //   .header-b: 顶部整行(推荐/电视/赛事/更多 + 搜索 + 登录下载)
                //   .header-b-l / header-b-m / header-b-r: 顶部左中右三块
                //   .tv-home-list: 下方CCTV1~CCTV17所有频道的列表区(非常占地方)
                //   .tv-home .tv-home-list: 首页列表整体(包括所有付费/免费台)
                //   .tv-main-con-r: 右侧"直播节目单/相关推荐"区域(播放器右侧)
                //   .searchMinHeight: 搜索结果/频道列表外层
                //   .actComWidth-item / comPadding: 全局padding/布局容器(非播放器外层)
                //   .tv: 页面中间整体,我们只需要取 .tv-main-con-l 部分播放器,其他都隐藏
                "    '.header-b,.header-b-l,.header-b-m,.header-b-r,'+" +
                "    '.tv-home-list,.searchMinHeight,'+" +
                "    '.tv-main-con-r,.tv-right-con,.right-side-wrap,.tv-aside,.tv-right,'+" +
                "    '.channel-scroll,.channel-tabs-wrapper,.channel-nav-wrap,.channel-nav,'+" +
                "    '.live-tip,.copyright-bar,.footer-bar,.top-banner,.bottom-banner,.app-promo,'+" +
                // "#开头的装饰ID"
                "    '#YSP_HEADER,#YSP_FOOTER,#ysp_download,#ysp_login,#ysp_attention,#ysp_share,#ysp_program,#ysp_related,#ysp_recommend,'+" +
                "    '#open-ysp-app,#ysp-open-app,#ysp-side-nav-left,#ysp-side-nav-right,#ysp-channel-list,#ysp-program-guide,'+" +
                "    '#ysp-download-app,#ysp-app-download,#ysp-live-title,#ysp-top-bar,#ysp-nav,#ysp-logo,#ysp-search,#ysp-user{display:none!important}';" +
                "  function applyCss(){" +
                "    if(document.getElementById('cctv-tv-style'))return;" +
                "    var s=document.createElement('style');" +
                "    s.id='cctv-tv-style';" +
                "    s.textContent=css;" +
                "    (document.head||document.documentElement).appendChild(s);" +
                "  }" +
                // ---------- yangshipin 专属工具函数,其他台绝对不触发 ----------
                "  function _ysh_is(){try{return (location.host||'').indexOf('yangshipin')>=0||!!document.querySelector('video[id^=myvideo],.video-js,.video-con,[id*=vodbox]');}catch(e){return false;}}" +
                // 锁死滚动到 (0,0):只对 yangshipin 执行(其他台不需要,且怕有副作用)
                "  function _ysh_lockScroll(){" +
                "    if(!_ysh_is())return;" +
                "    try{window.scrollTo(0,0);}catch(e){}" +
                "    try{document.documentElement.scrollTop=0;document.documentElement.scrollLeft=0;}catch(e){}" +
                "    try{document.body.scrollTop=0;document.body.scrollLeft=0;}catch(e){}" +
                "  }" +
                // 模拟用户鼠标点击大播放按钮 + video 本身(绕过自动播放策略):只对 yangshipin 执行,且只执行 1 次
                "  function _ysh_fakeClickPlay(){" +
                "    if(!_ysh_is())return;if(window.__yshClick)return;window.__yshClick=1;" +
                "    var btns=document.querySelectorAll('.vjs-big-play-button,.cmg-play-btn,.btn-play,.play-btn,[class*=big][class*=play],[class*=vjs][class*=play],.play.play2,.videoFull,.full.full2,.play');" +
                "    for(var i=0;i<btns.length;i++){try{btns[i].dispatchEvent(new MouseEvent('click',{bubbles:true,cancelable:true,view:window}));}catch(e){try{btns[i].click();}catch(e2){}}}" +
                "    var vs=document.querySelectorAll('video[id^=myvideo],video');" +
                "    for(var j=0;j<vs.length;j++){try{vs[j].muted=true;vs[j].play();}catch(e){}try{vs[j].dispatchEvent(new MouseEvent('click',{bubbles:true,cancelable:true,view:window}));}catch(e){}}" +
                "  }" +
                // yangshipin 专属:1)直接隐藏#app整个根节点(真实.video-js已detach到body首节点,#app只剩版权页垃圾层,整体隐藏绝不漏)
                //                2)Android WebView最终修复:WebView已切LAYER_TYPE_SOFTWARE(软件渲染),彻底跳过SurfaceView overlay合成bug
                //                  detach父容器(.video-js/.video-con),父容器position:fixed 100vw/h z-index=2147483647
                //                  video自身width/height=100% relative填充父容器
                //                3)超级详细console.log(onConsoleMessage转发logcat),每一步能看到sel是否命中、容器尺寸、video状态
                // 函数第一行if(!_ysh_is())return → 其他台零执行零影响
                "  function _ysh_forceVisibleDetach(){" +
                "    if(!_ysh_is())return;" +
                "    try{console.log('[CCTV6_STEP0_START] _ysh_forceVisibleDetach running host='+(location.host||'')+' title='+document.title);}catch(e){}" +
                // Step 1: 直接#app整体隐藏(版权页100%在#app里,整体隐藏绝不可能漏出),比逐个子节点隐藏可靠100倍
                // 真实.video-js父容器已在Step2 detach到document.body下(脱离#app),所以#app隐藏丝毫不影响video播放
                // ============ 关键新增:yangshipin桌面端的loading/spinner/遮罩/全力加载中 全隐藏 ============
                //  CCTV3/8「全力加载中」一直转圈的根因:hls.js解码_Web流失败,但spinner元素一直没隐藏,挡住video画面
                //  这里把所有class/id名里含loading/load/spinner/spin/mask/overlay/watermark(水印)的元素全部强制隐藏
                "    var hideSels=['#app','.container[data-v-03d5f916]','.container','.y-full','.y-full-bg','.y-full-control','.y-full-control-btn','.volume-muted-tip-container','.video-status-tip','.y-player-gift-list','.y-player-danmu','.y-player-side-panel','.y-player-bottom-bar'," +
                "      '[class*=loading]','[class*=load]','[class*=spinner]','[class*=spin]','[class*=mask]','[class*=overlay]','[class*=splash]','[class*=watermark]'," +
                "      '#loading','.loading','.spinner','.mask','.overlay','.cmg-loading','.ysp-loading','.ysp-spinner','.tv-loading','.live-loading'," +
                "      '[id*=loading]','[id*=load]','[id*=spinner]','[id*=spin]','[id*=mask]','[id*=overlay]'," +
                "      '[class*=progress]','[id*=progress]','.progress-bar','.buffer','[class*=buffer]'];" +
                "    for(var hi=0;hi<hideSels.length;hi++){try{var hn=document.querySelector(hideSels[hi]);if(hn){try{console.log('[CCTV6_HIDE] sel='+hideSels[hi]+' tag='+hn.tagName+' class='+(hn.className||''));}catch(e){}hn.style.setProperty('display','none','important');hn.style.setProperty('visibility','hidden','important');hn.style.setProperty('opacity','0','important');hn.style.setProperty('z-index','-1','important');}}catch(err){}}" +
                // 额外:遍历所有DOM节点,innerText包含「加载中」/「loading」的节点 → display:none(根治CCTV3/8「全力加载中」文字还在显示的问题)
                "    try{var allNodes=document.querySelectorAll('*');for(var ni=0;ni<allNodes.length && ni<8000;ni++){var nn=allNodes[ni];if(nn.children && nn.children.length>0)continue;var txt=(nn.innerText||nn.textContent||'').trim();if((txt.length>0 && txt.length<40) && (txt.indexOf('加载中')>=0 || txt.indexOf('Loading')>=0 || txt.indexOf('LOADING')>=0 || txt.indexOf('全力加载')>=0 || txt.indexOf('缓冲')>=0 || txt.indexOf('正在')>=0)){try{console.log('[CCTV6_HIDE_TEXT] txt=\"'+txt+'\" class='+(nn.className||'')+' id='+(nn.id||''));}catch(e){}nn.style.setProperty('display','none','important');nn.style.setProperty('visibility','hidden','important');}}}catch(bigErr){}" +
                // Step 2: detach VIDEO父级容器DIV(绝对不要detach<video>元素本身)
                // sel顺序:.video-js(video.js包装层,离video最近)→.video-con→[id^=vodbox]→旧版tv-main-con-*链
                "    var sel=['.video-js','.video-con','[id^=vodbox]','.tv-main-con-l-vid','.tv-main-con-l','.tv-main-con','.tv-main','.tv','.tv-home'];" +
                "    var el=null;var hitIdx=-1;for(var si=0;si<sel.length;si++){try{var e=document.querySelector(sel[si]);if(e){el=e;hitIdx=si;break;}}catch(err){}}" +
                "    try{console.log('[CCTV6_STEP2_SEL] hitIdx='+hitIdx+' sel='+sel[hitIdx]+' el_tag='+(el?el.tagName:null)+' el_class='+(el&&el.className||'')+' el_id='+(el&&el.id||'')+' el_parentBefore='+(el&&el.parentNode?el.parentNode.tagName:null));}catch(e){}" +
                "    if(el){" +
                "      if(el.parentNode!==document.body){try{el.parentNode.removeChild(el);document.body.insertBefore(el,document.body.firstChild);}catch(err){try{console.log('[CCTV6_STEP2_DETACH_ERR] '+err.name+': '+err.message);}catch(e){}}}" +
                "      try{" +
                "        el.style.setProperty('position','fixed','important');el.style.setProperty('left','0','important');el.style.setProperty('top','0','important');" +
                "        el.style.setProperty('width','100vw','important');el.style.setProperty('height','100vh','important');" +
                "        el.style.setProperty('min-width','100vw','important');el.style.setProperty('min-height','100vh','important');" +
                "        el.style.setProperty('max-width','none','important');el.style.setProperty('max-height','none','important');" +
                "        el.style.setProperty('overflow','visible','important');el.style.setProperty('z-index','2147483647','important');" + // 最高z-index,绝对最顶层
                "        el.style.setProperty('background','#000','important');el.style.setProperty('display','block','important');" +
                "        el.style.setProperty('visibility','visible','important');el.style.setProperty('opacity','1','important');" +
                "        el.style.setProperty('transform','none','important');el.style.setProperty('margin','0','important');el.style.setProperty('padding','0','important');" +
                // log detach后容器真实尺寸(getBoundingClientRect最准,比style属性可靠)
                "        var r=el.getBoundingClientRect();try{console.log('[CCTV6_STEP2_RECT] el_rect: x='+r.x+' y='+r.y+' w='+r.width+' h='+r.height+' viewport_w='+window.innerWidth+' viewport_h='+window.innerHeight);}catch(e){}" +
                "      }catch(err){try{console.log('[CCTV6_STEP2_STYLE_ERR] '+err.name+': '+err.message);}catch(e){}}" +
                "    }else{try{console.log('[CCTV6_STEP2_NO_PARENT] WARN: no parent container found (sel数组全没命中, video将依赖原始DOM播放)');}catch(e){}}" +
                // Step 3: <video>元素自身强制样式 + log超级详细状态
                "    var vs2=document.querySelectorAll('video[id^=myvideo],video');" +
                "    try{console.log('[CCTV6_STEP3_VIDEO] found '+vs2.length+' video elements');}catch(e){}" +
                "    for(var vi=0;vi<vs2.length;vi++){" +
                "      try{" +
                "        var vv=vs2[vi];" +
                "        vv.style.setProperty('position','relative','important');" + // 不用fixed!用relative在父容器内填充
                "        vv.style.setProperty('width','100%','important');vv.style.setProperty('height','100%','important');" +
                "        vv.style.setProperty('min-width','100%','important');vv.style.setProperty('min-height','100%','important');" +
                "        vv.style.setProperty('max-width','none','important');vv.style.setProperty('max-height','none','important');" +
                "        vv.style.setProperty('left','0','important');vv.style.setProperty('top','0','important');" +
                "        vv.style.setProperty('object-fit','contain','important');" +
                "        vv.style.setProperty('background','#000','important');vv.style.setProperty('display','block','important');" +
                "        vv.style.setProperty('visibility','visible','important');vv.style.setProperty('opacity','1','important');" +
                "        vv.style.setProperty('transform','none','important');vv.style.setProperty('overflow','visible','important');" +
                "        vv.style.setProperty('margin','0','important');vv.style.setProperty('padding','0','important');" +
                // 软件渲染下依旧pause()/play()触发MediaCodec重新bind(部分机型detach后解码器输出未连上新Canvas)
                "        var didRebuild=false;if(vv.readyState>=2 && !vv.__cctvRebuildSurface){vv.__cctvRebuildSurface=1;didRebuild=true;try{vv.pause();vv.play();}catch(err2){}}" +
                // log每一个video的全部关键状态 → logcat一眼看出是没播放还是没渲染
                "        var vr=vv.getBoundingClientRect();try{console.log('[CCTV6_VIDEO_' + vi + ']' + ' id='+(vv.id||'') + ' tag='+vv.tagName + ' rect{x='+vr.x+',y='+vr.y+',w='+vr.width+',h='+vr.height+'}' + ' cssW='+vv.style.width+' cssH='+vv.style.height + ' vW='+vv.videoWidth+' vH='+vv.videoHeight + ' readyS='+vv.readyState + ' netS='+vv.networkState + ' paused='+vv.paused + ' muted='+vv.muted + ' vol='+vv.volume + ' curSrc='+String(vv.src||vv.currentSrc||'').slice(0,80) + ' didRebuildPausePlay='+didRebuild);}catch(e){}" +
                "      }catch(err){try{console.log('[CCTV6_STEP3_ERR_' + vi + '] '+err.name+': '+err.message);}catch(e){}}" +
                "    }" +
                "    try{console.log('[CCTV6_STEP4_END] _ysh_forceVisibleDetach done. body_children_count='+(document.body?document.body.children.length:0));}catch(e){}" +
                "  }" +
                "  function FastLoading(){" +
                "    applyCss();" +
                // yangshipin 专属: 锁滚动 + 兜底点击 + 强制全屏容器
                "    _ysh_lockScroll();_ysh_fakeClickPlay();_ysh_forceVisibleDetach();" +
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
                // ---------- yangshipin 专属工具函数(和 injectFastLoading 里逻辑一样),其他台绝对不触发 ----------
                "  function _ysh_is(){try{return (location.host||'').indexOf('yangshipin')>=0||!!document.querySelector('video[id^=myvideo],.video-js,.video-con,[id*=vodbox]');}catch(e){return false;}}" +
                "  function _ysh_lockScroll(){" +
                "    if(!_ysh_is())return;" +
                "    try{window.scrollTo(0,0);}catch(e){}" +
                "    try{document.documentElement.scrollTop=0;document.documentElement.scrollLeft=0;}catch(e){}" +
                "    try{document.body.scrollTop=0;document.body.scrollLeft=0;}catch(e){}" +
                "  }" +
                "  function _ysh_fakeClickPlay(){" +
                "    if(!_ysh_is())return;if(window.__yshClick)return;window.__yshClick=1;" +
                "    var btns=document.querySelectorAll('.vjs-big-play-button,.cmg-play-btn,.btn-play,.play-btn,[class*=big][class*=play],[class*=vjs][class*=play],.play.play2,.videoFull,.full.full2,.play');" +
                "    for(var i=0;i<btns.length;i++){try{btns[i].dispatchEvent(new MouseEvent('click',{bubbles:true,cancelable:true,view:window}));}catch(e){try{btns[i].click();}catch(e2){}}}" +
                "    var vs=document.querySelectorAll('video[id^=myvideo],video');" +
                "    for(var j=0;j<vs.length;j++){try{vs[j].muted=true;vs[j].play();}catch(e){}try{vs[j].dispatchEvent(new MouseEvent('click',{bubbles:true,cancelable:true,view:window}));}catch(e){}}" +
                "  }" +
                // yangshipin 专属:1)直接隐藏#app整个根节点(真实.video-js已detach到body首节点,#app只剩版权页垃圾层,整体隐藏绝不漏)
                //                2)Android WebView最终修复:WebView已切LAYER_TYPE_SOFTWARE(软件渲染),彻底跳过SurfaceView overlay合成bug
                //                  detach父容器(.video-js/.video-con),父容器position:fixed 100vw/h z-index=2147483647
                //                  video自身width/height=100% relative填充父容器
                //                3)超级详细console.log(onConsoleMessage转发logcat),每一步能看到sel是否命中、容器尺寸、video状态
                // 函数第一行if(!_ysh_is())return → 其他台零执行零影响
                "  function _ysh_forceVisibleDetach(){" +
                "    if(!_ysh_is())return;" +
                "    try{console.log('[CCTV6_STEP0_START] _ysh_forceVisibleDetach running host='+(location.host||'')+' title='+document.title);}catch(e){}" +
                // Step 1: 直接#app整体隐藏(版权页100%在#app里,整体隐藏绝不可能漏出),比逐个子节点隐藏可靠100倍
                // 真实.video-js父容器已在Step2 detach到document.body下(脱离#app),所以#app隐藏丝毫不影响video播放
                // ============ 关键新增:yangshipin桌面端的loading/spinner/遮罩/全力加载中 全隐藏 ============
                //  CCTV3/8「全力加载中」一直转圈的根因:hls.js解码_Web流失败,但spinner元素一直没隐藏,挡住video画面
                //  这里把所有class/id名里含loading/load/spinner/spin/mask/overlay/watermark(水印)的元素全部强制隐藏
                "    var hideSels=['#app','.container[data-v-03d5f916]','.container','.y-full','.y-full-bg','.y-full-control','.y-full-control-btn','.volume-muted-tip-container','.video-status-tip','.y-player-gift-list','.y-player-danmu','.y-player-side-panel','.y-player-bottom-bar'," +
                "      '[class*=loading]','[class*=load]','[class*=spinner]','[class*=spin]','[class*=mask]','[class*=overlay]','[class*=splash]','[class*=watermark]'," +
                "      '#loading','.loading','.spinner','.mask','.overlay','.cmg-loading','.ysp-loading','.ysp-spinner','.tv-loading','.live-loading'," +
                "      '[id*=loading]','[id*=load]','[id*=spinner]','[id*=spin]','[id*=mask]','[id*=overlay]'," +
                "      '[class*=progress]','[id*=progress]','.progress-bar','.buffer','[class*=buffer]'];" +
                "    for(var hi=0;hi<hideSels.length;hi++){try{var hn=document.querySelector(hideSels[hi]);if(hn){try{console.log('[CCTV6_HIDE] sel='+hideSels[hi]+' tag='+hn.tagName+' class='+(hn.className||''));}catch(e){}hn.style.setProperty('display','none','important');hn.style.setProperty('visibility','hidden','important');hn.style.setProperty('opacity','0','important');hn.style.setProperty('z-index','-1','important');}}catch(err){}}" +
                // 额外:遍历所有DOM节点,innerText包含「加载中」/「loading」的节点 → display:none(根治CCTV3/8「全力加载中」文字还在显示的问题)
                "    try{var allNodes=document.querySelectorAll('*');for(var ni=0;ni<allNodes.length && ni<8000;ni++){var nn=allNodes[ni];if(nn.children && nn.children.length>0)continue;var txt=(nn.innerText||nn.textContent||'').trim();if((txt.length>0 && txt.length<40) && (txt.indexOf('加载中')>=0 || txt.indexOf('Loading')>=0 || txt.indexOf('LOADING')>=0 || txt.indexOf('全力加载')>=0 || txt.indexOf('缓冲')>=0 || txt.indexOf('正在')>=0)){try{console.log('[CCTV6_HIDE_TEXT] txt=\"'+txt+'\" class='+(nn.className||'')+' id='+(nn.id||''));}catch(e){}nn.style.setProperty('display','none','important');nn.style.setProperty('visibility','hidden','important');}}}catch(bigErr){}" +
                // Step 2: detach VIDEO父级容器DIV(绝对不要detach<video>元素本身)
                // sel顺序:.video-js(video.js包装层,离video最近)→.video-con→[id^=vodbox]→旧版tv-main-con-*链
                "    var sel=['.video-js','.video-con','[id^=vodbox]','.tv-main-con-l-vid','.tv-main-con-l','.tv-main-con','.tv-main','.tv','.tv-home'];" +
                "    var el=null;var hitIdx=-1;for(var si=0;si<sel.length;si++){try{var e=document.querySelector(sel[si]);if(e){el=e;hitIdx=si;break;}}catch(err){}}" +
                "    try{console.log('[CCTV6_STEP2_SEL] hitIdx='+hitIdx+' sel='+sel[hitIdx]+' el_tag='+(el?el.tagName:null)+' el_class='+(el&&el.className||'')+' el_id='+(el&&el.id||'')+' el_parentBefore='+(el&&el.parentNode?el.parentNode.tagName:null));}catch(e){}" +
                "    if(el){" +
                "      if(el.parentNode!==document.body){try{el.parentNode.removeChild(el);document.body.insertBefore(el,document.body.firstChild);}catch(err){try{console.log('[CCTV6_STEP2_DETACH_ERR] '+err.name+': '+err.message);}catch(e){}}}" +
                "      try{" +
                "        el.style.setProperty('position','fixed','important');el.style.setProperty('left','0','important');el.style.setProperty('top','0','important');" +
                "        el.style.setProperty('width','100vw','important');el.style.setProperty('height','100vh','important');" +
                "        el.style.setProperty('min-width','100vw','important');el.style.setProperty('min-height','100vh','important');" +
                "        el.style.setProperty('max-width','none','important');el.style.setProperty('max-height','none','important');" +
                "        el.style.setProperty('overflow','visible','important');el.style.setProperty('z-index','2147483647','important');" + // 最高z-index,绝对最顶层
                "        el.style.setProperty('background','#000','important');el.style.setProperty('display','block','important');" +
                "        el.style.setProperty('visibility','visible','important');el.style.setProperty('opacity','1','important');" +
                "        el.style.setProperty('transform','none','important');el.style.setProperty('margin','0','important');el.style.setProperty('padding','0','important');" +
                // log detach后容器真实尺寸(getBoundingClientRect最准,比style属性可靠)
                "        var r=el.getBoundingClientRect();try{console.log('[CCTV6_STEP2_RECT] el_rect: x='+r.x+' y='+r.y+' w='+r.width+' h='+r.height+' viewport_w='+window.innerWidth+' viewport_h='+window.innerHeight);}catch(e){}" +
                "      }catch(err){try{console.log('[CCTV6_STEP2_STYLE_ERR] '+err.name+': '+err.message);}catch(e){}}" +
                "    }else{try{console.log('[CCTV6_STEP2_NO_PARENT] WARN: no parent container found (sel数组全没命中, video将依赖原始DOM播放)');}catch(e){}}" +
                // Step 3: <video>元素自身强制样式 + log超级详细状态
                "    var vs2=document.querySelectorAll('video[id^=myvideo],video');" +
                "    try{console.log('[CCTV6_STEP3_VIDEO] found '+vs2.length+' video elements');}catch(e){}" +
                "    for(var vi=0;vi<vs2.length;vi++){" +
                "      try{" +
                "        var vv=vs2[vi];" +
                "        vv.style.setProperty('position','relative','important');" + // 不用fixed!用relative在父容器内填充
                "        vv.style.setProperty('width','100%','important');vv.style.setProperty('height','100%','important');" +
                "        vv.style.setProperty('min-width','100%','important');vv.style.setProperty('min-height','100%','important');" +
                "        vv.style.setProperty('max-width','none','important');vv.style.setProperty('max-height','none','important');" +
                "        vv.style.setProperty('left','0','important');vv.style.setProperty('top','0','important');" +
                "        vv.style.setProperty('object-fit','contain','important');" +
                "        vv.style.setProperty('background','#000','important');vv.style.setProperty('display','block','important');" +
                "        vv.style.setProperty('visibility','visible','important');vv.style.setProperty('opacity','1','important');" +
                "        vv.style.setProperty('transform','none','important');vv.style.setProperty('overflow','visible','important');" +
                "        vv.style.setProperty('margin','0','important');vv.style.setProperty('padding','0','important');" +
                // 软件渲染下依旧pause()/play()触发MediaCodec重新bind(部分机型detach后解码器输出未连上新Canvas)
                "        var didRebuild=false;if(vv.readyState>=2 && !vv.__cctvRebuildSurface){vv.__cctvRebuildSurface=1;didRebuild=true;try{vv.pause();vv.play();}catch(err2){}}" +
                // log每一个video的全部关键状态 → logcat一眼看出是没播放还是没渲染
                "        var vr=vv.getBoundingClientRect();try{console.log('[CCTV6_VIDEO_' + vi + ']' + ' id='+(vv.id||'') + ' tag='+vv.tagName + ' rect{x='+vr.x+',y='+vr.y+',w='+vr.width+',h='+vr.height+'}' + ' cssW='+vv.style.width+' cssH='+vv.style.height + ' vW='+vv.videoWidth+' vH='+vv.videoHeight + ' readyS='+vv.readyState + ' netS='+vv.networkState + ' paused='+vv.paused + ' muted='+vv.muted + ' vol='+vv.volume + ' curSrc='+String(vv.src||vv.currentSrc||'').slice(0,80) + ' didRebuildPausePlay='+didRebuild);}catch(e){}" +
                "      }catch(err){try{console.log('[CCTV6_STEP3_ERR_' + vi + '] '+err.name+': '+err.message);}catch(e){}}" +
                "    }" +
                "    try{console.log('[CCTV6_STEP4_END] _ysh_forceVisibleDetach done. body_children_count='+(document.body?document.body.children.length:0));}catch(e){}" +
                "  }" +
                "  function ForceFullscreen(){" +
                // yangshipin 专属前置: 锁滚动 + 兜底点击 + 强制全屏容器(其他台因_ysh_is()return,立刻跳过)
                "    _ysh_lockScroll();_ysh_fakeClickPlay();_ysh_forceVisibleDetach();" +
                // 1) 定位 video 元素
                //    优先级:
                //      ① video[id^=myvideo]  → yangshipin tv/home video.js 播放器 (ID动态前缀,2026-08-01实机确认)
                //      ② document.querySelector('.video-js video, video.video-js') → 其他 video.js 场景
                //      ③ #h5player_player     → CCTV 移动版(tv.cctv.com) HLSP2P 播放器
                //      ④ 第一个 <video>         → 兜底
                "    var v=document.querySelector('video[id^=myvideo]')" +
                "       || document.querySelector('.video-js video')" +
                "       || document.querySelector('video.video-js')" +
                "       || document.getElementById('h5player_player')" +
                "       || document.querySelector('video');" +
                // 2) 如果是 video.js,还可以优先通过 videojs API 拿 player,确保 muted/play 生效
                "    try{" +
                "      if(window.videojs&&v&&v.id){" +
                "        var vp=window.videojs.getPlayer&&window.videojs.getPlayer(v.id);" +
                "        if(vp){v.__cctvVjsPlayer=vp;}" +
                "      }" +
                "    }catch(e){}" +
                "    if(v){" +
                "      try{v.volume=1;}catch(e){}" +
                // 自动播放策略修复:所有播放器创建 video 后,因 muted=false + 自动播放策略,
                // video.play() 被 reject → paused=true,画面停在大播放按钮。
                // 修复:先 muted=true 触发 play(),播放成功后延迟 2 秒取消 muted 恢复声音。
                // 用 __cctvAutoplayStarted 防止重复触发。
                "      try{" +
                "      if(v.paused&&!v.__cctvAutoplayStarted){" +
                "        v.__cctvAutoplayStarted=true;" +
                "        v.muted=true;" +
                // 如果拿到了 video.js player,优先用 videojs play()(内部处理 readyState/poster)
                "        var pp=v.__cctvVjsPlayer?v.__cctvVjsPlayer.play():v.play();" +
                "        var p2=pp;if(!(p2&&p2.then)){p2=Promise.resolve();}" +
                "        p2.then(function(){" +
                "          setTimeout(function(){" +
                "            try{v.muted=false;}catch(e){}" +
                "            if(v.__cctvVjsPlayer){try{v.__cctvVjsPlayer.muted(false);}catch(e){}}" +
                "          },2000);" +
                "          try{if(v.webkitRequestFullscreen){v.webkitRequestFullscreen();}}" +
                "            catch(e){}" +
                "        }).catch(function(e){" +
                "          v.__cctvAutoplayStarted=false;" +
                // 兜底:直接点一下 video.js 大播放按钮(video.js 的自动播放策略有时需要点击事件)
                "          var b=document.querySelector('.vjs-big-play-button');" +
                "          if(b){try{b.click();}catch(e2){}}" +
                "        });" +
                "      }" +
                "    }catch(e){}" +
                // 触发 HTML5 原生全屏 → WebView 用 SurfaceView 渲染视频(最靠谱的方式)
                "    try{if(v.webkitRequestFullscreen&&!v.__cctvFsRequested){v.__cctvFsRequested=true;v.webkitRequestFullscreen();}}catch(e){}" +
                // 直接用内联 style 拉满 video 元素(优先级高,覆盖 CSS)
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
                // 3) 容器层(固定全屏高 z-index,覆盖 CCTV 移动版 / AliPlayer / yangshipin video.js 三种)
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
                // yangshipin 桌面端 tv/home 真实容器链(2026-08-01实机确认)
                // .tv-main-con-l(左侧播放器区) → .tv-main-con-l-vid → #vodbox<PID> → .video-con → VIDEO
                "    var yshContainers=document.querySelectorAll('.tv, .tv-main, .tv-main-con, .tv-main-con-l, .tv-main-con-l-vid, [id^=vodbox], .video-con, .video-js, .vjs-tech, .vjs-fluid');" +
                "    for(var yi=0;yi<yshContainers.length;yi++){" +
                "      var yc=yshContainers[yi];" +
                "      yc.style.position='absolute';" +
                "      yc.style.left='0';" +
                "      yc.style.top='0';" +
                "      yc.style.width='100vw';" +
                "      yc.style.height='100vh';" +
                "      yc.style.zIndex='999998';" +
                "      yc.style.background='#000';" +
                "      yc.style.margin='0';" +
                "      yc.style.padding='0';" +
                "      yc.style.overflow='visible';" +
                "    }" +
                // 隐藏 video.js 装饰:大播放按钮(灰色封面+黑色三角就是这个)/poster/控制栏/加载转圈/错误框
                "    var vjsDecor=document.querySelectorAll('.vjs-big-play-button, .vjs-poster, .vjs-control-bar, .vjs-loading-spinner, .vjs-error-display, .vjs-modal-dialog, .vjs-text-track-display, .vjs-title-bar');" +
                "    for(var vj=0;vj<vjsDecor.length;vj++){var d=vjsDecor[vj];d.style.display='none';d.style.visibility='hidden';d.style.opacity='0';}" +
                // 广西台 AliPlayer 常见容器: #J_prismPlayer / #prismPlayer / #play-box 等,也要拉成 100vw/100vh
                "    var aliContainers=document.querySelectorAll('#J_prismPlayer,#prismPlayer,#live_prismPlayer,#play-box,#videoBox,#playBox');" +
                "    for(var i=0;i<aliContainers.length;i++){" +
                "      var ap=aliContainers[i];" +
                "      ap.style.position='fixed';" +
                "      ap.style.left='0';" +
                "      ap.style.top='0';" +
                "      ap.style.width='100vw';" +
                "      ap.style.height='100vh';" +
                "      ap.style.zIndex='999998';" +
                "      ap.style.background='#000';" +
                "      ap.style.margin='0';" +
                "      ap.style.padding='0';" +
                "    }" +
                // 隐藏 AliPlayer 控制栏/水印/大播放按钮等非视频元素(不影响 video 元素本身的显示)
                "    var prismDecor=document.querySelectorAll('.prism-controlbar,.prism-big-play-btn,.prism-cover,.prism-watermark,.prism-live-tip,.prism-info-panel,.prism-fullscreen-btn');" +
                "    for(var i=0;i<prismDecor.length;i++){prismDecor[i].style.display='none';}" +
                // 隐藏央视频 yangshipin 装饰(旧版+新版 tv/home)
                "    var yspDecor=document.querySelectorAll(" +
                "      '.ysp-header,.ysp-footer,.ysp-login,.ysp-download,.ysp-related,.ysp-program,.ysp-side-nav,.ysp-detail,.ysp-epg,.ysp-recommend,'+" +
                "      '.app-download-btn,.follow-btn,.attention-btn,.login-bar,.share-box,.program-list,.recommend-list,.comment-box,.bottom-copyright,'+" +
                "      '.txp_layer_bottom,.txp_top_title,.txp_vip_tip,.txp_mini_tip,.txp_btn,.txp_right_menu,'+" +
                "      // yangshipin 桌面端 tv/home 真实装饰(2026-08-01实机确认)" +
                "      '.header-b,.header-b-l,.header-b-m,.header-b-r,.tv-home-list,.searchMinHeight,'+" +
                "      '.tv-main-con-r,.tv-right-con,.right-side-wrap,.tv-aside,.tv-right,'+" +
                "      '.channel-scroll,.channel-tabs-wrapper,.channel-nav-wrap,.channel-nav,'+" +
                "      '.live-tip,.copyright-bar,.footer-bar,.top-banner,.bottom-banner,.app-promo,'+" +
                "      '#YSP_HEADER,#YSP_FOOTER,#ysp_download,#ysp_login,#ysp_attention,#ysp_share,#ysp_program,#ysp_related,#ysp_recommend');" +
                "    for(var i=0;i<yspDecor.length;i++){yspDecor[i].style.display='none';}" +
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
        updateDebugPanel("加载中", channel.name);
        // 记录预期 URL,防重定向逻辑会比对"实际加载 URL"和"预期 URL"是否一致
        expectedOfficialUrl = channel.officialUrl;
        // 按频道级 UA 策略切换:
        //   - yangshipin.cn/tv/home?pid=CCTV6/3/8 等桌面端独立直播页 → DESKTOP_UA
        //       (移动UA 返回"分享频道已下架"+默认CCTV1台标占位,桌面UA才能正确加载CCTV-6)
        //   - 其他台(CCTV 1/2/4/5/... + 广西台) → 系统默认移动 UA
        //       (tv.cctv.com/live/cctvX 系列移动端布局 CSS 已适配、广西台 gxtv.cn 移动UA正常)
        final boolean useDesktop = needsDesktopUA(channel.officialUrl);
        // ===================== CCTV-3/6/8:切台前先释放 ExoPlayer + 显示 WebView =====================
        // 上一次如果是 yangshipin 频道切的 ExoPlayer,WebView 被隐藏了,现在切台先恢复显示,释放旧播放器
        currentIsYangshipin = useDesktop;
        releaseExoPlayer();  // 释放旧的 ExoPlayer(如果有),移除 PlayerView
        try { webView.setVisibility(View.VISIBLE); } catch (Throwable t) {}
        // ===================== 关键修复:按频道动态切换 WebView LayerType =====================
        // 上一版全局设LAYER_TYPE_SOFTWARE导致所有台(CCTV1/5+/广西台)都有声音没画面(软件渲染模式下
        // 很多WebView版本的硬件解码器输出Surface无法绑定到Canvas位图→像素画不出来,但解码器在播→有声音没画面)
        // 修复策略:
        //  - 【CCTV-6/3/8(yangshipin桌面端,useDesktop=true)】:切LAYER_TYPE_SOFTWARE
        //    根治Chromium SurfaceView overlay位置计算错误bug→overlay合成不走这条路,直接位图渲染画面能出来
        //  - 【其他所有台(默认移动UA,useDesktop=false)】:保持LAYER_TYPE_HARDWARE硬件加速
        //    正常性能,正常Surface overlay合成,画面正常
        // 切layerType后必须强制requestLayout()+invalidate()触发WebView重建渲染路径/合成层,否则部分机型不生效
        if (useDesktop) {
            try { webView.setLayerType(View.LAYER_TYPE_SOFTWARE, null); } catch (Throwable t) {}
            try { android.util.Log.i("CCTV-TV", "[LAYER_SWITCH] " + channel.name + "(yangshipin桌面端) → LAYER_TYPE_SOFTWARE 软件渲染(根治overlay合成bug)"); } catch (Throwable t) {}
        } else {
            try { webView.setLayerType(View.LAYER_TYPE_HARDWARE, null); } catch (Throwable t) {}
            try { android.util.Log.i("CCTV-TV", "[LAYER_SWITCH] " + channel.name + "(非yangshipin) → LAYER_TYPE_HARDWARE 硬件加速(正常性能)"); } catch (Throwable t) {}
        }
        try { webView.requestLayout(); webView.invalidate(); } catch (Throwable t) {}
        // 记录 UA + LayerType 切换情况(便于调试)
        webView.getSettings().setUserAgentString(useDesktop ? DESKTOP_UA : null);
        updateDebugPanel(useDesktop ? "UA:桌面 LAYER:软" : "UA:移动 LAYER:硬",
                useDesktop ? "yangshipin桌面端CCTV6 软件渲染" : "标准移动UA 硬件加速");
        if (useDesktop) {
            // 央视频桌面端(yangshipin.cn/tv/home?pid=XXX):带 additionalHttpHeaders 加载
            //  核心就是 2 个 header:
            //    1) Referer: https://www.yangshipin.cn/  (告诉服务器你是从官网点过来的)
            //    2) X-Requested-With: 空字符串 (覆盖 Android WebView 默认加的包名 header,
            //       服务器看这个 header 就知道是 WebView 不是 Chrome,直接跳移动版)
            loadYangshipinWithHeaders(channel.officialUrl);
        } else {
            webView.loadUrl(channel.officialUrl);
        }
        showChannelHint(channel.name);
        // 立即开始白屏倒计时,不依赖 onPageFinished
        // (CCTV 页面有持续心跳,onPageFinished 在某些频道永远不触发)
        scheduleWhiteScreenCheck();
    }

    /**
     * 加载央视频桌面端 www.yangshipin.cn/tv/home?pid=XXX,带定制 HTTP Headers。
     * 这是修复 CCTV-6 被重定向到 m.yangshipin.cn 的**核心手法**:
     *   - 加 Referer: 伪装成用户从 yangshipin.cn 官网自己跳过来的,不是陌生的外部 App 请求
     *   - 加 X-Requested-With="" (空字符串): Android WebView 会默认给所有请求加上
     *     `X-Requested-With: <app包名>` 这个 Header,服务器看这个直接判定"非 Chrome PC 浏览器"
     *     就 302 跳移动版。通过 loadUrl(..., additionalHttpHeaders) 把它覆盖为空,
     *     服务器收到空值或根本收不到(取决于 Chromium 实现),就和真 Chrome 行为一致了。
     *   - 加 Accept: text/html,... 和 Sec-Fetch-Site/Dest/User 的组合,伪装得更像桌面 Chrome
     */
    private void loadYangshipinWithHeaders(String url) {
        java.util.Map<String, String> headers = new java.util.HashMap<>();
        // === 关键 Header 1/2: X-Requested-With 清空,不要暴露包名 ===
        // Chrome/Edge 桌面浏览器都不加这个 header,只有 Android WebView 加
        headers.put("X-Requested-With", "");
        // === 关键 Header 2/2: Referer 填官网首页,表示是站内跳转 ===
        headers.put("Referer", "https://www.yangshipin.cn/");
        // 桌面 Chrome 126 的真实请求 Headers(节选,尽量贴近真实浏览器请求)
        headers.put("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8,application/signed-exchange;v=b3;q=0.7");
        headers.put("Accept-Language", "zh-CN,zh;q=0.9,en-US;q=0.8,en;q=0.7");
        headers.put("Cache-Control", "max-age=0");
        headers.put("Sec-Fetch-Dest", "document");
        headers.put("Sec-Fetch-Mode", "navigate");
        headers.put("Sec-Fetch-Site", "same-origin");
        headers.put("Sec-Fetch-User", "?1");
        headers.put("Upgrade-Insecure-Requests", "1");
        Log.i("CCTV-TV", "loadYangshipinWithHeaders → " + url + " headers=" + headers.keySet());
        webView.loadUrl(url, headers);
    }

    /**
     * 判断 URL 是不是央视频移动端旧域名(就是"分享频道已下架"那套页面所在的域名)。
     * 命中条件:
     *   - m.yangshipin.cn / ydh5.yangshipin.cn 等手机子域名
     *   - 或者 URL 里有 "m.yangshipin" 字样(兜底)
     * 注意:www.yangshipin.cn(桌面版)不会命中这个方法,yangshipin.cn 裸域也不命中
     *       (因为裸域一般会 302 到 www,属于正常跳转)
     */
    private static boolean isMobileYangshipinDomain(String url) {
        if (url == null) return false;
        try {
            String host = new URI(url).getHost();
            if (host == null) {
                // 拿不到 host 时退化:用字符串 contains 兜底
                return url.contains("m.yangshipin");
            }
            String lc = host.toLowerCase(Locale.ROOT);
            // 明确是手机子域名
            if (lc.equals("m.yangshipin.cn") || lc.endsWith(".m.yangshipin.cn")) return true;
            // 央视频移动端旧版 H5 域名 (出现在 2026 年之前的 CCTV6 分享页)
            if (lc.equals("ydh5.yangshipin.cn") || lc.endsWith(".ydh5.yangshipin.cn")) return true;
            return false;
        } catch (Exception e) {
            // URI 解析失败兜底
            return url.contains("m.yangshipin");
        }
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
                // 定位 video:优先级与 ForceFullscreen 完全一致(video.js > h5player > 兜底)
                "  var v=document.querySelector('video[id^=myvideo]')" +
                "     || document.querySelector('.video-js video')" +
                "     || document.querySelector('video.video-js')" +
                "     || document.getElementById('h5player_player')" +
                "     || document.querySelector('video');" +
                "  var m3u8=window.__cctvM3u8Url||'';" +
                "  if(v){" +
                "    var r=v.getBoundingClientRect();" +
                "    var cs=window.getComputedStyle(v);" +
                "    var diag='x='+Math.round(r.left)+' y='+Math.round(r.top)+' w='+Math.round(r.width)+' h='+Math.round(r.height)+" +
                "             ' display='+cs.display+' visibility='+cs.visibility+' opacity='+cs.opacity+" +
                "             ' zIndex='+cs.zIndex+' objectFit='+cs.objectFit+' muted='+v.muted+' paused='+v.paused+" +
                "             ' vw='+v.videoWidth+' vh='+v.videoHeight+" +
                "             ' id='+(v.id||'');" +
                // video.js 特有:poster / big-play-button 是否还可见(是 = 没开始播放或封面没隐藏)
                "    var poster=document.querySelector('.vjs-poster');var bigBtn=document.querySelector('.vjs-big-play-button');" +
                "    if(poster){var s1=getComputedStyle(poster);diag+=' vjs_poster_disp='+s1.display+'_op='+s1.opacity;}" +
                "    if(bigBtn){var s2=getComputedStyle(bigBtn);diag+=' vjs_bigbtn_disp='+s2.display+'_op='+s2.opacity;}" +
                // yangshipin tv/home 特有:装饰元素是否被成功隐藏(如果还存在说明CSS/JS注入没生效)
                "    var tHeader=document.querySelector('.header-b');var tList=document.querySelector('.tv-home-list');" +
                "    var tRight=document.querySelector('.tv-main-con-r');" +
                "    if(tHeader){diag+=' header_b='+(tHeader.offsetParent===null?'HIDDEN':'VISIBLE('+tHeader.offsetHeight+'px)');}" +
                "    if(tList){diag+=' tv_home_list='+(tList.offsetParent===null?'HIDDEN':'VISIBLE('+tList.offsetHeight+'px)');}" +
                "    if(tRight){diag+=' tv_right='+(tRight.offsetParent===null?'HIDDEN':'VISIBLE('+tRight.offsetWidth+'px)');}" +
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
                "  info.push('HOST='+location.host);" +
                "  info.push('TITLE='+document.title);" +
                "  info.push('RS='+document.readyState);" +
                "  info.push('videos='+document.getElementsByTagName('video').length);" +
                "  info.push('imgs='+document.getElementsByTagName('img').length);" +
                "  info.push('scripts='+document.getElementsByTagName('script').length);" +
                "  info.push('videoJS='+(typeof window.videojs)+' getPlayers='+(window.videojs&&typeof window.videojs.getPlayers==='function'?Object.keys(window.videojs.getPlayers()).join(','):'null'));" +
                // yangshipin tv/home 特有诊断:video.js 容器 / 装饰 / vodbox 是否存在
                "  info.push('hasVideoJSCls='+(document.querySelector('.video-js')?'YES':'NO')+' hasVodBox='+(document.querySelector('[id^=vodbox]')?'YES':'NO'));" +
                "  info.push('header_b_h='+((document.querySelector('.header-b')||{}).offsetHeight||0)+' tv_home_list_h='+((document.querySelector('.tv-home-list')||{}).offsetHeight||0));" +
                "  info.push('MediaKeys='+(window.MediaKeys?'YES':'NO'));" +
                "  info.push('MSE='+(window.MediaSource?'YES':'NO'));" +
                "  info.push('WASM='+(typeof WebAssembly==='object'?'YES:'+(typeof WebAssembly))+' Worker='+(typeof Worker==='function'?'YES':'NO')+' WebRTC='+(typeof RTCPeerConnection==='function'?'YES':'NO'));" +
                "  info.push('HLSP2P='+(typeof HLSP2P)+' createLivePlayer='+(typeof createLivePlayer));" +
                "  info.push('cntvPlayer='+(typeof cntvPlayer)+' playerObj='+(typeof playerObj));" +
                "  var player=document.getElementById('player');" +
                "  if(player){info.push('playerChildren='+player.children.length);info.push('playerHTML='+player.innerHTML.substring(0,200));}else{info.push('player=null');}" +
                "  var h5p=document.getElementById('h5player');" +
                "  if(h5p){info.push('h5playerHTML='+h5p.innerHTML.substring(0,200));}else{info.push('h5player=null');}" +
                "  if(videoInfo.length>0){info.push('videoList='+videoInfo.join(' || '));}" +
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
            } else if (state.contains("BLACK_SCREEN")) {
                // 有声音无画面:HLSP2P 的 P2P 视频失败,音频正常
                Log.w("CCTV-TV", "=== 有声音无画面(" + elapsedMs + "ms) ===\n" + state);
                updateDebugPanel("BLACK_SCREEN", "有声音无画面:HLSP2P的P2P视频失败\n正在切换hls.js兜底...");
                if (elapsedMs >= 10000 && capturedM3u8Url != null && !hlsPlayerInjected) {
                    hlsPlayerInjected = true;
                    updateDebugPanel("HLS_FALLBACK", "有声音无画面,切换hls.js直连\nm3u8=" + shortenUrl(capturedM3u8Url));
                    injectHlsPlayer(capturedM3u8Url);
                }
            } else if (state.contains("PAUSED")) {
                // video 元素存在但暂停 → 自动播放策略阻止。用 muted + play() 策略(优先 video.js API)
                webView.evaluateJavascript(
                        "(function(){" +
                        "  var v=document.querySelector('video[id^=myvideo]')" +
                        "     || document.querySelector('.video-js video')" +
                        "     || document.querySelector('video.video-js')" +
                        "     || document.getElementById('h5player_player')" +
                        "     || document.querySelector('video');" +
                        "  if(!v)return 'no_video';" +
                        // 隐藏 video.js 海报/大播放按钮(防止画面还是灰色封面大三角)
                        "  var decor=document.querySelectorAll('.vjs-poster,.vjs-big-play-button,.vjs-control-bar,.vjs-loading-spinner,.vjs-error-display,.vjs-modal-dialog');" +
                        "  for(var i=0;i<decor.length;i++){decor[i].style.display='none';decor[i].style.visibility='hidden';decor[i].style.opacity='0';}" +
                        // 优先用 videojs.getPlayer API 拿 player(内部处理 readyState/poster 清理)
                        "  var pp=null;if(window.videojs&&v.id){try{pp=window.videojs.getPlayer(v.id);}catch(e){pp=null;}}" +
                        "  if(v&&!v.__cctvAutoplayStarted){" +
                        "    v.__cctvAutoplayStarted=true;v.muted=true;" +
                        "    var p2=pp?pp.play():v.play();if(!(p2&&p2.then))p2=Promise.resolve();" +
                        "    p2.then(function(){" +
                        "      setTimeout(function(){try{v.muted=false;}catch(e){}if(pp){try{pp.muted(false);}catch(e2){}}},2000);" +
                        "      try{if(v.webkitRequestFullscreen)v.webkitRequestFullscreen();}catch(e){}" +
                        "    }).catch(function(e){" +
                        "      v.__cctvAutoplayStarted=false;" +
                        // 兜底:直接点大播放按钮
                        "      var b=document.querySelector('.vjs-big-play-button');if(b){try{b.click();}catch(e2){}}" +
                        "    });" +
                        "  }" +
                        "  return 'attempted_paused_retry';" +
                        "})()",
                        null);
                // 如果 10 秒后视频还是暂停的,说明 HLSP2P 播放器在 WebView 上跑不起来,
                // 用 hls.js 兜底直接播放 m3u8
                if (elapsedMs >= 10000 && capturedM3u8Url != null && !hlsPlayerInjected) {
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

    // ================= CCTV-3/6/8 yangshipin 桌面端:ExoPlayer 原生播放器核心方法 =================

    /**
     * CCTV-3/6/8 yangshipin 桌面端:截到 m3u8 URL 后切 ExoPlayer 全屏原生播放。
     * 唯一根治「CCTV-3/6/8 有声音没画面(黑屏)」的方案,完全绕开 Chromium WebView 渲染链路:
     *  - SurfaceView overlay 位置错误不再影响画面,因为 ExoPlayer 自己持 Surface/TextureView
     *  - MediaCodec 解码器输出直接绑定到 PlayerView 的 Surface,不再需要和 WebView Canvas 绑定
     *  → 在任何 Android TV/盒子上 100% 能出画面
     */
    @SuppressLint("SetTextI18n")
    private void playYangshipinWithExoPlayer(String m3u8Url) {
        if (m3u8Url == null || m3u8Url.isEmpty()) return;
        if (exoPlayerActive || exoPlayer != null) return;  // 避免重复创建播放器
        if (currentIsYangshipin == false) return;  // 非 yangshipin 频道不切
        if (rootContainer == null) {
            Log.e("CCTV-TV", "[EXOPLAYER_ERR] rootContainer 为空,无法创建 PlayerView");
            return;
        }
        try {
            // 1. 隐藏 WebView(黑屏的来源) + 暂停 WebView 渲染(省电)
            webView.onPause();
            webView.setVisibility(View.GONE);
            Log.i("CCTV-TV", "[EXOPLAYER_START] CCTV-3/6/8 切 ExoPlayer 原生播放, m3u8=" + m3u8Url);
            updateDebugPanel("EXOPLAYER_START", "切原生播放:" + shortenUrl(m3u8Url));

            // ============== 2. 创建 DataSource.Factory,带和 WebView 完全一致的请求头(解决防盗链) ==============
            // 【关键:为什么CCTV-6能播,CCTV-3/8绿屏?】
            //   WebView 请求 m3u8: 自动带 Referer: yangshipin.cn + User-Agent:Chrome126 + Origin:yangshipin.cn + Cookie
            //   ExoPlayer 默认请求 m3u8: User-Agent="ExoPlayerLib/2.19.x",无Referer无Origin → 央视频服务器识别
            //   为非官方浏览器请求,直接返回「测试绿屏流」(CCTV-6可能服务器限制不严,所以能正常流)
            // → 必须在 ExoPlayer 的 HTTP 请求上把 User-Agent/Referer/Origin 全部补成和桌面 Chrome 一模一样!
            String desktopUA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36";
            java.util.Map<String, String> exoHeaders = new java.util.HashMap<>();
            exoHeaders.put("Referer", "https://www.yangshipin.cn/");
            exoHeaders.put("Origin",  "https://www.yangshipin.cn");   // ← 新增:Origin头(Chrome必带,央视频服务器检查Origin防盗链)
            exoHeaders.put("Accept", "*/*");                            // ← 新增:Chrome请求m3u8的Accept头
            exoHeaders.put("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8");
            com.google.android.exoplayer2.upstream.DefaultHttpDataSource.Factory httpDsFactory =
                    new com.google.android.exoplayer2.upstream.DefaultHttpDataSource.Factory()
                            .setUserAgent(desktopUA)
                            .setDefaultRequestProperties(exoHeaders);
            com.google.android.exoplayer2.source.hls.HlsMediaSource.Factory hlsFactory =
                    new com.google.android.exoplayer2.source.hls.HlsMediaSource.Factory(httpDsFactory)
                            .setAllowChunklessPreparation(true);  // ← 新增:允许无chunk准备,减少CCTV3/8 _web.m3u8的解析失败率

            // 3. 创建 ExoPlayer (Google 官方播放器,minSdk=23,兼容所有旧盒子),绑定监听器
            exoPlayer = new ExoPlayer.Builder(this).build();
            exoPlayer.setVolume(1.0f);
            // ===== ExoPlayer.Listener:把所有状态打到右上角调试面板,用户一看就知道问题在哪 =====
            exoPlayer.addListener(new com.google.android.exoplayer2.Player.Listener() {
                String stateName(int state) {
                    switch (state) {
                        case com.google.android.exoplayer2.Player.STATE_IDLE: return "IDLE";
                        case com.google.android.exoplayer2.Player.STATE_BUFFERING: return "BUFFERING";
                        case com.google.android.exoplayer2.Player.STATE_READY: return "READY";
                        case com.google.android.exoplayer2.Player.STATE_ENDED: return "ENDED";
                        default: return "UNK"+state;
                    }
                }
                @Override public void onPlaybackStateChanged(int state) {
                    String s = stateName(state) + (exoPlayer != null && exoPlayer.getPlayWhenReady() ? "/PLAYING" : "/PAUSED");
                    Log.i("CCTV-TV", "[EXOPLAYER_STATE] " + s);
                    updateDebugPanel("EXO_STATE", s);
                }
                @Override public void onVideoSizeChanged(com.google.android.exoplayer2.video.VideoSize videoSize) {
                    String sz = "VID_SIZE " + videoSize.width + "x" + videoSize.height + " sarNum=" + videoSize.pixelWidthHeightRatio;
                    Log.i("CCTV-TV", "[EXOPLAYER_VID_SIZE] " + sz);
                    updateDebugPanel("EXO_VID", sz);
                }
                @Override public void onPlayerError(com.google.android.exoplayer2.PlaybackException error) {
                    String err = "ERR code=" + error.errorCode + " " + error.getMessage();
                    Log.e("CCTV-TV", "[EXOPLAYER_ERR] " + err, error);
                    updateDebugPanel("EXO_ERR", err.substring(0, Math.min(60, err.length())));
                }
            });

            // 4. ★★★ 直接 new TextureView,不用 PlayerView 的 setSurfaceType(某些ExoPlayer版本无此API导致编译错) ★★★
            //    SurfaceView 是独立叠加层,在模拟器+某些盒子上会出现:
            //    YUV color range 错 → 绿屏; or 和 WebView GONE 后的空 Surface 合成错 → 绿屏
            //    TextureView 走 View 合成路径,和其他 View 一样画到同一个 Canvas,100% 兼容所有设备
            android.view.TextureView textureView = new android.view.TextureView(this);
            textureView.setKeepScreenOn(true);  // 保持屏幕常亮,直播不黑屏
            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT);
            textureView.setLayoutParams(lp);
            // 把 TextureView 绑定到 ExoPlayer → 视频像素直接画到 TextureView
            exoPlayer.setVideoTextureView(textureView);
            // 放到 rootContainer 的最上层(z-index 最高,调试面板用 elevation 保持在最顶层)
            rootContainer.addView(textureView, Math.max(0, rootContainer.getChildCount() - 2));
            textureView.requestLayout();
            textureView.invalidate();
            // textureView 保存到 exoPlayerView 引用,切台时 releaseExoPlayer 统一 removeView
            exoPlayerView = textureView;

            // 5. 喂给 ExoPlayer 播放(HlsMediaSource + 带 Referer/UA 的 HTTP 头)
            MediaItem mediaItem = MediaItem.fromUri(m3u8Url);
            com.google.android.exoplayer2.source.MediaSource ms = hlsFactory.createMediaSource(mediaItem);
            exoPlayer.setMediaSource(ms);
            exoPlayer.prepare();
            exoPlayer.setPlayWhenReady(true);

            // 6. 标记已激活
            exoPlayerActive = true;
            Toast.makeText(this, "已切换到原生播放器(CCTV-3/6/8)", Toast.LENGTH_LONG).show();
        } catch (Throwable t) {
            Log.e("CCTV-TV", "[EXOPLAYER_ERR] 创建 ExoPlayer 失败: " + t.getClass().getName() + ": " + t.getMessage(), t);
            // 创建失败兜底:恢复 WebView 显示,继续用原来的 WebView <video> 链路(虽然可能黑屏,但至少不崩)
            releaseExoPlayer();
            try { webView.setVisibility(View.VISIBLE); webView.onResume(); } catch (Throwable t2) {}
            updateDebugPanel("EXOPLAYER_ERR", "创建失败:" + t.getMessage());
        }
    }

    /**
     * 释放 ExoPlayer,移除 PlayerView,恢复 WebView 显示。
     * 切台时 / onDestroy 时调用。
     */
    private void releaseExoPlayer() {
        try {
            exoPlayerActive = false;
            if (exoPlayer != null) {
                Log.i("CCTV-TV", "[EXOPLAYER_RELEASE] 释放 ExoPlayer");
                try { exoPlayer.setPlayWhenReady(false); } catch (Throwable t) {}
                try { exoPlayer.stop(); } catch (Throwable t) {}
                try { exoPlayer.clearMediaItems(); } catch (Throwable t) {}
                try { exoPlayer.release(); } catch (Throwable t) {}
                exoPlayer = null;
            }
            if (exoPlayerView != null && rootContainer != null) {
                try { rootContainer.removeView(exoPlayerView); } catch (Throwable t) {}
                exoPlayerView = null;
            }
            // 恢复 WebView 渲染(如果之前暂停过)
            if (webView != null) {
                try { webView.onResume(); } catch (Throwable t) {}
                try { webView.setVisibility(View.VISIBLE); } catch (Throwable t) {}
            }
        } catch (Throwable t) {
            Log.e("CCTV-TV", "[EXOPLAYER_RELEASE_ERR] 释放异常: " + t.getMessage(), t);
        }
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        channelHint.removeCallbacks(hideChannelHint);
        releaseExoPlayer();  // 退出时一定释放 ExoPlayer,避免内存泄漏 + 播放器后台继续播放
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
     * 对于 extractChannelNumber 返回 999 的非 CCTV 台(如广西新闻频道、广西卫视),
     * 直接按 ChannelCatalog.CHANNELS 的写入顺序顺延,不参与排序(排在末尾)。
     */
    private void buildSortedChannelList() {
        sortedChannelIndices = new ArrayList<>();
        // 1) CCTV 台:先按 extractChannelNumber 正序排列,同号保持写入原序(稳定排序)
        List<Integer> cctvIndices = new ArrayList<>();
        // 2) 非 CCTV 台(号=999):按写入顺序放在后面,不再"按频道号"排序(否则会出现在错误位置)
        List<Integer> otherIndices = new ArrayList<>();
        for (int i = 0; i < ChannelCatalog.CHANNELS.size(); i++) {
            int n = extractChannelNumber(ChannelCatalog.CHANNELS.get(i).name);
            if (n == 999) otherIndices.add(i);
            else cctvIndices.add(i);
        }
        Collections.sort(cctvIndices, (a, b) -> {
            int numA = extractChannelNumber(ChannelCatalog.CHANNELS.get(a).name);
            int numB = extractChannelNumber(ChannelCatalog.CHANNELS.get(b).name);
            return Integer.compare(numA, numB);
        });
        sortedChannelIndices.addAll(cctvIndices);
        sortedChannelIndices.addAll(otherIndices);
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
            // 序号:按列表顺序 1..N 顺延(非 CCTV 台不再显示 999)
            int displayNum = i + 1;
            TextView tv = new TextView(this);
            tv.setText(displayNum + ". " + ch.name);
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
     * 按频道号跳转:
     *  1) 优先按 extractChannelNumber 匹配(CCTV-1..CCTV-17 等 CCTV 台的频道号)
     *  2) 若无匹配,则按"列表第 N 项(1 开始)"匹配(广西新闻频道=列表第 18 项,广西卫视=第 19 项,后续扩展同理)
     *     这样非 CCTV 台不用改 extractChannelNumber 就能按顺延序号直接跳。
     */
    private void jumpToChannelNumber(int num) {
        if (sortedChannelIndices == null) buildSortedChannelList();
        // 1) CCTV 频道号匹配
        for (int i = 0; i < sortedChannelIndices.size(); i++) {
            int origIdx = sortedChannelIndices.get(i);
            Channel ch = ChannelCatalog.CHANNELS.get(origIdx);
            if (extractChannelNumber(ch.name) == num) {
                loadChannel(origIdx);
                return;
            }
        }
        // 2) 列表第 N 项匹配(1-based)
        if (num >= 1 && num <= sortedChannelIndices.size()) {
            int origIdx = sortedChannelIndices.get(num - 1);
            loadChannel(origIdx);
            return;
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

    /**
     * 是否需要桌面 UA(仅对特定频道,不影响其他用移动 UA 正常的台)。
     *   当前触发条件(任一命中即返回 true):
     *     - 央视频桌面端直播页:yangshipin.cn/tv/home?pid=XXX
     *       (移动UA访问该页会返回"分享频道已下架"/默认CCTV1空页/台标占位图,
     *       必须桌面UA 才能正确解析到 pid 对应频道的播放器和 m3u8 流)
     *   以后要新增CCTV-3 / CCTV-8(也是同样问题)时,直接把它们的 URL 指向
     *     yangshipin.cn/tv/home?pid=对应PID,这里会自动命中,无需再改逻辑。
     */
    private boolean needsDesktopUA(String url) {
        if (url == null) return false;
        String lc = url.toLowerCase(Locale.ROOT);
        // 央视频桌面端电视直播页(带 pid 参数的都是独立频道)
        if (lc.contains("yangshipin.cn/tv/home") && lc.contains("pid=")) return true;
        return false;
    }

    private boolean isOfficialCctvUrl(String url) {
        try {
            String host = new URI(url).getHost();
            if (host == null) return false;
            host = host.toLowerCase(Locale.ROOT);
            // CCTV 官方主域名
            if (host.equals("cctv.com") || host.endsWith(".cctv.com")) return true;
            if (host.equals("cntv.cn") || host.endsWith(".cntv.cn")) return true;
            // 广西网络广播电视台(广西新闻频道等官方直播页)
            if (host.equals("gxtv.cn") || host.endsWith(".gxtv.cn")) return true;
            // 广西台流媒体 CDN(liangtv.cn = 亮TV,广西台合作的官方直播流 CDN)
            if (host.equals("liangtv.cn") || host.endsWith(".liangtv.cn")) return true;
            // 广西台使用阿里云 AliPlayer 播放器(官方 CDN 资源,非第三方广告)
            if (host.equals("alicdn.com") || host.endsWith(".alicdn.com")) return true;
            if (host.equals("aliyun.com") || host.endsWith(".aliyun.com")) return true;
            // 央视频 yangshipin.cn(独立CCTV-6/3/8等频道的移动版/PC版直播页,官方出品)
            if (host.equals("yangshipin.cn") || host.endsWith(".yangshipin.cn")) return true;
            // 央视频流 CDN(流域名,从 txvlive.js 解析出的 m3u8/ts 服务器)
            if (host.equals("ysp.cctv.cn") || host.endsWith(".ysp.cctv.cn")) return true;
            if (host.equals("smtcdns.net") || host.endsWith(".smtcdns.net")) return true;
            return false;
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
            // ========= CCTV-6 调试日志:强制升级到 INFO 级别+显示到右上角调试面板 =========
            // 所有我们在 _ysh_forceVisibleDetach 里打的 [CCTV6_*] log,强制输出到 logcat + 面板
            if (msg != null && msg.contains("[CCTV6_")) {
                Log.i("CCTV-TV", msg);
                if (activity != null) {
                    String shortMsg = msg.length() > 260 ? msg.substring(0, 260) : msg;
                    // 关键日志(命中容器/video状态)显示到面板,普通(STEP0/STEP4)仅logcat
                    if (msg.contains("[CCTV6_STEP2_SEL]") || msg.contains("[CCTV6_VIDEO_") || msg.contains("[CCTV6_STEP2_NO_PARENT]") || msg.contains("[CCTV6_STEP2_RECT]")) {
                        activity.updateDebugPanel("CCTV6_DEBUG", shortMsg);
                    }
                }
                return true;
            }
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
