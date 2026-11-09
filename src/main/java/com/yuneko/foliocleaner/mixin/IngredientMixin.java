package com.yuneko.foliocleaner.mixin;

import com.yuneko.foliocleaner.UnifyRuntime;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 「统一 · 运行时」模式（B2）的兜底匹配：只在 {@code Ingredient#test} 放宽，**不动配方对象**。
 *
 * <p>为什么用 mixin 而不是改数组：这个模式要的语义就是「显示照旧、匹配放宽」——
 * 改数组（B1）会连 JEI/配方书里的显示一起变宽，那是另一种取舍。
 *
 * <p>代价控制：
 * <ul>
 *   <li>第一行只读一个 volatile 布尔（{@code matchActive}），模式没开时几乎没有开销</li>
 *   <li>只有「配方里含同组成员」的原料才会进 {@code EXTRA} 表，
 *       其余原料在 {@code accepts()} 里第一次 identity 查表就返回 false</li>
 * </ul>
 *
 * <p>方法名 {@code test} 来自 {@code Predicate<ItemStack>}，不参与混淆，SRG 下也叫 {@code test}。
 */
@Mixin(Ingredient.class)
public abstract class IngredientMixin {

    @Inject(method = "test", at = @At("HEAD"), cancellable = true, require = 0)
    private void folio$widenTest(ItemStack stack, CallbackInfoReturnable<Boolean> cir) {
        if (UnifyRuntime.accepts(this, stack)) {
            cir.setReturnValue(true);
        }
    }
}
