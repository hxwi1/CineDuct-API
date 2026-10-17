package dev.mediafix.api;

/**
 * 视频画面在屏幕矩形上的铺法（对应 {@code /cineduct screen fit ...}）。
 *
 * <p>屏幕的物理宽高比和视频的宽高比通常不一样（比如 2.0×1.125 的屏放 21:9 的电影），
 * 这里决定"多出来的部分怎么办"。
 */
public enum ScreenFit {

    /**
     * 等比缩放 + 黑边（默认）。
     *
     * <p>整幅画面都在，屏幕边上留黑边；不拉伸、不变形。影院幕布一般用这个。
     */
    LETTERBOX,

    /**
     * 等比放大铺满 + 裁掉溢出。
     *
     * <p>没有黑边，但画面四边会被裁掉一点（像"全屏覆盖"）。适合想让屏幕被画面填满的场合。
     */
    FILL,

    /**
     * 直接拉伸铺满（不等比）。
     *
     * <p>画面会变形 —— 只在"屏幕比例和视频比例本来就接近"或故意要这种效果时用。
     */
    STRETCH;

    /** 同步数据里的整数编号（不要改已有常量的顺序）。 */
    public int id() {
        return ordinal();
    }

    /** 从同步数据还原；非法值回落到 {@link #LETTERBOX}。 */
    public static ScreenFit byId(int id) {
        ScreenFit[] all = values();
        return id >= 0 && id < all.length ? all[id] : LETTERBOX;
    }
}
