# 央视官方直播导航 (CCTV Official Live Navigator)

Android 应用，通过 WebView 加载央视官方直播页面，实现全屏沉浸式观看体验。支持手机触屏滑屏和遥控器上下键切换频道。

## 项目概述

- **包名**: `com.example.cctvofficialnavigator`
- **语言**: Java（无 Kotlin 依赖）
- **最低 API**: 23 (Android 6.0)
- **目标 API**: 34 (Android 14)
- **AGP 版本**: 8.6.1
- **Gradle Wrapper**: 8.7

## 核心功能

1. **WebView 加载央视官方直播页面** — 不提取/存储任何流 URL，完全依赖官方页面
2. **全屏沉浸式播放** — 通过 CSS 强制全屏 + WebView HTML5 全屏机制
3. **频道切换** — 遥控器上下键 / 手机触屏上滑下滑
4. **白屏检测与自动跳转** — 5/10/15/20/30 秒检查 video 元素状态
5. **Debug 诊断面板** — 屏幕中央黄色字体显示加载状态和诊断信息
6. **hls.js 兜底播放** — 当官方播放器失败时，用 hls.js 直连 m3u8 流

## 项目结构

```
app/src/main/
├── java/com/example/cctvofficialnavigator/
│   ├── MainActivity.java          # 核心 Activity，WebView 配置、全屏逻辑、频道切换
│   ├── ChannelCatalog.java        # 20 个央视频道的 URL 列表
│   ├── Channel.java               # 频道数据类 (name, officialUrl)
│   └── LoggingWebChromeClient.java # WebChromeClient 子类，拦截 console 日志
├── res/
│   ├── layout/activity_main.xml   # 主布局: WebView + 频道提示 + Debug 面板
│   └── values/                    # 主题、样式
└── AndroidManifest.xml            # INTERNET 权限、横屏、LEANBACK_LAUNCHER
```

## 频道列表

共 20 个频道，定义在 [ChannelCatalog.java](file:///d:/badwp/tv1/app/src/main/java/com/example/cctvofficialnavigator/ChannelCatalog.java)：

| 序号 | 频道名 | URL | UA 策略 |
|------|--------|-----|---------|
| 0 | CCTV-9 纪录 | tv.cctv.com/live/cctvjilu/ | 移动 UA |
| 1 | CCTV-1 综合 | tv.cctv.com/live/cctv1/ | 移动 UA |
| 2 | CCTV-2 财经 | tv.cctv.com/live/cctv2/ | 移动 UA |
| 3 | CCTV-3 综艺 | tv.cctv.com/live/cctv3/m/index.shtml | 桌面 UA |
| 4 | CCTV-4 中文国际（亚） | tv.cctv.com/live/cctv4/ | 移动 UA |
| 5 | CCTV-4 中文国际（欧） | tv.cctv.com/live/cctveurope/index.shtml | 移动 UA |
| 6 | CCTV-4 中文国际（美） | tv.cctv.com/live/cctvamerica/ | 移动 UA |
| 7 | CCTV-5 体育 | tv.cctv.com/live/cctv5/ | 移动 UA |
| 8 | CCTV-5+ 体育赛事 | tv.cctv.com/live/cctv5plus/ | 移动 UA |
| 9 | CCTV-6 电影 | tv.cctv.com/live/cctv6/m/index.shtml | 桌面 UA |
| 10 | CCTV-7 国防军事 | tv.cctv.com/live/cctv7/ | 移动 UA |
| 11 | CCTV-8 电视剧 | tv.cctv.com/live/cctv8/m/index.shtml | 桌面 UA |
| 12 | CCTV-10 科教 | tv.cctv.com/live/cctv10/ | 移动 UA |
| 13 | CCTV-11 戏曲 | tv.cctv.com/live/cctv11/ | 移动 UA |
| 14 | CCTV-12 社会与法 | tv.cctv.com/live/cctv12/ | 移动 UA |
| 15 | CCTV-13 新闻 | tv.cctv.com/live/cctv13/ | 移动 UA |
| 16 | CCTV-14 少儿 | tv.cctv.com/live/cctvchild/ | 移动 UA |
| 17 | CCTV-15 音乐 | tv.cctv.com/live/cctv15/ | 移动 UA |
| 18 | CCTV-16 奥林匹克 | tv.cctv.com/live/cctv16/ | 移动 UA |
| 19 | CCTV-17 农业农村 | tv.cctv.com/live/cctv17/ | 移动 UA |

## 关键技术决策与踩坑记录

### 1. UA 切换策略

**问题**: CCTV 服务器根据 User-Agent 返回不同的页面和播放器：
- **移动 UA** → 返回移动端页面，使用简单播放器（普通 `<video src="...">`）
- **桌面 UA** → 返回桌面端页面，使用 HLSP2P 播放器（MSE blob URL + WebRTC/P2P）

**特殊频道**: CCTV-3/6/8 是版权敏感频道，使用移动 UA 会被 302 重定向到 `m.yangshipin.cn/static/empty.html`（刻意空白页，引导用户安装央视频 APP）。

**当前策略**:
- CCTV-3/6/8 使用桌面 UA（Chrome 126），URL 改为移动端路径 `/m/index.shtml`
- 其他频道使用系统默认移动 UA
- 桌面 UA 会让 CCTV-9 等频道黑屏，不能全频道统一

**已知问题**: CCTV-3/6/8 在部分设备上仍出现"有声音没画面"的问题。桌面 UA 加载的 HLSP2P 播放器使用 MSE blob URL，在某些 WebView 实现中视频帧无法正常渲染。移动端 URL（`/m/index.shtml`）理论上应使用简单播放器，但实际效果因设备而异。

### 2. 全屏策略

**为什么不用 click() "网页全屏"按钮**:
- CCTV 的"网页全屏"按钮 click() 在系统 WebView + Android TV 上不稳定
- 有些频道 click 一次就生效（CCTV-1, CCTV-9）
- 有些频道 click 一次不生效（CCTV-5, CCTV-5+），页面不切布局
- 有些频道根本没有 video 元素（频道下线了）

**解决方案**: 不依赖 click()，用 CSS 强制全屏：
- `video` 元素设为 `position:fixed; width:100vw; height:100vh; z-index:999999`
- 隐藏所有装饰元素（`.video_right` 频道列表、`.bg_top_h_tile` 顶部条等）
- 隐藏所有 `iframe`（广告，非播放器）
- `FastLoading` 每 200ms 执行一次，持续 30 秒
- `AutoFullscreen` 每 300ms 执行一次，持续 30 秒

### 3. document.write Polyfill

**问题**: Chromium 53+ 的"2G Intervention"会在慢网/2G 时直接丢弃"parser-blocking + cross-site + document.write 插入"的脚本。CCTV 页面通过 `document.write` 从 `r.img.cctvpics.com`（不同 eTLD+1）加载 `gray*.js` / DEPA 公共脚本，被丢弃后 `createLivePlayer()` 不执行。

**修复**: 在 `onPageStarted` 最早期注入 document.write polyfill，把 `<script src="跨站URL">` 转成 `createElement('script')` 异步插入，绕开 Chromium 干预。

**注意**: 不再调用原始 `document.write`，因为异步脚本调用原始 write 会抛出 "It isn't possible to write into a document from an asynchronously-loaded external script"，导致后续播放器初始化 JS 中断。

### 4. 自动播放策略修复

**问题**: CCTV 的 HLSP2P 播放器创建了 video 元素并加载了流，但因 `muted=false` + 浏览器自动播放策略，`video.play()` 被 reject，导致 `paused=true` → 黑屏。

**修复**: 先 `muted=true` 触发 `play()`，播放成功后延迟 2 秒取消 muted 恢复声音。用 `__cctvAutoplayStarted` 防止重复触发。

### 5. 白屏检测

**实现**: 5/10/15/20/30 秒各检查一次页面里是否有 video 元素：
- 没有 → 显示诊断面板
- 有但暂停 → 调 `play()` 强制播放
- 10 秒后仍无 video 且已拦截到 m3u8 → 用 hls.js 兜底播放

**为什么用 ScheduledExecutorService 而非 handler.postDelayed**: CCTV 页面有持续心跳，WebView 在 main thread 疯狂 load + parse + 跑 JS，`handler.postDelayed` 任务被压在队列里没机会跑。background thread 的倒计时不被 main thread 阻塞。

### 6. hls.js 兜底播放

**触发条件**: 白屏检测 10 秒后仍无 video 元素或视频暂停，且已拦截到 m3u8 URL。

**实现**:
- 通过 `shouldInterceptRequest` 和 `injectM3u8Capture`（hook XMLHttpRequest/fetch）拦截 m3u8 URL
- 注入 hls.js 库（jsdelivr CDN，失败则尝试 unpkg）
- hook `MediaSource.prototype.addSourceBuffer`，把 `avc1.64XXXX` 替换成 `avc1.640028`（CCTV .ts 流的 codec 被 MediaSource 拒绝）

### 7. 重定向防御

**问题**: CCTV-3/6/8 在某些情况下仍会被重定向到 `yangshipin.cn/static/empty.html`。

**修复**: 在 `onPageStarted` 中检测空页重定向，立刻用桌面 UA + Referer 头重新加载官方桌面 URL。

### 8. 跨域 Frame 访问

**问题**: CCTV 页面使用 iframe 嵌套播放器，跨域访问会抛出 "Blocked a frame with origin..." 错误。

**处理**: 在 `LoggingWebChromeClient` 中将此类错误降级为 INFO，不阻塞面板更新。

## 已知问题

### CCTV-3/6/8 "有声音没画面"

**现象**: 在部分设备（尤其是 MuMu x86 模拟器）上，CCTV-3/6/8 能听到声音但无视频画面。

**可能原因**:
1. **硬件解码器不支持**: MuMu 是 x86 模拟器，没有硬件 H.264 解码器。HLSP2P 播放器使用 MSE blob URL，视频帧需要硬件解码。真实 ARM 电视盒子有硬件解码器，不受影响。
2. **WebView 渲染问题**: 某些 Android WebView 版本对 MSE blob URL 视频的渲染有 bug，导致视频帧无法显示到 Surface。
3. **CSS 层级问题**: 虽然已设置 `z-index:999999` 和 `transform:translateZ(0)`，但某些 WebView 的 CSS 合成层处理可能有差异。
4. **移动端 URL 兼容性**: CCTV-3/6/8 的移动端 URL（`/m/index.shtml`）在不同设备上的表现不一致，可能仍会加载 HLSP2P 播放器。

**建议排查方向**:
1. 在真实 ARM 设备上测试，确认是否为模拟器硬件限制
2. 检查 Debug 面板中 video 元素的实际尺寸、位置、z-index
3. 尝试切换 WebView 的渲染模式（硬件加速 vs 软件渲染）
4. 检查是否有 overlay 元素遮挡 video
5. 尝试使用 ExoPlayer/MediaPlayer 替代 WebView 播放

### 其他已知问题

- **Aliyun Maven 仓库**: 可能返回 502 Bad Gateway，已配置 Google() 和 MavenCentral() 作为主要仓库
- **GitHub Actions**: runner 需要显式安装 Android SDK（platforms 和 build-tools）才能生成非空 APK
- **Gradle Wrapper**: 从 Windows 提交会丢失 +x 权限，CI 中需要 `chmod +x ./gradlew`

## 构建与部署

### 本地构建

```bash
.\gradlew.bat assembleDebug --no-daemon --rerun-tasks --no-build-cache
```

**注意**: 必须加 `--no-daemon --rerun-tasks --no-build-cache` 避免使用缓存的空 APK。

### GitHub Actions CI/CD

推送代码到 `master` 分支自动触发构建。APK 作为 artifact 上传，保留 30 天。

**构建流程** ([android-build.yml](file:///d:/badwp/tv1/.github/workflows/android-build.yml)):
1. Checkout 代码
2. 安装 JDK 17
3. 安装 Android SDK（cmdline-tools 11.0, platforms android-34, build-tools 34.0.0）
4. 清理旧缓存
5. 配置 Gradle 8.7
6. 编译 Debug APK
7. 上传 APK artifact

### 安装测试

```bash
adb install app/build/outputs/apk/debug/app-debug.apk
```

## 操作说明

### 手机触屏
- **上滑**: 下一个频道
- **下滑**: 上一个频道

### 遥控器
- **上键**: 上一个频道
- **下键**: 下一个频道
- **菜单键**: 显示频道提示

### 界面元素
- **左上角**: 频道名称提示（1.8 秒后自动隐藏）
- **屏幕中央**: Debug 诊断面板（白屏时显示，黄色字体黑色背景）

## 技术栈

- **Android**: Java, WebView, WebChromeClient, WebViewClient
- **前端注入**: CSS 强制全屏, JavaScript 自动播放修复, document.write polyfill
- **兜底播放**: hls.js 1.5.15 (MSE-based HLS player)
- **CI/CD**: GitHub Actions, Gradle 8.7, AGP 8.6.1

## 下一步改进建议

1. **解决 CCTV-3/6/8 黑屏问题**:
   - 尝试在真实 ARM 设备上测试，确认是否为模拟器限制
   - 考虑使用 ExoPlayer/MediaPlayer 替代 WebView 播放这三个频道
   - 检查移动端 URL 是否真的返回简单播放器

2. **性能优化**:
   - 减少 FastLoading/AutoFullscreen 的执行频率
   - 优化 CSS 注入逻辑，避免重复注入

3. **用户体验**:
   - 添加频道收藏功能
   - 添加频道搜索
   - 优化切台过渡动画

4. **稳定性**:
   - 增加网络状态检测
   - 添加播放失败重试机制
   - 优化错误处理和用户提示

## 相关文档

- [Android WebView 文档](https://developer.android.com/reference/android/webkit/WebView)
- [hls.js 文档](https://github.com/video-dev/hls.js)
- [Chromium 2G Intervention](https://www.chromium.org/blink/interventions/)
- [Media Source Extensions](https://developer.mozilla.org/en-US/docs/Web/API/Media_Source_Extensions_API)
