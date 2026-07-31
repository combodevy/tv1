package com.example.cctvofficialnavigator;

import android.annotation.SuppressLint;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

/**
 * 央视官方直播导航(Android TV)主界面。
 *
 * 行为契约(对齐 README):
 * - 启动加载默认频道(CCTV-9 纪录)的央视官方直播页;
 * - 遥控器上/下键循环切换官方频道网页;
 * - 遥控器确定键(DPAD_CENTER)不拦截,交给 WebView 内部的官方播放器;
 * - 遥控器菜单键(82)在底部显示当前频道名与切台提示,2 秒后自动消失;
 * - 系统返回键不拦截,交给 WebView 处理(回退到上一个网页)。
 */
public class MainActivity extends AppCompatActivity {

    private WebView webView;
    private TextView toast;
    private int currentIndex = ChannelCatalog.DEFAULT_INDEX;

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // 全屏沉浸(适合电视大屏)
        getWindow().setFlags(
                WindowManager.LayoutParams.FLAG_FULLSCREEN,
                WindowManager.LayoutParams.FLAG_FULLSCREEN);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
            getWindow().getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                            | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
        }

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        root.setFocusable(true);
        root.setFocusableInTouchMode(true);
        root.requestFocus();

        webView = new WebView(this);
        FrameLayout.LayoutParams webLp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT);
        root.addView(webView, webLp);

        toast = new TextView(this);
        toast.setBackgroundColor(0xCC000000);
        toast.setTextColor(Color.WHITE);
        toast.setTextSize(18f);
        toast.setPadding(48, 24, 48, 24);
        toast.setVisibility(View.GONE);
        FrameLayout.LayoutParams toastLp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT);
        toastLp.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
        toastLp.bottomMargin = 80;
        root.addView(toast, toastLp);

        setContentView(root);

        // WebView 基础配置:启用 JS / DOM Storage(央视官方页面需要)
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setUseWideViewPort(true);
        settings.setLoadWithOverviewMode(true);
        settings.setSupportZoom(true);
        settings.setBuiltInZoomControls(false);
        settings.setMediaPlaybackRequiresUserGesture(false);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            settings.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        }
        webView.setWebViewClient(new WebViewClient());

        // 把焦点交给 root,这样遥控器按键能先到 Activity 而不是 WebView
        root.setOnKeyListener(this::onRootKey);

        // 加载默认频道
        loadChannel(currentIndex, false);
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_UP:        // 遥控器上
                switchChannel(false);
                return true;
            case KeyEvent.KEYCODE_DPAD_DOWN:      // 遥控器下
                switchChannel(true);
                return true;
            case KeyEvent.KEYCODE_MENU:           // 遥控器菜单:显示频道提示
                showChannelToast();
                return true;
            default:
                return super.onKeyDown(keyCode, event);
        }
    }

    private boolean onRootKey(View v, int keyCode, KeyEvent event) {
        if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
        // 把方向键切台的处理也放在 root 层,防止 WebView 在某些 ROM 上抢焦点导致收不到
        if (keyCode == KeyEvent.KEYCODE_DPAD_UP) {
            switchChannel(false);
            return true;
        }
        if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
            switchChannel(true);
            return true;
        }
        if (keyCode == KeyEvent.KEYCODE_MENU) {
            showChannelToast();
            return true;
        }
        return false;
    }

    private void switchChannel(boolean forward) {
        currentIndex = ChannelCatalog.nextIndex(currentIndex, forward);
        loadChannel(currentIndex, true);
    }

    private void loadChannel(int index, boolean fromUser) {
        ChannelCatalog.Channel ch = ChannelCatalog.CHANNELS[index];
        webView.loadUrl(ch.url);
        if (fromUser) {
            showChannelToast();
        }
    }

    private void showChannelToast() {
        ChannelCatalog.Channel ch = ChannelCatalog.CHANNELS[currentIndex];
        int total = ChannelCatalog.size();
        toast.setText(String.format("%s  (%d/%d)\n上/下 切台  ·  菜单 显示此提示", ch.name, currentIndex + 1, total));
        toast.setVisibility(View.VISIBLE);
        toast.removeCallbacks(hideToastRunnable);
        toast.postDelayed(hideToastRunnable, 2000);
    }

    private final Runnable hideToastRunnable = new Runnable() {
        @Override public void run() { toast.setVisibility(View.GONE); }
    };

    @Override
    public void onBackPressed() {
        // 返回键交给 WebView 处理(回退到上一个网页);若没有历史则退出
        if (webView != null && webView.canGoBack()) {
            webView.goBack();
        } else {
            super.onBackPressed();
        }
    }

    @Override
    protected void onDestroy() {
        if (webView != null) {
            webView.destroy();
            webView = null;
        }
        super.onDestroy();
    }
}
