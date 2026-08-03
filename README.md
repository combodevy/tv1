# 央视直播电视盒子 | CCTV Live TV Box

[中文](#中文) | [English](#english)

---

## 中文

Android TV 应用，通过 WebView 加载央视官方直播页面观看电视节目。CCTV-6 使用 ExoPlayer 原生播放器解决 WebView 视频渲染问题，其余频道使用 WebView 内置播放器。
核心原理很简单，用安卓的内嵌浏览器打开央视官网的直播页面，然后塞css自动播放和全屏的拉伸，实现全屏看直播的效果，合法合规，没有爬取任何直播流。（注意：有时候cctv1会播放不了，因为官方写明有的时候无版权不支持播放，但基本都是那种电视剧，也许无伤大雅）

你只需要把apk安装到你的电视盒子就可以了，ok键选台，上下键换台。不需要申请任何系统权限。

### 频道列表

共 20 个频道，定义在 [ChannelCatalog.java](app/src/main/java/com/example/cctvofficialnavigator/ChannelCatalog.java)，按频道号排序：

| 频道 | 来源 | 播放方式 | UA 策略 |
|------|------|----------|---------|
| CCTV-1 综合 | tv.cctv.com | WebView | 移动 UA |
| CCTV-2 财经 | tv.cctv.com | WebView | 移动 UA |
| CCTV-4 中文国际（亚/欧/美） | tv.cctv.com | WebView | 移动 UA |
| CCTV-5 体育 | tv.cctv.com | WebView | 移动 UA |
| CCTV-5+ 体育赛事 | tv.cctv.com | WebView | 移动 UA |
| CCTV-6 电影 | yangshipin.cn | ExoPlayer | 桌面 UA |
| CCTV-7 国防军事 | tv.cctv.com | WebView | 移动 UA |
| CCTV-9~17 | tv.cctv.com | WebView | 移动 UA |
| 广西新闻频道 | tv.gxtv.cn | WebView | 移动 UA |
| 广西卫视 | tv.gxtv.cn | WebView | 移动 UA |

> CCTV-3/8 因央视频页面使用 CMG WASM 加密流（`_web.m3u8`），ExoPlayer 无法解码，WebView 也无法正常渲染，暂未收录。

### 技术栈

- **语言**：Java（无 Kotlin 依赖，兼容旧设备）
- **最低 API**：23（Android 6.0）
- **目标 API**：34（Android 14）
- **播放器**：ExoPlayer 2.19.1（仅 CCTV-6）
- **构建**：Gradle 8.7 + AGP 8.6.1
- **依赖**：AndroidX AppCompat、Material Design、ExoPlayer（HLS + UI 模块）

### 项目结构

```
app/src/main/
├── java/com/example/cctvofficialnavigator/
│   ├── MainActivity.java          # 核心逻辑（~2200行）
│   ├── ChannelCatalog.java        # 频道列表（URL + 顺序）
│   └── Channel.java               # 频道数据类（name + officialUrl）
├── res/
│   ├── layout/activity_main.xml   # 布局：WebView 全屏 + 频道提示 + 频道列表 + 数字输入提示
│   └── values/                    # 主题样式（无 ActionBar、全屏）
└── AndroidManifest.xml            # 横屏、LEANBACK_LAUNCHER、INTERNET 权限、hardwareAccelerated
```

`MainActivity.java` 是所有逻辑的核心，包含一个 `LoggingWebChromeClient` 内部类。以下按功能模块说明。

---

### 核心技术实现

#### 1. 双播放器策略与 m3u8 拦截

大部分频道通过 WebView 加载 `tv.cctv.com` 官方直播页，由页面内置的 HLS.js / HLSP2P 播放器播放。

CCTV-6 使用央视频桌面端页面（`yangshipin.cn/tv/home?pid=600108442`），在 `shouldInterceptRequest` 中拦截 HLS m3u8 请求后切换到 ExoPlayer。原因是 WebView 的 `<video>` 元素在 Android TV 上存在 SurfaceView overlay 坐标计算 bug——音频正常但画面不可见。ExoPlayer 使用 `TextureView` 渲染，绕过了这个限制。

**m3u8 流类型判断**（关键逻辑）：

```
shouldInterceptRequest 拦截到 .m3u8 URL 时:
  if URL 包含 "_web.m3u8"  → CMG WASM 加密流,ExoPlayer 无法解码 → 不切 ExoPlayer,留在 WebView
  if URL 包含 "_fhd.m3u8"  → 标准 HLS 清流,ExoPlayer 可播 → 切 ExoPlayer
  其他                      → 视为清流,切 ExoPlayer
```

只有 `currentIsYangshipin == true && !exoPlayerActive && !isEncryptedWebM3u8` 三个条件同时满足时才切换 ExoPlayer。

**ExoPlayer HTTP 请求头**：必须携带与桌面 Chrome 一致的请求头，否则央视频服务器拒绝或返回异常流：

```java
DefaultHttpDataSource.Factory factory = new DefaultHttpDataSource.Factory()
    .setUserAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) ... Chrome/126.0.0.0 ...")
    .setDefaultRequestProperties(Map.of(
        "Referer",          "https://www.yangshipin.cn/",
        "Origin",           "https://www.yangshipin.cn",
        "Accept",           "*/*",
        "Accept-Language",  "zh-CN,zh;q=0.9,en;q=0.8"
    ));
HlsMediaSource.Factory hlsFactory = new HlsMediaSource.Factory(factory)
    .setAllowChunklessPreparation(true);
```

ExoPlayer 播放时会隐藏 WebView（`setVisibility(GONE)` + `onPause()`），切台时释放 ExoPlayer 并恢复 WebView 显示。

#### 2. 频道级 UA 策略

```java
// 桌面 Chrome UA（仅 yangshipin.cn/tv/home?pid=* 使用）
DESKTOP_UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36";

// 判断条件
needsDesktopUA(url) = url.contains("yangshipin.cn/tv/home") && url.contains("pid=");

// loadChannel 里切换
settings.setUserAgentString(useDesktop ? DESKTOP_UA : null);  // null = 系统默认移动 UA
```

不要给所有频道使用桌面 UA——`tv.cctv.com` 的移动端页面用桌面 UA 会布局错乱或黑屏。

#### 3. 防重定向机制（三层拦截）

Android WebView 自动添加 `X-Requested-With: <包名>` 请求头，央视频服务器据此识别 WebView 并 302 重定向到 `m.yangshipin.cn`，显示"分享频道已下架"。

**拦截层 1 — 加载时覆盖 Header**（`loadYangshipinWithHeaders`）：

```java
Map<String, String> headers = new HashMap<>();
headers.put("X-Requested-With", "");                    // 覆盖为空字符串
headers.put("Referer", "https://www.yangshipin.cn/");   // 伪装站内跳转
headers.put("Accept", "text/html,application/xhtml+xml,...");
headers.put("Sec-Fetch-Dest", "document");
headers.put("Sec-Fetch-Mode", "navigate");
headers.put("Sec-Fetch-Site", "same-origin");
// ... 其他 Sec-Fetch 头
webView.loadUrl(url, headers);
```

**拦截层 2 — `shouldOverrideUrlLoading`**：检测到跳转目标是 `m.yangshipin.cn` 时拦截，重新加载桌面端 URL。最多重试 3 次。

**拦截层 3 — `onPageStarted` / `onPageFinished`**：如果页面已经加载到移动域名，`handler.post()` 重新加载桌面端 URL（`onPageStarted` 内不能直接 `loadUrl`，会打断当前流程，必须 `post`）。

**反射清除 X-Requested-With**（`removeXRequestedWithHeader`）：部分 Chromium 版本中 `additionalHttpHeaders` 的空字符串会被底层覆盖为包名。通过反射清除 `WebSettings` 内部的 `mRequestedWithHeader` 字段。Best-effort，任何步骤失败都不影响播放。

#### 4. WebView 配置

```java
settings.setJavaScriptEnabled(true);
settings.setDomStorageEnabled(true);
settings.setDatabaseEnabled(true);
settings.setMediaPlaybackRequiresUserGesture(false);     // 允许自动播放
settings.setMixedContentMode(MIXED_CONTENT_ALWAYS_ALLOW); // CCTV 页面有 http 混合资源
settings.setLoadWithOverviewMode(true);
settings.setUseWideViewPort(true);
settings.setSupportZoom(false);
settings.setBuiltInZoomControls(false);
webView.setInitialScale(100);
webView.setBackgroundColor(Color.TRANSPARENT);
WebView.setWebContentsDebuggingEnabled(true);            // Chrome://inspect 可远程调试
```

**LayerType 策略**：所有频道统一使用 `LAYER_TYPE_HARDWARE`。原因是 CCTV-6 切 ExoPlayer 后 WebView 被隐藏（`GONE`），LayerType 不影响其画面；而其他频道如果用 `LAYER_TYPE_SOFTWARE` 会禁用 WebGL，导致部分播放器（如 CMG WASM）无法渲染。

#### 5. JavaScript 注入体系

`onPageStarted` 时按顺序注入以下 JS，不等 `onPageFinished`（CCTV 页面有持续心跳，`onPageFinished` 可能永远不触发）：

**5.1 document.write Polyfill**（`injectDocumentWrite`）

Chromium 53+ 在慢速网络下丢弃通过 `document.write` 插入的跨站 `<script>`（2G Intervention）。CCTV 播放器启动链依赖此方式加载 `r.img.cctvpics.com` 的公共库。Hook `document.write`，将 `<script src=...>` 转为 `createElement('script')` 异步插入。

**5.2 m3u8 捕获**（`injectM3u8Capture`）

Hook `XMLHttpRequest.open/send` 和 `fetch`，在主线程拦截 VDN API 响应中的 m3u8 URL（`streamUrl` 字段）。这是 `shouldInterceptRequest` 的补充——HLSP2P 在 Web Worker 里发 XHR，Java 层拦截不到 Worker 请求，但 VDN API 从主线程发出可以 hook。

**5.3 CSS 注入 + 全屏拉满**（`injectFastLoading`）

注入一大段 CSS：
- `<video>` 元素 `position:fixed; width:100vw; height:100vh; z-index:999999; object-fit:contain`
- 所有播放器容器（`.video-js`, `#player`, `[id^=vodbox]`, `.video-con`, AliPlayer `#J_prismPlayer` 等）拉满 100vw/100vh
- 隐藏所有装饰元素（header/footer/nav/控制栏/水印/大播放按钮/poster/iframe 广告等）
- 覆盖 CCTV 移动版、广西台 AliPlayer、央视频 video.js 三种页面结构

**5.4 yangshipin 专属函数**（`_ysh_*` 系列）

所有函数第一行 `if(!_ysh_is())return;`——非 yangshipin 页面立即跳过，零影响。

- `_ysh_is()`：判断是否在 yangshipin 页面（检查 host 或 DOM 中是否有 `.video-js` / `[id^=vodbox]` 等）
- `_ysh_forceVisibleDetach()`：核心函数，3 步操作：
  1. 隐藏 `#app` 根节点（版权页在 `#app` 内，真实 `.video-js` 已 detach 到 body）+ 隐藏所有 loading/spinner/mask/overlay 元素 + 遍历 DOM 隐藏包含"加载中/Loading"文字的节点
  2. Detach `<video>` 的父级容器（`.video-js` → `.video-con` → `[id^=vodbox]` 按优先级），`removeChild` + `insertBefore` 移到 `body` 首节点，`position:fixed; z-index:2147483647`
  3. `<video>` 元素自身 `position:relative; width:100%; height:100%` 填充父容器，`pause()` + `play()` 触发解码器重新绑定
- `_ysh_fakeClickPlay()`：模拟点击 `.vjs-big-play-button` 和 `video` 元素绕过自动播放策略（只执行 1 次）
- `_ysh_lockScroll()`：锁死滚动到 (0,0)

**5.5 自动全屏轮询**（`injectAutoFullscreen`）

`onPageStarted` 和 `onPageFinished` 各注入一次。每 300ms 执行一次 `ForceFullscreen()`，持续 30 秒（100 次）。即使 `<video>` 是 JS 动态插入的，一旦出现就立即拉满全屏。内容包括：
- 触发 `video.webkitRequestFullscreen()`（HTML5 原生全屏，WebView 用 `onShowCustomView` 处理）
- 内联 style 拉满 video + 所有父容器 `overflow:visible`
- 隐藏 video.js / AliPlayer / yangshipin 装饰元素
- 向上遍历 video 的所有父元素强制 `overflow:visible; width:100vw; height:100vh`
- 隐藏 z-index >= 999990 的兄弟遮罩层（广告弹窗等）

#### 6. 白屏/黑屏检测与 hls.js 兜底

**检测机制**：使用 `ScheduledExecutorService`（后台线程，不依赖主线程 Handler——CCTV 页面心跳会塞满 Handler 队列导致 `postDelayed` 不执行），在 5s/10s/15s/20s/30s 各触发一次检查。

**检测逻辑**（`doWhiteScreenCheck`）：
1. 通过 `evaluateJavascript` 注入诊断脚本，返回页面状态（video 数量、paused 状态、videoWidth/Height、readyState、currentSrc、HLSP2P/createLivePlayer 是否存在等）
2. 如果有 `<video>` 且 `paused=true` → 注入 `video.play()`
3. 如果超过 10s 仍无 `<video>` 且已拦截到 m3u8 → 注入 hls.js 兜底播放

**hls.js 兜底**（`injectHlsPlayer`）：
- 从 jsdelivr（`cdn.jsdelivr.net/npm/hls.js@1.5.15`）加载 hls.js，失败后从 unpkg 备用 CDN 加载，再失败直接 `video.src = m3u8Url`（部分 WebView 原生支持 HLS）
- **Codec 修复**：CCTV 的 `.ts` 流视频 codec 是 `avc1.64011f`，`MediaSource.addSourceBuffer` 拒绝该字符串。Hook `MediaSource.prototype.addSourceBuffer`，将 `avc1.64XXXX` 替换为 `avc1.640028`（广泛支持的 codec）。只影响类型声明，不影响实际解码

#### 7. 频道列表与排序

`buildSortedChannelList()` 构建 `sortedChannelIndices` 列表。CCTV 频道按频道号正序排列，非 CCTV 频道（广西台）追加在末尾。频道列表通过 OK 键弹出（左半屏 ScrollView），支持上下键导航和高亮选中。

**数字键直跳**：按数字键 0-9，3 秒内连按 2 位数字直接跳转对应序号。`pendingNumber` StringBuilder 缓存输入，超时自动清空。

#### 8. 遥控器与触屏交互

| 操作 | 触屏 | 遥控器 |
|------|------|--------|
| 上一台 | 下滑 | 上键 |
| 下一台 | 上滑 | 下键 |
| 频道列表 | 单击 | OK 键 |
| 选频道 | 点击列表项 | 上下移动 + OK |
| 数字直跳 | — | 数字键 0-9 |

触屏通过 `GestureDetector` 检测：单击（`onSingleTapConfirmed`）、上下滑动（`onFling`，阈值 100px + 100px/s）。频道列表可见时滑动导航列表项，否则切换频道。

#### 9. HTML5 全屏处理

`LoggingWebChromeClient` 继承 `WebChromeClient`，实现 `onShowCustomView` / `onHideCustomView` 处理 `video.webkitRequestFullscreen()` 触发的 HTML5 原生全屏。全屏时将 CustomView 添加到 `rootContainer`，隐藏 WebView；退出全屏时反向操作。

#### 10. Console 日志拦截

`LoggingWebChromeClient.onConsoleMessage` 拦截所有 `console.log/warn/error`。含 `[CCTV6_` 前缀的消息会 `Log.i("CCTV-TV", msg)` 写入 logcat。关键日志标签：
- `[CCTV6_STEP2_SEL]` — detach 命中的父容器选择器
- `[CCTV6_STEP2_RECT]` — detach 后容器实际尺寸
- `[CCTV6_VIDEO_0]` — video 元素状态（rect/videoWidth/paused/muted/src 等）
- `[CCTV-HLS]` — hls.js 兜底播放器日志

#### 11. 域名白名单

`isOfficialCctvUrl()` 判断 URL 是否属于官方域名，白名单内的跳转允许在 WebView 内加载，白名单外的跳转打开外部浏览器：

```
cctv.com / cntv.cn / gxtv.cn / liangtv.cn / alicdn.com / aliyun.com /
yangshipin.cn / ysp.cctv.cn / smtcdns.net / cctvpics.com
```

#### 12. 进度提示

`progressHint`（右上角 TextView）只在加载进度变化时显示（`onProgressChanged`），3 秒后自动隐藏。切台时 `channelHint`（左上角）显示频道名，1.8 秒后自动隐藏。

#### 13. 沉浸模式

`enterImmersiveMode()`：通过 `WindowInsetsController`（API 30+）或 `setSystemUiVisibility`（旧 API）隐藏状态栏和导航栏。`onWindowFocusChanged` 时重新进入沉浸模式。Android 9+ 设置 `LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES` 支持刘海屏。

---

### 构建

```powershell
.\gradlew.bat assembleDebug
```

产物：`app\build\outputs\apk\debug\app-debug.apk`

### CI 自动构建

GitHub Actions（`.github/workflows/android-build.yml`）：push 到 master 自动构建 APK。JDK 17 + Android SDK 34 + Gradle 8.7。构建产物在 Actions → Run → Artifacts 下载，保留 30 天。

### 调试

```powershell
# 查看应用日志
adb logcat -s "CCTV-TV"

# 查看 WebView/Chromium 日志
adb logcat -s "CCTV-TV" WebView chromium

# Chrome 远程调试
# Chrome 浏览器访问 chrome://inspect，可直接调试 WebView 的 DOM/console/network
```

---

## English

Android TV app for watching CCTV live channels via official web pages. CCTV-6 uses ExoPlayer for native playback to work around WebView video rendering issues; all other channels use WebView's built-in player.

### Channel List

20 channels defined in [ChannelCatalog.java](app/src/main/java/com/example/cctvofficialnavigator/ChannelCatalog.java), sorted by channel number:

| Channel | Source | Player | UA Strategy |
|---------|--------|--------|-------------|
| CCTV-1 News | tv.cctv.com | WebView | Mobile UA |
| CCTV-2 Finance | tv.cctv.com | WebView | Mobile UA |
| CCTV-4 International (Asia/Europe/America) | tv.cctv.com | WebView | Mobile UA |
| CCTV-5 Sports | tv.cctv.com | WebView | Mobile UA |
| CCTV-5+ Sports Events | tv.cctv.com | WebView | Mobile UA |
| CCTV-6 Movies | yangshipin.cn | ExoPlayer | Desktop UA |
| CCTV-7 Defense | tv.cctv.com | WebView | Mobile UA |
| CCTV-9~17 | tv.cctv.com | WebView | Mobile UA |
| Guangxi News | tv.gxtv.cn | WebView | Mobile UA |
| Guangxi Satellite | tv.gxtv.cn | WebView | Mobile UA |

> CCTV-3/8 are excluded because their yangshipin pages use CMG WASM encrypted streams (`_web.m3u8`) that neither ExoPlayer nor WebView can properly decode/render.

### Tech Stack

- **Language**: Java (no Kotlin, compatible with older devices)
- **minSdk**: 23 (Android 6.0)
- **targetSdk**: 34 (Android 14)
- **Player**: ExoPlayer 2.19.1 (CCTV-6 only)
- **Build**: Gradle 8.7 + AGP 8.6.1
- **Dependencies**: AndroidX AppCompat, Material Design, ExoPlayer (HLS + UI modules)

### Project Structure

```
app/src/main/
├── java/com/example/cctvofficialnavigator/
│   ├── MainActivity.java          # Core logic (~2200 lines)
│   ├── ChannelCatalog.java        # Channel list (URLs + order)
│   └── Channel.java               # Channel data class (name + officialUrl)
├── res/
│   ├── layout/activity_main.xml   # Layout: fullscreen WebView + channel hint + channel list + number input
│   └── values/                    # Theme (no ActionBar, fullscreen)
└── AndroidManifest.xml            # Landscape, LEANBACK_LAUNCHER, INTERNET permission, hardwareAccelerated
```

`MainActivity.java` contains all logic, including a `LoggingWebChromeClient` inner class. Below is a module-by-module breakdown.

---

### Key Technical Details

#### 1. Dual-Player Strategy & m3u8 Interception

Most channels load `tv.cctv.com` official live pages in WebView, using the page's built-in HLS.js / HLSP2P player.

CCTV-6 uses the yangshipin desktop page (`yangshipin.cn/tv/home?pid=600108442`). When `shouldInterceptRequest` detects an HLS m3u8 request, it switches to ExoPlayer. This bypasses a WebView `<video>` SurfaceView overlay coordinate bug on Android TV where audio works but video is invisible. ExoPlayer uses `TextureView` rendering, which avoids this issue.

**m3u8 stream type detection** (critical logic):

```
When shouldInterceptRequest intercepts a .m3u8 URL:
  if URL contains "_web.m3u8"  → CMG WASM encrypted stream, ExoPlayer can't decode → stay in WebView
  if URL contains "_fhd.m3u8"  → Standard HLS clean stream, ExoPlayer can play → switch to ExoPlayer
  otherwise                    → treat as clean stream → switch to ExoPlayer
```

ExoPlayer is only activated when `currentIsYangshipin == true && !exoPlayerActive && !isEncryptedWebM3u8`.

**ExoPlayer HTTP headers** must match desktop Chrome, otherwise the yangshipin server rejects or returns abnormal streams:

```java
DefaultHttpDataSource.Factory factory = new DefaultHttpDataSource.Factory()
    .setUserAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) ... Chrome/126.0.0.0 ...")
    .setDefaultRequestProperties(Map.of(
        "Referer",          "https://www.yangshipin.cn/",
        "Origin",           "https://www.yangshipin.cn",
        "Accept",           "*/*",
        "Accept-Language",  "zh-CN,zh;q=0.9,en;q=0.8"
    ));
HlsMediaSource.Factory hlsFactory = new HlsMediaSource.Factory(factory)
    .setAllowChunklessPreparation(true);
```

During ExoPlayer playback, WebView is hidden (`setVisibility(GONE)` + `onPause()`). On channel switch, ExoPlayer is released and WebView is restored.

#### 2. Channel-Specific UA Strategy

```java
// Desktop Chrome UA (only for yangshipin.cn/tv/home?pid=*)
DESKTOP_UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36";

// Detection
needsDesktopUA(url) = url.contains("yangshipin.cn/tv/home") && url.contains("pid=");

// Switch in loadChannel
settings.setUserAgentString(useDesktop ? DESKTOP_UA : null);  // null = system default mobile UA
```

Do not use desktop UA for all channels — `tv.cctv.com` mobile pages break with desktop UA (layout corruption or black screen).

#### 3. Anti-Redirect Mechanism (Three Layers)

Android WebView automatically adds `X-Requested-With: <package-name>`. The yangshipin server detects this and 302-redirects to `m.yangshipin.cn`, showing "channel removed".

**Layer 1 — Header Override at Load Time** (`loadYangshipinWithHeaders`):

```java
Map<String, String> headers = new HashMap<>();
headers.put("X-Requested-With", "");                    // Override with empty string
headers.put("Referer", "https://www.yangshipin.cn/");   // Simulate in-site navigation
headers.put("Accept", "text/html,application/xhtml+xml,...");
headers.put("Sec-Fetch-Dest", "document");
headers.put("Sec-Fetch-Mode", "navigate");
headers.put("Sec-Fetch-Site", "same-origin");
// ... other Sec-Fetch headers
webView.loadUrl(url, headers);
```

**Layer 2 — `shouldOverrideUrlLoading`**: Intercept redirect to `m.yangshipin.cn`, reload desktop URL. Max 3 retries.

**Layer 3 — `onPageStarted` / `onPageFinished`**: If the page loaded on the mobile domain, `handler.post()` a reload of the desktop URL (cannot call `loadUrl` directly inside `onPageStarted` — it disrupts the current load cycle; must `post`).

**Reflection-based X-Requested-With removal** (`removeXRequestedWithHeader`): Some Chromium versions override the empty string from `additionalHttpHeaders` with the package name at the native layer. This method uses reflection to clear the internal `mRequestedWithHeader` field in `WebSettings`. Best-effort — any failure is silently ignored.

#### 4. WebView Configuration

```java
settings.setJavaScriptEnabled(true);
settings.setDomStorageEnabled(true);
settings.setDatabaseEnabled(true);
settings.setMediaPlaybackRequiresUserGesture(false);     // Allow autoplay
settings.setMixedContentMode(MIXED_CONTENT_ALWAYS_ALLOW); // CCTV pages have mixed http resources
settings.setLoadWithOverviewMode(true);
settings.setUseWideViewPort(true);
settings.setSupportZoom(false);
settings.setBuiltInZoomControls(false);
webView.setInitialScale(100);
webView.setBackgroundColor(Color.TRANSPARENT);
WebView.setWebContentsDebuggingEnabled(true);            // Chrome://inspect remote debugging
```

**LayerType strategy**: All channels use `LAYER_TYPE_HARDWARE`. CCTV-6 is fine because WebView is hidden (`GONE`) during ExoPlayer playback. Other channels need hardware acceleration for WebGL (used by CMG WASM player). Using `LAYER_TYPE_SOFTWARE` would disable WebGL, causing black screens.

#### 5. JavaScript Injection System

The following JS is injected in `onPageStarted` (not waiting for `onPageFinished` — CCTV pages have continuous heartbeats that may prevent `onPageFinished` from firing):

**5.1 document.write Polyfill** (`injectDocumentWrite`)

Chromium 53+ discards cross-site `<script>` tags inserted via `document.write` on slow connections (2G Intervention). CCTV's player startup chain uses this pattern to load libraries from `r.img.cctvpics.com`. Hook `document.write` to convert `<script src=...>` to `createElement('script')` async insertion.

**5.2 m3u8 Capture** (`injectM3u8Capture`)

Hook `XMLHttpRequest.open/send` and `fetch` to intercept m3u8 URLs from VDN API responses (`streamUrl` field). This complements `shouldInterceptRequest` — HLSP2P sends XHR from a Web Worker (not interceptable by Java), but VDN API calls come from the main thread and can be hooked.

**5.3 CSS Injection + Fullscreen** (`injectFastLoading`)

Injects CSS that:
- Sets `<video>` to `position:fixed; width:100vw; height:100vh; z-index:999999; object-fit:contain`
- Expands all player containers (`.video-js`, `#player`, `[id^=vodbox]`, `.video-con`, AliPlayer `#J_prismPlayer`, etc.) to 100vw/100vh
- Hides all decorative elements (header/footer/nav/control bars/watermarks/big play buttons/posters/iframe ads)
- Covers three page structures: CCTV mobile, Guangxi AliPlayer, yangshipin video.js

**5.4 yangshipin-Specific Functions** (`_ysh_*` series)

All functions start with `if(!_ysh_is())return;` — no effect on non-yangshipin pages.

- `_ysh_is()`: Detects yangshipin page (checks host or DOM for `.video-js` / `[id^=vodbox]`)
- `_ysh_forceVisibleDetach()`: Core function, 3 steps:
  1. Hide `#app` root node (copyright page is inside `#app`; real `.video-js` is detached to body) + hide all loading/spinner/mask/overlay elements + traverse DOM to hide nodes containing "加载中/Loading" text
  2. Detach `<video>`'s parent container (`.video-js` → `.video-con` → `[id^=vodbox]` by priority), `removeChild` + `insertBefore` to body first child, `position:fixed; z-index:2147483647`
  3. `<video>` element itself: `position:relative; width:100%; height:100%` to fill parent, `pause()` + `play()` to trigger decoder rebind
- `_ysh_fakeClickPlay()`: Simulate click on `.vjs-big-play-button` and `video` to bypass autoplay policy (runs once)
- `_ysh_lockScroll()`: Lock scroll to (0,0)

**5.5 Auto-Fullscreen Polling** (`injectAutoFullscreen`)

Injected in both `onPageStarted` and `onPageFinished`. Runs `ForceFullscreen()` every 300ms for 30 seconds (100 iterations). Even if `<video>` is dynamically inserted by JS, it gets fullscreened immediately. Includes:
- Trigger `video.webkitRequestFullscreen()` (HTML5 native fullscreen, handled by `onShowCustomView`)
- Inline style to expand video + all parent containers `overflow:visible`
- Hide video.js / AliPlayer / yangshipin decorations
- Walk up video's parent chain forcing `overflow:visible; width:100vw; height:100vh`
- Hide sibling elements with z-index >= 999990 (ad overlays)

#### 6. White/Black Screen Detection & hls.js Fallback

**Detection**: Uses `ScheduledExecutorService` (background thread — CCTV page heartbeats flood the main thread Handler, making `postDelayed` unreliable). Checks at 5s/10s/15s/20s/30s intervals.

**Detection logic** (`doWhiteScreenCheck`):
1. Inject diagnostic JS via `evaluateJavascript` — returns page state (video count, paused state, videoWidth/Height, readyState, currentSrc, HLSP2P/createLivePlayer existence, etc.)
2. If `<video>` exists but `paused=true` → inject `video.play()`
3. If 10s passed with no `<video>` and m3u8 was captured → inject hls.js fallback player

**hls.js fallback** (`injectHlsPlayer`):
- Load hls.js from jsdelivr (`cdn.jsdelivr.net/npm/hls.js@1.5.15`), fallback to unpkg CDN, then fallback to native `video.src = m3u8Url`
- **Codec fix**: CCTV `.ts` streams report codec `avc1.64011f`, which `MediaSource.addSourceBuffer` rejects. Hook `MediaSource.prototype.addSourceBuffer` to replace `avc1.64XXXX` with `avc1.640028` (widely supported). Only affects type declaration, not actual decoding.

#### 7. Channel List & Sorting

`buildSortedChannelList()` creates `sortedChannelIndices`. CCTV channels sorted by channel number; non-CCTV channels (Guangxi) appended at the end. Channel list opens via OK key (left-side ScrollView), supports D-pad navigation and highlight selection.

**Number key direct jump**: Press 0-9 keys, enter a 2-digit number within 3 seconds to jump to the corresponding channel index. `pendingNumber` StringBuilder buffers input with auto-timeout.

#### 8. Remote Control & Touch Input

| Action | Touch | Remote |
|--------|-------|--------|
| Previous channel | Swipe down | Up key |
| Next channel | Swipe up | Down key |
| Channel list | Tap | OK key |
| Select channel | Tap item | D-pad + OK |
| Number jump | — | Number keys 0-9 |

Touch input via `GestureDetector`: single tap (`onSingleTapConfirmed`), swipe (`onFling`, threshold 100px + 100px/s). When channel list is visible, swipe navigates list items; otherwise switches channels.

#### 9. HTML5 Fullscreen Handling

`LoggingWebChromeClient` extends `WebChromeClient`, implementing `onShowCustomView` / `onHideCustomView` to handle `video.webkitRequestFullscreen()`. On fullscreen, CustomView is added to `rootContainer` and WebView is hidden; reversed on exit.

#### 10. Console Log Interception

`LoggingWebChromeClient.onConsoleMessage` intercepts all `console.log/warn/error`. Messages with `[CCTV6_` prefix are logged to logcat via `Log.i("CCTV-TV", msg)`. Key log tags:
- `[CCTV6_STEP2_SEL]` — which parent container selector was hit during detach
- `[CCTV6_STEP2_RECT]` — container dimensions after detach
- `[CCTV6_VIDEO_0]` — video element state (rect/videoWidth/paused/muted/src etc.)
- `[CCTV-HLS]` — hls.js fallback player logs

#### 11. Domain Whitelist

`isOfficialCctvUrl()` checks if a URL belongs to official domains. Whitelisted domains load inside WebView; others open in external browser:

```
cctv.com / cntv.cn / gxtv.cn / liangtv.cn / alicdn.com / aliyun.com /
yangshipin.cn / ysp.cctv.cn / smtcdns.net / cctvpics.com
```

#### 12. Progress & Channel Hints

`progressHint` (top-right TextView) only shows during loading progress changes (`onProgressChanged`), auto-hides after 3 seconds. `channelHint` (top-left) shows channel name on switch, auto-hides after 1.8 seconds.

#### 13. Immersive Mode

`enterImmersiveMode()`: Hides status bar and navigation bar via `WindowInsetsController` (API 30+) or `setSystemUiVisibility` (legacy). Re-enters immersive mode on `onWindowFocusChanged`. Android 9+ sets `LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES` for notch support.

---

### Build

```powershell
.\gradlew.bat assembleDebug
```

Output: `app\build\outputs\apk\debug\app-debug.apk`

### CI

GitHub Actions (`.github/workflows/android-build.yml`): Auto-builds APK on push to master. JDK 17 + Android SDK 34 + Gradle 8.7. Download from Actions → Run → Artifacts (retained 30 days).

### Debugging

```powershell
# App logs
adb logcat -s "CCTV-TV"

# WebView/Chromium logs
adb logcat -s "CCTV-TV" WebView chromium

# Chrome remote debugging
# Open chrome://inspect in Chrome browser to debug WebView DOM/console/network
```
