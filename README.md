# 央视直播电视盒子 | CCTV Live TV Box

[中文](#中文) | [English](#english)

---

## 中文

一个 Android TV 应用，通过 WebView 加载央视官方直播页面观看电视节目。部分频道使用 ExoPlayer 原生播放器解决 WebView 视频渲染问题。

### 频道列表

共 20 个频道，按频道号排序：

| 频道 | 来源 | 播放方式 |
|------|------|----------|
| CCTV-1 综合 | tv.cctv.com | WebView |
| CCTV-2 财经 | tv.cctv.com | WebView |
| CCTV-4 中文国际（亚/欧/美） | tv.cctv.com | WebView |
| CCTV-5 体育 | tv.cctv.com | WebView |
| CCTV-5+ 体育赛事 | tv.cctv.com | WebView |
| CCTV-6 电影 | yangshipin.cn | ExoPlayer |
| CCTV-7 国防军事 | tv.cctv.com | WebView |
| CCTV-9 纪录 | tv.cctv.com | WebView |
| CCTV-10 科教 | tv.cctv.com | WebView |
| CCTV-11 戏曲 | tv.cctv.com | WebView |
| CCTV-12 社会与法 | tv.cctv.com | WebView |
| CCTV-13 新闻 | tv.cctv.com | WebView |
| CCTV-14 少儿 | tv.cctv.com | WebView |
| CCTV-15 音乐 | tv.cctv.com | WebView |
| CCTV-16 奥林匹克 | tv.cctv.com | WebView |
| CCTV-17 农业农村 | tv.cctv.com | WebView |
| 广西新闻频道 | tv.gxtv.cn | WebView |
| 广西卫视 | tv.gxtv.cn | WebView |

> CCTV-3/8 因央视频页面使用 CMG WASM 加密流（`_web.m3u8`），ExoPlayer 无法解码，WebView 也无法正常渲染，暂未收录。

### 技术架构

**技术栈**：Java（无 Kotlin）、Android SDK 34、minSdk 23（Android 6.0）、ExoPlayer 2.19.1、Gradle 8.7

**项目结构**：

```
app/src/main/
├── java/com/example/cctvofficialnavigator/
│   ├── MainActivity.java          # 核心逻辑（WebView 配置、频道切换、ExoPlayer、遥控器/触屏交互）
│   ├── ChannelCatalog.java        # 频道列表
│   └── Channel.java               # 频道数据类
├── res/
│   ├── layout/activity_main.xml   # 布局（WebView 全屏 + 频道提示 + 频道列表）
│   └── values/                    # 主题样式
└── AndroidManifest.xml            # 横屏、LEANBACK_LAUNCHER、INTERNET 权限
```

### 核心技术实现

#### 1. 双播放器策略

大部分频道通过 WebView 直接加载 `tv.cctv.com` 的官方直播页，由页面内置的 HLS.js 播放器播放。

CCTV-6 使用央视频桌面端页面（`yangshipin.cn/tv/home?pid=600108442`），在 `shouldInterceptRequest` 中拦截 HLS m3u8 请求后，切换到 ExoPlayer 原生播放。原因是 WebView 的 `<video>` 元素在 Android TV 上存在 SurfaceView overlay 坐标计算问题，导致有声音无画面。ExoPlayer 使用 `TextureView` 渲染，绕过了这个限制。

ExoPlayer 请求 m3u8 时需要携带与桌面 Chrome 一致的 HTTP 头，否则央视频服务器会拒绝或返回异常流：

```java
DefaultHttpDataSource.Factory factory = new DefaultHttpDataSource.Factory()
    .setUserAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) ... Chrome/126.0.0.0 ...")
    .setDefaultRequestProperties(Map.of(
        "Referer", "https://www.yangshipin.cn/",
        "Origin",  "https://www.yangshipin.cn"
    ));
```

#### 2. 防重定向拦截

Android WebView 会自动添加 `X-Requested-With: <包名>` 请求头，央视频服务器据此识别出 WebView 并 302 重定向到移动端旧域名（`m.yangshipin.cn`），显示"分享频道已下架"。

解决方法：加载时通过 `loadUrl(url, additionalHttpHeaders)` 用空字符串覆盖该头，并添加 `Referer`。同时在 `shouldOverrideUrlLoading` 和 `onPageStarted` 两层拦截重定向到移动域名的请求，强制重新加载桌面端 URL。

#### 3. document.write Polyfill

Chromium 53+ 在慢速网络下会丢弃通过 `document.write` 插入的跨站 `<script>` 标签（2G Intervention）。CCTV 播放器的启动链依赖这种方式加载公共库，命中该策略后播放器无法初始化。在 `onPageStarted` 最早阶段 hook `document.write`，将 `<script src=...>` 转为 `createElement('script')` 异步插入。

#### 4. 遥控器与触屏交互

- 上下键：切换频道
- OK 键：打开/关闭频道列表
- 数字键 0-9：3 秒内输入两位数字直接跳转对应频道
- 触屏：单击打开频道列表，上下滑动切换频道

#### 5. 全屏沉浸模式

应用以横屏全屏启动，通过 `WindowInsetsController` 隐藏系统栏，保持沉浸式观看体验。

### 构建

```powershell
.\gradlew.bat assembleDebug
```

产物路径：`app\build\outputs\apk\debug\app-debug.apk`

### CI 自动构建

配置了 GitHub Actions（`.github/workflows/android-build.yml`），push 到 master 分支自动构建 APK，可在 Actions → Run → Artifacts 下载，保留 30 天。

### 操作说明

| 操作 | 触屏 | 遥控器 |
|------|------|--------|
| 上一台 | 下滑 | 上键 |
| 下一台 | 上滑 | 下键 |
| 频道列表 | 单击 | OK 键 |
| 选频道 | 点击列表项 | 上下移动 + OK |

---

## English

An Android TV app for watching CCTV live channels via official web pages. Uses a dual-player strategy: WebView for most channels and ExoPlayer for channels where WebView rendering fails.

### Channel List

20 channels total, sorted by channel number:

| Channel | Source | Player |
|---------|--------|--------|
| CCTV-1 News | tv.cctv.com | WebView |
| CCTV-2 Finance | tv.cctv.com | WebView |
| CCTV-4 International (Asia/Europe/America) | tv.cctv.com | WebView |
| CCTV-5 Sports | tv.cctv.com | WebView |
| CCTV-5+ Sports Events | tv.cctv.com | WebView |
| CCTV-6 Movies | yangshipin.cn | ExoPlayer |
| CCTV-7 Defense | tv.cctv.com | WebView |
| CCTV-9 Documentary | tv.cctv.com | WebView |
| CCTV-10 Science | tv.cctv.com | WebView |
| CCTV-11 Opera | tv.cctv.com | WebView |
| CCTV-12 Law | tv.cctv.com | WebView |
| CCTV-13 News | tv.cctv.com | WebView |
| CCTV-14 Children | tv.cctv.com | WebView |
| CCTV-15 Music | tv.cctv.com | WebView |
| CCTV-16 Olympic | tv.cctv.com | WebView |
| CCTV-17 Agriculture | tv.cctv.com | WebView |
| Guangxi News | tv.gxtv.cn | WebView |
| Guangxi Satellite | tv.gxtv.cn | WebView |

> CCTV-3/8 are not included because their yangshipin pages use CMG WASM encrypted streams (`_web.m3u8`) that neither ExoPlayer nor WebView can properly decode/render.

### Technical Architecture

**Stack**: Java (no Kotlin), Android SDK 34, minSdk 23 (Android 6.0), ExoPlayer 2.19.1, Gradle 8.7

**Project structure**:

```
app/src/main/
├── java/com/example/cctvofficialnavigator/
│   ├── MainActivity.java          # Core logic (WebView config, channel switching, ExoPlayer, input handling)
│   ├── ChannelCatalog.java        # Channel list
│   └── Channel.java               # Channel data class
├── res/
│   ├── layout/activity_main.xml   # Layout (fullscreen WebView + channel hint + channel list)
│   └── values/                    # Theme styles
└── AndroidManifest.xml            # Landscape, LEANBACK_LAUNCHER, INTERNET permission
```

### Key Technical Details

#### 1. Dual-Player Strategy

Most channels load `tv.cctv.com` official live pages directly in WebView, using the page's built-in HLS.js player.

CCTV-6 uses the yangshipin desktop page (`yangshipin.cn/tv/home?pid=600108442`). When `shouldInterceptRequest` detects an HLS m3u8 request, it switches to ExoPlayer for native playback. This is because WebView's `<video>` element has a SurfaceView overlay coordinate bug on Android TV — audio works but video is invisible. ExoPlayer with `TextureView` bypasses this limitation.

ExoPlayer must send HTTP headers matching desktop Chrome, otherwise the yangshipin server rejects the request or returns an abnormal stream:

```java
DefaultHttpDataSource.Factory factory = new DefaultHttpDataSource.Factory()
    .setUserAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) ... Chrome/126.0.0.0 ...")
    .setDefaultRequestProperties(Map.of(
        "Referer", "https://www.yangshipin.cn/",
        "Origin",  "https://www.yangshipin.cn"
    ));
```

#### 2. Anti-Redirect Interception

Android WebView automatically adds an `X-Requested-With: <package-name>` header. The yangshipin server detects this and 302-redirects to the mobile domain (`m.yangshipin.cn`), showing "channel removed".

Fix: Override this header with an empty string via `loadUrl(url, additionalHttpHeaders)` and add a `Referer`. Additionally, intercept redirect requests to the mobile domain in both `shouldOverrideUrlLoading` and `onPageStarted`, forcing a reload of the desktop URL.

#### 3. document.write Polyfill

Chromium 53+ discards cross-site `<script>` tags inserted via `document.write` on slow connections (2G Intervention). CCTV's player startup chain relies on this pattern to load shared libraries, so it fails silently. Fix: Hook `document.write` at the earliest point in `onPageStarted`, converting `<script src=...>` to `createElement('script')` async insertion.

#### 4. Remote Control & Touch Input

- Up/Down keys: Switch channels
- OK key: Toggle channel list
- Number keys 0-9: Enter a two-digit number within 3 seconds to jump to a channel
- Touch: Tap to open channel list, swipe up/down to switch channels

#### 5. Immersive Fullscreen

The app launches in landscape fullscreen, hiding system bars via `WindowInsetsController` for an immersive viewing experience.

### Build

```powershell
.\gradlew.bat assembleDebug
```

Output: `app\build\outputs\apk\debug\app-debug.apk`

### CI

GitHub Actions (`.github/workflows/android-build.yml`) automatically builds the APK on push to master. Download from Actions → Run → Artifacts (retained for 30 days).

### Controls

| Action | Touch | Remote |
|--------|-------|--------|
| Previous channel | Swipe down | Up key |
| Next channel | Swipe up | Down key |
| Channel list | Tap | OK key |
| Select channel | Tap item | Navigate + OK |
