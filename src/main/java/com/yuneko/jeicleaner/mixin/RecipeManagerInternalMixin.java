package com.yuneko.jeicleaner.mixin;

import com.yuneko.jeicleaner.RuntimeFilter;
import mezz.jei.api.recipe.IFocusGroup;
import mezz.jei.api.recipe.RecipeType;
import mezz.jei.library.recipes.RecipeManagerInternal;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.stream.Stream;

/**
 * 在 JEI 输出配方流的最后一刻过滤。
 *
 * <p>{@code RecipeManagerInternal#getRecipesStream} 会把所有 {@code IRecipeManagerPlugin}
 * 的结果合并起来，这里对最终结果按规则再过一遍。
 *
 * <p>故意 {@code require = 0}：JEI 版本变动导致注入失败时静默跳过，不能因此让客户端崩。
 */
@Mixin(value = RecipeManagerInternal.class, remap = false)
public class RecipeManagerInternalMixin {

    @Inject(method = "getRecipesStream", at = @At("RETURN"), require = 0, cancellable = true, remap = false)
    private void jeicleaner$filterRecipes(RecipeType<?> recipeType, IFocusGroup focuses,
                                          boolean includeHidden,
                                          CallbackInfoReturnable<Stream<?>> cir) {
        Stream<?> original = cir.getReturnValue();
        if (original == null) return;
        try {
            cir.setReturnValue(original.filter(recipe -> RuntimeFilter.keep(recipe, recipeType)));
        } catch (Throwable ignored) {
            // 过滤出问题时保持原样
        }
    }
}
