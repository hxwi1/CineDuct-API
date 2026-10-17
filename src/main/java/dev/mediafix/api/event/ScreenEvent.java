package dev.mediafix.api.event;

import dev.mediafix.api.ScreenHandle;
import dev.mediafix.api.ScreenInfo;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.bus.api.Event;

/**
 * 屏幕相关事件的共同基类（都发在服务端侧的游戏事件总线上）。
 *
 * <p>附属 mod 用 {@code NeoForge.EVENT_BUS.addListener(...)} 订阅即可。
 */
public abstract class ScreenEvent extends Event {

    private final ServerLevel level;
    private final ScreenHandle screen;
    private final ScreenInfo info;

    protected ScreenEvent(ServerLevel level, ScreenHandle screen, ScreenInfo info) {
        this.level = level;
        this.screen = screen;
        this.info = info;
    }

    public ServerLevel level() {
        return this.level;
    }

    public ScreenHandle screen() {
        return this.screen;
    }

    /** 事件发生时的状态快照。 */
    public ScreenInfo info() {
        return this.info;
    }
}
