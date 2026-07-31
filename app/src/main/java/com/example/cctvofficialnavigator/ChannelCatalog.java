package com.example.cctvofficialnavigator;

/**
 * 央视官方直播频道目录。
 *
 * 合规说明:
 * - 仅保存央视官网"直播大全"页面的入口 URL,不保存或提取任何直播流地址;
 * - 实际播放、登录、鉴权、广告与内容规则由央视官方网页控制;
 * - 调整时务必保持为官方域名(tv.cctv.com),不要替换为第三方代理/解析地址。
 *
 * 频道顺序即为遥控器"上/下"键循环切换顺序,启动默认加载第 0 项(CCTV-9 纪录)。
 */
public final class ChannelCatalog {

    /** 单个频道的展示信息。 */
    public static final class Channel {
        public final String name;   // 频道名(用于底部提示)
        public final String url;    // 央视官方直播页面 URL

        public Channel(String name, String url) {
            this.name = name;
            this.url = url;
        }
    }

    private ChannelCatalog() { }

    /**
     * 央视官网"直播大全"展示的 20 个独立直播频道
     * (含 CCTV-4 亚洲、欧洲、美洲三个版本)。
     */
    public static final Channel[] CHANNELS = new Channel[] {
            new Channel("CCTV-1 综合",   "https://tv.cctv.com/live/cctv1/"),
            new Channel("CCTV-2 财经",   "https://tv.cctv.com/live/cctv2/"),
            new Channel("CCTV-3 综艺",   "https://tv.cctv.com/live/cctv3/"),
            new Channel("CCTV-4 中文国际(亚洲)", "https://tv.cctv.com/live/cctv4/"),
            new Channel("CCTV-4 中文国际(欧洲)", "https://tv.cctv.com/live/cctveurope/"),
            new Channel("CCTV-4 中文国际(美洲)", "https://tv.cctv.com/live/cctvamerica/"),
            new Channel("CCTV-5 体育",   "https://tv.cctv.com/live/cctv5/"),
            new Channel("CCTV-5+ 体育赛事", "https://tv.cctv.com/live/cctv5plus/"),
            new Channel("CCTV-6 电影",   "https://tv.cctv.com/live/cctv6/"),
            new Channel("CCTV-7 国防军事", "https://tv.cctv.com/live/cctv7/"),
            new Channel("CCTV-8 电视剧", "https://tv.cctv.com/live/cctv8/"),
            new Channel("CCTV-9 纪录",   "https://tv.cctv.com/live/cctv9/"),
            new Channel("CCTV-10 科教",  "https://tv.cctv.com/live/cctv10/"),
            new Channel("CCTV-11 戏曲",  "https://tv.cctv.com/live/cctv11/"),
            new Channel("CCTV-12 社会与法", "https://tv.cctv.com/live/cctv12/"),
            new Channel("CCTV-13 新闻",  "https://tv.cctv.com/live/cctv13/"),
            new Channel("CCTV-14 少儿",  "https://tv.cctv.com/live/cctv14/"),
            new Channel("CCTV-15 音乐",  "https://tv.cctv.com/live/cctv15/"),
            new Channel("CCTV-16 奥林匹克", "https://tv.cctv.com/live/cctv16/"),
            new Channel("CCTV-17 农业农村", "https://tv.cctv.com/live/cctv17/"),
    };

    /** 启动默认频道索引(CCTV-9 纪录)。 */
    public static final int DEFAULT_INDEX = 11;

    public static int size() {
        return CHANNELS.length;
    }

    /** 循环安全地获取下一个索引(向上=前一个,向下=后一个)。 */
    public static int nextIndex(int current, boolean forward) {
        int n = CHANNELS.length;
        if (n <= 0) return 0;
        int delta = forward ? 1 : -1;
        int next = ((current + delta) % n + n) % n;
        return next;
    }
}
