package dev.cineduct.net;

import dev.mediafix.CineDuct;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

import java.nio.charset.StandardCharsets;

/**
 * 服务端控制通道（单向下行，{@code docs/paper-control-payload.md}）。
 *
 * <p><b>这个通道只有下行</b>：服务端广播"这块屏在哪、放什么、从哪一刻起播"，
 * 客户端收下就够 —— 播放进度由服务端用"锚点 + 流逝时间"自己算，客户端**不需要回传任何数据**
 * （省掉一条上行通道，也省掉"客户端回报不可信"这类问题）。
 *
 * <h2>两个关键实现事实（都是踩过的坑，别改）</h2>
 *
 * <p><b>① 必须注册成 {@code optional()}</b>：NeoForge 在连到**非 NeoForge 服务器**（Paper / 原版）时
 * 会走 {@code initializeOtherConnection}，用"服务端没有任何通道"去协商客户端注册表 ——
 * 只要有一个**非 optional** 的通道，客户端会直接断开连接。
 *
 * <p><b>② 编解码必须是"裸字节"</b>：Paper 插件的 {@code sendPluginMessage} 发的是**原样 UTF-8 字节**，
 * 没有长度前缀。用 {@code ByteBufCodecs.STRING_UTF8} 会写出 VarInt 长度前缀，到对面就解不出来。
 */
@EventBusSubscriber(modid = CineDuct.MODID, bus = EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public final class CineDuctNetwork {

    /** 单包上限：Bukkit 自定义通道约 32 KB，这里留一点余量。 */
    private static final int MAX_BYTES = 30 * 1024;

    private CineDuctNetwork() {
    }

    /** 服务端 → 客户端：一条控制报文（JSON 文本）。 */
    public record Control(String json) implements CustomPacketPayload {
        public static final CustomPacketPayload.Type<Control> TYPE =
                new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(CineDuct.MODID, "control"));

        public static final StreamCodec<RegistryFriendlyByteBuf, Control> STREAM_CODEC =
                StreamCodec.of((buf, value) -> writeRaw(buf, value.json()),
                        buf -> new Control(readRaw(buf)));

        @Override
        public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** 裸字节写（不带长度前缀：对面可能是 Paper 插件，按原样 UTF-8 读）。 */
    private static void writeRaw(RegistryFriendlyByteBuf buf, String text) {
        final byte[] bytes = (text == null ? "" : text).getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_BYTES) {
            throw new IllegalArgumentException("payload 过大: " + bytes.length + " 字节");
        }
        buf.writeBytes(bytes);
    }

    /** 读到 buffer 末尾；超限直接拒绝（外部输入不能让我们分配任意大小）。 */
    private static String readRaw(RegistryFriendlyByteBuf buf) {
        final int n = buf.readableBytes();
        if (n <= 0 || n > MAX_BYTES) {
            throw new IllegalArgumentException("payload 长度非法: " + n);
        }
        final byte[] bytes = new byte[n];
        buf.readBytes(bytes);
        return new String(bytes, StandardCharsets.UTF_8);
    }

    @SubscribeEvent
    public static void register(RegisterPayloadHandlersEvent event) {
        final PayloadRegistrar registrar = event.registrar("1").optional();
        registrar.playToClient(Control.TYPE, Control.STREAM_CODEC, (payload, context) ->
                context.enqueueWork(() -> dev.mediafix.net.CineControlHandler.handleOnClient(payload.json())));
        CineDuct.LOGGER.info("[CineDuct] 服务端控制通道已注册（cineduct:control，optional，单向）");
    }
}
