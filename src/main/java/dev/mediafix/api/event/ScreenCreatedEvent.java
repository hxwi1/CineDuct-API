package dev.mediafix.api.event;

import dev.mediafix.api.ScreenHandle;
import dev.mediafix.api.ScreenInfo;
import net.minecraft.server.level.ServerLevel;

/** 一块屏幕被创建出来（指令或 API 都走这里）。 */
public final class ScreenCreatedEvent extends ScreenEvent {

    public ScreenCreatedEvent(ServerLevel level, ScreenHandle screen, ScreenInfo info) {
        super(level, screen, info);
    }
}
