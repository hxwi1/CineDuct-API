package dev.mediafix.api.event;

import dev.mediafix.api.ScreenHandle;
import dev.mediafix.api.ScreenInfo;
import net.minecraft.server.level.ServerLevel;

/** 一块屏幕被删除（拆掉/指令/API）。 */
public final class ScreenRemovedEvent extends ScreenEvent {

    public ScreenRemovedEvent(ServerLevel level, ScreenHandle screen, ScreenInfo info) {
        super(level, screen, info);
    }
}
