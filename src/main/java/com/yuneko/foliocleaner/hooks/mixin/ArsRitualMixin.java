package com.yuneko.foliocleaner.hooks.mixin;

import com.yuneko.foliocleaner.Rules;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 新生魔艺（Ars Nouveau）**仪式**的「删除」规则拦截。
 *
 * <h2>为什么仪式必须 hook，而灌注室/附魔装置不用</h2>
 * 灌注室 {@code IImbuementRecipe extends Recipe}、附魔装置
 * {@code IEnchantingRecipe extends Recipe} —— 都是真配方，删配方就没人能合。
 * 但仪式是 {@code RitualRegistry}（代码注册表）里的 {@code AbstractRitual} 子类，
 * **根本不存在"配方"可删**，所以只能拦。
 *
 * <h2>为什么拦这一个方法就够（万无一失）</h2>
 * {@code AbstractRitual#canConsumeItem(ItemStack)} 是**所有**投料路径的共同终点：
 * <pre>
 *   空手右键 → RitualBrazierTile.startRitual      ┐
 *   红石信号 → startRitual(null)                   ├→ 收集材料
 *   地上物品 → tick() 每 5 tick 扫 ItemEntity      ┘   → tryBurnStack → canConsumeItem ← 这里
 * </pre>
 * 只要对被「删除」的物品返回 false，材料永远集不齐，仪式自然做不出来 —— 三条路径一起废。
 *
 * <h2>为什么用 {@code targets =} 字符串</h2>
 * 主 jar 不能对 Ars 有编译期依赖。字符串目标 + {@code remap = false} +
 * {@link com.yuneko.foliocleaner.hooks.FolioMixinPlugin} 的门控 = 没装就完全不参与。
 */
@Mixin(targets = "com.hollingsworth.arsnouveau.api.ritual.AbstractRitual", remap = false)
public abstract class ArsRitualMixin {

    @Inject(method = "canConsumeItem", at = @At("HEAD"), cancellable = true, require = 0, remap = false)
    private void folio$blockRemovedItems(ItemStack stack, CallbackInfoReturnable<Boolean> cir) {
        try {
            // 适配器界面上关掉「[hook] 新生魔艺·仪式（内置）」后即时失效
            if (com.yuneko.foliocleaner.ClientSettings.disabledGuards.contains("hook:arsnouveau")) return;
            if (Rules.isRemoved(stack)) {
                cir.setReturnValue(false);   // 这个物品不许被仪式消耗
            }
        } catch (Throwable ignored) {
            // 判定出错就放行（宁可少拦一次，也不能让仪式卡死/崩）
        }
    }
}
