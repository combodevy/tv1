package com.example.cctvofficialnavigator;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.view.KeyEvent;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.FrameLayout;

import java.net.URI;
import java.util.Locale;

/** A remote-first navigator for official CCTV pages. It deliberately has no stream extraction code. */
public final class MainActivity extends Activity {
    private static final String SAVED_CHANNEL_INDEX = "channel_index";
    private static final long CHANNEL_HINT_DURATION_MS = 1800L;
    // 等 CCTV 父页把 iframe 渲染好,再去抓 iframe src
    private static final long IFRAME_LOOKUP_DELAY_MS = 2500L;
    // 兜底:加载 8 秒后还没看到 video 元素,提示白屏
    private static final long WHITE_SCREEN_CHECK_DELAY_MS = 8000L;

    private WebView webView;
    private TextView channelHint;
    private FrameLayout rootContainer;
    private int channelIndex;
    private View customFullscreenView;
    private WebChromeClient.CustomViewCallback customFullscreenCallback;
    private final Runnable hideChannelHint = () -> channelHint.setVisibility(View.GONE);
    private final Handler handler = new Handler(Looper.getMainLooper());
    // 防止 iframe 提取无限递归
    private int iframeRedirectCount = 0;
    // 防止白屏检测 / iframe 提取跨频道串台
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
        settings.setMediaPlaybackRequiresUserGesture(false);
        settings.setLoadWithOverviewMode(true);
        settings.setUseWideViewPort(true);
        // 移动端 UA:CCTV 返回 H5 移动版,布局更紧凑
        settings.setUserAgentString(
                "Mozilla/5.0 (Linux; Android 13; TV) AppleWebKit/537.36 (KHTML, like Gecko) "
                        + "Chrome/120.0.0.0 Mobile Safari/537.36 CctvOfficialNavigator/1.0");
        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onShowCustomView(View view, CustomViewCallback callback) {
                if (customFullscreenView != null) {
                    callback.onCustomViewHidden();
                    return;
                }
                customFullscreenView = view;
                customFullscreenCallback = callback;
                rootContainer.addView(view, new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
                webView.setVisibility(View.GONE);
                enterImmersiveMode();
            }

            @Override
            public void onHideCustomView() {
                hideOfficialPlayerFullscreen();
            }
        });
        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                return !isOfficialCctvUrl(request.getUrl().toString());
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                applyFullscreenAndAutoPlay(url);
                scheduleWhiteScreenCheck();
            }
        });
    }

    private void loadChannel(int requestedIndex) {
        // 切台:取消所有待执行任务,避免上一个频道的延迟回调串到新频道
        handler.removeCallbacksAndMessages(null);
        loadGeneration++;
        iframeRedirectCount = 0;
        int count = ChannelCatalog.CHANNELS.size();
        channelIndex = ((requestedIndex % count) + count) % count;
        Channel channel = ChannelCatalog.CHANNELS.get(channelIndex);
        webView.loadUrl(channel.officialUrl);
        showChannelHint(channel.name);
    }

    private void showChannelHint(String channelName) {
        channelHint.removeCallbacks(hideChannelHint);
        channelHint.setText(channelName + "  ·  ↑↓ 切换频道  ·  菜单键显示提示");
        channelHint.setVisibility(View.VISIBLE);
        channelHint.postDelayed(hideChannelHint, CHANNEL_HINT_DURATION_MS);
    }

    /**
     * 每次页面加载完都跑:先注 CSS,然后 2.5 秒后找 iframe,找到了就跳过去(纯播放器页更好控制),
     * 找不到就尝试点页面的播放按钮,再尝试 requestFullscreen。
     */
    private void applyFullscreenAndAutoPlay(String loadedUrl) {
        // 1. 立即注入全屏 CSS
        injectFullscreenCSS();

        // 2. 判断当前是不是已经在 player.cntv.cn 域
        boolean onIframeHost = loadedUrl != null && loadedUrl.contains("player.cntv.cn");

        if (onIframeHost) {
            // 已经在纯播放器页,直接尝试播放 + 全屏
            handler.postDelayed(this::tryClickPlayAndFullscreen, 800);
        } else {
            // 在 CCTV 父页,等一下再去找 iframe
            if (iframeRedirectCount < 2) {
                handler.postDelayed(this::tryNavigateToIframe, IFRAME_LOOKUP_DELAY_MS);
            } else {
                // 兜底:跳太多次了,直接点播放
                handler.postDelayed(this::tryClickPlayAndFullscreen, 1000);
            }
        }
    }

    private void injectFullscreenCSS() {
        String js =
                "(function(){" +
                "  var s=document.createElement('style');" +
                "  s.id='cctv-nav-style';" +
                "  s.innerHTML=[" +
                "    'html,body{margin:0!important;padding:0!important;background:#000!important;" +
                "       overflow:hidden!important;width:100%!important;height:100%!important}'," +
                "    'body>*:not(iframe):not(video):not(.player):not(.video):not(.player-container):not(.video-container):not(.live-player):not(#player){" +
                "       display:none!important}'," +
                "    'body>header,body>nav,body>footer,body>.header,body>.top,body>.nav,body>.top-bar," +
                "       body>.search,body>.menu,body>.toolbar,body>.side,body>.sidebar,body>.aside{" +
                "       display:none!important}'," +
                "    'iframe,video,#player,.player,.player-container,.video-container,.live-player,.video,.video-wrap{" +
                "       position:fixed!important;top:0!important;left:0!important;" +
                "       width:100vw!important;height:100vh!important;" +
                "       max-width:none!important;max-height:none!important;" +
                "       z-index:99999!important;border:0!important;background:#000!important}'," +
                "    'iframe[allowfullscreen]{allowfullscreen:true;webkitallowfullscreen:true;mozallowfullscreen:true}'" +
                "  ].join('');" +
                "  var old=document.getElementById('cctv-nav-style');" +
                "  if(old)old.remove();" +
                "  (document.head||document.documentElement).appendChild(s);" +
                "  return true;" +
                "})()";
        webView.evaluateJavascript(js, null);
    }

    /**
     * 找 CCTV 父页里的 player.cntv.cn iframe,找到了直接导航过去。
     * 父页是垃圾(顶部 logo+菜单+新闻),iframe 才是真正的播放器。
     */
    private void tryNavigateToIframe() {
        final int gen = loadGeneration;
        String js =
                "(function(){" +
                "  var iframe=document.querySelector('iframe[src*=\"player.cntv.cn\"]');" +
                "  if(iframe&&iframe.src){return iframe.src;}" +
                "  return '';" +
                "})()";
        webView.evaluateJavascript(js, value -> {
            if (gen != loadGeneration) return;          // 用户已切台
            if (value == null) {
                tryClickPlayAndFullscreen();
                return;
            }
            // evaluateJavascript 返回的字符串带引号,要去掉
            String iframeUrl = value.toString();
            if (iframeUrl.length() >= 2 && iframeUrl.startsWith("\"") && iframeUrl.endsWith("\"")) {
                iframeUrl = iframeUrl.substring(1, iframeUrl.length() - 1);
            }
            iframeUrl = iframeUrl.replace("\\/", "/").replace("\\\"", "\"").replace("\\\\", "\\");

            if (!iframeUrl.isEmpty() && !iframeUrl.equals("null") && !iframeUrl.equals("undefined")) {
                iframeRedirectCount++;
                webView.loadUrl(iframeUrl);             // 跳到纯播放器页
            } else {
                tryClickPlayAndFullscreen();            // 没 iframe,兜底点播放
            }
        });
    }

    /**
     * 兜底:在父页找不到 iframe 时,点页面里的"直播/Live/播放"按钮,
     * 再尝试把 video / iframe 拉成 HTML5 全屏(走 onShowCustomView)。
     */
    private void tryClickPlayAndFullscreen() {
        final int gen = loadGeneration;
        String js =
                "(function(){" +
                "  var clicked=false;" +
                "  // 1. 选择器匹配播放按钮" +
                "  var selectors=[" +
                "    'button[title*=\"播放\" i]','button[title*=\"play\" i]'," +
                "    'button[title*=\"Live\" i]','button[title*=\"直播\" i]'," +
                "    '[class*=\"play-btn\"]','[class*=\"playBtn\"]','.play-btn','.play-button'," +
                "    '[class*=\"live-btn\"]','[class*=\"liveBtn\"]'," +
                "    '[class*=\"play\"]:not([class*=\"player\"]):not([class*=\"playlist\"])'" +
                "  ];" +
                "  for(var s of selectors){" +
                "    var b=document.querySelector(s);" +
                "    if(b&&b.offsetParent!==null){b.click();clicked=true;break;}" +
                "  }" +
                "  // 2. 按按钮文字匹配" +
                "  if(!clicked){" +
                "    var btns=document.querySelectorAll('button,[role=\"button\"],a,div[onclick]');" +
                "    for(var b of btns){" +
                "      var t=(b.textContent||'').trim();" +
                "      if(t==='直播'||t==='Live'||t==='播放'||t==='Play'||t==='开始观看'||t==='观看直播'){" +
                "        b.click();clicked=true;break;" +
                "      }" +
                "    }" +
                "  }" +
                "  // 3. 尝试 HTML5 全屏" +
                "  setTimeout(function(){" +
                "    var v=document.querySelector('video');" +
                "    if(v&&v.requestFullscreen){try{v.play();v.requestFullscreen();}catch(e){}}" +
                "    var fs=document.querySelector('iframe[src*=\"player.cntv.cn\"]');" +
                "    if(fs&&fs.requestFullscreen){try{fs.requestFullscreen();}catch(e){}}" +
                "  },500);" +
                "  return clicked?'CLICKED':'NO_BUTTON';" +
                "})()";
        webView.evaluateJavascript(js, value -> {
            if (gen != loadGeneration) return;
            // 拿到结果,失败/成功都无所谓,白屏检测会兜底
        });
    }

    /**
     * 8 秒后看页面里到底有没有 video / iframe。没有 → 提示白屏。
     * 用 loadGeneration 防串台。
     */
    private void scheduleWhiteScreenCheck() {
        final int gen = loadGeneration;
        handler.postDelayed(() -> {
            if (gen != loadGeneration) return;          // 用户已经切台
            String js =
                    "(function(){" +
                    "  var v=document.querySelector('video');" +
                    "  if(v){return 'VIDEO:'+(v.paused?'PAUSED':'PLAYING');}" +
                    "  var fs=document.querySelector('iframe[src*=\"player.cntv.cn\"]');" +
                    "  if(fs){return 'IFRAME_ONLY';}" +
                    "  return 'WHITE_SCREEN';" +
                    "})()";
            webView.evaluateJavascript(js, value -> {
                if (gen != loadGeneration) return;
                if (value == null) return;
                String state = value.toString();
                if (state.contains("WHITE_SCREEN")) {
                    Toast.makeText(MainActivity.this,
                            "频道暂不可用,按 ↓ 跳下一个", Toast.LENGTH_LONG).show();
                } else if (state.contains("PAUSED")) {
                    // 有 video 但被暂停,再点一次播放
                    webView.evaluateJavascript(
                            "(function(){var v=document.querySelector('video');if(v){v.play();}return true;})()",
                            null);
                }
            });
        }, WHITE_SCREEN_CHECK_DELAY_MS);
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (event.getKeyCode() == KeyEvent.KEYCODE_DPAD_CENTER) {
            if (event.getAction() == KeyEvent.ACTION_DOWN) focusOfficialPlayer();
            return webView.dispatchKeyEvent(event);
        }
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
                showChannelHint(ChannelCatalog.CHANNELS.get(channelIndex).name);
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

    @Override
    public void onBackPressed() {
        if (customFullscreenView != null) {
            hideOfficialPlayerFullscreen();
            return;
        }
        super.onBackPressed();
    }

    /**
     * 沉浸式全屏:Android 11+ 用 WindowInsetsController,旧版降级用 systemUiVisibility。
     */
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

    /** Focuses the official player frame only; it does not access or modify its cross-origin content. */
    private void focusOfficialPlayer() {
        webView.requestFocus();
        webView.evaluateJavascript(
                "(function(){var frame=document.querySelector('iframe[src*=\"player.cntv.cn\"]');"
                        + "if(frame){frame.tabIndex=0;frame.focus();return true;}return false;})()",
                null);
    }

    private void hideOfficialPlayerFullscreen() {
        if (customFullscreenView == null) return;
        rootContainer.removeView(customFullscreenView);
        customFullscreenView = null;
        webView.setVisibility(View.VISIBLE);
        if (customFullscreenCallback != null) customFullscreenCallback.onCustomViewHidden();
        customFullscreenCallback = null;
        enterImmersiveMode();
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
}
