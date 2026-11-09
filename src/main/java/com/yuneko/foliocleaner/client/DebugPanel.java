package com.yuneko.foliocleaner.client;

import com.yuneko.foliocleaner.ClientSettings;
import com.yuneko.foliocleaner.FolioCleaner;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ResultContainer;
import net.minecraft.world.inventory.Slot;

/**
 * 调试悬浮窗：**快捷键开关 + 可拖动 + 按钮操作**。
 *
 * <p>为什么不用指令：打开机器界面后聊天框打不开（原版就不转发键盘），
 * 所以调试必须能在"有界面"的情况下操作：
 * <ul>
 *   <li>快捷键（默认 F7，可在「按键设置 → Folio Cleaner」里改）开关整个面板</li>
 *   <li>面板可拖动（拖标题栏），位置记在内存里（重开游戏回默认位置）</li>
 *   <li>按钮：复制骨架 / 演练开关 / 关闭</li>
 *   <li>槽位编号直接叠在界面槽位上，面板上也写当前界面的关键信息</li>
 * </ul>
 *
 * <p>鼠标事件通过 {@code ScreenEvent.Mouse*Pre} 拦截 —— 只有点在面板上才消费，
 * 点机器界面照常操作（拖东西不会被挡）。
 */
public final class DebugPanel {

    private static final int W = 172;
    private static final int H = 84;
    private static final int TITLE_H = 13;

    private static boolean open;
    private static int x = 8;
    private static int y = 8;
    private static boolean dragging;
    private static int dragDX;
    private static int dragDY;
    /** 面板按钮的点击区（渲染时刷新） */
    private static int bx1, bx2, bx3, by, bw, bh;

    private DebugPanel() {
    }

    public static boolean isOpen() {
        return open;
    }

    public static void setOpen(boolean value) {
        if (open != value) toggle();
    }

    public static void toggle() {
        open = !open;
        if (open) {
            Screen s = Minecraft.getInstance().screen;
            if (s != null && s.width > 0) {
                x = Math.max(2, Math.min(s.width - W - 2, x));
                y = Math.max(2, Math.min(s.height - H - 2, y));
            }
        }
        ClientSettings.debugOverlay = open;    // 和旧开关同步，槽位编号跟着走
    }

    // ---------------------------------------------------------------- 渲染

    public static void render(GuiGraphics gg, Screen screen) {
        if (!open) return;
        Font font = Minecraft.getInstance().font;
        if (screen instanceof AbstractContainerScreen<?> cs) {
            DebugOverlay.render(gg, cs);       // 槽位编号
        }

        int screenW = screen.width;
        int screenH = screen.height;
        x = Math.max(2, Math.min(screenW - W - 2, x));
        y = Math.max(2, Math.min(screenH - H - 2, y));

        gg.flush();
        gg.fill(x, y, x + W, y + H, 0xE0101018);
        gg.renderOutline(x, y, W, H, 0xFF3E4A63);
        gg.fill(x, y, x + W, y + TITLE_H, 0xFF2A3550);
        gg.drawString(font, "§e适配器调试", x + 5, y + 3, 0xFFFFFFFF, false);
        gg.drawString(font, "§8拖动", x + W - 28, y + 3, 0xFF808080, false);

        int ty = y + TITLE_H + 3;
        if (screen instanceof AbstractContainerScreen<?> cs) {
            AbstractContainerMenu menu = cs.getMenu();
            gg.drawString(font, "§7" + cut(font, menu.getClass().getSimpleName(), W - 10), x + 5, ty, 0xFFFFFFFF, false);
            ty += 10;
            int resultSlots = 0;
            for (int i = 0; i < menu.slots.size(); i++) {
                if (menu.getSlot(i).container instanceof ResultContainer) resultSlots++;
            }
            gg.drawString(font, "§8槽位 " + menu.slots.size() + " · 结果槽 " + resultSlots, x + 5, ty, 0xFFFFFFFF, false);
            ty += 10;
            // 鼠标所在槽位
            var hovered = cs.getSlotUnderMouse();
            String hover = hovered == null ? "-"
                    : hovered.index + " " + (hovered.getItem().isEmpty() ? "-"
                    : net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(hovered.getItem().getItem()).toString());
            gg.drawString(font, "§8指向：" + cut(font, hover, W - 34), x + 5, ty, 0xFFFFFFFF, false);
        } else {
            gg.drawString(font, "§8先打开一个容器界面", x + 5, ty, 0xFFFFFFFF, false);
        }

        // 按钮行
        bw = 52;
        bh = 14;
        by = y + H - bh - 4;
        bx1 = x + 4;
        bx2 = bx1 + bw + 2;
        bx3 = bx2 + bw + 2;
        button(gg, font, bx1, by, "复制骨架", isHover(Minecraft.getInstance().mouseHandler, bx1, by, bw, bh));
        button(gg, font, bx2, by, ClientSettings.adapterDryRun ? "演练:开" : "演练:关",
                isHover(Minecraft.getInstance().mouseHandler, bx2, by, bw, bh));
        button(gg, font, bx3, by, "关闭", isHover(Minecraft.getInstance().mouseHandler, bx3, by, bw, bh));
    }

    private static void button(GuiGraphics gg, Font font, int bx, int by, String label, boolean hover) {
        gg.fill(bx, by, bx + bw, by + bh, hover ? 0x804E6A9E : 0x60304058);
        gg.renderOutline(bx, by, bw, bh, 0xFF4E5A73);
        gg.drawString(font, "§f" + label, bx + 4, by + 3, 0xFFFFFFFF, false);
    }

    private static boolean isHover(net.minecraft.client.MouseHandler mouse, int bx, int by, int bw, int bh) {
        var mc = Minecraft.getInstance();
        double mx = mc.mouseHandler.xpos() * (double) mc.getWindow().getGuiScaledWidth() / (double) mc.getWindow().getScreenWidth();
        double my = mc.mouseHandler.ypos() * (double) mc.getWindow().getGuiScaledHeight() / (double) mc.getWindow().getScreenHeight();
        return mx >= bx && mx < bx + bw && my >= by && my < by + bh;
    }

    // ---------------------------------------------------------------- 鼠标

    /** @return true = 这次点击被面板吃掉（不要传给下面的机器界面） */
    public static boolean mousePressed(double mx, double my, int button) {
        if (!open || button != 0) return false;
        if (mx < x || mx >= x + W || my < y || my >= y + H) return false;
        // 标题栏拖动
        if (my < y + TITLE_H) {
            dragging = true;
            dragDX = (int) (mx - x);
            dragDY = (int) (my - y);
            return true;
        }
        if (my >= by && my < by + bh) {
            if (mx >= bx1 && mx < bx1 + bw) {
                copySkeleton();
                return true;
            }
            if (mx >= bx2 && mx < bx2 + bw) {
                ClientSettings.adapterDryRun = !ClientSettings.adapterDryRun;
                ClientSettings.touch();
                return true;
            }
            if (mx >= bx3 && mx < bx3 + bw) {
                toggle();
                return true;
            }
        }
        return true;   // 面板内其它区域也吃掉，免得误点机器
    }

    public static boolean mouseDragged(double mx, double my) {
        if (!dragging) return false;
        x = (int) mx - dragDX;
        y = (int) my - dragDY;
        return true;
    }

    public static boolean mouseReleased() {
        boolean was = dragging;
        dragging = false;
        return was;
    }

    // ---------------------------------------------------------------- 动作

    private static void copySkeleton() {
        var mc = Minecraft.getInstance();
        if (!(mc.screen instanceof AbstractContainerScreen<?> cs)) return;
        try {
            mc.keyboardHandler.setClipboard(DebugOverlay.skeleton(cs));
            FolioCleaner.LOGGER.info("[Folio Cleaner] 已把适配器骨架复制到剪贴板");
        } catch (Throwable t) {
            FolioCleaner.LOGGER.warn("[Folio Cleaner] 复制骨架失败", t);
        }
    }

    private static String cut(Font font, String text, int width) {
        if (text == null) return "";
        if (font.width(text) <= width) return text;
        return font.plainSubstrByWidth(text, Math.max(0, width - 6)) + "…";
    }
}
