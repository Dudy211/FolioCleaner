package com.yuneko.jeicleaner.compat;

import net.minecraft.world.Container;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 适配器注册表。
 *
 * <p>所有 {@link CraftGuard} 在这里登记；Mixin 拦截到「工作台生成结果」时
 * 调 {@link #isBlocked(Container)} 逐个询问。
 *
 * <p>新增适配器只需要在静态块里加一行 {@code register(new XxxGuard())}，
 * 不用改 Mixin。
 */
public final class CraftGuards {

    private static final List<CraftGuard> GUARDS = new ArrayList<>();

    static {
        // 内置：铁砧系（原版铁砧、Iron's Spellbooks 奥术铁砧……）
        register(new CombinerCraftGuard());
    }

    private CraftGuards() {
    }

    public static void register(CraftGuard guard) {
        if (guard == null) return;
        if (GUARDS.contains(guard)) return;
        GUARDS.add(guard);
    }

    public static List<CraftGuard> all() {
        return Collections.unmodifiableList(GUARDS);
    }

    /** 任意适配器认为该拦 → 拦 */
    public static boolean isBlocked(Container inputs) {
        if (inputs == null) return false;
        for (CraftGuard guard : GUARDS) {
            try {
                if (guard.blocks(inputs)) return true;
            } catch (Throwable ignored) {
                // 单个适配器出错不影响其它
            }
        }
        return false;
    }

    /** 只询问指定适配器（Mixin 给自己的目标类型用，避免串味） */
    public static boolean isBlocked(Container inputs, CraftGuard guard) {
        if (inputs == null || guard == null) return false;
        try {
            return guard.blocks(inputs);
        } catch (Throwable t) {
            return false;
        }
    }
}
