# Debug Session: cctv6-yangshipin-issue
- **Status**: [OPEN]
- **Issue**: CCTV-6（央视频 yangshipin 桌面端）在 WebView 中显示为版权页 footer + 黑屏，无法全屏播放；其他频道（CCTV-5+/CCTV-1/广西台等）全屏正常。
- **Debug Server**: http://192.168.1.4:7777/event
- **Log File**: .dbg/trae-debug-log-cctv6-yangshipin-issue.ndjson

## Reproduction Steps
1. 打开 App，切到 CCTV-6（yangshipin 桌面端）。
2. 等待页面加载完成（约 3-10 秒）。
3. 观察到上半屏显示「关于央视频 | 服务协议 | 中央广播电视总台...」版权页，下半屏黑屏。
4. 其他频道（如 CCTV-5+）全屏播放正常。

## Hypotheses & Verification
| ID | Hypothesis | Likelihood | Effort | Evidence |
|----|------------|------------|--------|----------|
| A | yangshipin 页面被 WebView 自动滚动到页面底部（版权页位置），播放器在 DOM 上方但视口未显示 | High | Low | Pending |
| B | 播放器内部使用了新的 Vue scoped DOM（.container/.y-full/.play.play2），现有 JS/CSS 未匹配到控制按钮，导致未触发全屏/播放 | High | Low | Pending |
| C | 当前返回的 CCTV-6 资源为付费/限制内容，.video-status-tip / .volume-muted-tip 遮挡或替换了 video 元素 | Med | Low | Pending |
| D | WebView 的桌面 UA / Header 设置被服务器识别为异常，实际未返回可播放的直播流（仅返回版权页/空页面） | Low | Med | Pending |
| E | video 元素存在但尺寸为 0（clientHeight/offsetHeight 为 0），或 video.js 初始化失败，导致画面未渲染 | Med | Med | Pending |

## Log Evidence
[待收集]

## Verification Conclusion
[待填写]
