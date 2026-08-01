# 央视官方直播导航 (CCTV Official Live Navigator)

> **给接手工程师的 FIRST THING FIRST：先读下面"核心问题与方向"那一节，否则你会重复踩我们已经踩过的坑。**

---

## 1. 核心问题与接手方向（重要！先看这里）

### 🚨 当前阻塞问题：CCTV-6 电影台（目标：全屏正常出画面

**现象（MuMu/电视盒子上**
**用户 2026-08-01 最后一次反馈截图：
```
onPageFinished -> https://m.yangshipin.cn/tv?vid=2000203303&pid=600001859&delete_...
  页面显示：CCTV1 综合 + "分享频道已下架" 灰色提示 + 底部"打开央视频"按钮
```

我们想访问 CCTV6 的是 **`www.yangshipin.cn/tv/home?pid=600108442` + 桌面 UA，但**网页**，访问后央视频的服务器**，不管你 UA 是桌面还是移动**，访问 `www.yangshipin.cn` 一旦检测到请求来自 Android（通过其他 HTTP Header：`Sec-CH-UA` / `X-Requested-With` / `Accept-Language` / `Cookie 域` / `URL 命中移动端重定向规则），**就会发 302/内部 JS 跳回 `m.yangshipin.cn/tv?vid=旧PID...`**，然后跳回的这个页面就是"分享频道已下架"的空页。

这就是 CCTV6 到现在都没画面的根本原因。不是我们 UA 没改，不是 CSS 没做好，是**重定向回了旧 URL，加载的不是我们想要的页面。

### ✅ 接手方向（按优先级从高到低）

**优先级 1 - 防重定向（必做！）
在 [MainActivity.java#L1151-L1166](file:///d:/badwp/tv1/app/src/main/java/com/example/cctvofficialnavigator/MainActivity.java) 的 `WebViewClient.shouldOverrideUrlLoading` / `onPageStarted` 里**加拦截：
- 如果 URL 命中 `m.yangshipin.cn`（移动端旧域名） → 立刻 `return true;` 不跳转，或者重新 `webView.loadUrl(www.yangshipin.cn/tv/home?pid=对应PID)`，并且在 `loadUrl` 时用 `additionalHttpHeaders` 里塞 `Referer: https://www.yangshipin.cn/` 还有把 `X-Requested-With`（Android WebView 默认会加这个 header，目标服务器看这个就知道是 WebView 不是 Chrome 所以跳移动版）。
- 或者：在 `shouldInterceptRequest` 里对 `m.yangshipin.cn` 的请求直接拦截返回 HTTP 307 到 www 版。
- 或者：最暴力：`WebView.setWebViewClient` 重写 `shouldInterceptRequest(WebResourceRequest)` 遇到 301/302/303/307/308 重定向时手动处理。
- 注：X-Requested-With 这个 header 是 Android WebView 自动加的，用 reflection 去不掉（Android 9+ 能改），但用 `WebSettings.setUserAgentString` 没用，它是独立 header，服务器看这个就知道你是 App 不是 Chrome。

**优先级 2 - 绕开 WebView 直接用 ExoPlayer / Media3 播放（长期方案）
CCTV-6 的官方 m3u8 流 URL 是能在页面加载后被 shouldInterceptRequest 拦截到的（流域名是 `mobilelive-play.ysp.cctv.cn`，纯 HLS，无 DRM，hls.js 能播）。与其让 WebView 加载 HTML → 播放器 JS → 解出 m3u8 → 再播，**不如在 Java/Kotlin 层直接解析一次拿到 m3u8，然后用 ExoPlayer 起一个 TextureView/PlayerView 全屏播，稳定100倍。**
- 要注意的点：拿 m3u8 URL 里带的 token / 签名参数有时效，不能硬编码，必须每次启动频道时从 www 页面实时解析（或者 hook 页面里 JS 的 xhr/fetch）。
- 实现思路：后台一个 headless WebView / 或 Java 层请求 URL 注入 JS 拦截出 m3u8 URL，然后把 URL 扔给 ExoPlayer。
- 优点：彻底解决 WebView 层渲染问题（有声音没画面、WebView 版本兼容、硬件解码、CSS 拉全屏等所有问题一次性都没了。

**优先级 3 - 用其他官方源替换 CCTV6 URL
- Ku9-IPTV 维护的 "央视 m3u8 集合里 CCTV-6 有长期可用的官方无 DRM 源（可以搜 "yangshipin mobilelive-play.ysp.cctv.cn"，直接当 URL 用（但需要实时 token 解析同上优先级2）
- 或者用 cctv.cdn* 其他 CDN 的 CCTV-6（比如 tv.cctv.com 的 HLSP2P 解析出的 m3u8 无 DRM 源。

---

## 2. 项目概述

- **包名**: `com.example.cctvofficialnavigator`
- **语言**: Java（无 Kotlin 依赖，因为原作者想尽量少引入依赖）
- **最低 API**: 23 (Android 6.0，覆盖绝大多数旧电视盒子)
- **目标 API**: 34 (Android 14)
- **AGP**: 8.6.1
- **Gradle Wrapper**: 8.7
- **构建命令**: `.\gradlew.bat assembleDebug`（Windows PowerShell）
- **产物**: `app/build/outputs/apk/debug/app-debug.apk`

## 3. 项目结构

```
app/src/main/
├── java/com/example/cctvofficialnavigator/
│   ├── MainActivity.java              # 核心 Activity（99% 逻辑都在这里）
│   ├── ChannelCatalog.java          # 频道 URL 列表 + 顺序
│   ├── Channel.java                # 数据类：name + officialUrl
│   └── LoggingWebChromeClient.java  # 拦截 console 日志到 logcat
├── res/
│   ├── layout/activity_main.xml      # 布局：WebView + 频道提示 + 频道列表左半屏 + 数字输入提示
│   └── values/                     # 主题样式
└── AndroidManifest.xml              # INTERNET 权限、横屏、LEANBACK_LAUNCHER（电视盒子入口）
```

## 4. 当前频道列表（**最终版**）

**写入顺序即用户看到的序号（1-based）**，定义在 [ChannelCatalog.java](file:///d:/badwp/tv1/app/src/main/java/com/example/cctvofficialnavigator/ChannelCatalog.java)。CCTV 台先按频道号正序排，**非 CCTV 台（广西台系列）直接写在最后按写入顺序顺延，不参与按频道号排序**（对应 [MainActivity.java `buildSortedChannelList()`](file:///d:/badwp/tv1/app/src/main/java/com/example/cctvofficialnavigator/MainActivity.java) 的逻辑）。

| 序号 | 频道名 | URL | UA 策略 | 状态 |
|------|--------|-----|---------|------|
| 1 | CCTV-1 综合 | `https://tv.cctv.com/live/cctv1/` | 移动 UA | ✅ 正常 |
| 2 | CCTV-2 财经 | `https://tv.cctv.com/live/cctv2/` | 移动 UA | ✅ 正常 |
| 3 | CCTV-4 中文国际（亚） | `https://tv.cctv.com/live/cctv4/` | 移动 UA | ✅ 正常 |
| 4 | CCTV-4 中文国际（欧） | `https://tv.cctv.com/live/cctveurope/index.shtml` | 移动 UA | ✅ 正常 |
| 5 | CCTV-4 中文国际（美） | `https://tv.cctv.com/live/cctvamerica/` | 移动 UA | ✅ 正常 |
| 6 | CCTV-5 体育 | `https://tv.cctv.com/live/cctv5/` | 移动 UA | ✅ 正常 |
| 7 | CCTV-5+ 体育赛事 | `https://tv.cctv.com/live/cctv5plus/` | 移动 UA | ✅ 正常 |
| **8** | **CCTV-6 电影** | **`https://www.yangshipin.cn/tv/home?pid=600108442`** | **桌面 UA（仅这一台）** | ❌ **重定向失败**（见第 1 节） |
| 9 | CCTV-7 国防军事 | `https://tv.cctv.com/live/cctv7/` | 移动 UA | ✅ 正常 |
| 10 | CCTV-9 纪录 | `https://tv.cctv.com/live/cctvjilu/` | 移动 UA | ✅ 正常 |
| 11 | CCTV-10 科教 | `https://tv.cctv.com/live/cctv10/` | 移动 UA | ✅ 正常 |
| 12 | CCTV-11 戏曲 | `https://tv.cctv.com/live/cctv11/` | 移动 UA | ✅ 正常 |
| 13 | CCTV-12 社会与法 | `https://tv.cctv.com/live/cctv12/` | 移动 UA | ✅ 正常 |
| 14 | CCTV-13 新闻 | `https://tv.cctv.com/live/cctv13/` | 移动 UA | ✅ 正常 |
| 15 | CCTV-14 少儿 | `https://tv.cctv.com/live/cctvchild/` | 移动 UA | ✅ 正常 |
| 16 | CCTV-15 音乐 | `https://tv.cctv.com/live/cctv15/` | 移动 UA | ✅ 正常 |
| 17 | CCTV-16 奥林匹克 | `https://tv.cctv.com/live/cctv16/` | 移动 UA | ✅ 正常 |
| 18 | CCTV-17 农业农村 | `https://tv.cctv.com/live/cctv17/` | 移动 UA | ✅ 正常 |
| 19 | 广西新闻频道 | `https://tv.gxtv.cn/channel/channelivePlay_9dfd8600075811e9ba67e41f13b60c62.html` | 移动 UA | ✅ 正常（已测） |
| 20 | 广西卫视 | `https://tv.gxtv.cn/channel/channelivePlay_e7a7ab7df9fe11e88bcfe41f13b60c62.html` | 移动 UA | ✅ 正常（已测） |

> **备注 1**：CCTV-3/8 目前在 ChannelCatalog 里**已删除**（因为 tv.cctv.com 版权页也会跳到空页，央视频移动端下架，用 yangshipin PC版的话和 CCTV6 一样的重定向问题，待修复CCTV6后可以同样手法加回来）：
> - CCTV-3 综艺 yangshipin PC版 PID：`600108439`
> - CCTV-8 电视剧 yangshipin PC版 PID：`600108443`

> **备注 2**：广西台用的是阿里云 AliPlayer H5（.m3u8 流，在 `*.liangtv.cn` + `*.alicdn.com` CDN，已加白名单，现有 hls.js 兜底逻辑 100% 能播，不用动。

---

## 5. 已实现的核心机制（接手别重写！已工作正常，全部有用）

### 5.1 WebView 配置（[MainActivity.java configureWebView()](file:///d:/badwp/tv1/app/src/main/java/com/example/cctvofficialnavigator/MainActivity.java)）

```java
settings.setJavaScriptEnabled(true)
DomStorage / DatabaseEnabled → 播放器需要
MixedContentMode = MIXED_CONTENT_ALWAYS_ALLOW → CCTV 页面里有 http 统计脚本
settings.setMediaPlaybackRequiresUserGesture(false) → 不用用户点一下自动播
webView.setLayerType(LAYER_TYPE_HARDWARE) → GPU 合成，解决有声无画（有时有效
setAllowFileAccess / AllowContentAccess(true)
```

### 5.2 频道级 UA 切换（**仅CCTV6用桌面 UA，其他台都用移动 UA）

**关键代码位置**：[MainActivity.java DESKTOP_UA + needsDesktopUA() + loadChannel()

```java
// 桌面 UA（仅 yangshipin 桌面端 home/pid 命中才启用
DESKTOP_UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"

needsDesktopUA(url) → url.contains("yangshipin.cn/tv/home") && url.contains("pid=")

loadChannel(requestedIndex):
  useDesktop = needsDesktopUA(channel.officialUrl)
  webView.getSettings().setUserAgentString(useDesktop ? DESKTOP_UA : null)
  webView.loadUrl(channel.officialUrl)
```

**不要给所有台开桌面 UA：CCTV-9 等正常台用桌面 UA 会直接黑屏（桌面版布局 CSS 不对）。

### 5.3 白名单域名（URL 加载不跳外部浏览器）[isOfficialCctvUrl](file:///d:/badwp/tv1/app/src/main/java/com/example/cctvofficialnavigator/MainActivity.java)

```
cctv.com / cntv.cn / gxtv.cn / liangtv.cn / alicdn.com / aliyun.com / yangshipin.cn / ysp.cctv.cn / smtcdns.net
```
新增域名在 yangshipin/tv/home 页面加载时会请求 `mobilelive-play.ysp.cctv.cn / pcsite.ysp.cctv.cn，所以都已经全部进了。

### 5.4 CSS 全屏 CSS 注入（每帧强制全屏（FastLoading + AutoFullscreen）

注入时机：**onPageStarted → 注入 JS 注入 CSS style `position:fixed 100vw 100vh z-index:999999 + GPU 合成层 transform:translateZ(0) backface-visibility:hidden（修有声无画）
CSS 里把所有装饰元素：header, footer, nav, 广西台 .header/footer / 央视频 ysp-* / yangshipin 桌面端所有导航栏 / 侧边栏 / 推荐 / 下载按钮 / LOGO / EPG 节目单 / Tab 分类 / 搜索框 / 个人中心 / 频道列表 / 节目信息 等全部 `display:none!important`（**具体 CSS 字符串看 FastLoading 里看代码，接手如果将来 yangshipin 页面改版，加 class/id 名到display:none 列表里即可，不要自己写新的逻辑

### 5.5 document.write Polyfill（必留）
解决 CCTV 的播放器初始化脚本是 `document.write` 加载 `r.img.cctvpics.com` 的跨站 script，Chromium 53+ 的 2G Intervention 不执行，polyfill 把 document.write 把转 createElement('script') 异步插入。已在 onPageStarted 最早期注入。

### 5.6 自动播放 muted 修复（mute=true play() 成功后 2s 取消 muted
Android 自动播放政策要求视频必须静音才能自动播。CCTV 的 HLSP2P 播放器本身会这么做但偶尔失败，所以在白屏检测 + AutoFullscreen 里兜底也做 `video.muted=true; video.play().then(()=>setTimeout unmute))

### 5.7 白屏/黑屏有声无画检测
ScheduledExecutorService 倒计时 5 10 15 20 30秒。后台线程倒计时 + 30s，不在主线程 queue 阻塞（CCTV 页面心跳把 handler 消息队列塞爆 postDelayed 执行不了）。
判断逻辑 `doWhiteScreenCheck` 里 evaluateJavascript 找：
- 无 video → 等 → display: diagnostic
- 有但 paused=true play()
- 超过 10s 无 video 且已拦截到 m3u8 → **hls.js 兜底播

### 5.8 m3u8 拦截 + hls.js 兜底
**两层拦截**：
1. Java 层 `shouldInterceptRequest`：URL 结尾 .m3u8 → 保存到 capturedM3u8Url
2. JS 层 `injectM3u8Capture`：页面 JS 注入 hook XMLHttpRequest/open/send fetch 都搜 "/playlist 返回的 m3u8 → evaluateJavascript 回传
hls.js 从 `jsdelivr` + unpkg 双源，失败再试 unpkg，最后最后试原生 video.src=m3u8（hls.js 对 DRM 的 MSE addSourceBuffer 的 codec 改 `avc1.640028`（CCTV 源 codec string 被 WebView MediaSource 不认）

### 5.9 频道列表 + 数字键跳转 + 触屏手势
布局 activity_main.xml: WebView 全屏，上层左上角 channelHint 角进度 debugPanel display progressHint 数字 半屏 channelListScroll ScrollView + number 数字输入 numberInputHint 3 缓冲 OK键 OK键弹出左半屏频道列表
遥控器 OK OK选 OK键 OK 数字键 0-9 3 秒没按第二个数字按列表序号直跳：
- 优先频道号（CCTV1=1, CCTV2=2,... 广西台 19=广西新闻=20=广西卫视顺延

触屏 GestureDetector SimpleOnGestureListener：
- 单击 → show/hide 频道列表（列表显示时 OK = 高亮
- 上滑 → 切台 / 列表显示时 = 滚动下一项
- 下滑 → 切台上一个 / 列表显示时 = 滚动到上一项

---

## 6. 全部踩坑历史（接手工程师请务必读完，不然你会和我们一样试一圈）

### 坑 1：CCTV-3/6/8 tv.cctv.com/live/cctv3 → 移动 UA 302 → 空页（版权空页移动 `m.yangshipin.cn/static/empty.html
→ 所以桌面 UA，加载 HLSP2P MSE blob URL WebRTC P2P → Android TV WebView 不兼容WASM+P2P 不兼容 → 有声无画 + 黑屏
→ 所以 tv.cctv.com 桌面端 HLSP2P 的 m3u8 还带 cdrm 加密（DRM） hls.js 能解 cdrm 的 m3u8 WebView 层 所以尝试过，但是播放器 DRM 有些 TV 盒子取流能播成功了画面 → 还是黑屏

### 坑 2：CCTV-6 m.yangshipin.cn/video?type=1&vid=2000203303&pid=600001802 → 移动UA 分享频道已下架 → 页面里vid=2026/8 已经挂了
然后替换为 yangshipin.cn/tv/home?pid=600108442（桌面端入口）+ 桌面 UA，但用户反馈还是失败
### 坑 3（就是现在：www.yangshipin.cn/tv/home?pid=CCTV6，即使 WebView 加桌面 UA 加载 发请求 → 服务器重定向 m.yangshipin.cn 重定向 ，因为：
- 原因分析
服务器加看 HTTP Header：`X-Requested-With: com.example.cctvofficialnavigator`（Android WebView 自带 header，Chrome PC Chrome 这个都没有这个）
服务器一看到这个就知道你是 WebView，直接跳移动版）
解决方案（根本原因！这个是根本重定向
根本不解决根本永远拿不到桌面版的页面，改 UA 没用，CSS 写得再满也没什么

### 坑 4：CSS 把播放器容器全屏
CCTV tv.cctv.com 桌面版是 HLSP2P 页面布局 容器变 layout 不同 ，播放器容器 ID class 全部列了 都 加 写了 container video position:fixed 拉到最高 这个 加 transform:translateZ(0) GPU 合成有声无画广西台 AliPlayer 用 #J_prismPlayer，yangshipin 用 #cmgPlayer / .CMGPlayer / .ysp-player / .txp_container，所以每次页面改版后加名字就行。

---

## 7. 接手工程师 TODO（30 分钟快速 Checklist

### 做完后你能立刻让 CCTV6 出画面

- [ ] 断点 shouldOverrideUrlLoading 里加 if url.startsWith("https://m.yangshipin.cn") 直接 return true; 不跳转并且重新 load www 版 URL
- [ ] 试 2：X-Requested-With 这个 header 能删则删（Android API < 9 reflection 掉 / 更高 API 里 AndroidFramework 实现层，或者干脆 Java 层，或者 (Android XXXXX 没发，发请求时加 Chrome 桌面 要
- [ ] 试 3：WebView.loadUrl(url, additionalHttpHeaders) 手动补 Referer: https://www.yangshipin.cn/ ， 加
- [ ] 试 4：shouldInterceptRequest 里拦截到 mobilelive-play.ysp.cctv.cn 的 m3u8，打印完整 URL 到 logcat（如果拿到手后立刻 看能不能在浏览器里直接打开，能打开的话 hls.js 兜底逻辑肯定能播，那 ExoPlayer 一定能
- [ ] 试 5：直接放弃 WebView 起个 TextureView 拿拦截到的 m3u8 直接扔给 ExoPlayer 全屏播 CCTV6 单独接好（长期稳定

### 调试命令：
```powershell
adb logcat -s "CCTV-" System.err WebView chromium
# 或者：
adb logcat | grep -i "m3u8\|yangshipin\|useragent\|shouldoverride
```
Debug 面板在屏幕**右上角**有进度提示 Debug Panel
频道加载状态

---

## 8. 其他操作说明（给用户

| 操作 | 手机触屏 | 遥控器 |
|------|---------|--------|
| 切到上一台 | 下滑 | 上键 |
| 切到下一台 | 上滑 | 下键 |
| 打开频道列表 | 单击屏幕 | OK 键 OK |
| 选频道 | 点列表项 | 上下移动后再按OK键 |
| 数字键直跳频道 | 不支持（触屏无数字键盘） | 数字 0-9（3 秒内连按两位数自动跳） |

---

## 9. 相关文件清单（点击跳转）

- 核心逻辑 99% 都在这里：[MainActivity.java](file:///d:/badwp/tv1/app/src/main/java/com/example/cctvofficialnavigator/MainActivity.java)
- 频道列表 URL 全在这：[ChannelCatalog.java](file:///d:/badwp/tv1/app/src/main/java/com/example/cctvofficialnavigator/ChannelCatalog.java)
- UI 布局：[activity_main.xml](file:///d:/badwp/tv1/app/src/main/res/layout/activity_main.xml)
- 拦截 console 日志：[LoggingWebChromeClient.java](file:///d:/badwp/tv1/app/src/main/java/com/example/cctvofficialnavigator/LoggingWebChromeClient.java)

---

## 10. 构建（能工作正常的，接手不用改）

本地构建：
```
.\gradlew.bat assembleDebug
```
产物：`app\build\outputs\apk\debug\app-debug.apk`

CI 已经配置了 GitHub Actions（`.github/workflows/android-build.yml`），推 master 自动构建，artifact 保留 30 天。

---

> 最后一次 commit 2026/8/1：CCTV6 的根本问题是 WebView 访问 www.yangshipin.cn 被跳 m.yangshipin.cn 旧页，不是 UA/CSS 问题，**解决防重定向 + 解析 m3u8 + ExoPlayer 能 100% 搞定，祝君好运。
