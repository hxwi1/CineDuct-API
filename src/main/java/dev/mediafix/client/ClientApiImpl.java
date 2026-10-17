package dev.mediafix.client;

import dev.mediafix.api.client.ClientScreenStatus;
import dev.mediafix.api.client.CineDuctClientApi;
import dev.mediafix.gpu.GpuContext;
import dev.mediafix.host.HostSession;
import dev.mediafix.player.MediaSession;
import dev.mediafix.player.Sessions;
import dev.mediafix.screen.ScreenEntity;
import net.minecraft.client.Minecraft;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** {@link CineDuctClientApi} 的实现（内部类）。 */
public final class ClientApiImpl implements CineDuctClientApi {

    @Override
    public Optional<ClientScreenStatus> status(UUID screenId) {
        final Minecraft mc = Minecraft.getInstance();
        if (mc == null || mc.level == null || screenId == null) return Optional.empty();
        for (net.minecraft.world.entity.Entity entity : mc.level.entitiesForRendering()) {
            if (!(entity instanceof ScreenEntity screen) || !screen.getUUID().equals(screenId)) continue;
            final MediaSession session = Sessions.of(ScreenClientState.sessionIdOf(screen.getId()));
            if (session == null) return Optional.empty();
            final HostSession host = session.hostSession();
            return Optional.of(new ClientScreenStatus(
                    screenId,
                    session.state(),
                    session.timeMs(),
                    session.durationMs(),
                    session.transportId(),
                    session.isLive(),
                    host != null ? host.publishedFrames() : 0L,
                    host != null ? host.droppedFrames() : 0L,
                    host != null ? host.lastAcquireUs() : 0,
                    session.lastError() == null ? "" : session.lastError(),
                    session.audioModeName(),
                    session.volumeLocked(),
                    host != null ? host.width() : 0,
                    host != null ? host.height() : 0,
                    session.liveLatencyMs(),
                    host != null ? spatialText(host) : "",
                    host != null ? host.audioDeviceName() : ""));
        }
        return Optional.empty();
    }

    /**
     * 空间音效状态（0 支持未启用 / 1 已启用 / 2 查不到 / 3 端点不支持）+ 格式名，合成一句人话。
     *
     * <p>映射关系与子进程 {@code audio_device.cpp} 的枚举一致，见 {@code HostSession.spatialAudioState()}。
     */
    private static String spatialText(HostSession host) {
        final int state = host.spatialAudioState();
        final String format = host.spatialAudioFormat();
        final String label = switch (state) {
            case 0 -> "端点支持空间音效，当前未启用";
            case 1 -> "空间音效已启用";
            case 3 -> "端点不支持 Windows 空间音效";
            default -> "空间音效状态未知";
        };
        return format == null || format.isBlank() ? label : (label + "（" + format + "）");
    }

    @Override
    public List<UUID> activeScreens() {
        final Minecraft mc = Minecraft.getInstance();
        final List<UUID> out = new ArrayList<>();
        if (mc == null || mc.level == null) return out;
        for (int entityId : ScreenClientState.knownScreenIds()) {
            if (mc.level.getEntity(entityId) instanceof ScreenEntity screen) {
                out.add(screen.getUUID());
            }
        }
        return out;
    }

    @Override
    public boolean zeroCopyAvailable() {
        return GpuContext.available();
    }

    @Override
    public String transportDiagnostics() {
        final StringBuilder sb = new StringBuilder();
        sb.append("零拷贝=").append(GpuContext.available() ? "可用" : "不可用");
        if (GpuContext.available()) {
            sb.append(" 适配器=").append(GpuContext.adapterName());
        } else {
            sb.append("（").append(GpuContext.reason()).append("）");
        }
        sb.append(" | 会话数=").append(Sessions.count());
        return sb.toString();
    }

    @Override
    public String shaderDiagnostics() {
        return IrisCompat.diagnostics();
    }

    @Override
    public boolean shadersInUse() {
        return IrisCompat.shadersInUse();
    }

    @Override
    public boolean noticesEnabled() {
        return dev.mediafix.ui.Notice.enabled();
    }

    @Override
    public boolean setNoticesEnabled(boolean enabled) {
        return dev.mediafix.ui.Notice.setEnabled(enabled);
    }
}
