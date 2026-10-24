package dev.mediafix.net;

/**
 * 上行回执的**占位实现**：什么都不发。
 *
 * <p>通道语义已改成**单向下行**（服务端广播锚点、自己算进度，客户端不回传任何数据），
 * 但 {@code CineControlHandler} 里还留着"被拒/失败时回报一条"的调用点 —— 那些调用现在没有意义，
 * 却也不值得为了删它们去动那一整段逻辑（改坏的排查成本更高）。
 *
 * <p>所以这里保留同名同签名的空实现：**零网络流量**，调用点不用改。将来若要重新引入上行
 * （例如"客户端上报真实卡顿"），把这几个方法填上即可，协议文档 §6 仍保留定义。
 *
 * <p>另外客户端**不再注册** {@code cineduct:state} 通道（见 {@link CineDuctNetwork}），
 * 所以即使这里真发了服务端也收不到 —— 这是刻意的，避免"看起来有上行其实半通"。
 */
public final class CineStateUplink {

    private CineStateUplink() {
    }

    /** 不支持上行：空实现。 */
    public static void sendNow(String reason) {
        // no-op：单向下行设计，见类注释
    }

    /** 不支持上行：空实现。 */
    public static void sendError(int echoedVersion, String message) {
        // no-op：单向下行设计，见类注释
    }

    /** 客户端 tick：不做任何事（保留签名，将来加回上行只改这里）。 */
    public static void tick() {
        // no-op
    }
}
