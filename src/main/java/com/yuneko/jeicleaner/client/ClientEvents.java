package com.yuneko.jeicleaner.client;

import com.yuneko.jeicleaner.Cleaner;
import com.yuneko.jeicleaner.JeiCleaner;
import com.yuneko.jeicleaner.Rules;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.client.event.RecipesUpdatedEvent;
import net.minecraftforge.client.event.RegisterClientCommandsEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.common.Mod;

/**
 * 客户端事件：驱动规则应用、感知配方刷新、注册客户端指令。
 *
 * <p>JEI 是可选依赖：没装时这里直接跳过，不会因为缺类而崩。
 */
@Mod.EventBusSubscriber(modid = JeiCleaner.MODID, value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class ClientEvents {

    private static int seenRevision = -1;

    private ClientEvents() {
    }

    private static boolean jeiLoaded() {
        return ModList.get().isLoaded("jei");
    }

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        if (!jeiLoaded()) return;

        int revision = Rules.revision();
        if (revision != seenRevision) {
            seenRevision = revision;
            Cleaner.requestApply();
        }

        try {
            Cleaner.tick();
        } catch (NoClassDefFoundError ignored) {
            // JEI 在半路不可用，静默跳过
        }
    }

    /** 服务端把完整配方表发过来之后，JEI 会重建索引，稍后再应用规则 */
    @SubscribeEvent
    public static void onRecipesUpdated(RecipesUpdatedEvent event) {
        if (!jeiLoaded()) return;
        com.yuneko.jeicleaner.ItemStats.invalidate();
        Cleaner.requestApplyDelayed(4);
    }

    @SubscribeEvent
    public static void onLoggingIn(ClientPlayerNetworkEvent.LoggingIn event) {
        if (!jeiLoaded()) return;
        Cleaner.requestApplyDelayed(4);
    }

    @SubscribeEvent
    public static void onRegisterClientCommands(RegisterClientCommandsEvent event) {
        if (!jeiLoaded()) return;
        try {
            CleanerCommands.register(event.getDispatcher());
        } catch (NoClassDefFoundError ignored) {
            // JEI 类不可用，直接跳过
        }
    }
}
