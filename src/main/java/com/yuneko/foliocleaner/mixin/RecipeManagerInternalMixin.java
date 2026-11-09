package com.yuneko.foliocleaner.mixin;

import com.yuneko.foliocleaner.RuntimeFilter;
import mezz.jei.api.recipe.IFocusGroup;
import mezz.jei.api.recipe.RecipeType;
import mezz.jei.library.recipes.PluginManager;
import mezz.jei.library.recipes.RecipeManagerInternal;
import mezz.jei.library.recipes.collect.RecipeTypeData;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.util.stream.Stream;

/**
 * 在 JEI 输出配方流的最后一刻过滤。
 *
 * <p>{@code RecipeManagerInternal#getRecipesStream} 会把所有 {@code IRecipeManagerPlugin} 的结果合并，
 * 这里对最终结果按规则再过一遍 —— 不管配方是注册进来的还是动态生成的。
 *
 * <p><b>1. 为什么用 {@code @Redirect} 而不是 {@code @Inject(at = RETURN)}</b>：
 * 上一版在 RETURN 上注入，在 JEI 15.74 上直接把 {@code getRecipesStream} 的字节码校验搞挂了：
 * <pre>
 * java.lang.VerifyError: Bad local variable type
 *   ... getRecipesStream ... @119: iload_3
 *   Type 'java.util.stream.Stream' (locals[3]) is not assignable to integer
 * </pre>
 * 原因是那个方法有多个返回点，RETURN 注入要为每个返回点生成一份回调、还要分配局部变量，
 * 15.74 改过方法结构后就撞车了。{@code @Redirect} 只改**一个调用点**，不碰返回、不开新局部变量，稳得多。
 *
 * <p><b>2. 为什么 {@code require = 0}</b>：JEI 版本变化导致这个调用点消失时静默跳过，
 * 宁可少一层兜底，也不能让客户端起不来。
 */
@Mixin(value = RecipeManagerInternal.class, remap = false)
public class RecipeManagerInternalMixin {

    @Redirect(
            method = "getRecipesStream",
            at = @At(value = "INVOKE",
                    target = "Lmezz/jei/library/recipes/PluginManager;"
                            + "getRecipes(Lmezz/jei/library/recipes/collect/RecipeTypeData;"
                            + "Lmezz/jei/api/recipe/IFocusGroup;Z)Ljava/util/stream/Stream;"),
            require = 0, remap = false)
    @SuppressWarnings({"rawtypes", "unchecked"})
    private Stream<?> folio$filterRecipes(PluginManager manager, RecipeTypeData<?> data,
                                              IFocusGroup focuses, boolean includeHidden) {
        Stream<?> original = manager.getRecipes(data, focuses, includeHidden);
        if (original == null) return null;
        try {
            final RecipeType<?> type = (data != null && data.getRecipeCategory() != null)
                    ? data.getRecipeCategory().getRecipeType()
                    : null;
            return original.filter(recipe -> RuntimeFilter.keep(recipe, type));
        } catch (Throwable t) {
            // 过滤出问题就保持原样，不影响 JEI 正常显示
            return original;
        }
    }
}
