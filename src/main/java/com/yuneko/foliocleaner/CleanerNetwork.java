package com.yuneko.foliocleaner;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.simple.SimpleChannel;

import java.util.function.Supplier;

/**
 * 规则同步（双向）。
 *
 * <ul>
 *   <li>S2C {@link SyncRulesPacket}：登录时下发服务端规则 + 当前玩家是不是管理员</li>
 *   <li>C2S {@link UpdateServerRulesPacket}：管理员在客户端点了「服务端」模式后提交新规则，
 *       服务端校验 OP 权限 → 写 server.json → 重新应用 → 广播给所有人</li>
 *   <li>C2S {@link UnifyRequestPacket} / S2C {@link UnifyResultPacket}：
 *       多人服生成 / 撤销统一数据包（数据包必须由服务端写；校验 OP 2）</li>
 * </ul>
 */
public final class CleanerNetwork {

    private static final String PROTOCOL = "3";

    public static final SimpleChannel CHANNEL = NetworkRegistry.ChannelBuilder
            .named(new ResourceLocation(FolioCleaner.MODID, "rules"))
            .networkProtocolVersion(() -> PROTOCOL)
            .clientAcceptedVersions(PROTOCOL::equals)
            .serverAcceptedVersions(PROTOCOL::equals)
            .simpleChannel();

    private CleanerNetwork() {
    }

    public static void register() {
        int id = 0;
        CHANNEL.messageBuilder(SyncRulesPacket.class, id++, NetworkDirection.PLAY_TO_CLIENT)
                .encoder(SyncRulesPacket::encode)
                .decoder(SyncRulesPacket::decode)
                .consumerMainThread(SyncRulesPacket::handle)
                .add();

        CHANNEL.messageBuilder(UpdateServerRulesPacket.class, id++, NetworkDirection.PLAY_TO_SERVER)
                .encoder(UpdateServerRulesPacket::encode)
                .decoder(UpdateServerRulesPacket::decode)
                .consumerMainThread(UpdateServerRulesPacket::handle)
                .add();

        CHANNEL.messageBuilder(UnifyRequestPacket.class, id++, NetworkDirection.PLAY_TO_SERVER)
                .encoder(UnifyRequestPacket::encode)
                .decoder(UnifyRequestPacket::decode)
                .consumerMainThread(UnifyRequestPacket::handle)
                .add();

        CHANNEL.messageBuilder(AdapterCheckPacket.class, id++, NetworkDirection.PLAY_TO_CLIENT)
                .encoder(AdapterCheckPacket::encode)
                .decoder(AdapterCheckPacket::decode)
                .consumerMainThread(AdapterCheckPacket::handle)
                .add();

        CHANNEL.messageBuilder(AdapterAckPacket.class, id++, NetworkDirection.PLAY_TO_SERVER)
                .encoder(AdapterAckPacket::encode)
                .decoder(AdapterAckPacket::decode)
                .consumerMainThread(AdapterAckPacket::handle)
                .add();

        CHANNEL.messageBuilder(UnifyResultPacket.class, id++, NetworkDirection.PLAY_TO_CLIENT)
                .encoder(UnifyResultPacket::encode)
                .decoder(UnifyResultPacket::decode)
                .consumerMainThread(UnifyResultPacket::handle)
                .add();
    }

    /** 把服务端规则 + 管理员标记发给某个玩家 */
    public static void sendTo(ServerPlayer player) {
        boolean admin = player.hasPermissions(2);
        CHANNEL.send(PacketDistributor.PLAYER.with(() -> player),
                new SyncRulesPacket(Rules.exportJson(), admin));
    }

    /** 广播给所有在线玩家（管理员改完服务端规则后） */
    public static void broadcast(net.minecraft.server.MinecraftServer server) {
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            sendTo(player);
        }
    }

    // ------------------------------------------------------------ S2C

    public record SyncRulesPacket(String json, boolean admin) {

        public static void encode(SyncRulesPacket msg, FriendlyByteBuf buf) {
            buf.writeUtf(msg.json, 32767);
            buf.writeBoolean(msg.admin);
        }

        public static SyncRulesPacket decode(FriendlyByteBuf buf) {
            return new SyncRulesPacket(buf.readUtf(32767), buf.readBoolean());
        }

        public static void handle(SyncRulesPacket msg, Supplier<NetworkEvent.Context> ctx) {
            NetworkEvent.Context context = ctx.get();
            context.enqueueWork(() -> {
                ClientSettings.admin = msg.admin();
                Rules.replaceFromServer(msg.json);
            });
            context.setPacketHandled(true);
        }
    }

    // ------------------------------------------------------------ C2S

    public record UpdateServerRulesPacket(String json) {

        public static void encode(UpdateServerRulesPacket msg, FriendlyByteBuf buf) {
            buf.writeUtf(msg.json, 32767);
        }

        public static UpdateServerRulesPacket decode(FriendlyByteBuf buf) {
            return new UpdateServerRulesPacket(buf.readUtf(32767));
        }

        public static void handle(UpdateServerRulesPacket msg, Supplier<NetworkEvent.Context> ctx) {
            NetworkEvent.Context context = ctx.get();
            ServerPlayer sender = context.getSender();
            context.enqueueWork(() -> {
                if (sender == null || !sender.hasPermissions(2)) {
                    FolioCleaner.LOGGER.warn("[Folio Cleaner] 非管理员尝试修改服务端规则，已拒绝");
                    return;
                }
                Rules.acceptServerRulesFromAdmin(msg.json);
                if (sender.getServer() != null) {
                    RecipeRemover.apply(sender.getServer());
                    broadcast(sender.getServer());
                }
            });
            context.setPacketHandled(true);
        }
    }

    // ------------------------------------------------------------ 统一数据包（C2S 请求 / S2C 回执）

    /**
     * 客户端注册的回执回调。
     * 网络层在 common 包里，不能直接引用 {@code client.UnifyActions} —— 用一个 sink 桥接，
     * 客户端初始化时把 {@code UnifyActions::onResult} 挂进来。
     */
    public static java.util.function.Consumer<String> unifyResultSink;

    /**
     * 比对客户端指纹（供网络包和 {@code /folioserver adaptercheck} 共用）。
     *
     * @return null = 一致；否则是人话差异
     */
    public static String diffAdapters(String clientFp, String clientIds) {
        String serverFp = com.yuneko.foliocleaner.compat.AdapterDir.fingerprint();
        if (serverFp.equals(clientFp)) return null;
        return AdapterAckPacket.diff(com.yuneko.foliocleaner.compat.AdapterDir.idList(),
                clientIds == null ? "" : clientIds);
    }

    /** 登录后让客户端报一次适配器指纹 */
    public static void requestAdapterCheck(ServerPlayer player) {
        try {
            CHANNEL.send(PacketDistributor.PLAYER.with(() -> player),
                    new AdapterCheckPacket(
                            com.yuneko.foliocleaner.compat.AdapterDir.fingerprint(),
                            com.yuneko.foliocleaner.compat.AdapterDir.all().size(),
                            String.join(",", ClientSettings.disabledGuards)));
        } catch (Throwable t) {
            FolioCleaner.LOGGER.warn("[Folio Cleaner] 发送适配器校验失败", t);
        }
    }

    /** 让服务端生成 / 撤销 / 删除统一数据包（服务端会校验 OP 2） */
    public static void requestUnify(String op, String overridesJson, boolean crossTag, boolean autoReload) {
        CHANNEL.sendToServer(new UnifyRequestPacket(op, overridesJson, crossTag, autoReload));
    }

    /** 服务端 → 客户端：请把你的适配器指纹报上来 */
    public record AdapterCheckPacket(String fp, int count, String disabled) {
        public static void encode(AdapterCheckPacket m, FriendlyByteBuf b) {
            b.writeUtf(m.fp, 64);
            b.writeVarInt(m.count);
            b.writeUtf(m.disabled == null ? "" : m.disabled, 4096);
        }

        public static AdapterCheckPacket decode(FriendlyByteBuf b) {
            return new AdapterCheckPacket(b.readUtf(64), b.readVarInt(), b.readUtf(4096));
        }

        public static void handle(AdapterCheckPacket m, Supplier<NetworkEvent.Context> ctx) {
            NetworkEvent.Context c = ctx.get();
            c.enqueueWork(() -> {
                // 开关以服务端为准：直接覆盖本地
                try {
                    ClientSettings.disabledGuards.clear();
                    if (m.disabled != null && !m.disabled.isBlank()) {
                        for (String id : m.disabled.split(",")) {
                            if (!id.isBlank()) ClientSettings.disabledGuards.add(id);
                        }
                    }
                    ClientSettings.touch();
                    com.yuneko.foliocleaner.compat.AdapterDir.invalidate();
                } catch (Throwable ignored) {
                }
                try {
                    String local = com.yuneko.foliocleaner.compat.AdapterDir.fingerprint();
                    CHANNEL.sendToServer(new AdapterAckPacket(local,
                            com.yuneko.foliocleaner.compat.AdapterDir.all().size(),
                            com.yuneko.foliocleaner.compat.AdapterDir.idList()));
                } catch (Throwable t) {
                    FolioCleaner.LOGGER.warn("[Folio Cleaner] 上报适配器指纹失败", t);
                }
            });
            c.setPacketHandled(true);
        }
    }

    /** 客户端 → 服务端：我的指纹 + id 清单 */
    public record AdapterAckPacket(String fp, int count, String ids) {
        public static void encode(AdapterAckPacket m, FriendlyByteBuf b) {
            b.writeUtf(m.fp, 64);
            b.writeVarInt(m.count);
            b.writeUtf(m.ids == null ? "" : m.ids, 8192);
        }

        public static AdapterAckPacket decode(FriendlyByteBuf b) {
            return new AdapterAckPacket(b.readUtf(64), b.readVarInt(), b.readUtf(8192));
        }

        public static void handle(AdapterAckPacket m, Supplier<NetworkEvent.Context> ctx) {
            NetworkEvent.Context c = ctx.get();
            ServerPlayer sender = c.getSender();
            c.enqueueWork(() -> {
                if (sender == null) return;
                String mode = com.yuneko.foliocleaner.ServerSettings.adapterCheck;
                if ("off".equals(mode)) return;
                String serverFp = com.yuneko.foliocleaner.compat.AdapterDir.fingerprint();
                if (serverFp.equals(m.fp)) return;

                String diff = diff(com.yuneko.foliocleaner.compat.AdapterDir.idList(), m.ids);
                FolioCleaner.LOGGER.warn("[Folio Cleaner] 适配器不一致：{} 服务端 {} / 客户端 {}",
                        sender.getGameProfile().getName(), serverFp, m.fp);
                if ("warn".equals(mode)) {
                    sender.displayClientMessage(net.minecraft.network.chat.Component.literal(
                            "§e[Folio Cleaner] 你的适配器和服务端不一致：\n" + diff), false);
                    return;
                }
                sender.connection.disconnect(net.minecraft.network.chat.Component.literal(
                        "§c适配器不一致，无法进入服务器\n\n" + diff
                                + "\n§7请从 config/foliocleaner/adapters/ 补齐后重进"));
            });
            c.setPacketHandled(true);
        }

        /** 对比两边 id 清单，返回人话差异 */
        static String diff(String serverIds, String clientIds) {
            var s = new java.util.LinkedHashSet<>(java.util.Arrays.asList(serverIds.split(",")));
            var cl = new java.util.LinkedHashSet<>(java.util.Arrays.asList(clientIds.split(",")));
            var missing = new java.util.ArrayList<String>();
            var extra = new java.util.ArrayList<String>();
            for (String id : s) if (!cl.contains(id)) missing.add(id);
            for (String id : cl) if (!s.contains(id)) extra.add(id);
            StringBuilder sb = new StringBuilder();
            if (!missing.isEmpty()) sb.append("§7缺少：§f").append(String.join("§7, §f", missing)).append('\n');
            if (!extra.isEmpty()) sb.append("§7多出：§f").append(String.join("§7, §f", extra)).append('\n');
            if (sb.length() == 0) sb.append("§7内容不同（同名但文件变了）\n");
            return sb.toString();
        }
    }

    public record UnifyRequestPacket(String op, String overridesJson, boolean crossTag, boolean autoReload) {

        public static void encode(UnifyRequestPacket msg, FriendlyByteBuf buf) {
            buf.writeUtf(msg.op == null ? "" : msg.op, 32);
            buf.writeUtf(msg.overridesJson == null ? "" : msg.overridesJson, 32767);
            buf.writeBoolean(msg.crossTag);
            buf.writeBoolean(msg.autoReload);
        }

        public static UnifyRequestPacket decode(FriendlyByteBuf buf) {
            return new UnifyRequestPacket(buf.readUtf(32), buf.readUtf(32767),
                    buf.readBoolean(), buf.readBoolean());
        }

        public static void handle(UnifyRequestPacket msg, Supplier<NetworkEvent.Context> ctx) {
            NetworkEvent.Context context = ctx.get();
            ServerPlayer sender = context.getSender();
            context.enqueueWork(() -> {
                if (sender == null || sender.getServer() == null) return;
                if (!sender.hasPermissions(2)) {
                    FolioCleaner.LOGGER.warn("[Folio Cleaner] 非管理员尝试生成统一数据包，已拒绝");
                    reply(sender, "§c需要管理员（OP 2）权限");
                    return;
                }
                String out;
                try {
                    out = ServerUnify.run(sender.getServer(), msg.op(), msg.overridesJson(),
                            msg.crossTag(), msg.autoReload());
                } catch (Throwable t) {
                    out = "§c失败：" + t.getClass().getSimpleName()
                            + (t.getMessage() == null ? "" : " " + t.getMessage());
                }
                reply(sender, out);
            });
            context.setPacketHandled(true);
        }

        private static void reply(ServerPlayer player, String message) {
            CHANNEL.send(PacketDistributor.PLAYER.with(() -> player),
                    new UnifyResultPacket(message == null ? "" : message));
        }
    }

    public record UnifyResultPacket(String message) {

        public static void encode(UnifyResultPacket msg, FriendlyByteBuf buf) {
            buf.writeUtf(msg.message, 16384);
        }

        public static UnifyResultPacket decode(FriendlyByteBuf buf) {
            return new UnifyResultPacket(buf.readUtf(16384));
        }

        public static void handle(UnifyResultPacket msg, Supplier<NetworkEvent.Context> ctx) {
            NetworkEvent.Context context = ctx.get();
            context.enqueueWork(() -> {
                java.util.function.Consumer<String> sink = unifyResultSink;
                if (sink != null) sink.accept(msg.message());
            });
            context.setPacketHandled(true);
        }
    }
}
