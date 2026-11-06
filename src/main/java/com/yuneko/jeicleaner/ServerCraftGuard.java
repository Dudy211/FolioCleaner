package com.yuneko.jeicleaner;

import com.yuneko.jeicleaner.compat.CraftGuards;
import net.minecraft.world.Container;

/**
 * 兼容入口：转发到 {@link CraftGuards}。
 *
 * <p>具体适配器在 {@code com.yuneko.jeicleaner.compat} 包里，
 * 新增一种工作台只需要往那里加一个 CraftGuard。
 */
public final class ServerCraftGuard {

    private ServerCraftGuard() {
    }

    public static boolean isBlocked(Container inputs) {
        return CraftGuards.isBlocked(inputs);
    }
}
