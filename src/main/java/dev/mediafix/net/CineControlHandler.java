package dev.mediafix.net;

import dev.mediafix.CineDuct;
import dev.mediafix.api.PlaybackState;
import dev.mediafix.api.player.CinePlayer;
import dev.mediafix.api.player.CinePlayerApi;
import dev.mediafix.api.player.PlayerOptions;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ServerData;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 把 {@code cineduct:control} 的 op 落到 {@link CinePlayer} 上（{@code docs/paper-control-payload.md} §4）。
 *
 * <p><b>只依赖公开 API</b>（{@code dev.mediafix.api.player}），不碰播放内部实现 ——
 * 于是这条通道出问题不会连累正常播放，反过来播放改动也不会悄悄改变协议语义。
 *
 * <p><b>线程</b>：{@link #handleOnClient(String)} 必须在**客户端主线程**调用
 * （{@code Minecraft} 与纹理侧的东西都在那边；{@link CinePlayerApi#open} 本身非阻塞）。
 *
 * <p><b>生命周期</b>：这里维护的是"服务端创建的 slot"，退服/换服一律清空（§7）；
 * 玩家自己开的播放器不在这张表里，不受影响。
 */
public final class CineControlHandler {

    /** slot → 播放器。用 LinkedHashMap 保持"下发顺序"，便于 `list` 与排查。 */
    private static final Map<String, Slot> SLOTS = new LinkedHashMap<>();

    /** 最近一次成功处理的 {@code seq}（回执里的 ack）。-1 = 服务端没带 seq。 */
    private static volatile long lastAckSeq = -1L;

    /** 一条 slot 的簿记：播放器 + 原始链接 + 上次下发的比例（用于 sync 判断"要不要重开"）。 */
    private static final class Slot {
        CinePlayer player;
        String uri;
        float aspect;
    }

    private CineControlHandler() {
    }

    /** 当前由服务端控制的 slot 数量（诊断/心跳判断用）。 */
    public static synchronized int slotCount() {
        return SLOTS.size();
    }

    public static long lastAckSeq() {
        return lastAckSeq;
    }

    /**
     * 处理一条报文（客户端主线程）。
     *
     * <p>闸门 → 解析 → 分派，任何一步失败都只回错误、不抛异常（外部输入不能把客户端搞崩）。
     */
    public static void handleOnClient(String json) {
        final String addr = serverAddress();

        final CineControlPacket packet;
        try {
            packet = CineControlPacket.parse(json);
        } catch (CineControlPacket.Rejected rejected) {
            CineDuct.LOGGER.info("[CineDuct] 服务端控制报文被拒：{}", rejected.getMessage());
            return;
        }

        final CineGate.Verdict verdict = CineGate.check(packet, addr);
        if (!verdict.allow()) {
            CineGate.logDeny(packet.op, packet.slot, verdict.reason());
            return;
        }

        if (!CinePlayerApi.available()) {
            return;
        }

        try {
            apply(packet);
            if (packet.seq > lastAckSeq) lastAckSeq = packet.seq;
        } catch (Throwable t) {
            CineDuct.LOGGER.warn("[CineDuct] 执行服务端控制失败（op={} slot={}）", packet.op, packet.slot, t);
            return;
        }
    }

    /**
     * 本地调试入口（{@code /cineduct packet test <json>}）：**跳过闸门**，直接把报文喂给处理器。
     *
     * <p>存在的意义：P0 的验收不该依赖"先起一个 Paper 服务器"。它走的仍然是同一条
     * 解析 → 分派 → 回执路径，所以能验证 op 语义；闸门本身要用 {@code /cineduct packet status}
     * 看判定链、或者起服真测。
     */
    public static void handleLocal(String json) {
        final CineControlPacket packet;
        try {
            packet = CineControlPacket.parse(json);
        } catch (CineControlPacket.Rejected rejected) {
            CineDuct.LOGGER.info("[CineDuct] 本地测试报文被拒：{}", rejected.getMessage());
            return;
        }
        try {
            apply(packet);
            if (packet.seq > lastAckSeq) lastAckSeq = packet.seq;
        } catch (Throwable t) {
            CineDuct.LOGGER.warn("[CineDuct] 本地测试执行失败（op={} slot={}）", packet.op, packet.slot, t);
            return;
        }
    }

    /** 真正执行。**幂等**：重复下发同一 op 结果一致（§3 的通用规则）。 */
    private static void apply(CineControlPacket p) throws Exception {
        /*
         * 先问虚拟屏：它的播放器是"距离门控"建的，不在这张 slot 表里。
         * 不先问的话，pause/seek/volume/close 对虚拟屏就是空操作（指令回"已发送"，画面不动）。
         */
        if (dev.mediafix.client.SlotPlayback.handleControl(p.op, p.slot, p.paused, p.positionMs,
                p.volume, p.muted)) {
            return;
        }
        switch (p.op) {
            case "open" -> open(p);
            case "close" -> close(p.slot, "服务端关闭");
            case "pause" -> {
                final CinePlayer pl = player(p.slot);
                if (pl != null && p.paused != null) pl.pause(p.paused);
            }
            case "seek" -> {
                final CinePlayer pl = player(p.slot);
                if (pl != null && p.positionMs != null && !pl.isLive()) pl.seek(p.positionMs);
            }
            case "volume" -> {
                final CinePlayer pl = player(p.slot);
                if (pl != null && p.volume != null) pl.setVolumePercent(p.volume);
            }
            case "mute" -> {
                final CinePlayer pl = player(p.slot);
                if (pl != null && p.muted != null) pl.setMuted(p.muted);
            }
            case "repeat" -> {
                final CinePlayer pl = player(p.slot);
                if (pl != null && p.repeat != null) pl.setRepeat(p.repeat);
            }
            case "aspect" -> {
                final Slot s = SLOTS.get(p.slot);
                if (s != null && s.player != null && p.aspect != null) {
                    s.player.setDisplayAspect(p.aspect);
                    s.aspect = p.aspect;
                }
            }
            case "rebuild" -> {
                final CinePlayer pl = player(p.slot);
                if (pl != null) pl.rebuild();
            }
            case "list" -> {
                // 无副作用：回执由调用方统一发（sendNow）
            }
            case "sync" -> sync(p);
            /*
             * 虚拟屏（docs/paper-control-payload.md §8）：
             *   place  = 几何（四个角点），客户端只负责画；
             *   anchor = 进度权威，客户端按它算"现在该播到哪"，进范围内才真的开播。
             * 两个都不直接开播放器 —— 开播由距离门控决定（SlotPlayback.tick）。
             */
            case "place" -> dev.mediafix.client.SlotPlayback.applyPlace(
                    dev.mediafix.client.SlotScreen.parse(p.slot, p.raw));
            case "anchor" -> dev.mediafix.client.SlotPlayback.applyAnchor(
                    p.slot, p.url, p.positionMs == null ? 0L : p.positionMs,
                    p.serverTimeMs == null ? 0L : p.serverTimeMs,
                    p.paused != null && p.paused, p.live != null && p.live);
            default -> throw new IllegalStateException("未实现的 op: " + p.op);
        }
    }

    /** {@code open}：同一 slot 已存在则先关再开（换源语义），避免两条链同时出声。 */
    private static void open(CineControlPacket p) {
        final String uri = p.url;
        if (uri == null || uri.isBlank()) {
            throw new IllegalArgumentException("open 缺少 url");
        }
        Slot slot = SLOTS.get(p.slot);
        if (slot != null && slot.player != null && uri.equals(slot.uri)) {
            /*
             * 已经播的就是这个链接：不重开（幂等），但把可变的控制量应用上去。
             * 重开一次会丢缓冲、画面闪一下，服务端重连时很容易反复下发同一条 open。
             */
            if (p.paused != null) slot.player.pause(p.paused);
            if (p.volume != null) slot.player.setVolumePercent(p.volume);
            if (p.muted != null) slot.player.setMuted(p.muted);
            if (p.repeat != null) slot.player.setRepeat(p.repeat);
            if (p.aspect != null) {
                slot.player.setDisplayAspect(p.aspect);
                slot.aspect = p.aspect;
            }
            return;
        }
        close(p.slot, "换源");

        final PlayerOptions.Builder opt = PlayerOptions.builder();
        if (p.volume != null) opt.volume(p.volume);
        if (p.muted != null) opt.muted(p.muted);
        if (p.repeat != null) opt.loop(p.repeat);
        if (p.paused != null) opt.paused(p.paused);
        if (p.positionMs != null) opt.startAt(p.positionMs);
        if (p.aspect != null) opt.displayAspect(p.aspect);

        final CinePlayer player = CinePlayerApi.get().open(uri, opt.build());
        final Slot created = new Slot();
        created.player = player;
        created.uri = uri;
        created.aspect = p.aspect == null ? 0f : p.aspect;
        SLOTS.put(p.slot, created);
        CineDuct.LOGGER.info("[CineDuct] 服务端开播 slot={} → {}（player #{}）",
                p.slot, CineGate.safeUrl(uri), player.id());
    }

    /** {@code sync}：把客户端收敛到给定集合 —— 多出来的关掉，给出的按 open 处理。 */
    private static void sync(CineControlPacket p) throws Exception {
        final List<String> wanted = new ArrayList<>(p.entries.size());
        for (CineControlPacket.Entry e : p.entries) {
            wanted.add(e.slot());
            open(p.asOpen(e));
        }
        final Iterator<Map.Entry<String, Slot>> it = SLOTS.entrySet().iterator();
        while (it.hasNext()) {
            final Map.Entry<String, Slot> e = it.next();
            if (wanted.contains(e.getKey())) continue;
            it.remove();
            final Slot s = e.getValue();
            if (s != null && s.player != null) s.player.close();
            CineDuct.LOGGER.info("[CineDuct] 服务端 sync：关闭多余 slot={}", e.getKey());
        }
    }

    /** 关闭一个 slot（不存在 = 无操作）。 */
    private static void close(String slot, String why) {
        if (slot == null) return;
        final Slot s = SLOTS.remove(slot);
        if (s == null || s.player == null) return;
        s.player.close();
        CineDuct.LOGGER.info("[CineDuct] 关闭服务端 slot={}（{}）", slot, why);
    }

    /** 退服 / 换服 / 模组重载：把所有服务端 slot 关掉（§7）。 */
    public static synchronized void closeAll(String why) {
        if (SLOTS.isEmpty()) return;
        final int n = SLOTS.size();
        for (Slot s : SLOTS.values()) {
            if (s.player != null) s.player.close();
        }
        SLOTS.clear();
        CineGate.resetWindow();
        CineDuct.LOGGER.info("[CineDuct] 已清理 {} 个服务端播放（{}）", n, why);
    }

    private static CinePlayer player(String slot) {
        final Slot s = SLOTS.get(slot);
        return s == null ? null : s.player;
    }

    /** 当前所有 slot 的快照（回执用）。 */
    public static synchronized List<Snapshot> snapshot() {
        final List<Snapshot> out = new ArrayList<>(SLOTS.size());
        for (Map.Entry<String, Slot> e : SLOTS.entrySet()) {
            final CinePlayer p = e.getValue().player;
            if (p == null) continue;
            out.add(new Snapshot(e.getKey(), p.state(), p.positionMs(), p.durationMs(), p.isLive(),
                    p.isBuffering(), p.transport(), p.volumePercent(), p.isMuted(), p.lastError()));
        }
        return out;
    }

    /** 一条 slot 的状态快照（{@code cineduct:state} 的 slots[] 元素）。 */
    public record Snapshot(String slot, PlaybackState state, long positionMs, long durationMs,
                           boolean live, boolean buffering, String transport, int volume,
                           boolean muted, String err) {
    }

    /** 当前服务器地址（单人游戏返回 {@code singleplayer}）。 */
    private static String serverAddress() {
        final Minecraft mc = Minecraft.getInstance();
        final ServerData data = mc.getCurrentServer();
        if (data == null) return mc.hasSingleplayerServer() ? "singleplayer" : "";
        return data.ip == null ? "" : data.ip;
    }

    // =====================================================================
    // 上行：一次性的时长上报
    // =====================================================================

    /** 已经上报过时长的 (slot, url)。 */
    private static final java.util.Set<String> REPORTED =
            java.util.Collections.newSetFromMap(new java.util.concurrent.ConcurrentHashMap<>());

    /**
     * 客户端 → 服务端：时长上报（JSON 文本，裸字节，与下行同样的编解码约定）。
     *
     * <p>payload 放在这个类里而不是单独一个类：同包两个类互相引用时 javac 曾报
     * "找不到符号 / 程序包不存在"（排查成本很高），而这里本来就和处理链路同源，合并没损失。
     */
    public record Payload(String json)
            implements net.minecraft.network.protocol.common.custom.CustomPacketPayload {

        public static final net.minecraft.network.protocol.common.custom.CustomPacketPayload.Type<Payload> TYPE =
                new net.minecraft.network.protocol.common.custom.CustomPacketPayload.Type<>(
                        net.minecraft.resources.ResourceLocation.fromNamespaceAndPath(
                                dev.mediafix.CineDuct.MODID, "state"));

        public static final net.minecraft.network.codec.StreamCodec<
                net.minecraft.network.RegistryFriendlyByteBuf, Payload> STREAM_CODEC =
                net.minecraft.network.codec.StreamCodec.of((buf, value) -> {
                    final byte[] bytes = (value.json() == null ? "" : value.json())
                            .getBytes(java.nio.charset.StandardCharsets.UTF_8);
                    if (bytes.length > 30 * 1024) {
                        throw new IllegalArgumentException("上报过大: " + bytes.length);
                    }
                    buf.writeBytes(bytes);
                }, buf -> {
                    final int n = buf.readableBytes();
                    if (n <= 0 || n > 30 * 1024) throw new IllegalArgumentException("上报长度非法: " + n);
                    final byte[] bytes = new byte[n];
                    buf.readBytes(bytes);
                    return new Payload(new String(bytes, java.nio.charset.StandardCharsets.UTF_8));
                });

        @Override
        public net.minecraft.network.protocol.common.custom.CustomPacketPayload.Type<? extends
                net.minecraft.network.protocol.common.custom.CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /**
     * 上报一条播放的实际时长（**只报一次**）。
     *
     * <p>服务端要自动推进播放列表就必须知道"这条放完了没有"，而时长只有真放起来的客户端才知道
     * —— 它天然带着"非大会员只能试看 6 分钟"这类截断，服务端查 API 查不出来。
     */
    public static void reportDuration(String slot, String url, long durationMs, boolean live) {
        if (slot == null || url == null || url.isEmpty()) return;
        if (!live && durationMs <= 0) return;                 // 还没拿到时长：下一帧再报
        if (!REPORTED.add(slot + '\u0000' + url)) return;      // 已经报过
        final String json = "{"
                + "\"v\":" + CineControlPacket.PROTOCOL
                + ",\"op\":\"duration\""
                + ",\"slot\":\"" + CineControlPacket.escapeJson(slot) + "\""
                + ",\"url\":\"" + CineControlPacket.escapeJson(url) + "\""
                + ",\"durationMs\":" + Math.max(0L, durationMs)
                + ",\"live\":" + live
                + "}";
        try {
            net.neoforged.neoforge.network.PacketDistributor.sendToServer(new Payload(json));
        } catch (Throwable t) {
            REPORTED.remove(slot + '\u0000' + url);
        }
    }

    /** 退服/换服：清掉"已上报"记录（换服后同一个视频要重新告诉新服务端）。 */
    public static void resetReports() {
        REPORTED.clear();
    }
}

