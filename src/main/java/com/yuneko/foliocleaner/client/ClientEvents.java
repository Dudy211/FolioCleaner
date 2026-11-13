package com.yuneko.foliocleaner.client;

import com.yuneko.foliocleaner.ClientSettings;
import com.yuneko.foliocleaner.FolioCleaner;
import com.yuneko.foliocleaner.Rules;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.client.event.RecipesUpdatedEvent;
import net.minecraftforge.client.event.RegisterClientCommandsEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * 客户端事件：驱动规则应用、感知配方刷新、注册客户端指令。
 *
 * <p>本模组依赖 JEI，但 JEI 声明为「可选依赖」：没装 JEI 时这里会直接跳过，
 * 不会因为缺少 JEI 的类而崩溃。
 */
@Mod.EventBusSubscriber(modid = FolioCleaner.MODID, value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class ClientEvents {

    private static int seenRevision = -1;

    private ClientEvents() {
    }

    /** 至少装了一个配方浏览器 —— 界面/引擎才有意义 */
    private static boolean viewerAvailable() {
        return com.yuneko.foliocleaner.viewer.Backends.any();
    }

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;

        // 调试面板快捷键（默认 F7）**放在 JEI 检查之前**：没有 JEI 也要能调出来看槽位
        try {
            while (KeyBinds.DEBUG.consumeClick()) {
                DebugPanel.toggle();
            }
        } catch (Throwable ignored) {
        }

        if (!viewerAvailable()) return;

        int revision = Rules.revision();
        if (revision != seenRevision) {
            seenRevision = revision;
            // 默认不同步：改完规则要点「同步」按钮才生效。
            // 打开「优化设置 → 自动同步」才会在这里自动重算（延后 8 tick，连续编辑只扫一次）。
            if (ClientSettings.autoSync) {
                com.yuneko.foliocleaner.viewer.Backends.requestApplyDelayed(8);
            }
        }

        try {
            com.yuneko.foliocleaner.viewer.Backends.tickAll();
        } catch (Throwable ignored) {
            // 某个后端在半路不可用，静默跳过（每个后端自己也有 try/catch）
        }
    }

    /** 服务端把完整配方表发过来之后，JEI 会重建索引，我们稍后再应用规则 */
    @SubscribeEvent
    public static void onRecipesUpdated(RecipesUpdatedEvent event) {
        // REI 的可见性谓词列表会随资源重载重建 —— 每次都得重新确认挂上了
        com.yuneko.foliocleaner.viewer.ReiRecipeHider.install();
        com.yuneko.foliocleaner.ItemStats.invalidate();
        com.yuneko.foliocleaner.DuplicateGroups.invalidate();
        com.yuneko.foliocleaner.viewer.Backends.invalidateAll();
        if (!viewerAvailable()) return;
        com.yuneko.foliocleaner.viewer.Backends.requestApplyDelayed(4);
    }

    @SubscribeEvent
    public static void onLoggingIn(ClientPlayerNetworkEvent.LoggingIn event) {
        // 多人服统一数据包的回执要回到 UnifyActions（网络层不直接引用 client 包）
        com.yuneko.foliocleaner.CleanerNetwork.unifyResultSink = UnifyActions::onResult;
        // 进世界后再试一次挂 REI 过滤器（REI 可能比我们晚初始化）
        com.yuneko.foliocleaner.viewer.ReiRecipeHider.install();
        // 声明式适配器（json）每次进世界重读一次 —— 玩家改完不用重开游戏
        try {
            com.yuneko.foliocleaner.compat.DeclarativeGuards.reload();
        } catch (Throwable ignored) {
        }
        com.yuneko.foliocleaner.DuplicateGroups.invalidate();
        if (!viewerAvailable()) return;
        com.yuneko.foliocleaner.viewer.Backends.requestApplyDelayed(4);
    }

    /**
     * 界面打开时按键要先经过 Screen —— 这时候 KeyMapping 收不到点击，
     * 所以调试面板必须在 ScreenEvent.KeyPressed 里也能触发（这就是 F7 之前没反应的原因）。
     */
    @SubscribeEvent
    public static void onScreenKey(net.minecraftforge.client.event.ScreenEvent.KeyPressed.Pre event) {
        if (KeyBinds.DEBUG.matches(event.getKeyCode(), event.getScanCode())) {
            DebugPanel.toggle();
            event.setCanceled(true);
        }
    }

    /** 调试面板 + 槽位编号（快捷键开关；面板可拖动） */
    @SubscribeEvent
    public static void onScreenRender(net.minecraftforge.client.event.ScreenEvent.Render.Post event) {
        DebugPanel.render(event.getGuiGraphics(), event.getScreen());
    }

    @SubscribeEvent
    public static void onMousePressed(net.minecraftforge.client.event.ScreenEvent.MouseButtonPressed.Pre event) {
        if (DebugPanel.mousePressed(event.getMouseX(), event.getMouseY(), event.getButton())) {
            event.setCanceled(true);
        }
    }

    @SubscribeEvent
    public static void onMouseReleased(net.minecraftforge.client.event.ScreenEvent.MouseButtonReleased.Pre event) {
        if (DebugPanel.mouseReleased()) {
            event.setCanceled(true);
        }
    }

    @SubscribeEvent
    public static void onMouseDragged(net.minecraftforge.client.event.ScreenEvent.MouseDragged.Pre event) {
        if (DebugPanel.mouseDragged(event.getMouseX(), event.getMouseY())) {
            event.setCanceled(true);
        }
    }

    /** 同步进度条：屏幕上方居中，不影响游戏操作 */
    @SubscribeEvent
    public static void onRenderGui(net.minecraftforge.client.event.RenderGuiEvent.Post event) {
        // ★进度条只问后端注册表：它内部对每个后端都 try/catch，
        //   所以「没装某个 viewer」不会让这里抛 NoClassDefFoundError（test53 的崩溃就是这么来的）
        if (!viewerAvailable()) return;
        float progress;
        try {
            progress = com.yuneko.foliocleaner.viewer.Backends.progress();
        } catch (Throwable t) {
            return;
        }
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

            String text = "Folio Cleaner 同步中 " + (int) (progress * 100f) + "%";
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
        // 装了任意一个配方浏览器就注册；没有的话 /foliocleaner viewers 也没意义
        if (!viewerAvailable()) return;
        try {
            CleanerCommands.register(event.getDispatcher());
        } catch (Throwable ignored) {
            // 后端类不可用，直接跳过
        }
    }
}
