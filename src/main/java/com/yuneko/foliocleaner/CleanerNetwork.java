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

    private static final String PROTOCOL = "7";

    /** 规则 JSON 的包上限（字符）。以前写死 32767 —— 规则一多（≈200 条）就会超，
     *  编码抛异常 = 玩家登录直接失败。放到 1MB，和重复组一个量级。 */
    private static final int RULES_MAX = 1048576;

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

        CHANNEL.messageBuilder(SetUnifyModePacket.class, id++, NetworkDirection.PLAY_TO_SERVER)
                .encoder(SetUnifyModePacket::encode)
                .decoder(SetUnifyModePacket::decode)
                .consumerMainThread(SetUnifyModePacket::handle)
                .add();

        CHANNEL.messageBuilder(RequestGroupsPacket.class, id++, NetworkDirection.PLAY_TO_SERVER)
                .encoder(RequestGroupsPacket::encode)
                .decoder(RequestGroupsPacket::decode)
                .consumerMainThread(RequestGroupsPacket::handle)
                .add();
    }

    /** 把服务端规则 + 管理员标记 + 重复组 + 统一实现方式发给某个玩家 */
    public static void sendTo(ServerPlayer player) {
        boolean admin = player.hasPermissions(2);
        CHANNEL.send(PacketDistributor.PLAYER.with(() -> player),
                new SyncRulesPacket(Rules.exportJson(), admin, groupsForClient(player),
                        ServerSettings.unifyMode));
    }

    /**
     * 客户端（管理员）请求改「统一实现方式」。服务端写盘 + 重新应用 + 广播给所有人。
     *
     * <p>为什么必须服务端权威：{@code rewrite} 改的是「原料接受哪些物品」，
     * 客户端改宽了、服务端没改，界面会显示可以合成而实际被拒；
     * 反过来更糟 —— 客户端不显示、服务端却接受。所以只允许一个来源。
     */
    public static void setUnifyMode(String mode) {
        CHANNEL.sendToServer(new SetUnifyModePacket(mode));
    }

    /** 客户端改了本地 dup-overrides.json 之后，请服务端把（它那份）重复组重新发一遍 */
    public static void requestGroups() {
        CHANNEL.sendToServer(new RequestGroupsPacket());
    }

    /**
     * 服务端自己的重复组（{@link GroupSync} 编码）。
     *
     * <p>只有 {@code rewrite} / {@code match} 需要（数据包模式改的是 json，不看重复组），
     * 所以另外两种模式直接给空串 —— 省掉一次全量扫描和几十 KB 流量。
     */
    private static String groupsForClient(ServerPlayer player) {
        try {
            String mode = ServerSettings.unifyMode;
            if (!"rewrite".equals(mode) && !"match".equals(mode)) return GroupSync.NONE;
            var server = player.getServer();
            if (server == null) return GroupSync.NONE;
            String text = GroupSync.fromLevel(server.overworld(), DupOverrides.load());
            // 防御：极端整合包可能几十万字符，超出包上限就不发（客户端退回自行扫描）
            if (text.length() > 400_000) {
                FolioCleaner.LOGGER.warn("[Folio Cleaner] 重复组数据过大（{} 字符），跳过同步", text.length());
                return GroupSync.NONE;
            }
            return text;
        } catch (Throwable t) {
            FolioCleaner.LOGGER.warn("[Folio Cleaner] 生成重复组同步数据失败（客户端将自行扫描）", t);
            return GroupSync.NONE;
        }
    }

    /** 广播给所有在线玩家（管理员改完服务端规则后） */
    public static void broadcast(net.minecraft.server.MinecraftServer server) {
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            sendTo(player);
        }
    }

    // ------------------------------------------------------------ S2C

    public record SyncRulesPacket(String json, boolean admin, String groups, String unifyMode) {

        /** 组数据的包上限（字符）。实际数据通常是几 KB；超了就是异常整合包 */
        private static final int GROUPS_MAX = 4194304;

        public static void encode(SyncRulesPacket msg, FriendlyByteBuf buf) {
            buf.writeUtf(msg.json, RULES_MAX);
            buf.writeBoolean(msg.admin);
            buf.writeUtf(msg.groups == null ? "" : msg.groups, GROUPS_MAX);
            buf.writeUtf(msg.unifyMode == null ? "" : msg.unifyMode, 32);
        }

        public static SyncRulesPacket decode(FriendlyByteBuf buf) {
            String json = buf.readUtf(RULES_MAX);
            boolean admin = buf.readBoolean();
            String groups = buf.readUtf(GROUPS_MAX);
            String unifyMode = buf.readUtf(32);
            return new SyncRulesPacket(json, admin, groups, unifyMode);
        }

        public static void handle(SyncRulesPacket msg, Supplier<NetworkEvent.Context> ctx) {
            NetworkEvent.Context context = ctx.get();
            context.enqueueWork(() -> {
                ClientSettings.admin = msg.admin();
                // 统一实现方式：服务端权威（客户端的这一项会被覆盖并变灰）
                boolean modeChanged = false;
                if (ServerSettings.validMode(msg.unifyMode()) && !msg.unifyMode().equals(ClientSettings.unifyMode)) {
                    ClientSettings.unifyMode = msg.unifyMode();
                    ClientSettings.touch();
                    modeChanged = true;
                    FolioCleaner.LOGGER.info("[Folio Cleaner] 统一实现方式由服务端指定：{}", msg.unifyMode());
                }
                // 先给统一模块喂组数据（它会清掉 revision，让下面的规则变更触发一次重建）
                UnifyRuntime.setServerGroups(msg.groups());
                Rules.replaceFromServer(msg.json());
                // 换了实现方式要立刻重算一次（规则本身没变的话没人会触发 apply）
                if (modeChanged) {
                    com.yuneko.foliocleaner.Cleaner.requestApplyDelayed(2);
                }
            });
            context.setPacketHandled(true);
        }
    }

    /** 客户端 → 服务端：我的覆盖层变了，请重发一次规则+组数据（单人等于自问自答，无害） */
    public record RequestGroupsPacket() {

        /** 每个玩家最多 1.5 秒一次：防客户端狂刷导致服务端反复编码 */
        private static final java.util.Map<java.util.UUID, Long> LAST =
                new java.util.concurrent.ConcurrentHashMap<>();

        public static void encode(RequestGroupsPacket msg, FriendlyByteBuf buf) {
            // 空包
        }

        public static RequestGroupsPacket decode(FriendlyByteBuf buf) {
            return new RequestGroupsPacket();
        }

        public static void handle(RequestGroupsPacket msg, Supplier<NetworkEvent.Context> ctx) {
            NetworkEvent.Context context = ctx.get();
            ServerPlayer sender = context.getSender();
            context.enqueueWork(() -> {
                if (sender == null) return;
                long now = System.currentTimeMillis();
                Long last = LAST.get(sender.getUUID());
                if (last != null && now - last < 1500L) return;
                LAST.put(sender.getUUID(), now);
                sendTo(sender);
            });
            context.setPacketHandled(true);
        }
    }

    /** 客户端（管理员）→ 服务端：把统一实现方式改成某个值 */
    public record SetUnifyModePacket(String mode) {

        public static void encode(SetUnifyModePacket msg, FriendlyByteBuf buf) {
            buf.writeUtf(msg.mode == null ? "" : msg.mode, 32);
        }

        public static SetUnifyModePacket decode(FriendlyByteBuf buf) {
            return new SetUnifyModePacket(buf.readUtf(32));
        }

        public static void handle(SetUnifyModePacket msg, Supplier<NetworkEvent.Context> ctx) {
            NetworkEvent.Context context = ctx.get();
            ServerPlayer sender = context.getSender();
            context.enqueueWork(() -> {
                if (sender == null || sender.getServer() == null) return;
                if (!sender.hasPermissions(2)) {
                    FolioCleaner.LOGGER.warn("[Folio Cleaner] 非管理员尝试修改统一实现方式，已拒绝");
                    sender.displayClientMessage(net.minecraft.network.chat.Component.literal(
                            "§c需要管理员（OP 2）才能改统一实现方式"), false);
                    return;
                }
                if (!ServerSettings.validMode(msg.mode())) {
                    sender.displayClientMessage(net.minecraft.network.chat.Component.literal(
                            "§c不认识的统一实现方式：" + msg.mode()), false);
                    return;
                }
                ServerSettings.unifyMode = msg.mode();
                ServerSettings.save();
                UnifyRuntime.applyOnServer(sender.getServer(), ServerSettings.unifyMode);
                // 广播：所有人的设置项一起变（客户端那项是灰的，只有这条通路能改）
                broadcast(sender.getServer());
                FolioCleaner.LOGGER.info("[Folio Cleaner] 统一实现方式已改为 {}（{}）",
                        ServerSettings.unifyMode, sender.getGameProfile().getName());
            });
            context.setPacketHandled(true);
        }
    }

    // ------------------------------------------------------------ C2S

    public record UpdateServerRulesPacket(String json) {

        public static void encode(UpdateServerRulesPacket msg, FriendlyByteBuf buf) {
            buf.writeUtf(msg.json, RULES_MAX);
        }

        public static UpdateServerRulesPacket decode(FriendlyByteBuf buf) {
            return new UpdateServerRulesPacket(buf.readUtf(RULES_MAX));
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
     * 比对客户端适配器（服务端 / 网络包 / {@code /folioserver adaptercheck} 共用）。
     *
     * <p><b>语义是「子集」不是「相等」</b>：适配器只负责"适配"，不会改动规则，
     * 所以玩家完全可以自己多装几个适配器（用来配合他自己的客户端隐藏规则）。
     * 因此：
     * <ul>
     *   <li>服务端有的，客户端**必须有** → 缺一个就拦</li>
     *   <li>同名但内容不同 → 拦（版本漂移），并指出是哪个</li>
     *   <li>客户端多出来的 → **放行**（只在消息里提一句）</li>
     * </ul>
     *
     * @param clientIds 客户端 id 清单，格式与 {@link com.yuneko.foliocleaner.compat.AdapterDir#hashList()} 相同
     *                  （{@code kind:id[=hash8]} 逗号分隔；只给 id 也能用，那就只能报「内容不同」不报具体哪个）
     * @return null = 通过；否则是人话原因
     */
    public static String diffAdapters(String clientFp, String clientIds) {
        String serverFp = com.yuneko.foliocleaner.compat.AdapterDir.fingerprint();
        if (serverFp.equals(clientFp)) return null;   // 完全一致，最快路径

        java.util.Map<String, String> need = parseIdList(com.yuneko.foliocleaner.compat.AdapterDir.hashList());
        java.util.Map<String, String> have = parseIdList(clientIds);

        java.util.List<String> missing = new java.util.ArrayList<>();
        java.util.List<String> changed = new java.util.ArrayList<>();
        for (var e : need.entrySet()) {
            String mine = have.get(e.getKey());
            if (mine == null) {
                missing.add(e.getKey());
                continue;
            }
            // 任何一边没有哈希（老版本）就跳过逐条比对，只靠总指纹
            String a = e.getValue(), b = mine;
            if (!a.isEmpty() && !b.isEmpty() && !a.equals(b)) {
                changed.add(e.getKey());
            }
        }
        java.util.List<String> extra = new java.util.ArrayList<>();
        for (String id : have.keySet()) {
            if (!need.containsKey(id)) extra.add(id);
        }

        if (missing.isEmpty() && changed.isEmpty()) {
            if (extra.isEmpty()) return null;
            // 有额外 id、又没有逐项哈希 → 分不清「只是多装」还是「多装 + 内容变了」
            // （真客户端 protocol 5 一定会给 hashes，这里只有控制台模拟才会遇到）
            boolean anyHashMissing = false;
            for (String h : have.values()) {
                if (h.isEmpty()) {
                    anyHashMissing = true;
                    break;
                }
            }
            if (anyHashMissing) {
                return "§7内容不同：客户端没提供逐项哈希，无法指名是哪个（用同版本客户端进服即可）\n";
            }
            // 只是多装了客户端自己的适配器 —— 按子集语义放行
            return null;
        }
        StringBuilder sb = new StringBuilder();
        if (!missing.isEmpty()) {
            sb.append("§7缺少适配器：§f").append(String.join("§7, §f", missing)).append('\n');
        }
        if (!changed.isEmpty()) {
            sb.append("§7内容不同：§f").append(String.join("§7, §f", changed))
                    .append("§7（同名但文件变了，请用服务端那份）\n");
        }
        if (!extra.isEmpty()) {
            sb.append("§8多出的不算错：").append(String.join("§8, §8", extra)).append('\n');
        }
        return sb.toString();
    }

    /**
     * 解析 {@code kind:id[=hash8],...}（也接受老格式的纯 id 清单）。
     * 哈希为空串表示"没给"。
     */
    private static java.util.Map<String, String> parseIdList(String csv) {
        java.util.Map<String, String> out = new java.util.LinkedHashMap<>();
        if (csv == null) return out;
        for (String token : csv.split(",")) {
            String t = token.trim();
            if (t.isEmpty()) continue;
            int eq = t.indexOf('=');
            if (eq < 0) out.put(t, "");
            else out.put(t.substring(0, eq), t.substring(eq + 1));
        }
        return out;
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
                            com.yuneko.foliocleaner.compat.AdapterDir.idList(),
                            com.yuneko.foliocleaner.compat.AdapterDir.hashList()));
                } catch (Throwable t) {
                    FolioCleaner.LOGGER.warn("[Folio Cleaner] 上报适配器指纹失败", t);
                }
            });
            c.setPacketHandled(true);
        }
    }

    /**
     * 客户端 → 服务端：我的指纹 + id 清单 + 每个适配器的内容哈希。
     *
     * <p>{@code hashes} 是 {@code kind:id=hash8} 形式（见
     * {@link com.yuneko.foliocleaner.compat.AdapterDir#hashList()}）：
     * 只靠总指纹只能说「有东西不一样」，带上它就能指出**哪个**不一样。
     */
    public record AdapterAckPacket(String fp, int count, String ids, String hashes) {
        public static void encode(AdapterAckPacket m, FriendlyByteBuf b) {
            b.writeUtf(m.fp, 64);
            b.writeVarInt(m.count);
            b.writeUtf(m.ids == null ? "" : m.ids, 32768);
            b.writeUtf(m.hashes == null ? "" : m.hashes, 65536);
        }

        public static AdapterAckPacket decode(FriendlyByteBuf b) {
            return new AdapterAckPacket(b.readUtf(64), b.readVarInt(), b.readUtf(32768), b.readUtf(65536));
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

                String clientList = m.hashes == null || m.hashes.isBlank() ? m.ids : m.hashes;
                String diff = diffAdapters(m.fp, clientList);
                if (diff == null) {       // 只是多装了客户端自己的适配器 —— 按子集语义放行
                    FolioCleaner.LOGGER.info("[Folio Cleaner] {} 多装了适配器（允许）：{}",
                            sender.getGameProfile().getName(), m.ids);
                    return;
                }
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
    }

    public record UnifyRequestPacket(String op, String overridesJson, boolean crossTag, boolean autoReload) {

        public static void encode(UnifyRequestPacket msg, FriendlyByteBuf buf) {
            buf.writeUtf(msg.op == null ? "" : msg.op, 32);
            buf.writeUtf(msg.overridesJson == null ? "" : msg.overridesJson, RULES_MAX);
            buf.writeBoolean(msg.crossTag);
            buf.writeBoolean(msg.autoReload);
        }

        public static UnifyRequestPacket decode(FriendlyByteBuf buf) {
            return new UnifyRequestPacket(buf.readUtf(32), buf.readUtf(RULES_MAX),
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
