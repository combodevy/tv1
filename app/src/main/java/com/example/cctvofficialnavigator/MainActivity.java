package com.example.cctvofficialnavigator;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.graphics.Color;
import android.graphics.Bitmap;
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

/**
 * A remote-first navigator for official CCTV pages. It deliberately has no stream extraction code.
 *
 * Fullscreen strategy (verified against tv.cctv.com/live/cctv* on 2026-07-31):
 *  1. {@code onPageStarted} injects a {@code FastLoading} function that runs every 4ms.
 *     It strips non-essential images, scripts, and decorative divs so the CCTV H5 player
 *     has nothing in the way, and stops as soon as the page renders the "page fullscreen"
 *     button that the player creates.
 *  2. {@code onPageFinished} injects an {@code AutoFullscreen} function that polls every
 *     16ms and clicks {@code #player_pagefullscreen_yes_player} (or {@code .videoFull})
 *     the moment it appears. That is the official CCTV page-fullscreen button; clicking
 *     it puts the player in a built-in pseudo-fullscreen layout (header/nav stripped).
 *  3. We do NOT chase iframes or guess selectors. The CCTV team renames them periodically.
 */
public final class MainActivity extends Activity {
    private static final String SAVED_CHANNEL_INDEX = "channel_index";
    private static final long CHANNEL_HINT_DURATION_MS = 1800L;
    private static final long WHITE_SCREEN_CHECK_DELAY_MS = 8000L;

    private WebView webView;
    private TextView channelHint;
    private FrameLayout rootContainer;
    private int channelIndex;
    private final Runnable hideChannelHint = () -> channelHint.setVisibility(View.GONE);
    private final Handler handler = new Handler(Looper.getMainLooper());
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
        settings.setDatabaseEnabled(true);
        // 关闭自动加载图片:屏蔽非播放器相关图片,大幅减少 4G 流量与首屏时间
        settings.setLoadsImagesAutomatically(false);
        settings.setBlockNetworkImage(true);
        settings.setMediaPlaybackRequiresUserGesture(false);
        settings.setLoadWithOverviewMode(true);
        settings.setUseWideViewPort(true);

        webView.setWebChromeClient(new WebChromeClient());
        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                return !isOfficialCctvUrl(request.getUrl().toString());
            }

            @Override
            public void onPageStarted(WebView view, String url, Bitmap favicon) {
                super.onPageStarted(view, url, favicon);
                injectFastLoading(view);
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                injectAutoFullscreen(view);
                scheduleWhiteScreenCheck();
            }
        });
    }

    /**
     * 页面一开始加载就注入 FastLoading(每 4ms 跑一次):
     *  1. 清空所有 img 的 src(屏蔽非播放器相关图片)
     *  2. 清空某些不必要 script 的 src
     *  3. 清空特定 class 的 div 内容
     *  4. 一旦央视的"网页全屏"按钮出现,就停止递归
     */
    private void injectFastLoading(WebView view) {
        final int gen = loadGeneration;
        String js =
                "(function(){" +
                "  if(window.__cctvFastLoadingInjected)return;" +
                "  window.__cctvFastLoadingInjected=true;" +
                "  function FastLoading(){" +
                "    var btn=document.querySelector('#player_pagefullscreen_yes_player')||document.querySelector('.videoFull');" +
                "    if(btn)return;" +
                "    var imgs=document.getElementsByTagName('img');" +
                "    for(var i=0;i<imgs.length;i++){try{imgs[i].src='';}catch(e){}}" +
                "    var kw=['login','index','daohang','grey','jquery'];" +
                "    var scripts=document.getElementsByTagName('script');" +
                "    for(var j=0;j<scripts.length;j++){" +
                "      var s=scripts[j].src||'';" +
                "      for(var k=0;k<kw.length;k++){if(s.indexOf(kw[k])>=0){try{scripts[j].src='';}catch(e){}break;}}" +
                "    }" +
                "    var cls=['newmap','newtopbz','newtopbzTV','column_wrapper'];" +
                "    for(var c=0;c<cls.length;c++){" +
                "      var nodes=document.getElementsByClassName(cls[c]);" +
                "      for(var n=0;n<nodes.length;n++){try{nodes[n].innerHTML='';}catch(e){}}" +
                "    }" +
                "    setTimeout(FastLoading,4);" +
                "  }" +
                "  if(document.readyState==='complete'||document.readyState==='interactive'){" +
                "    FastLoading();" +
                "  }else{" +
                "    document.addEventListener('DOMContentLoaded',FastLoading);" +
                "  }" +
                "})()";
        view.evaluateJavascript(js, value -> {
            // 不管结果,继续白屏检测
        });
        // gen 仅用于防止串台;这里没有 callback,所以忽略
        if (gen != loadGeneration) { /* cut over happened */ }
    }

    /**
     * 页面加载完注入 AutoFullscreen(每 16ms 检查一次):
     *  央视的播放器在视频真正开始后才会注入 #player_pagefullscreen_yes_player 这个按钮。
     *  看到就 click() 一下,央视自己会切到"网页全屏"布局(隐藏顶/底/侧栏,只留播放器)。
     */
    private void injectAutoFullscreen(WebView view) {
        final int gen = loadGeneration;
        String js =
                "(function(){" +
                "  function AutoFullscreen(){" +
                "    var btn=document.querySelector('#player_pagefullscreen_yes_player')||document.querySelector('.videoFull');" +
                "    if(btn){" +
                "      try{btn.click();}catch(e){}" +
                "      var v=document.querySelector('video');" +
                "      if(v){try{v.volume=1;}catch(e){}}" +
                "    }else{" +
                "      setTimeout(AutoFullscreen,16);" +
                "    }" +
                "  }" +
                "  AutoFullscreen();" +
                "})()";
        view.evaluateJavascript(js, null);
        if (gen != loadGeneration) { /* cut over happened */ }
    }

    private void loadChannel(int requestedIndex) {
        handler.removeCallbacksAndMessages(null);
        loadGeneration++;
        int count = ChannelCatalog.CHANNELS.size();
        channelIndex = ((requestedIndex % count) + count) % count;
        Channel channel = ChannelCatalog.CHANNELS.get(channelIndex);
        webView.loadUrl(channel.officialUrl);
        showChannelHint(channel.name);
    }

    private void showChannelHint(String channelName) {
        channelHint.removeCallbacks(hideChannelHint);
        channelHint.setText(channelName + "  ·  上下键切换频道  ·  菜单键显示提示");
        channelHint.setVisibility(View.VISIBLE);
        channelHint.postDelayed(hideChannelHint, CHANNEL_HINT_DURATION_MS);
    }

    /**
     * 8 秒后检查页面里到底有没有 video 元素。
     * 没有 → 提示白屏,让用户按 ↓ 跳下一个。
     */
    private void scheduleWhiteScreenCheck() {
        final int gen = loadGeneration;
        handler.postDelayed(() -> {
            if (gen != loadGeneration) return;
            String js =
                    "(function(){" +
                    "  var v=document.querySelector('video');" +
                    "  if(v){return 'OK:'+(v.paused?'PAUSED':'PLAYING');}" +
                    "  return 'NO_VIDEO';" +
                    "})()";
            webView.evaluateJavascript(js, value -> {
                if (gen != loadGeneration) return;
                if (value == null) return;
                String state = value.toString();
                if (state.contains("NO_VIDEO")) {
                    Toast.makeText(MainActivity.this,
                            "频道暂不可用,按 下方向键 跳下一个", Toast.LENGTH_LONG).show();
                } else if (state.contains("PAUSED")) {
                    webView.evaluateJavascript(
                            "(function(){var v=document.querySelector('video');if(v){v.play();}return true;})()",
                            null);
                }
            });
        }, WHITE_SCREEN_CHECK_DELAY_MS);
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
}
