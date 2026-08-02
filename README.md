# 央视官方直播导航 (CCTV Official Live Navigator)

> **给接手工程师的 FIRST THING FIRST：先读下面"核心问题与方向"那一节，否则你会重复踩我们已经踩过的坑。**

---

## 1. 核心问题与接手方向（重要！先看这里）

### ✅ 2026-08-02 最新状态：全频道 ExoPlayer 原生播放，20 台稳定运行

**核心架构变更：所有频道（非 `_web.m3u8` 加密流）一律切 ExoPlayer 原生播放**

之前只有央视频 yangshipin.cn 的 m3u8 才切 ExoPlayer，央视主源 tv.cctv.com 的 m3u8 全靠 WebView 里的 hls.js 兜底，经常黑屏/有声音没画面。现在改成：`shouldInterceptRequest` 截到**任何** m3u8（只要不是 `_web.m3u8` 加密流）→ 立刻切 ExoPlayer 原生播放。

**关键发现：央视频桌面端两种流**
| 流后缀 | 加密 | ExoPlayer | 频道 |
|--------|------|-----------|------|
| `_fhd.m3u8` | encrypt=0 清流 | ✅ 可播 | **CCTV-6**（pid=600108442，唯一清流频道） |
| `_web.m3u8` | encrypt=2 CMG WASM 加密 | ❌ 解不了（绿屏/花屏） | CCTV-1/2/3/4/5/5+/7-15/CGTN 等所有其他频道 |

> CCTV-3/8 已移除（yangshipin 返回 `_web.m3u8` 加密流，ExoPlayer 和 WebView 都播不了视频）。CCTV-1 备用源也已注释（同原因）。

**用户 2026-08-02 最新实测：**
- ✅ CCTV-6 电影：yangshipin 桌面端 `_fhd.m3u8` 清流 → ExoPlayer 原生播放，100% 正常
- ✅ 其他所有台（CCTV-1/2/4/5/5+/7/9~17 + 广西台）：tv.cctv.com / gxtv.cn 的 cdrm/ld 流 → ExoPlayer 原生播放，100% 正常（cdrm 流 TS 分片实际无加密，ExoPlayer 直接能播）

| 历史阻塞问题 | 根因 | 最终解决手法 | 当前状态 |
|---|---|---|---|
| CCTV-6 显示「分享频道已下架」+ 跳 m.yangshipin.cn | Android WebView 自动加 `X-Requested-With: <包名>` Header → 服务器识别为 WebView，跳移动端 | 防重定向 2 层拦截 + `loadYangshipinWithHeaders()` 带 `X-Requested-With: ""` + `Referer` | ✅ 解决 |
| CCTV-6 有声音没画面（黑屏） | Chromium `<video>` SurfaceView overlay 位置计算错误 | 切 ExoPlayer 原生播放，完全绕开 WebView 渲染 | ✅ 根治 |
| 央视频 CCTV-3/8 绿屏/黑屏 | `_web.m3u8` 是 CMG WASM 加密流，ExoPlayer 解不了；WebView 也无法渲染 | 已移除这两个频道 | ⚠️ 已移除 |
| 央视主源（tv.cctv.com）偶发黑屏 | 依赖 WebView hls.js 兜底，不稳定 | 所有非 `_web.m3u8` 流一律切 ExoPlayer | ✅ 根治 |

### ⚠️ 接手方向（2026-08-02 后，按优先级）
1. **不要在 WebView 渲染链路上浪费时间**：所有频道已切 ExoPlayer 原生播放，100% 根治黑屏
2. **不要动 ExoPlayer 核心逻辑** → 全频道稳定运行，改了会出问题
3. **yangshipin 页面改版时** → 如果 m3u8 URL 域名变了，改 `shouldInterceptRequest` 里判断 `.m3u8` 的条件
4. **想加新频道** → 如果用 tv.cctv.com 源，直接加（ExoPlayer 自动接管）；如果用 yangshipin.cn 源，注意只有返回 `_fhd.m3u8` 清流的 pid 才能用（目前只有 CCTV-6）

---

## 2. 项目概述

- **包名**: `com.example.cctvofficialnavigator`
- **语言**: Java（无 Kotlin 依赖，兼容旧电视盒子）
- **最低 API**: 23 (Android 6.0)
- **目标 API**: 34 (Android 14)
- **AGP**: 8.6.1
- **Gradle Wrapper**: 8.7
- **硬件加速**: `<application>` 节点已开 `android:hardwareAccelerated="true"`，所有频道统一 `LAYER_TYPE_HARDWARE`
- **构建命令**: `.\gradlew.bat assembleDebug`（Windows PowerShell）
- **产物**: `app/build/outputs/apk/debug/app-debug.apk`
- **CI**: `.github/workflows/android-build.yml`，push `master` 自动构建，artifact 保留 30 天

## 3. 项目结构

```
app/src/main/
├── java/com/example/cctvofficialnavigator/
│   ├── MainActivity.java              # 核心 Activity（99% 逻辑都在这里）
│   ├── ChannelCatalog.java          # 频道 URL 列表 + 顺序 + 排序规则
│   └── Channel.java                # 数据类：name + officialUrl
├── res/
│   ├── layout/activity_main.xml      # 布局：WebView 全屏 + 左上角 channelHint + 右上角 debugPanel + 左半屏频道列表
│   └── values/                     # 主题样式
└── AndroidManifest.xml              # INTERNET 权限、横屏、LEANBACK_LAUNCHER
```

## 4. 当前频道列表（**最终版 2026-08-02**）

共 20 个频道，定义在 [ChannelCatalog.java](file:///d:/badwp/tv1/app/src/main/java/com/example/cctvofficialnavigator/ChannelCatalog.java)。

| 序号 | 频道名 | URL | 播放方式 | 状态 |
|------|--------|-----|---------|------|
| 1 | CCTV-1 综合 | `https://tv.cctv.com/live/cctv1/` | ExoPlayer（移动 UA） | ✅ 正常 |
| 2 | CCTV-2 财经 | `https://tv.cctv.com/live/cctv2/` | ExoPlayer（移动 UA） | ✅ 正常 |
| 3 | CCTV-4 中文国际（亚） | `https://tv.cctv.com/live/cctv4/` | ExoPlayer（移动 UA） | ✅ 正常 |
| 4 | CCTV-4 中文国际（欧） | `https://tv.cctv.com/live/cctveurope/index.shtml` | ExoPlayer（移动 UA） | ✅ 正常 |
| 5 | CCTV-4 中文国际（美） | `https://tv.cctv.com/live/cctvamerica/` | ExoPlayer（移动 UA） | ✅ 正常 |
| 6 | CCTV-5 体育 | `https://tv.cctv.com/live/cctv5/` | ExoPlayer（移动 UA） | ✅ 正常 |
| 7 | CCTV-5+ 体育赛事 | `https://tv.cctv.com/live/cctv5plus/` | ExoPlayer（移动 UA） | ✅ 正常 |
| 8 | CCTV-6 电影 | `https://www.yangshipin.cn/tv/home?pid=600108442` | ExoPlayer（桌面 UA，`_fhd.m3u8` 清流） | ✅ 正常 |
| 9 | CCTV-7 国防军事 | `https://tv.cctv.com/live/cctv7/` | ExoPlayer（移动 UA） | ✅ 正常 |
| 10 | CCTV-9 纪录 | `https://tv.cctv.com/live/cctvjilu/` | ExoPlayer（移动 UA） | ✅ 正常 |
| 11 | CCTV-10 科教 | `https://tv.cctv.com/live/cctv10/` | ExoPlayer（移动 UA） | ✅ 正常 |
| 12 | CCTV-11 戏曲 | `https://tv.cctv.com/live/cctv11/` | ExoPlayer（移动 UA） | ✅ 正常 |
| 13 | CCTV-12 社会与法 | `https://tv.cctv.com/live/cctv12/` | ExoPlayer（移动 UA） | ✅ 正常 |
| 14 | CCTV-13 新闻 | `https://tv.cctv.com/live/cctv13/` | ExoPlayer（移动 UA） | ✅ 正常 |
| 15 | CCTV-14 少儿 | `https://tv.cctv.com/live/cctvchild/` | ExoPlayer（移动 UA） | ✅ 正常 |
| 16 | CCTV-15 音乐 | `https://tv.cctv.com/live/cctv15/` | ExoPlayer（移动 UA） | ✅ 正常 |
| 17 | CCTV-16 奥林匹克 | `https://tv.cctv.com/live/cctv16/` | ExoPlayer（移动 UA） | ✅ 正常 |
| 18 | CCTV-17 农业农村 | `https://tv.cctv.com/live/cctv17/` | ExoPlayer（移动 UA） | ✅ 正常 |
| 19 | 广西新闻频道 | `https://tv.gxtv.cn/channel/...` | ExoPlayer（移动 UA） | ✅ 正常 |
| 20 | 广西卫视 | `https://tv.gxtv.cn/channel/...` | ExoPlayer（移动 UA） | ✅ 正常 |

> **已移除**：CCTV-3 综艺、CCTV-8 电视剧（yangshipin 返回 `_web.m3u8` CMG WASM 加密流，ExoPlayer 和 WebView 都无法播放视频）
> **已注释**：CCTV-1 综合（备用）（yangshipin pid=600001859，同加密流问题）

---

## 5. 已实现的核心机制

### 5.1 ExoPlayer 原生播放（核心！所有频道统一使用）

位置：[MainActivity.java shouldInterceptRequest() + playWithExoPlayer()](file:///d:/badwp/tv1/app/src/main/java/com/example/cctvofficialnavigator/MainActivity.java)

**流程**：
```
shouldInterceptRequest 截到 .m3u8 请求
  → 判断:是否 _web.m3u8 (CMG WASM 加密流)?
    → YES: 跳过,不切 ExoPlayer (ExoPlayer 解不了加密流)
    → NO:  切 ExoPlayer 原生播放
      1. webView.setVisibility(GONE)  // 隐藏 WebView
      2. 根据频道类型动态设置 UA/Referer/Origin:
         - yangshipin.cn → 桌面 Chrome UA + yangshipin.cn Referer/Origin
         - tv.cctv.com 等 → 移动 Chrome UA + 对应频道 URL 作 Referer/Origin
      3. DefaultHttpDataSource.Factory + HlsMediaSource
      4. ExoPlayer + TextureView (不用 SurfaceView,避免 overlay bug)
      5. 全屏播放,右上角调试面板显示 EXO_STATE/EXO_VID 状态
```

**为什么 cdrm 流也能播**：tv.cctv.com 的 m3u8 URL 里有 `cdrm` 字样，但所谓 "cdrm" 只是 JS 播放器层面的 DRM 能力检测，TS 分片实际无 `EXT-X-KEY` 加密，是标准 H.264+AAC，ExoPlayer 直接能播。

### 5.2 WebView 配置 + 统一 LAYER_TYPE_HARDWARE

所有频道统一用 `LAYER_TYPE_HARDWARE`（之前 yangshipin 频道用 SOFTWARE 是为了修 SurfaceView overlay bug，但现在所有频道都切 ExoPlayer，WebView 被隐藏，LayerType 不影响画面）。

### 5.3 频道级 UA 策略

```java
needsDesktopUA(url) = url.contains("yangshipin.cn/tv/home") && url.contains("pid=")
// 只有 CCTV-6 (yangshipin 桌面端) 用桌面 Chrome UA
// 其他所有台用系统默认移动 UA
```

### 5.4 防重定向 2 层拦截（CCTV-6 不再跳 m.yangshipin.cn）

`shouldOverrideUrlLoading` + `onPageStarted` 双重拦截 + `loadYangshipinWithHeaders()` 带 `X-Requested-With: ""` + `Referer` 定制 Header。

### 5.5 ExoPlayer 请求头动态选择

```java
if (currentIsYangshipin) {
    ua = "Mozilla/5.0 (Windows NT 10.0; ...) Chrome/126 ...";  // 桌面 UA
    referer = "https://www.yangshipin.cn/";
    origin = "https://www.yangshipin.cn";
} else {
    ua = "Mozilla/5.0 (Linux; Android 13; ...) Chrome/126 ... Mobile ...";  // 移动 UA
    referer = expectedOfficialUrl;  // 当前频道 URL
    origin = URI(referer).getScheme() + "://" + URI(referer).getHost();
}
```

### 5.6 白屏/黑屏检测 + hls.js 兜底（ExoPlayer 激活时自动跳过）

`doWhiteScreenCheck()` 在 ExoPlayer 已激活时直接 return（WebView 已隐藏，检测无意义）。三处 hls.js 兜底逻辑也加了 `!exoPlayerActive` 保护，防止 ExoPlayer 播放时 hls.js 注入抢音频。

### 5.7 调试面板（右上角）

实时显示：
- `M3U8_EXO_CLEAN` / `M3U8_SKIP_ENCRYPT`：m3u8 拦截结果（切 ExoPlayer / 跳过加密流）
- `EXOPLAYER_START`：ExoPlayer 启动
- `EXO_STATE`：BUFFERING / READY(PLAYING) / ENDED
- `EXO_VID`：VID_SIZE 1920x1080（视频真实尺寸）
- `EXO_ERR`：错误信息

### 5.8 其他机制

- **防重定向**：2 层拦截 + 定制 Header
- **yangshipin 专属 JS 注入**：`_ysh_*` 函数集（仅 yangshipin 页面执行）
- **document.write Polyfill**：绕开 Chromium 53+ 2G Intervention
- **频道列表 + 数字键跳转 + 触屏手势**：遥控器/触屏完整支持

---

## 6. 全部踩坑历史

### 坑 1：CCTV-6 显示「分享频道已下架」
根因：Android WebView 自动加 `X-Requested-With: <包名>` Header → 服务器识别为 WebView → 跳移动端
修复：`loadUrl(url, additionalHttpHeaders)` 把 `X-Requested-With: ""` 覆盖掉 + `Referer: https://www.yangshipin.cn/`

### 坑 2：CCTV-6 有声音没画面（黑屏）
根因：Chromium `<video>` SurfaceView overlay 位置计算错误
修复：切 ExoPlayer 原生播放，完全绕开 WebView 渲染

### 坑 3：CCTV-3/8 绿屏/黑屏
根因：yangshipin 对 CCTV-3/8 返回 `_web.m3u8`（CMG WASM 加密流，encrypt=2），ExoPlayer 解不了（绿屏），WebView 也无法渲染（黑屏）
结论：已移除 CCTV-3/8，目前只有 CCTV-6 的 yangshipin 源返回 `_fhd.m3u8` 清流

### 坑 4：央视频 CCTV-1 备用也不行
根因：pid=600001859 同样返回 `_web.m3u8` 加密流
结论：已注释，等待央视频放开清流

### 坑 5：央视主源（tv.cctv.com）偶发黑屏
根因：之前只有 yangshipin 的 m3u8 切 ExoPlayer，央视主源全靠 hls.js 兜底，不稳定
修复：所有非 `_web.m3u8` 流一律切 ExoPlayer

### 坑 6：document.write 跨站脚本被 Chromium 丢弃
修复：onPageStarted 最早期 hook document.write，转成 createElement 异步插入

---

## 7. 操作说明（给最终用户）

| 操作 | 手机触屏 | 遥控器 / TV 按键 |
|------|---------|----------------|
| 切到上一台 | 下滑 | 上键 |
| 切到下一台 | 上滑 | 下键 |
| 打开频道列表 | 单击屏幕任意处 | OK / 确定键 |
| 选频道播放 | 点击列表项 | 上下移动高亮 → 再按 OK/确定 |
| 数字键直跳频道 | 不支持 | 数字键 0-9，3 秒内连按两位数自动跳对应序号 |

---

## 8. 构建

本地构建：
```powershell
.\gradlew.bat assembleDebug
```
产物：`app\build\outputs\apk\debug\app-debug.apk`（可直接安装）

CI 已配置 `.github/workflows/android-build.yml`，**push 到 master 分支自动触发构建**，构建成功后在 GitHub Actions → Run → Artifacts 里下载 `app-debug.apk`（保留 30 天）。

---

## 9. 相关文件清单

- **核心逻辑 99% 都在这里**：[MainActivity.java](file:///d:/badwp/tv1/app/src/main/java/com/example/cctvofficialnavigator/MainActivity.java)
- 频道列表 URL + 顺序：[ChannelCatalog.java](file:///d:/badwp/tv1/app/src/main/java/com/example/cctvofficialnavigator/ChannelCatalog.java)
- UI 布局 XML：[activity_main.xml](file:///d:/badwp/tv1/app/src/main/res/layout/activity_main.xml)
- Manifest：[AndroidManifest.xml](file:///d:/badwp/tv1/app/src/main/AndroidManifest.xml)
- CI 自动构建 APK：[android-build.yml](file:///d:/badwp/tv1/.github/workflows/android-build.yml)

---

> 最后更新 2026/8/2：全频道 ExoPlayer 原生播放（非 `_web.m3u8` 一律切 ExoPlayer），移除 CCTV-3/8（CMG WASM 加密流无法播放），注释 CCTV-1 备用（同原因），20 台稳定运行。
