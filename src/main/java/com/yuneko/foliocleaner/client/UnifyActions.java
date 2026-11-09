package com.yuneko.foliocleaner.client;

import com.yuneko.foliocleaner.Cleaner;
import com.yuneko.foliocleaner.CleanerNetwork;
import com.yuneko.foliocleaner.ClientSettings;
import com.yuneko.foliocleaner.DupOverrides;
import com.yuneko.foliocleaner.ServerUnify;
import com.yuneko.foliocleaner.UnifyManifest;
import net.minecraft.client.Minecraft;
import net.minecraft.server.MinecraftServer;

import java.util.function.Consumer;

/**
 * 「统一数据包」的**客户端入口**：单人直接在本进程的服务端线程跑，多人发网络包让服务端跑。
 *
 * <p>真正的活儿全在 {@link ServerUnify}（那一份不引客户端类，专用服务器也能用）；
 * 这里只负责「怎么把请求送过去」和「结果怎么回到界面」。
 *
 * <p>指令（{@code /foliocleaner unify ...}）和重复页属性台的按钮共用本类 —— 两边各写一遍迟早就漂移。
 */
public final class UnifyActions {

    public static final String STATUS = ServerUnify.STATUS;
    public static final String GEN = ServerUnify.GEN;
    public static final String ROLLBACK = ServerUnify.ROLLBACK;
    public static final String WIPE = ServerUnify.WIPE;

    /** 属性台状态行缓存（渲染每帧都读盘太浪费） */
    private static String cachedLine = "";
    private static long cachedAt;

    /** 等待服务端回执的回调（多人服那条路） */
    private static Consumer<String> pending;

    static {
        // 服务端回执要回到这里；网络层不直接引用 client 包，靠这个回调桥接
        CleanerNetwork.unifyResultSink = UnifyActions::onResult;
    }

    private UnifyActions() {
    }

    /**
     * 执行一个操作；{@code done} 会被调一次，参数是可直接展示的一段话。
     * 没网 / 没权限 / 没服务器也会回调一句提示，不会静默什么都不做。
     */
    public static void run(String op, Consumer<String> done) {
        Minecraft mc = Minecraft.getInstance();
        MinecraftServer server = mc.getSingleplayerServer();
        if (server != null) {
            String overrides = DupOverrides.exportJson();
            boolean crossTag = ClientSettings.dupCrossTag;
            boolean autoReload = ClientSettings.autoReload;
            server.execute(() -> {
                String msg;
                try {
                    msg = ServerUnify.run(server, op, overrides, crossTag, autoReload);
                } catch (Throwable t) {
                    msg = "§c失败：" + t.getClass().getSimpleName()
                            + (t.getMessage() == null ? "" : " " + t.getMessage());
                }
                final String out = msg;
                invalidate();
                Cleaner.resetUnifyCache();
                mc.execute(() -> done.accept(out));
            });
            return;
        }

        // ---- 多人服：请服务端执行（数据包必须是服务端权威的）
        if (mc.getConnection() == null) {
            done.accept("还没连上任何服务器");
            return;
        }
        if (!ClientSettings.admin) {
            done.accept("§e多人服要管理员（OP 2）才能生成统一数据包");
            return;
        }
        pending = done;
        CleanerNetwork.requestUnify(op, DupOverrides.exportJson(),
                ClientSettings.dupCrossTag, ClientSettings.autoReload);
    }

    /** 服务端回执（S2C handler 调，已经在主线程） */
    public static void onResult(String message) {
        invalidate();
        Cleaner.resetUnifyCache();
        Consumer<String> done = pending;
        pending = null;
        if (done != null) done.accept(message == null ? "" : message);
    }

    /** 让属性台的状态行下次重新算 */
    public static void invalidate() {
        cachedAt = 0L;
        cachedLine = "";
    }

    /** 属性台用的短状态行（单人每 2 秒最多读一次盘；多人只能提示） */
    public static String statusLine() {
        Minecraft mc = Minecraft.getInstance();
        MinecraftServer server = mc.getSingleplayerServer();
        if (server == null) {
            if (mc.getConnection() == null) return "未连接";
            return ClientSettings.admin ? "多人服 · 服务端执行" : "多人服 · 需 OP";
        }
        long now = System.currentTimeMillis();
        if (now - cachedAt < 2000L) return cachedLine;
        cachedAt = now;
        try {
            UnifyManifest.Data m = UnifyManifest.load(ServerUnify.root(server));
            // 属性台只有 96px 宽，这里只放最短的一句；明细看 /foliocleaner unify status
            cachedLine = m.entries.isEmpty() ? "还没有清单" : "清单 " + m.entries.size() + " 条";
        } catch (Throwable t) {
            cachedLine = "读取失败";
        }
        return cachedLine;
    }
}
