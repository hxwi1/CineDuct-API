package dev.mediafix.api;

import net.minecraft.world.phys.Vec3;

import java.util.UUID;

/**
 * 一块屏幕的状态快照（服务端权威值）。
 *
 * <p>这是"应该是什么样"，不是"实际播成了什么样" —— 后者见
 * {@code dev.mediafix.api.client.ClientScreenStatus}。
 *
 * @param id          屏幕 UUID
 * @param source      播放源（原始链接；空串 = 无源）
 * @param centerX     中心点 X
 * @param centerY     中心点 Y
 * @param centerZ     中心点 Z
 * @param yaw         朝向（玩家视角习惯：0 = 朝南 +Z，90 = 朝西）
 * @param pitch       俯仰（正数 = 朝下）
 * @param width       宽（世界单位）
 * @param height      高（世界单位）
 * @param aspect      宽高比（宽/高）
 * @param paused      是否暂停
 * @param loop        是否循环
 * @param muted       是否静音
 * @param volume      音量（0~100，未计距离衰减与主音量）
 * @param rangeMin    距离衰减起点（世界单位）
 * @param rangeMax    距离衰减终点（世界单位）
 * @param positionMs  **此刻**按服务端锚点算出的位置（毫秒）——多端一致
 * @param qualityCap  清晰度上限（-1 自动 / 0 最高 / 否则 B 站 qn）
 * @param alpha       不透明度（0.05~1）
 * @param brightness  亮度倍率（0.05~1，只能调暗）
 * @param fit         画面铺法
 * @param doubleSided 是否双面可见（背面镜像）
 * @param audioMode   每屏音频输出形态覆盖（空串 = 跟随全局配置）
 */
public record ScreenInfo(UUID id, String source,
                         double centerX, double centerY, double centerZ, float yaw, float pitch,
                         float width, float height, float aspect,
                         boolean paused, boolean loop, boolean muted, int volume,
                         float rangeMin, float rangeMax, long positionMs, int qualityCap,
                         float alpha, float brightness, ScreenFit fit, boolean doubleSided,
                         String audioMode) {

    /** 中心点。 */
    public Vec3 center() {
        return new Vec3(this.centerX, this.centerY, this.centerZ);
    }

    /** 是否配置了每屏音频形态覆盖。 */
    public boolean hasAudioModeOverride() {
        return this.audioMode != null && !this.audioMode.isEmpty();
    }
}
