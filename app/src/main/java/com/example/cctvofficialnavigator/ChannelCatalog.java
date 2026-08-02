package com.example.cctvofficialnavigator;

import java.util.Arrays;
import java.util.List;

/** Only official CCTV web pages are listed here; this app never stores stream URLs. */
final class ChannelCatalog {
    static final List<Channel> CHANNELS = Arrays.asList(
            new Channel("CCTV-1 综合",              "https://tv.cctv.com/live/cctv1/"),
            // ===== CCTV-1 备用源说明 =====
            // 央视频桌面端(yangshipin.cn/tv/home?pid=xxx)实测结果(2026-08):
            //   只有CCTV-6(pid=600108442)返回 _fhd.m3u8 清流(encrypt=0),ExoPlayer可正常播放。
            //   其他所有频道(CCTV-1/CCTV-2/3/4/5/5+/7-15/CGTN)全部返回 _web.m3u8(encrypt=2,CMG WASM加密流),
            //   这种加密流在Android上无论送给ExoPlayer(绿屏/花屏)还是留在WebView(黑屏)都无法正常播放视频,
            //   只能解出音频(有声音没画面)。所以CCTV-1备用的yangshipin源暂时不可用,已移除。
            //   如果以后央视频放开CCTV-1的清流pid,可以参考CCTV-6的方式重新添加。
            // new Channel("CCTV-1 综合（备用）",    "https://www.yangshipin.cn/tv/home?pid=600001859"),  // 不可用:encrypt=2加密流
            new Channel("CCTV-2 财经",              "https://tv.cctv.com/live/cctv2/"),
            // CCTV-6 在 tv.cctv.com 走 HLSP2P+DRM,Android WebView 黑屏有声音。
            // 改用央视频桌面端独立直播页:yangshipin.cn/tv/home?pid=600108442
            // 桌面UA加载后请求_fhd.m3u8(encrypt=0标准HLS清流,无加密),
            // 被 shouldInterceptRequest 拦截后切 ExoPlayer 原生播放(彻底根治有声音没画面)。
            // 注意:这是目前央视频桌面端唯一返回清流的频道!其他频道pid全部返回encrypt=2加密流,不可用。
            new Channel("CCTV-4 中文国际（亚）",     "https://tv.cctv.com/live/cctv4/"),
            new Channel("CCTV-4 中文国际（欧）",     "https://tv.cctv.com/live/cctveurope/index.shtml"),
            new Channel("CCTV-4 中文国际（美）",     "https://tv.cctv.com/live/cctvamerica/"),
            new Channel("CCTV-5 体育",              "https://tv.cctv.com/live/cctv5/"),
            new Channel("CCTV-5+ 体育赛事",          "https://tv.cctv.com/live/cctv5plus/"),
            new Channel("CCTV-6 电影",              "https://www.yangshipin.cn/tv/home?pid=600108442"),
            new Channel("CCTV-7 国防军事",           "https://tv.cctv.com/live/cctv7/"),
            new Channel("CCTV-9 纪录",              "https://tv.cctv.com/live/cctvjilu/"),
            new Channel("CCTV-10 科教",             "https://tv.cctv.com/live/cctv10/"),
            new Channel("CCTV-11 戏曲",             "https://tv.cctv.com/live/cctv11/"),
            new Channel("CCTV-12 社会与法",          "https://tv.cctv.com/live/cctv12/"),
            new Channel("CCTV-13 新闻",             "https://tv.cctv.com/live/cctv13/"),
            new Channel("CCTV-14 少儿",             "https://tv.cctv.com/live/cctvchild/"),
            new Channel("CCTV-15 音乐",             "https://tv.cctv.com/live/cctv15/"),
            new Channel("CCTV-16 奥林匹克",          "https://tv.cctv.com/live/cctv16/"),
            new Channel("CCTV-17 农业农村",          "https://tv.cctv.com/live/cctv17/"),
            new Channel("广西新闻频道",               "https://tv.gxtv.cn/channel/channelivePlay_9dfd8600075811e9ba67e41f13b60c62.html"),
            new Channel("广西卫视",                   "https://tv.gxtv.cn/channel/channelivePlay_e7a7ab7df9fe11e88bcfe41f13b60c62.html")
    );

    private ChannelCatalog() { }
}
