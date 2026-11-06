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
 * <p>本模组依赖 JEI，但 JEI 声明为「可选依赖」：没装 JEI 时这里会直接跳过，
 * 不会因为缺少 JEI 的类而崩溃。
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
            // 自动同步：原版配方立刻应用（很快），自定义配方走分帧任务（顶部有进度条）
            Cleaner.requestApply();
        }

        try {
            Cleaner.tick();
        } catch (NoClassDefFoundError ignored) {
            // JEI 在半路被卸载 / 类不可用，静默跳过
        }
    }

    /** 服务端把完整配方表发过来之后，JEI 会重建索引，我们稍后再应用规则 */
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

    /** 同步进度条：屏幕上方居中，不影响游戏操作 */
    @SubscribeEvent
    public static void onRenderGui(net.minecraftforge.client.event.RenderGuiEvent.Post event) {
        float progress = Cleaner.progress();
        if (progress < 0f) return;

        try {
            var gg = event.getGuiGraphics();
            var minecraft = net.minecraft.client.Minecraft.getInstance();
            var font = minecraft.font;
            int screenW = event.getWindow().getGuiScaledWidth();

            int barW = 160;
            int barH = 5;
            int x = (screenW - barW) / 2;
            int y = 18;

            String text = "JEI Cleaner 同步中 " + (int) (progress * 100f) + "%";
            int textW = font.width(text);

            gg.fill(x - 1, y - 12, x + barW + 1, y + barH + 1, 0xC0000000);
            gg.fill(x, y, x + (int) (barW * progress), y + barH, 0xFF7CD992);
            gg.drawString(font, text, (screenW - textW) / 2, y - 11, 0xFFE8E8F0, false);
        } catch (Throwable ignored) {
            // HUD 出问题不影响游戏
        }
    }

    @SubscribeEvent
    public static void onRegisterClientCommands(RegisterClientCommandsEvent event) {
        // 没装 JEI 时连指令都不注册：Cleaner / CleanerCommands 里引用了 JEI 的类型，
        // 提前返回可以避免类加载失败。
        if (!jeiLoaded()) return;
        try {
            CleanerCommands.register(event.getDispatcher());
        } catch (NoClassDefFoundError ignored) {
            // JEI 类不可用，直接跳过
        }
    }
}
