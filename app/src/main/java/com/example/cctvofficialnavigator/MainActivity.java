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
     * 页面一开始加载就注入 FastLoading(每 200ms 跑一次):
     *  1. 注入强力 CSS,强制让播放器容器 #player 占满 100vw/100vh,隐藏所有非播放器装饰元素
     *     (顶部"体育频道直播"标题条、底部版权、右侧频道列表、节目预告区、广告等)
     *  2. 清空所有 img 的 src(屏蔽非播放器相关图片)
     *  3. 清空某些不必要 script 的 src
     *  4. 不再依赖"网页全屏"按钮 click()(在某些频道上不可靠)
     *  5. 持续运行,即使视频元素已出现,也要把页面装饰元素持续清空
     */
    private void injectFastLoading(WebView view) {
        final int gen = loadGeneration;
        String js =
                "(function(){" +
                "  if(window.__cctvFastLoadingInjected)return;" +
                "  window.__cctvFastLoadingInjected=true;" +
                // CSS:
                //  - 外层容器(playingVideo / video_left / video_flash) 100vw/100vh + position:absolute 占满
                //  - #player 容器 100vw/100vh
                //  - video 元素 width/height 100% 铺满父容器 .video_flash(不写 object-fit,
                //    避免覆盖 liveplayer.js 的 object-fit 设置)
                //  - 装饰元素隐藏
                "  var css=" +
                "    'html,body{width:100%!important;height:100%!important;margin:0!important;padding:0!important;background:#000!important;overflow:hidden!important}'+" +
                "    '.jiemuguanwang18950_zhibo_ind01,.zhibo19629_ind01,.playingVideo{width:100vw!important;height:100vh!important;margin:0!important;padding:0!important;position:absolute!important;left:0!important;top:0!important;right:0!important;bottom:0!important}'+" +
                "    '.video_left,.video_flash{width:100vw!important;height:100vh!important;margin:0!important;padding:0!important;position:absolute!important;left:0!important;top:0!important;right:0!important;bottom:0!important;background:#000!important;overflow:hidden!important}'+" +
                "    '#player{width:100%!important;height:100%!important;margin:0!important;padding:0!important;background:#000!important;position:relative!important;overflow:hidden!important}'+" +
                "    'video{width:100%!important;height:100%!important;display:block!important}'+" +
                "    '.video_right,.video_btnBar,.bg_top_h_tile,.bg_top_owner,.bg_bottom_h_tile,header,footer,nav,.vspace,.column_wrapper{display:none!important}';" +
                "  function applyCss(){" +
                "    var existing=document.getElementById('cctv-tv-style');" +
                "    if(existing){try{existing.textContent=css;}catch(e){}return;}" +
                "    var s=document.createElement('style');" +
                "    s.id='cctv-tv-style';" +
                "    s.textContent=css;" +
                "    // 一定要 append 到 head,如果 head 还没就绪就等下一次循环\n" +
                "    if(document.head){document.head.appendChild(s);}else{document.addEventListener('DOMContentLoaded',function(){if(document.head&&!document.getElementById('cctv-tv-style')){document.head.appendChild(s);}});}" +
                "  }" +
                "  function stripImages(){" +
                "    var imgs=document.getElementsByTagName('img');" +
                "    for(var i=0;i<imgs.length;i++){try{imgs[i].src='';imgs[i].removeAttribute('src');}catch(e){}}" +
                "  }" +
                "  function stripScripts(){" +
                "    var kw=['login','index','daohang','grey','jquery.qrcode','tinyscrollbar','shareindex','zhibo_shoucang','h5_shield','cntv_Advertise'];" +
                "    var scripts=document.getElementsByTagName('script');" +
                "    for(var j=0;j<scripts.length;j++){" +
                "      var s=scripts[j].src||'';" +
                "      for(var k=0;k<kw.length;k++){if(s.indexOf(kw[k])>=0){try{scripts[j].parentNode&&scripts[j].parentNode.removeChild(scripts[j]);}catch(e){}break;}}" +
                "    }" +
                "  }" +
                "  function FastLoading(){" +
                "    applyCss();" +
                "    stripImages();" +
                "    stripScripts();" +
                "    if(window.__cctvFlStart===undefined)window.__cctvFlStart=Date.now();" +
                "    if(Date.now()-window.__cctvFlStart<30000)setTimeout(FastLoading,200);" +
                "  }" +
                "  if(document.readyState==='complete'||document.readyState==='interactive'){" +
                "    FastLoading();" +
                "  }else{" +
                "    document.addEventListener('DOMContentLoaded',FastLoading);" +
                "  }" +
                "  // 兜底:即使已经 load 完了,第一帧还是要跑\n" +
                "  setTimeout(FastLoading,50);" +
                "})()";
        view.evaluateJavascript(js, null);
        if (gen != loadGeneration) { /* cut over happened */ }
    }

    /**
     * 页面加载完注入 AutoFullscreen:
     *  关键修正:不再修改 video 元素自身的 style(避免覆盖 liveplayer.js 注入的 src/size),
     *  只调音量和尝试 play()。
     *  全屏布局完全由 FastLoading 注入的 CSS(针对 .video_left / .video_flash / .playingVideo 等
     *  外层容器)负责。
     */
    private void injectAutoFullscreen(WebView view) {
        final int gen = loadGeneration;
        String js =
                "(function(){" +
                "  function Nudge(){" +
                "    var v=document.querySelector('video');" +
                "    if(v){" +
                "      try{v.volume=1;}catch(e){}" +
                "      try{v.muted=false;}catch(e){}" +
                "      try{v.play();}catch(e){}" +
                "    }" +
                "  }" +
                "  Nudge();" +
                "  var count=0;" +
                "  function loop(){" +
                "    Nudge();" +
                "    count++;" +
                "    if(count<26)setTimeout(loop,300);" +
                "  }" +
                "  setTimeout(loop,300);" +
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
     * 8 秒后检查页面里到底有没有 video 元素,以及视频是否真的有内容。
     *  - video 元素不存在 → 自动跳下一个频道
     *  - video 元素存在但 readyState < 2 (HAVE_CURRENT_DATA) 或 videoWidth=0 → 流还没加载好,
     *    直接跳下一个(某些频道 CCTV 后端没流)
     *  - video.paused=true → 调 play() 强制播放
     */
    private void scheduleWhiteScreenCheck() {
        final int gen = loadGeneration;
        handler.postDelayed(() -> {
            if (gen != loadGeneration) return;
            String js =
                    "(function(){" +
                    "  var v=document.querySelector('video');" +
                    "  if(!v){return 'NO_VIDEO';}" +
                    "  var rs=v.readyState||0;" +
                    "  var w=v.videoWidth||0;" +
                    "  if(rs<2 || w===0){return 'NOT_READY:'+rs+','+w;}" +
                    "  return 'OK:'+rs+','+w+','+(v.paused?'PAUSED':'PLAYING');" +
                    "})()";
            webView.evaluateJavascript(js, value -> {
                if (gen != loadGeneration) return;
                if (value == null) return;
                String state = value.toString();
                if (state.contains("NO_VIDEO") || state.contains("NOT_READY")) {
                    Toast.makeText(MainActivity.this,
                            "此频道暂不可用,自动跳下一个", Toast.LENGTH_SHORT).show();
                    handler.postDelayed(() -> {
                        if (gen != loadGeneration) return;
                        loadChannel(channelIndex + 1);
                    }, 1200);
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
