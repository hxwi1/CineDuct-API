package dev.mediafix.api.event;

import dev.mediafix.api.ScreenHandle;
import dev.mediafix.api.ScreenInfo;
import net.minecraft.server.level.ServerLevel;

/** 屏幕的播放源被换掉（进度同时被重置）。 */
public final class ScreenSourceChangedEvent extends ScreenEvent {

    private final String oldSource;
    private final String newSource;

    public ScreenSourceChangedEvent(ServerLevel level, ScreenHandle screen, ScreenInfo info,
                                    String oldSource, String newSource) {
        super(level, screen, info);
        this.oldSource = oldSource;
        this.newSource = newSource;
    }

    public String oldSource() {
        return this.oldSource;
    }

    public String newSource() {
        return this.newSource;
    }
}
