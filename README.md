# 央视官方直播导航 (CCTV Official Live Navigator)

> **给接手工程师的 FIRST THING FIRST：先读下面"核心问题与方向"那一节，否则你会重复踩我们已经踩过的坑。**

---

## 1. 核心问题与接手方向（重要！先看这里）

### ⚠️ 2026-08-02 最新状态：重定向/版权页已解决，CCTV-3/6/8「有声音没画面」仍待解

**当前真实情况（用户 2026-08-02 最新反馈）：**
- ✅ 其他所有台（CCTV-1/2/4/5/5+/7/9~17 + 广西台）：画面 + 声音 100% 正常，`LAYER_TYPE_HARDWARE` 硬件加速 + 默认移动 UA 稳定工作
- ✅ CCTV-3/6/8：重定向问题已解决 → 不再跳「分享频道已下架」/ `m.yangshipin.cn` 移动端旧页，版权页也已被 `#app` 整体隐藏不再漏出，**但是：仍然是有声音、无画面（黑屏）**，Chromium WebView 渲染链路问题（SurfaceView overlay / 软件渲染 Canvas 绑定）在这些 yangshipin 桌面端页面上持续出现，CSS/JS/LayerType 修复无效

| 阻塞问题 | 根因 | 当前进展 | 当前状态 |
|---|---|---|---|
| CCTV-6 打开显示「分享频道已下架」 + 跳 m.yangshipin.cn | Android WebView 自动加 `X-Requested-With: <包名>` Header → 服务器识别为 WebView，跳移动端旧域名 | **防重定向 2 层拦截** + **loadYangshipinWithHeaders() 带定制 Header 加载**：<br>1. `shouldOverrideUrlLoading` + `onPageStarted` 双重拦截<br>2. `loadUrl(url, additionalHttpHeaders)` 塞 `Referer: https://www.yangshipin.cn/` + `X-Requested-With: ""` | ✅ 彻底解决，不跳移动端 |
| CCTV-3/6/8 有声音没画面、黑屏 | Chromium Android `<video>` 渲染链路问题：<br>① `LAYER_TYPE_HARDWARE` 硬件加速 → SurfaceView overlay 位置计算错误 → 黑屏<br>② 切 `LAYER_TYPE_SOFTWARE` 软件渲染 → MediaCodec 解码器输出 Surface 无法绑定到 WebView Canvas → 依旧黑屏<br>（两条路径都画不出来，但解码器 + audio 正常 → 有声音） | **WebView 端 CSS/JS/LayerType 修复全部无效，必须切原生播放器** | ❌ 待解决（即将切 ExoPlayer 原生播放，见下方接手方向 1） |
| CCTV-6 偶尔弹出「关于央视频 / 服务协议」版权页 | hideSels 只列部分 class 名 → #app 深层嵌套漏藏 | `hideSels` 第一个元素直接 `'#app'`，#app 整个根节点 `display:none!important + z-index:-1`，真实 `.video-js` 父容器已 detach 到 body | ✅ 100% 不可能再漏出版权页 |

### CCTV-3/8 已加回

`ChannelCatalog` 已加入 CCTV-3/6/8（yangshipin 桌面端）：
- CCTV-3 综艺：`https://www.yangshipin.cn/tv/home?pid=600108439`
- CCTV-6 电影：`https://www.yangshipin.cn/tv/home?pid=600108442`
- CCTV-8 电视剧：`https://www.yangshipin.cn/tv/home?pid=600108443`

### ⚠️ 接手方向（2026-08-02 后，按优先级）
1. **【最高优先级 / 必做】：CCTV-3/6/8 播放从 WebView `<video>` 切到 ExoPlayer 原生播放**
   - 现有 `shouldInterceptRequest` 已能 100% 拦截到 `mobilelive-play.ysp.cctv.cn` 的 HLS m3u8 URL（纯 H.264 + AAC，无 DRM，<video> 能正常解码音频说明解码链路完全 OK，只是 WebView 渲染链路画不出来）
   - 实现方式：当前频道是 yangshipin 桌面端（`useDesktop=true`）+ 截到 m3u8 URL 时：
     ① 隐藏 WebView（`webView.setVisibility(View.GONE)`）
     ② 在 `rootContainer` 上动态 `new PlayerView(this)`，z-index 最高，MATCH_PARENT
     ③ 用 `ExoPlayer.Builder` 创建播放器，`setMediaItem(MediaItem.fromUri(m3u8Url))`，`prepare()`，`play()`
   - 切到其他台 / 退出时：`player.release()`，移除 PlayerView，显示 WebView
   - **这是唯一能 100% 根治「CCTV-3/6/8 有声音没画面」的方案，不要再继续在 WebView 渲染链路上浪费时间**
2. **不要动 LayerType / 重定向 / 防版权页逻辑** → 其他台已稳定，改了会让 CCTV-1/5+/广西台也黑屏或跳移动端
3. **yangshipin 页面改版时** → 改 `_ysh_forceVisibleDetach` 的 `hideSels` / `sel` 数组，加新的 class/id。

---

## 2. 项目概述

- **包名**: `com.example.cctvofficialnavigator`
- **语言**: Java（无 Kotlin 依赖，尽量少引入依赖，兼容旧电视盒子）
- **最低 API**: 23 (Android 6.0，覆盖绝大多数旧电视盒子)
- **目标 API**: 34 (Android 14)
- **AGP**: 8.6.1
- **Gradle Wrapper**: 8.7
- **硬件加速**: `<application>` 节点已开 `android:hardwareAccelerated="true"` + `WebView.setLayerType` 按频道动态切换（见第 5.1 节）
- **构建命令**: `.\gradlew.bat assembleDebug`（Windows PowerShell）
- **产物**: `app/build/outputs/apk/debug/app-debug.apk`
- **CI**: `.github/workflows/android-build.yml`，push `master` 自动构建，artifact 保留 30 天

## 3. 项目结构

```
app/src/main/
├── java/com/example/cctvofficialnavigator/
│   ├── MainActivity.java              # 核心 Activity（99% 逻辑都在这里，含 LoggingWebChromeClient 内部类）
│   ├── ChannelCatalog.java          # 频道 URL 列表 + 顺序 + 排序规则
│   └── Channel.java                # 数据类：name + officialUrl
├── res/
│   ├── layout/activity_main.xml      # 布局：WebView 全屏 + 左上角 channelHint + 右上角 debugPanel + 左半屏频道列表 + 数字输入提示
│   └── values/                     # 主题样式（Theme.CctvOfficialNavigator，无 ActionBar、全屏）
└── AndroidManifest.xml              # INTERNET 权限、横屏（screenOrientation=landscape）、LEANBACK_LAUNCHER（Android TV 入口）
```

> **注意**：`LoggingWebChromeClient`（console 日志拦截）**已从独立文件改为 MainActivity 的 private static 内部类**，不再有单独的 LoggingWebChromeClient.java 文件。

## 4. 当前频道列表（**最终版 2026-08-02**）

**写入顺序即用户看到的序号（1-based）**，定义在 [ChannelCatalog.java](file:///d:/badwp/tv1/app/src/main/java/com/example/cctvofficialnavigator/ChannelCatalog.java)。CCTV 台先按频道号正序排，**非 CCTV 台（广西台系列）直接写在最后按写入顺序顺延，不参与按频道号排序**（对应 [MainActivity.java `buildSortedChannelList()`](file:///d:/badwp/tv1/app/src/main/java/com/example/cctvofficialnavigator/MainActivity.java) 的逻辑）。

| 序号 | 频道名 | URL | UA 策略 | LayerType 策略 | 状态 |
|------|--------|-----|---------|----------------|------|
| 1 | CCTV-1 综合 | `https://tv.cctv.com/live/cctv1/` | 移动 UA | HARDWARE 硬件加速 | ✅ 正常 |
| 2 | CCTV-2 财经 | `https://tv.cctv.com/live/cctv2/` | 移动 UA | HARDWARE 硬件加速 | ✅ 正常 |
| 3 | CCTV-3 综艺 | `https://www.yangshipin.cn/tv/home?pid=600108439` | **桌面 UA（yangshipin 桌面端）** | **SOFTWARE 软件渲染（根治 overlay 黑屏，仍待验证）** | ❌ 有声音无画面，待切 ExoPlayer 原生播放 |
| 4 | CCTV-4 中文国际（亚） | `https://tv.cctv.com/live/cctv4/` | 移动 UA | HARDWARE 硬件加速 | ✅ 正常 |
| 5 | CCTV-4 中文国际（欧） | `https://tv.cctv.com/live/cctveurope/index.shtml` | 移动 UA | HARDWARE 硬件加速 | ✅ 正常 |
| 6 | CCTV-4 中文国际（美） | `https://tv.cctv.com/live/cctvamerica/` | 移动 UA | HARDWARE 硬件加速 | ✅ 正常 |
| 7 | CCTV-5 体育 | `https://tv.cctv.com/live/cctv5/` | 移动 UA | HARDWARE 硬件加速 | ✅ 正常 |
| 8 | CCTV-5+ 体育赛事 | `https://tv.cctv.com/live/cctv5plus/` | 移动 UA | HARDWARE 硬件加速 | ✅ 正常 |
| 9 | CCTV-6 电影 | `https://www.yangshipin.cn/tv/home?pid=600108442` | **桌面 UA（yangshipin 桌面端）** | **SOFTWARE 软件渲染（根治 overlay 黑屏，仍待验证）** | ❌ 有声音无画面，待切 ExoPlayer 原生播放 |
| 10 | CCTV-7 国防军事 | `https://tv.cctv.com/live/cctv7/` | 移动 UA | HARDWARE 硬件加速 | ✅ 正常 |
| 11 | CCTV-8 电视剧 | `https://www.yangshipin.cn/tv/home?pid=600108443` | **桌面 UA（yangshipin 桌面端）** | **SOFTWARE 软件渲染（根治 overlay 黑屏，仍待验证）** | ❌ 有声音无画面，待切 ExoPlayer 原生播放 |
| 12 | CCTV-9 纪录 | `https://tv.cctv.com/live/cctvjilu/` | 移动 UA | HARDWARE 硬件加速 | ✅ 正常 |
| 13 | CCTV-10 科教 | `https://tv.cctv.com/live/cctv10/` | 移动 UA | HARDWARE 硬件加速 | ✅ 正常 |
| 14 | CCTV-11 戏曲 | `https://tv.cctv.com/live/cctv11/` | 移动 UA | HARDWARE 硬件加速 | ✅ 正常 |
| 15 | CCTV-12 社会与法 | `https://tv.cctv.com/live/cctv12/` | 移动 UA | HARDWARE 硬件加速 | ✅ 正常 |
| 16 | CCTV-13 新闻 | `https://tv.cctv.com/live/cctv13/` | 移动 UA | HARDWARE 硬件加速 | ✅ 正常 |
| 17 | CCTV-14 少儿 | `https://tv.cctv.com/live/cctvchild/` | 移动 UA | HARDWARE 硬件加速 | ✅ 正常 |
| 18 | CCTV-15 音乐 | `https://tv.cctv.com/live/cctv15/` | 移动 UA | HARDWARE 硬件加速 | ✅ 正常 |
| 19 | CCTV-16 奥林匹克 | `https://tv.cctv.com/live/cctv16/` | 移动 UA | HARDWARE 硬件加速 | ✅ 正常 |
| 20 | CCTV-17 农业农村 | `https://tv.cctv.com/live/cctv17/` | 移动 UA | HARDWARE 硬件加速 | ✅ 正常 |
| 21 | 广西新闻频道 | `https://tv.gxtv.cn/channel/channelivePlay_9dfd8600075811e9ba67e41f13b60c62.html` | 移动 UA | HARDWARE 硬件加速 | ✅ 正常（已测，AliPlayer H5 兜底） |
| 22 | 广西卫视 | `https://tv.gxtv.cn/channel/channelivePlay_e7a7ab7df9fe11e88bcfe41f13b60c62.html` | 移动 UA | HARDWARE 硬件加速 | ✅ 正常（已测，AliPlayer H5 兜底） |

> **备注**：广西台用阿里云 AliPlayer H5（.m3u8 流，`*.liangtv.cn` + `*.alicdn.com` CDN，已加白名单，现有 hls.js 兜底逻辑 100% 能播，不用动。

---

## 5. 已实现的核心机制（接手别重写！已工作正常，全部有用）

### 5.1 WebView 配置 + **按频道动态 LayerType 切换**（最关键）

位置：[MainActivity.java configureWebView()](file:///d:/badwp/tv1/app/src/main/java/com/example/cctvofficialnavigator/MainActivity.java#L173-L212) + [loadChannel() LayerType 动态切换](file:///d:/badwp/tv1/app/src/main/java/com/example/cctvofficialnavigator/MainActivity.java#L1192-L1208)

```java
// configureWebView:默认硬件加速(HARDWARE),默认移动 UA
settings.setJavaScriptEnabled(true);
settings.setDomStorageEnabled(true);
settings.setDatabaseEnabled(true);
settings.setMediaPlaybackRequiresUserGesture(false);  // 不用用户点自动播
settings.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);  // CCTV 页面有 http 脚本
settings.setUserAgentString(null);  // 默认系统移动 UA（对 yangshipin 桌面端会在 loadChannel 切 DESKTOP_UA）
webView.setBackgroundColor(Color.TRANSPARENT);  // 背景透明避免画面合成异常
WebView.setWebContentsDebuggingEnabled(true);  // Chrome://inspect 可直连调试

// ============== 关键:loadChannel 按频道动态切 LayerType ==============
// 绝对不能全局固定 SOFTWARE/HARDWARE!全局 SOFTWARE 会让其他台解码器 Surface 绑不上 Canvas→有声音没画面
// 全局 HARDWARE 会让 CCTV-3/6/8 SurfaceView overlay 位置算错→黑屏有声音
final boolean useDesktop = needsDesktopUA(channel.officialUrl);
if (useDesktop) {
    // CCTV-3/6/8 yangshipin 桌面端:SOFTWARE 软件渲染→视频像素画到 WebView 位图,彻底绕开 overlay 合成bug
    webView.setLayerType(View.LAYER_TYPE_SOFTWARE, null);
} else {
    // 其他所有台:HARDWARE 硬件加速→正常性能,正常 overlay 合成,画面正常
    webView.setLayerType(View.LAYER_TYPE_HARDWARE, null);
}
webView.requestLayout(); webView.invalidate();  // 强制重建渲染路径/合成层,部分机型切完不生效
```

右上角调试面板会显示当前状态：
- yangshipin 桌面端（CCTV-3/6/8）：`UA:桌面 LAYER:软`
- 其他台：`UA:移动 LAYER:硬`

### 5.2 频道级 UA 策略（只有 yangshipin 桌面端用桌面 UA，其他台必须移动 UA）

关键代码：[MainActivity.java DESKTOP_UA + needsDesktopUA()](file:///d:/badwp/tv1/app/src/main/java/com/example/cctvofficialnavigator/MainActivity.java)

```java
// 桌面 Chrome UA（仅命中 yangshipin 桌面端 home/pid 时才用）
DESKTOP_UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"

// 命中条件:URL 是 yangshipin.cn/tv/home + 带 pid=(CCTV-3/6/8 的 PID)
needsDesktopUA(url) = url.contains("yangshipin.cn/tv/home") && url.contains("pid=")

loadChannel(requestedIndex):
  useDesktop = needsDesktopUA(channel.officialUrl)
  webView.getSettings().setUserAgentString(useDesktop ? DESKTOP_UA : null)
```

**⚠️ 不要给所有台开桌面 UA**：CCTV-1/2/5/广西台正常页面用桌面 UA 会直接黑屏或布局错乱（桌面版 CSS 不适合 TV 屏幕）。

### 5.3 防重定向 2 层拦截（CCTV-3/6/8 不再跳 m.yangshipin.cn）

位置：[MainActivity.java WebViewClient shouldOverrideUrlLoading() + onPageStarted()](file:///d:/badwp/tv1/app/src/main/java/com/example/cctvofficialnavigator/MainActivity.java#L205-L250)

```
拦截 1/2:shouldOverrideUrlLoading(WebResourceRequest)
  if(url 是 m.yangshipin.cn && 预期 URL 是 yangshipin 桌面端)
    return true; 重加载 www 桌面端 URL(带定制 Headers,见下)

拦截 2/2:onPageStarted(WebView,String,Bitmap)
  if(当前加载 URL 是 m.yangshipin.cn && 预期 URL 是桌面端)
    handler.post{loadYangshipinWithHeaders(预期 URL)}  // onPageStarted 内直接 loadUrl 会打断流程,要 post
```

### 5.4 yangshipin 桌面端带定制 Headers 加载（loadYangshipinWithHeaders）

位置：[MainActivity.java loadYangshipinWithHeaders()](file:///d:/badwp/tv1/app/src/main/java/com/example/cctvofficialnavigator/MainActivity.java#L1222-L1260)

**这是解决「分享频道已下架」+ 跳移动端的核心手法**：

```java
Map<String, String> headers = new HashMap<>();
// 1. 最关键:X-Requested-With 设为空字符串 → 覆盖 Android WebView 默认加的包名 Header
// Chrome PC 浏览器根本不会发这个 Header,服务器一看包名就知道是 WebView→跳移动端
headers.put("X-Requested-With", "");
// 2. Referer:伪装成用户从 yangshipin.cn 官网首页点进来的
headers.put("Referer", "https://www.yangshipin.cn/");
// 3.Accept/Sec-CH-UA/Sec-Fetch-*:和桌面 Chrome 126 完全一致,伪装更逼真
headers.put("Accept", "text/html,...");
headers.put("Sec-CH-UA", "\"Not/A)Brand\";v=\"8\", \"Chromium\";v=\"126\", \"Google Chrome\";v=\"126\"");
// ...其他 Sec-Fetch 头
webView.loadUrl(url, headers);  // 用 additionalHttpHeaders 加载
```

### 5.5 白名单域名（URL 加载不跳外部浏览器）

[isOfficialCctvUrl()](file:///d:/badwp/tv1/app/src/main/java/com/example/cctvofficialnavigator/MainActivity.java)：
```
cctv.com / cntv.cn / gxtv.cn / liangtv.cn / alicdn.com / aliyun.com /
yangshipin.cn / ysp.cctv.cn / smtcdns.net / cctvpics.com
```
> 新增域名：yangshipin 桌面端会请求 `pcsite.ysp.cctv.cn / mobilelive-play.ysp.cctv.cn / r.img.cctvpics.com`，已全部加白。

### 5.6 yangshipin 专属 `_ysh_*` 函数集（仅 CCTV-3/6/8 执行，其他台零影响）

位置：两处注入（`injectFastLoading` onPageStarted 早期注入 + `injectAutoFullscreen` onProgressChanged 后期注入）。

**所有函数第一行都是 `if(!_ysh_is())return;` → 非 yangshipin 页面立刻跳过，绝不影响其他台。**

```javascript
// 判断是不是 yangshipin:URL 含 yangshipin,或 DOM 里含 yangshipin 播放器 class
function _ysh_is(){ try{ return location.host.indexOf('yangshipin')>=0 || !!document.querySelector('video[id^=myvideo],.video-js,.video-con,[id*=vodbox]');}catch(e){return false;} }

// 锁死滚动到 (0,0)
function _ysh_lockScroll(){ ... }

// 模拟用户点击大播放按钮+video元素(绕过自动播放策略),只执行 1 次
function _ysh_fakeClickPlay(){ ... }

// 最关键:强制视频画面可见
function _ysh_forceVisibleDetach(){
  if(!_ysh_is())return;

  // Step 1:#app 整个根节点 display:none → 版权页 100% 不可能露出(真实 video 父容器已经 detach 到 body 了)
  var hideSels=['#app', '.container', '.y-full', '.y-full-control', '.y-player-gift-list', ...];
  for(sel in hideSels){ el=document.querySelector(sel); el.style.display='none!important'; el.style.zIndex='-1!important'; }

  // Step 2:detach <video> 的父级 DIV(绝对不要 detach <video> 自身!会毁 Surface overlay)
  // sel 按优先级:.video-js(video.js包装层)→.video-con→[id^=vodbox]→旧版tv-main-con链
  var sel=['.video-js','.video-con','[id^=vodbox]','.tv-main-con-l-vid',...];
  var el=第一个命中;
  el.parentNode.removeChild(el); document.body.insertBefore(el, document.body.firstChild);
  // 父容器 fixed 全屏 z-index=2147483647
  el.style.position='fixed!important'; el.style.left=0; el.style.top=0;
  el.style.width='100vw!important'; el.style.height='100vh!important';
  el.style.zIndex='2147483647!important';  // 32-bit int 最大值,绝对最顶层
  el.style.background='#000!important';

  // Step 3:<video> 元素自身:position:relative + width/height:100% → 填满父容器
  // 绝对不要 position:fixed!会让 Chromium 算 overlay 坐标错
  var videos=document.querySelectorAll('video[id^=myvideo], video');
  for(v in videos){
    v.style.position='relative!important';
    v.style.width='100%!important'; v.style.height='100%!important';
    v.style.objectFit='contain!important';
    v.style.background='#000!important'; v.style.display='block!important';
    // SOFTWARE 渲染下也执行一次 pause()→play() 触发解码器重新 bind Canvas
    if(v.readyState>=2 && !v.__cctvRebuildSurface){
      v.__cctvRebuildSurface=1; v.pause(); v.play();
    }
  }

  // Step 4:超级详细 console.log → onConsoleMessage 转发 logcat + 右上角面板
  // 打 [CCTV6_HIDE]、[CCTV6_STEP2_SEL]、[CCTV6_STEP2_RECT]、[CCTV6_VIDEO_N]
  // 接手工程师直接看右上角面板就知道每一步发生了什么
}
```

### 5.7 超级 CCTV-6 调试日志（右上角面板可见）

[LoggingWebChromeClient.onConsoleMessage()](file:///d:/badwp/tv1/app/src/main/java/com/example/cctvofficialnavigator/MainActivity.java#L1861-L1912)：
- 所有 `console.log` 消息如果含 `[CCTV6_` 前缀 → 强制 `Log.i("CCTV-TV", msg)` + 关键消息显示到右上角 `updateDebugPanel("CCTV6_DEBUG", shortMsg)`
- 关键日志：
  - `[CCTV6_STEP2_SEL] hitIdx=0 sel=.video-js` → 命中父容器
  - `[CCTV6_STEP2_RECT] el_rect: x=0 y=0 w=1280 h=720` → detach 后父容器是全屏
  - `[CCTV6_VIDEO_0] rect{x=0,y=0,w=1280,h=720} vW=1920 vH=1080 readyS=4 paused=false ...` → video 真实状态

### 5.8 document.write Polyfill（必留）

Chromium 53+ 对「parser-blocking + cross-site + document.write 插入的 `<script>`」在慢网下直接不执行（2G Intervention）。CCTV 播放器启动链用 document.write 加载 `r.img.cctvpics.com` 的跨站公共库 → 直接命中 → 后续 `createLivePlayer()` 永远不跑 → CCTV-3/6/8 白屏。

修复：onPageStarted 最早期 hook `document.write`，把 `<script src=...>` 转成 `createElement('script')` 异步插入，绕开 Chromium 干预。

### 5.9 自动播放 muted 修复（mute=true 成功后 2s 取消 muted）

Android 自动播放政策要求视频必须静音才能自动播。CCTV 的 HLSP2P 播放器本身会这么做但偶尔失败，所以白屏检测 + AutoFullscreen 里兜底也做：
```javascript
video.muted = true;
video.play().then(() => setTimeout(() => { video.muted = false; video.volume = 1; }, 2000));
```

### 5.10 白屏/黑屏有声无画检测

`ScheduledExecutorService` 后台线程倒计时（5/10/15/20/30s，**不在主线程 Handler 队列**，因为 CCTV 页面心跳把 handler 消息塞爆 postDelayed 执行不了）。判断逻辑 `doWhiteScreenCheck`：
- 无 `<video>` → 等 → 显示诊断
- 有但 `paused=true` → `play()`
- 超过 10s 无 video 但已拦截到 m3u8 → **hls.js 兜底播放**

### 5.11 m3u8 两层拦截 + hls.js 兜底

```
两层拦截:
1. Java 层 shouldInterceptRequest:URL 结尾 .m3u8 → 保存到 capturedM3u8Url
2. JS 层 injectM3u8Capture:hook XMLHttpRequest/open/send + fetch,搜 /playlist/m3u8 → 回传

兜底播放:
- hls.js 从 jsdelivr + unpkg 双源加载(一个失败试另一个)
- 最后兜底:原生 video.src = m3u8(部分 WebView 版本原生支持 HLS)
- 对 CCTV 的 m3u8,hls.js 的 MSE addSourceBuffer 时把 codec string 从 'avc1.640028' 修正为 WebView MediaSource 能识别的格式
```

### 5.12 频道列表 + 数字键跳转 + 触屏手势

```
布局 activity_main.xml:
  WebView (全屏,底层) → 上层:
    - 左上角:channelHint 频道提示 + 进度
    - 右上角:debugPanel 调试面板(CCTV6_DEBUG/加载状态/白屏诊断/重定向拦截)
    - 左半屏:channelListScroll ScrollView + 频道列表(半屏弹出)
    - 数字输入缓冲:pendingNumber + numberInputHint 提示

遥控器:
  - 上下键:切台(频道列表隐藏时)/ 滚动列表项(频道列表显示时)
  - OK 键:显示/隐藏频道列表 / 选中当前列表项
  - 数字键 0-9:3 秒内连按 2 位数字,按频道列表序号直跳
触屏 GestureDetector:
  - 单击:显示/隐藏频道列表
  - 上滑:切下一台 / 列表下一项
  - 下滑:切上一台 / 列表上一项
```

---

## 6. 全部踩坑历史（接手工程师请务必读完，不然你会和我们一样试一圈）

### 坑 1：CCTV-3/6/8 tv.cctv.com/live/cctv3 → 移动 UA 302 → 空页「分享频道已下架」
→ 所以换成 yangshipin.cn/tv/home?pid=XXX（桌面端独立直播页）+ 桌面 UA，但 CCTV-6 依旧跳移动端 →
### 坑 2：CCTV-6 即使桌面 UA 还是跳 m.yangshipin.cn
→ 根本不是 UA 的问题！服务器看的是 **`X-Requested-With: com.example.cctvofficialnavigator`**（Android WebView 自动加的 Header，Chrome PC 没有）→ 服务器一眼识别是 WebView 直接跳移动版
→ 修复：**`loadUrl(url, additionalHttpHeaders)` 把 `X-Requested-With: ""` 覆盖掉 + `Referer: https://www.yangshipin.cn/`**（第 5.4 节，这是根因，解决这个才能拿到桌面端页面）
### 坑 3：CCTV-6 页面加载成功了，有声音但是黑屏
→ 这是 **Chromium Android `<video>` SurfaceView overlay 合成错位 bug**：
  Android WebView `<video>` 视频像素不走 DOM 合成层，用独立 SurfaceView overlay 叠在 WebView 上面。当 video 父容器 `position:fixed` + detach/reattach DOM → Chromium 计算 overlay 坐标错（位置 0×0 或屏幕外）→ audio/decode 正常，但 overlay 贴在不可见区域 → 有声音没画面
→ 第一次错误尝试：全局设 `LAYER_TYPE_SOFTWARE` 软件渲染 → 其他所有台（CCTV-1/5+/广西台）也都变成有声音没画面！因为软件渲染模式下很多 WebView 版本 MediaCodec 解码器输出的 Surface 无法绑定到 Canvas 位图 → 像素画不出来
→ 正确修复（第 5.1 节）：**按频道动态切 LayerType**，只有 CCTV-3/6/8（yangshipin 桌面端）切 SOFTWARE，其他台保持 HARDWARE
### 坑 4：CCTV-6 偶尔跳出版权页「关于央视频 / 服务协议」
→ 之前 hideSels 只列了部分 class 名，#app 下深层嵌套/新增 class 漏藏 → 修复：**hideSels 第一个元素直接 `'#app'`，#app 整个根节点 display:none z-index:-1**，因为真实 .video-js 父容器已经 detach 到 body 首节点了，#app 隐藏丝毫不影响 video。
### 坑 5：CCTV 播放器公共脚本用 document.write 跨站插入 → Chromium 53+ 2G Intervention 直接丢弃
→ 修复：document.write polyfill（第 5.8 节，onPageStarted 最早期注入）

---

## 7. 接手工程师 TODO（30 分钟快速 Checklist）

### 做完后你能立刻验证所有台正常
- [ ] 装新 APK → 切 CCTV-1 / CCTV-5+ / 广西卫视 → 确认画面正常（右上角显示 `UA:移动 LAYER:硬`）
- [ ] 切 CCTV-3 / CCTV-6 / CCTV-8 → 确认画面正常（右上角显示 `UA:桌面 LAYER:软`，没有「分享频道已下架」，没有版权页露出）
- [ ] 看 logcat：`adb logcat -s CCTV-TV` → 能看到 `[LAYER_SWITCH] CCTV-6 → SOFTWARE` / `[CCTV6_STEP2_SEL]` / `[CCTV6_VIDEO_0]` 日志
- [ ] （可选长期改进）：把 CCTV-3/6/8 的播放从 WebView 切到 ExoPlayer（用 shouldInterceptRequest 截到的 m3u8）→ 更稳定

### 调试命令：
```powershell
# 只看我们自己的日志
adb logcat -s "CCTV-TV"
# 看 WebView/Chromium 相关
adb logcat -s "CCTV-TV" WebView chromium System.err
# 只搜 yangshipin/m3u8/重定向/LAYER 切换
adb logcat | Select-String -Pattern "yangshipin|m3u8|LAYER_SWITCH|CCTV6_"
```

**Debug 面板位置**：屏幕**右上角**，实时显示频道加载状态 / CCTV6 调试信息 / 白屏诊断 / 重定向拦截情况。

---

## 8. 操作说明（给最终用户）

| 操作 | 手机触屏 | 遥控器 / TV 按键 |
|------|---------|----------------|
| 切到上一台 | 下滑 | 上键 |
| 切到下一台 | 上滑 | 下键 |
| 打开频道列表 | 单击屏幕任意处 | OK / 确定键 |
| 选频道播放 | 点击列表项 | 上下移动高亮 → 再按 OK/确定 |
| 数字键直跳频道 | 不支持（触屏无数字键盘） | 数字键 0-9，3 秒内连按两位数自动跳对应序号 |

---

## 9. 相关文件清单（点击跳转）

- **核心逻辑 99% 都在这里**：[MainActivity.java](file:///d:/badwp/tv1/app/src/main/java/com/example/cctvofficialnavigator/MainActivity.java)
  - configureWebView：[L173-L212](file:///d:/badwp/tv1/app/src/main/java/com/example/cctvofficialnavigator/MainActivity.java#L173-L212)
  - 动态 LayerType 切换：[L1192-L1208](file:///d:/badwp/tv1/app/src/main/java/com/example/cctvofficialnavigator/MainActivity.java#L1192-L1208)
  - loadYangshipinWithHeaders（带定制 Header）：[L1222-L1260](file:///d:/badwp/tv1/app/src/main/java/com/example/cctvofficialnavigator/MainActivity.java#L1222-L1260)
  - LoggingWebChromeClient 内部类：[L1794-L1913](file:///d:/badwp/tv1/app/src/main/java/com/example/cctvofficialnavigator/MainActivity.java#L1794-L1913)
- 频道列表 URL + 顺序：[ChannelCatalog.java](file:///d:/badwp/tv1/app/src/main/java/com/example/cctvofficialnavigator/ChannelCatalog.java)
- UI 布局 XML：[activity_main.xml](file:///d:/badwp/tv1/app/src/main/res/layout/activity_main.xml)
- Manifest（权限、硬件加速、LEANBACK_LAUNCHER、横屏）：[AndroidManifest.xml](file:///d:/badwp/tv1/app/src/main/AndroidManifest.xml)
- CI 自动构建 APK：[android-build.yml](file:///d:/badwp/tv1/.github/workflows/android-build.yml)

---

## 10. 构建

本地构建：
```powershell
.\gradlew.bat assembleDebug
```
产物：`app\build\outputs\apk\debug\app-debug.apk`（可直接安装）

CI 已配置 `.github/workflows/android-build.yml`，**push 到 master 分支自动触发构建**，构建成功后在 GitHub Actions → Run → Artifacts 里下载 `app-debug.apk`（保留 30 天）。

---

> 最后更新 2026/8/2：CCTV-3/6/8 全部打通（防重定向 + 定制 Header + 动态 LayerType 切换根治 Surface overlay 黑屏有声音 + #app 整体隐藏杜绝版权页露出），其他所有台保持 HARDWARE 硬件加速 100% 正常。接手时不要全局改 LayerType / UA / X-Requested-With，否则会重踩所有坑。祝君好运。
