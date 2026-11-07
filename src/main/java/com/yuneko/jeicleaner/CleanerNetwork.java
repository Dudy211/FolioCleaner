package com.yuneko.jeicleaner;

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
 * </ul>
 */
public final class CleanerNetwork {

    private static final String PROTOCOL = "1";

    public static final SimpleChannel CHANNEL = NetworkRegistry.ChannelBuilder
            .named(new ResourceLocation(JeiCleaner.MODID, "rules"))
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
                    JeiCleaner.LOGGER.warn("[JEI Cleaner] 非管理员尝试修改服务端规则，已拒绝");
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
}
