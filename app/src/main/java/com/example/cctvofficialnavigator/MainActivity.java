package com.example.cctvofficialnavigator;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.view.KeyEvent;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.TextView;
import android.widget.FrameLayout;

import java.net.URI;
import java.util.Locale;

/** A remote-first navigator for official CCTV pages. It deliberately has no stream extraction code. */
public final class MainActivity extends Activity {
    private static final String SAVED_CHANNEL_INDEX = "channel_index";
    private static final long CHANNEL_HINT_DURATION_MS = 1800L;

    private WebView webView;
    private TextView channelHint;
    private FrameLayout rootContainer;
    private int channelIndex;
    private View customFullscreenView;
    private WebChromeClient.CustomViewCallback customFullscreenCallback;
    private final Runnable hideChannelHint = () -> channelHint.setVisibility(View.GONE);

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // 必须先于 setContentView:无标题、状态栏 / 导航栏透明
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        Window window = getWindow();
        window.setStatusBarColor(Color.TRANSPARENT);
        window.setNavigationBarColor(Color.TRANSPARENT);
        // Android P+ 允许画到刘海区域
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            WindowManager.LayoutParams lp = window.getAttributes();
            lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
            window.setAttributes(lp);
        }

        setContentView(R.layout.activity_main);
        webView = findViewById(R.id.live_web_view);
        channelHint = findViewById(R.id.channel_hint);
        rootContainer = findViewById(R.id.root_container);
        // 黑底,避免白闪
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
        // 用移动端 UA,CCTV 会返回 H5 移动版页面,布局更紧凑,更易全屏化
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
                // 先注入 CSS 隐藏 CCTV 页面 chrome + 撑满 iframe
                // 再调 iframe.requestFullscreen() 触发真正的 HTML5 全屏(走 onShowCustomView)
                makeVideoFullscreen();
                focusOfficialPlayer();
            }
        });
    }

    private void loadChannel(int requestedIndex) {
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

    /**
     * 三步走:
     *  1. 注入 CSS 隐藏 CCTV 页面所有 chrome(头部 / 导航 / 搜索 / 菜单 / 侧边栏 / 页脚 / 广告)
     *  2. 把 video / iframe 撑满 100vw x 100vh
     *  3. 调 iframe.requestFullscreen() 触发真正的 HTML5 全屏 → 走 onShowCustomView
     *
     * CSS 注入会重复执行,先清旧 style 再加新的,保证 CCTV 动态加载广告时也能被覆盖。
     */
    private void makeVideoFullscreen() {
        String js =
                "(function(){" +
                "  var old=document.getElementById('cctv-nav-style');" +
                "  if(old)old.remove();" +
                "  var s=document.createElement('style');" +
                "  s.id='cctv-nav-style';" +
                "  s.innerHTML=[" +
                "    'html,body{margin:0!important;padding:0!important;background:#000!important;" +
                "       overflow:hidden!important;width:100%!important;height:100%!important}'," +
                "    'body>*:not(iframe):not(video):not(.player):not(.video):not(.player-container):not(.video-container):not(.live-player):not(#player){" +
                "       display:none!important}'," +
                "    'body>header,body>nav,body>footer,body>.header,body>.top,body>.nav,body>.top-bar," +
                "       body>.search,body>.menu,body>.toolbar,body>.side,body>.sidebar,body>.aside,body>.ad,body>.advert{" +
                "       {display:none!important}'.replace('{}{','{')," +
                "    'iframe,video,#player,.player,.player-container,.video-container,.live-player,.video,.video-wrap{" +
                "       position:fixed!important;top:0!important;left:0!important;" +
                "       width:100vw!important;height:100vh!important;" +
                "       max-width:none!important;max-height:none!important;" +
                "       z-index:99999!important;border:0!important;background:#000!important}'," +
                "    'iframe[allowfullscreen]{allowfullscreen:true;webkitallowfullscreen:true;mozallowfullscreen:true}'" +
                "  ].join('');" +
                "  (document.head||document.documentElement).appendChild(s);" +
                "  // 尝试让 iframe 真正进入 HTML5 全屏(走 onShowCustomView → 全屏视频)" +
                "  setTimeout(function(){" +
                "    var fs=document.querySelector(" +
                "      'iframe[src*=\"player.cntv.cn\"],iframe[src*=\"cntv.cn\"]');" +
                "    if(fs&&fs.requestFullscreen){try{fs.requestFullscreen();}catch(e){}}" +
                "    // 如果还有 video 元素,也对它尝试" +
                "    var v=document.querySelector('video');" +
                "    if(v&&v.requestFullscreen){try{v.requestFullscreen();}catch(e){}}" +
                "  },1500);" +
                "  return true;" +
                "})()";
        webView.evaluateJavascript(js, null);
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
