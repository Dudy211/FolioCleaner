package com.hollingsworth.arsnouveau.api.ritual;

import net.minecraft.world.item.ItemStack;

/**
 * **只用于编译的桩（stub）**，不会被打进 jar。
 *
 * <p>为什么需要它：Mixin 的注解处理器（`mixin-processor`）在编译期会去 classpath 上找
 * `@Mixin(targets = "…")` 指定的目标类，找不到就报
 * `Mixin target … could not be found` 并让整个编译失败。
 *
 * <p>所以我们在这里放一个**最小签名**的假类，只为让注解处理器闭嘴。
 * 真实的方法体/字段由玩家安装的 Ars Nouveau 提供；运行时靠
 * {@code foliocleaner.hooks.mixins.json} 的 plugin + `require = 0` 兜底。
 *
 * <p>★ 改签名时要注意：这里的方法必须和 Ars 1.20.1 的 `canConsumeItem(ItemStack)`
 * 保持一致，否则注解处理器认不出注入点（但运行时不会崩，只是 hook 静默失效）。
 */
public class AbstractRitual {

    /** 桩：真实实现由 Ars Nouveau 提供 */
    public boolean canConsumeItem(ItemStack stack) {
        return false;
    }
}
