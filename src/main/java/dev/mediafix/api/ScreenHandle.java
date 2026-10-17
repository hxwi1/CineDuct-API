package dev.mediafix.api;

import net.minecraft.world.phys.Vec3;

import java.util.UUID;

/**
 * 一块屏幕的操作句柄 —— 附属 mod 调整屏幕的**唯一入口**。
 *
 * <p>所有改动方法都会**自动调度到服务端线程**执行，从任意线程调用都安全
 * （实体状态是服务端权威的，客户端算出来的进度由客户端自己对齐，见 {@link ScreenInfo#positionMs()}）。
 *
 * <p>句柄本身很轻：只拿一个 UUID，每次调用按需在世界里找实体。
 * 实体被删除后 {@link #isAlive()} 变 false，后续调用静默无效。
 *
 * <h2>能调的东西一览</h2>
 * <ul>
 *   <li><b>变换</b>：{@link #size}、{@link #setPosition}、{@link #translate}、
 *       {@link #rotation}、{@link #lookAt}</li>
 *   <li><b>播放</b>：{@link #source}、{@link #play}、{@link #pause}、{@link #stop}、
 *       {@link #seek}、{@link #loop}、{@link #quality}</li>
 *   <li><b>声音</b>：{@link #volume}、{@link #range}、{@link #muted}、{@link #audioMode}</li>
 *   <li><b>画面</b>：{@link #alpha}、{@link #brightness}、{@link #fit}、{@link #doubleSided}</li>
 *   <li><b>读回</b>：{@link #info()}</li>
 * </ul>
 */
public interface ScreenHandle {

    UUID id();

    /** 实体是否还在世界里。 */
    boolean isAlive();

    // =====================================================================
    // 播放
    // =====================================================================

    /** 换播放源（重置进度，从头开始）。空串 = 停播并清空源。 */
    void source(String uri);

    void play();

    void pause();

    /** 暂停并回到开头。 */
    void stop();

    /** 跳到指定位置（毫秒）。直播的 seek 无意义（会被引擎忽略）。 */
    void seek(long ms);

    void loop(boolean on);

    /** 清晰度上限：-1 自动（按网速自适应）/ 0 账号内最高 / 否则 B 站 qn（如 80=1080P, 116=1080P60, 120=4K, 127=8K）。 */
    void quality(int qn);

    // =====================================================================
    // 声音
    // =====================================================================

    /** 音量 0~100（还要再乘距离衰减与 MC 主音量）。 */
    void volume(int percent);

    /** 距离衰减范围：{@code min} 内满音量，线性衰减到 {@code max} 处静音。 */
    void range(float min, float max);

    void muted(boolean muted);

    /**
     * 每块屏幕的音频输出形态覆盖（空串 = 跟随全局配置）。
     *
     * <p>{@code auto}（能直通就直通，全景声走这条）/ {@code shared}（系统混音器）/
     * {@code exclusive}（独占 PCM，会独占声卡）/ {@code passthrough}（强制 IEC 61937 直通）。
     *
     * <p>音频形态是"开声卡那一刻"定下来的，对已经在播的会话不会立刻生效：换源或重开会话后生效。
     */
    void audioMode(String mode);

    // =====================================================================
    // 变换
    // =====================================================================

    /** 屏幕尺寸（世界单位，宽×高，各 0.25~64）。 */
    void size(float width, float height);

    /** 直接放到指定位置（**中心点**）。 */
    void setPosition(Vec3 pos);

    /** 相对当前中心点位移。 */
    void translate(double dx, double dy, double dz);

    /** 朝向：yaw 0 = 朝南（+Z）、90 = 朝西；pitch 正数朝下（±90 会被夹住）。 */
    void rotation(float yaw, float pitch);

    /** 让屏幕正面转向某个世界坐标点（例如玩家或讲台）。 */
    void lookAt(Vec3 target);

    // =====================================================================
    // 画面
    // =====================================================================

    /** 不透明度 0.05~1（1 = 完全不透明）。 */
    void alpha(float value);

    /**
     * 亮度倍率 0.05~1（调暗用）。
     *
     * <p>屏幕本身已经是满亮自发光，顶点色上限就是 1.0 —— **没有"更亮"这一档**，
     * 想要更亮请调光影包的曝光/自发光。
     */
    void brightness(float value);

    /** 画面铺法：等比加黑边 / 裁切铺满 / 拉伸。 */
    void fit(ScreenFit fit);

    /** 是否双面可见（true = 背面也画一张镜像）。 */
    void doubleSided(boolean value);

    // =====================================================================
    // 读回 / 生命周期
    // =====================================================================

    /** 当前状态快照；实体已不存在时返回 {@code null}。 */
    ScreenInfo info();

    /** 删除这块屏幕（客户端会回收会话与纹理）。 */
    void remove();
}
