package dev.mediafix.net;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.mediafix.CineDuct;

import java.util.List;

/**
 * 上行回执 {@code cineduct:state}（{@code docs/paper-control-payload.md} §6）。
 *
 * <p>发出去的时机：{@code open} 成功后、状态跳变、出错、{@code list} 请求、以及每 2 秒心跳
 * （只在有 slot 活着时）。**上行是可选能力**：插件不订阅也不影响下行控制。
 *
 * <p>报文里**没有**子进程 pid / UUID（§10）—— 那些是客户端内部实现，服务端不该知道。
 */
public final class CineStateUplink {

    /** 心跳间隔（毫秒）。 */
    private static final long HEARTBEAT_MS = 2000L;

    private static long lastHeartbeatAt;
    private static int lastSeq;
    /** 上一轮各 slot 的状态，用来判断"状态跳变"（只在变化时发，避免每 tick 一包）。 */
    private static String lastStateSignature = "";

    private CineStateUplink() {
    }

    /** 立即发一条（失败只记一次日志，不重试、不影响播放）。 */
    public static void sendNow(String reason) {
        final String json = build(-1, "");
        if (json == null) return;
        send(json, reason);
    }

    /** 发一条错误回执（{@code err} 非空，slots 照常带上）。 */
    public static void sendError(int echoedVersion, String message) {
        final String json = build(echoedVersion, message == null ? "error" : message);
        if (json == null) return;
        send(json, "error");
    }

    /**
     * 每 tick 调一次（客户端主线程）：负责 2 秒心跳与"状态跳变"上报。
     *
     * <p>只看**签名**变化，不做逐帧比较 —— 位置每帧都在动，按位置发就是每帧一包。
     */
    public static void tick() {
        if (!CinePacketConfig.enabled) return;
        if (CineControlHandler.slotCount() == 0) {
            lastStateSignature = "";
            return;
        }
        final long now = System.currentTimeMillis();
        final String signature = signature();
        final boolean changed = !signature.equals(lastStateSignature);
        final boolean heartbeat = now - lastHeartbeatAt >= HEARTBEAT_MS;
        if (!changed && !heartbeat) return;
        lastStateSignature = signature;
        lastHeartbeatAt = now;
        sendNow(changed ? "state-change" : "heartbeat");
    }

    /** 状态签名：不含位置，所以"在正常播放"不会每帧触发。 */
    private static String signature() {
        final StringBuilder sb = new StringBuilder();
        for (CineControlHandler.Snapshot s : CineControlHandler.snapshot()) {
            sb.append(s.slot()).append('=').append(s.state()).append(',')
                    .append(s.live() ? 'L' : '-').append(s.buffering() ? 'B' : '-')
                    .append(s.err() == null || s.err().isEmpty() ? "" : 'E').append(';');
        }
        return sb.toString();
    }

    /** 组 JSON（{@code err} 只有出错/拒绝时才有内容）。 */
    private static String build(int echoedVersion, String err) {
        final List<CineControlHandler.Snapshot> slots = CineControlHandler.snapshot();
        final JsonObject o = new JsonObject();
        o.addProperty("v", CineControlPacket.PROTOCOL);
        o.addProperty("seq", ++lastSeq);
        o.addProperty("ack", CineControlHandler.lastAckSeq());
        final JsonArray list = new JsonArray();
        for (CineControlHandler.Snapshot s : slots) {
            final JsonObject e = new JsonObject();
            e.addProperty("slot", s.slot());
            e.addProperty("state", s.state() == null ? "ERROR" : s.state().name());
            e.addProperty("positionMs", s.positionMs());
            e.addProperty("durationMs", s.durationMs());
            e.addProperty("live", s.live());
            e.addProperty("buffering", s.buffering());
            e.addProperty("transport", s.transport() == null ? "" : s.transport());
            e.addProperty("volume", s.volume());
            e.addProperty("muted", s.muted());
            e.addProperty("err", s.err() == null ? "" : s.err());
            list.add(e);
        }
        o.add("slots", list);
        o.addProperty("err", err == null ? "" : err);
        if (echoedVersion >= 0) o.addProperty("echoV", echoedVersion);
        return o.toString();
    }

    private static void send(String json, String reason) {
        try {
            CineDuctNetwork.sendState(json);
        } catch (Throwable t) {
            CineDuct.LOGGER.debug("[CineDuct] 状态回执发送失败（{}）：{}", reason, t.toString());
        }
    }
}
