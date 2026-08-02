package com.example.cctvofficialnavigator;

import java.util.Arrays;
import java.util.List;

/** Only official CCTV web pages are listed here; this app never stores stream URLs. */
final class ChannelCatalog {
    static final List<Channel> CHANNELS = Arrays.asList(
            new Channel("CCTV-1 综合",              "https://tv.cctv.com/live/cctv1/"),
            new Channel("CCTV-2 财经",              "https://tv.cctv.com/live/cctv2/"),
            // CCTV-6 在 tv.cctv.com 走 HLSP2P+DRM,Android WebView 黑屏有声音。
            // 改用央视频桌面端独立直播页:yangshipin.cn/tv/home?pid=600108442
            // 桌面UA加载后走标准HLS.js,请求_fhd.m3u8(标准HLS流,无加密),
            // 被 shouldInterceptRequest 拦截后切 ExoPlayer 原生播放(彻底根治有声音没画面)。
            // 注意:央视频页面分两种流:
            //   _fhd.m3u8 → 标准HLS清流,无加密,ExoPlayer可播(CCTV-6属于这一类)
            //   _web.m3u8 → CMG WASM加密流,需WASM解密+WebGL渲染,留在WebView播放(CCTV-1备用/CCTV-3/8属于这一类)
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
