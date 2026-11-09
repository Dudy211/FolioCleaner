package com.yuneko.foliocleaner.client;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ResultContainer;
import net.minecraft.world.inventory.Slot;

import java.lang.reflect.Field;

/**
 * 调试面板：在**任何容器界面**上叠加槽位编号，帮玩家写适配器。
 *
 * <p>做法：反射读 {@code AbstractContainerScreen} 的 {@code leftPos/topPos}（开发名）或
 * {@code f_97735_/f_97736_}（SRG 名），然后把编号画在 {@code slot.x/slot.y} 上 ——
 * 不用给容器界面加 mixin/accessor，任何模组的界面都通用。
 *
 * <p>配色：普通槽位灰白、结果槽（{@link ResultContainer}）绿色、其它容器淡蓝。
 */
public final class DebugOverlay {

    private static Field leftPos;
    private static Field topPos;
    private static boolean broken;

    private DebugOverlay() {
    }

    /** 画编号 + 顶部标题 */
    public static void render(GuiGraphics gg, AbstractContainerScreen<?> screen) {
        if (broken) return;
        try {
            int left = fieldInt(left(false), screen);
            int top = fieldInt(fieldIntField(), screen);
            AbstractContainerMenu menu = screen.getMenu();

            gg.drawString(screen.getMinecraft().font,
                    "§e[调试] §f" + menu.getClass().getSimpleName() + " §8槽位 " + menu.slots.size(),
                    left, top - 22, 0xFFFFFF, true);

            for (int i = 0; i < menu.slots.size(); i++) {
                Slot slot = menu.getSlot(i);
                String color = slot.container instanceof ResultContainer ? "§a" : "§7";
                gg.drawString(screen.getMinecraft().font, color + i,
                        left + slot.x, top + slot.y, 0xFFFFFF, true);
            }
        } catch (Throwable t) {
            broken = true;
        }
    }

    /** 生成 json 骨架（给剪贴板用） */
    public static String skeleton(AbstractContainerScreen<?> screen) {
        AbstractContainerMenu menu = screen.getMenu();
        StringBuilder result = new StringBuilder();
        StringBuilder inputs = new StringBuilder();
        for (int i = 0; i < menu.slots.size(); i++) {
            Slot slot = menu.getSlot(i);
            boolean isResult = slot.container instanceof ResultContainer;
            StringBuilder target = isResult ? result : inputs;
            if (target.length() > 0) target.append(", ");
            target.append(i);
        }
        String simple = menu.getClass().getSimpleName();
        return "{\n"
                + "  \"id\": \"" + simple.toLowerCase(java.util.Locale.ROOT) + "\",\n"
                + "  \"name\": \"" + simple + "\",\n"
                + "  \"forVersion\": \"请填模组版本\",\n"
                + "  \"comment\": \"由调试面板生成，请核对槽位\",\n"
                + "  \"scope\": { \"menu\": \"" + menu.getClass().getName() + "\" },\n"
                + "  \"probe\": { \"result\": [" + result + "], \"inputs\": [" + inputs + "] },\n"
                + "  \"action\": \"clearResult\"\n"
                + "}\n";
    }

    /** 槽位清单（聊天里展示） */
    public static String listing(AbstractContainerScreen<?> screen) {
        AbstractContainerMenu menu = screen.getMenu();
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < menu.slots.size(); i++) {
            Slot slot = menu.getSlot(i);
            String id = slot.getItem().isEmpty() ? "-"
                    : net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(slot.getItem().getItem()).toString();
            sb.append(i).append(' ').append(id);
            if (slot.container instanceof ResultContainer) sb.append(" §a(结果)");
            sb.append('\n');
        }
        return sb.toString();
    }

    // ---------------------------------------------------------------- 反射

    private static Field left(boolean unused) {
        if (leftPos != null) return leftPos;
        for (String n : new String[]{"leftPos", "f_97735_"}) {
            try {
                Field f = AbstractContainerScreen.class.getDeclaredField(n);
                f.setAccessible(true);
                leftPos = f;
                return f;
            } catch (Throwable ignored) {
            }
        }
        broken = true;
        return null;
    }

    private static Field fieldIntField() {
        if (topPos != null) return topPos;
        for (String n : new String[]{"topPos", "f_97736_"}) {
            try {
                Field f = AbstractContainerScreen.class.getDeclaredField(n);
                f.setAccessible(true);
                topPos = f;
                return f;
            } catch (Throwable ignored) {
            }
        }
        broken = true;
        return null;
    }

    private static int fieldInt(Field f, Object owner) throws IllegalAccessException {
        return f == null ? 0 : f.getInt(owner);
    }
}
