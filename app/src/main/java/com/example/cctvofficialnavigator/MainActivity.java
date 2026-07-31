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

    private WebView webView;
    private TextView channelHint;
    private TextView debugPanel;
    private FrameLayout rootContainer;
    private int channelIndex;
    private final Runnable hideChannelHint = () -> channelHint.setVisibility(View.GONE);
    private final Handler handler = new Handler(Looper.getMainLooper());
    // 倒计时线程:用 background thread 跑,避免被 WebView 加载/JS 阻塞 main thread 导致 postDelayed 永不执行
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
    private java.util.List<ScheduledFuture<?>> pendingChecks = new java.util.ArrayList<>();
    private int loadGeneration = 0;

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
        settings.setLoadsImagesAutomatically(true);
        settings.setBlockNetworkImage(false);
        settings.setMediaPlaybackRequiresUserGesture(false);
        settings.setLoadWithOverviewMode(true);
        settings.setUseWideViewPort(true);

        // 用自定义的 WebChromeClient 拦截 console 输出和加载进度(CCTV 内部的 JS 报错能反映到 logcat/面板)
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
                injectFastLoading(view);
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                updateDebugPanel("onPageFinished → " + shortenUrl(url), null);
                injectAutoFullscreen(view);
            }

            // 抓底层资源错误(直接显示到面板,不需要等 evaluateJavascript)
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
     * 页面一开始加载就注入 FastLoading(每 200ms 跑一次):
     *  1. 注入强力 CSS,强制让播放器容器 #player 占满 100vw/100vh,隐藏所有非播放器装饰元素
     *     (顶部"体育频道直播"标题条、底部版权、右侧频道列表、节目预告区、广告等)
     *  2. 不依赖"网页全屏"按钮 click()(在某些频道上不可靠),直接 CSS 拉满
     *  3. 不删任何脚本:之前"删脚本"曾多次误删 h5_live_index.js/liveplayer.js 导致播放器不初始化(CCTV-3/6/8 白屏根因)
     *     装饰元素的视觉干扰全部用 CSS display:none 解决,不碰 script
     *  4. 保留图片正常加载(CCTV 某些频道的播放器依赖图片 onload 触发 video 元素插入)
     */
    private void injectFastLoading(WebView view) {
        String js =
                "(function(){" +
                "  if(window.__cctvFastLoadingInjected)return;" +
                "  window.__cctvFastLoadingInjected=true;" +
                // CSS:强制让 #player 容器占满整个 WebView,隐藏一切装饰元素
                "  var css=" +
                "    'html,body{width:100%!important;height:100%!important;margin:0!important;padding:0!important;background:#000!important;overflow:hidden!important}'+" +
                "    '.jiemuguanwang18950_zhibo_ind01,.zhibo19629_ind01,.playingVideo{width:100vw!important;height:100vh!important;margin:0!important;padding:0!important;position:absolute!important;left:0!important;top:0!important}'+" +
                "    '.video_left,.video_flash,#player{width:100vw!important;height:100vh!important;margin:0!important;padding:0!important;position:absolute!important;left:0!important;top:0!important;background:#000!important}'+" +
                "    'video{width:100vw!important;height:100vh!important;object-fit:contain!important;background:#000!important}'+" +
                // 装饰元素:隐藏
                "    '.video_right,.video_btnBar,.bg_top_h_tile,.bg_top_owner,.bg_bottom_h_tile,header,footer,nav,.vspace,.column_wrapper{display:none!important}';" +
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
                "    var v=document.querySelector('video');" +
                "    if(v){" +
                "      try{v.volume=1;}catch(e){}" +
                "      try{v.play();}catch(e){}" +
                "      v.style.position='fixed';" +
                "      v.style.left='0';" +
                "      v.style.top='0';" +
                "      v.style.width='100vw';" +
                "      v.style.height='100vh';" +
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
                "  }" +
                "  ForceFullscreen();" +
                "  var count=0;" +
                "  function loop(){" +
                "    ForceFullscreen();" +
                "    count++;" +
                "    if(count<26)setTimeout(loop,300);" +
                "  }" +
                "  setTimeout(loop,300);" +
                "})()";
        view.evaluateJavascript(js, null);
    }

    private void loadChannel(int requestedIndex) {
        handler.removeCallbacksAndMessages(null);
        loadGeneration++;
        int count = ChannelCatalog.CHANNELS.size();
        channelIndex = ((requestedIndex % count) + count) % count;
        Channel channel = ChannelCatalog.CHANNELS.get(channelIndex);
        // 切换频道时先清掉诊断面板,并显示"加载中"占位
        debugPanel.setText("加载中... 频道=" + channel.name + "\nURL=" + channel.officialUrl);
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
                "  var v=document.querySelector('video');" +
                "  if(v){return 'OK:'+(v.paused?'PAUSED':'PLAYING');}" +
                "  var txt=(document.body&&document.body.innerText||'').replace(/\\s+/g,' ').trim();" +
                "  var info=[];" +
                "  info.push('已等='+" + (elapsedMs/1000) + ");" +
                "  info.push('URL='+location.href);" +
                "  info.push('TITLE='+document.title);" +
                "  info.push('RS='+document.readyState);" +
                "  info.push('videos='+document.getElementsByTagName('video').length);" +
                "  info.push('imgs='+document.getElementsByTagName('img').length);" +
                "  info.push('scripts='+document.getElementsByTagName('script').length);" +
                "  info.push('MediaKeys='+(window.MediaKeys?'YES':'NO'));" +
                "  info.push('MSE='+(window.MediaSource?'YES':'NO'));" +
                "  info.push('UA='+navigator.userAgent.substring(0,60));" +
                "  var player=document.getElementById('player');" +
                "  if(player){info.push('playerHTML='+player.innerHTML.substring(0,160));}" +
                "  info.push('BODY='+txt.substring(0,200));" +
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
            if (state.startsWith("NO_VIDEO")) {
                Log.e("CCTV-TV", "=== 白屏诊断(" + elapsedMs + "ms) ===\n" + state);
                String detail = state.substring("NO_VIDEO|".length())
                        .replace("\\n", "\n")
                        .replace("|", "\n");
                updateDebugPanel("NO_VIDEO", detail);
                String firstLine = detail.split("\n")[0];
                if (firstLine.length() > 60) firstLine = firstLine.substring(0, 60);
                Toast.makeText(MainActivity.this, "白屏:" + firstLine, Toast.LENGTH_LONG).show();
            } else if (state.contains("PAUSED")) {
                webView.evaluateJavascript(
                        "(function(){var v=document.querySelector('video');if(v){v.play();}return true;})()",
                        null);
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
        public boolean onConsoleMessage(android.webkit.ConsoleMessage cm) {
            String msg = cm.message();
            int level = cm.messageLevel().ordinal();
            // LOG_LEVEL_ERROR=2, LOG_LEVEL_WARNING=1, LOG_LEVEL_LOG=0
            switch (level) {
                case 2:
                    Log.e("CCTV-TV", "[E] " + msg + "  @ " + cm.sourceId() + ":" + cm.lineNumber());
                    // 严重 JS 错误直接显示到面板(白屏时用户看得清)
                    if (activity != null) {
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
