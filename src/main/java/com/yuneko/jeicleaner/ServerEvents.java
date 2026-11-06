package com.yuneko.jeicleaner;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimplePreparableReloadListener;
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraftforge.event.AddReloadListenerEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * 服务端事件：加载完配方后执行「删除」规则，玩家登录时下发规则。
 *
 * <p>本类运行在双端，不能引用任何 JEI 类型。
 */
@Mod.EventBusSubscriber(modid = JeiCleaner.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class ServerEvents {

    private static MinecraftServer server;

    private ServerEvents() {
    }

    @SubscribeEvent
    public static void onServerStarted(ServerStartedEvent event) {
        server = event.getServer();
        Rules.loadServerRules();
        RecipeRemover.apply(server);
        JeiCleaner.LOGGER.info("[JEI Cleaner] 服务端已就绪，规则 {} 条", Rules.size());
    }

    @SubscribeEvent
    public static void onServerStopping(ServerStoppingEvent event) {
        RecipeRemover.restore(server);
        server = null;
    }

    /** /reload 会重新加载配方，这里在重载链最后再应用一次删除规则 */
    @SubscribeEvent
    public static void onAddReloadListener(AddReloadListenerEvent event) {
        event.addListener(new SimplePreparableReloadListener<Void>() {
            @Override
            protected Void prepare(ResourceManager resourceManager, ProfilerFiller profiler) {
                return null;
            }

            @Override
            protected void apply(Void unused, ResourceManager resourceManager, ProfilerFiller profiler) {
                Rules.loadServerRules();
                if (server != null) {
                    RecipeRemover.apply(server);
                }
            }
        });
    }

    @SubscribeEvent
    public static void onPlayerLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            CleanerNetwork.sendTo(player);
        }
    }
}
