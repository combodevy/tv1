package com.example.cctvofficialnavigator;

import java.util.Arrays;
import java.util.List;

/** Only official CCTV web pages are listed here; this app never stores stream URLs. */
final class ChannelCatalog {
    static final List<Channel> CHANNELS = Arrays.asList(
            new Channel("CCTV-9 纪录", "https://tv.cctv.com/live/cctvjilu/"),
            new Channel("CCTV-1 综合", "https://tv.cctv.com/live/cctv1/"),
            new Channel("CCTV-2 财经", "https://tv.cctv.com/live/cctv2/"),
            new Channel("CCTV-3 综艺", "https://tv.cctv.com/live/cctv3/m/index.shtml"),
            new Channel("CCTV-4 中文国际（亚）", "https://tv.cctv.com/live/cctv4/"),
            new Channel("CCTV-4 中文国际（欧）", "https://tv.cctv.com/live/cctveurope/index.shtml"),
            new Channel("CCTV-4 中文国际（美）", "https://tv.cctv.com/live/cctvamerica/"),
            new Channel("CCTV-5 体育", "https://tv.cctv.com/live/cctv5/"),
            new Channel("CCTV-5+ 体育赛事", "https://tv.cctv.com/live/cctv5plus/"),
            new Channel("CCTV-6 电影", "https://tv.cctv.com/live/cctv6/m/index.shtml"),
            new Channel("CCTV-7 国防军事", "https://tv.cctv.com/live/cctv7/"),
            new Channel("CCTV-8 电视剧", "https://tv.cctv.com/live/cctv8/m/index.shtml"),
            new Channel("CCTV-10 科教", "https://tv.cctv.com/live/cctv10/"),
            new Channel("CCTV-11 戏曲", "https://tv.cctv.com/live/cctv11/"),
            new Channel("CCTV-12 社会与法", "https://tv.cctv.com/live/cctv12/"),
            new Channel("CCTV-13 新闻", "https://tv.cctv.com/live/cctv13/"),
            new Channel("CCTV-14 少儿", "https://tv.cctv.com/live/cctvchild/"),
            new Channel("CCTV-15 音乐", "https://tv.cctv.com/live/cctv15/"),
            new Channel("CCTV-16 奥林匹克", "https://tv.cctv.com/live/cctv16/"),
            new Channel("CCTV-17 农业农村", "https://tv.cctv.com/live/cctv17/")
    );

    private ChannelCatalog() { }
}
