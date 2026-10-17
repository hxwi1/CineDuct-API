package dev.mediafix.api;

/** 播放状态（对外稳定枚举；内部引擎状态映射到这里）。 */
public enum PlaybackState {
    /** 正在打开/预缓冲，还没有画面。 */
    LOADING,
    PLAYING,
    PAUSED,
    /** 缓冲见底，音视频一起停住（不是暂停）。 */
    BUFFERING,
    /** 播到片尾。 */
    ENDED,
    ERROR
}
