package com.yuneko.foliocleaner.mixin;

import com.yuneko.foliocleaner.FolioCleaner;
import com.yuneko.foliocleaner.LazyPages;
import com.yuneko.foliocleaner.client.PagedRecipeLayoutList;
import mezz.jei.api.recipe.IFocusGroup;
import mezz.jei.api.recipe.IRecipeManager;
import mezz.jei.api.recipe.category.IRecipeCategory;
import mezz.jei.common.config.RecipeSorterStage;
import mezz.jei.gui.recipes.IRecipeLayoutWithButtonsFactory;
import mezz.jei.gui.recipes.RecipeGuiLogic;
import mezz.jei.gui.recipes.layouts.IRecipeLayoutList;
import mezz.jei.gui.recipes.lookups.IFocusedRecipes;
import mezz.jei.gui.recipes.lookups.ILookupState;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;
import java.util.Set;

/**
 * 把「一次性建完整个分类的布局」换成「只建当前页」。
 *
 * <p>JEI 的 {@code createRecipeLayoutsWithButtons} 会
 * {@code recipes.stream().map(createRecipeLayoutDrawable).map(factory::create).toList()}，
 * 也就是有多少条配方就建多少个布局。1400 条的奥术铁砧按 U 直接 OOM 就是这个原因
 * （{@code LazySortedRecipeLayoutList} 名字里的 Lazy 只管排序）。
 *
 * <p>配方条数超过 {@link com.yuneko.foliocleaner.LazyPages#MAX_EAGER} 时，改交给
 * {@link PagedRecipeLayoutList}：分页用真实条数算，只有当前页才建布局。
 * 小分类原样走 JEI 的逻辑（保留「可合成优先」排序等行为）。
 */
@Mixin(value = RecipeGuiLogic.class, remap = false)
public abstract class RecipeGuiLogicMixin {

    @Shadow
    @Final
    private IRecipeManager recipeManager;

    @Shadow
    @Final
    private IRecipeLayoutWithButtonsFactory recipeLayoutFactory;

    @Shadow
    private ILookupState state;

    @Inject(method = "createRecipeLayoutsWithButtons", at = @At("HEAD"), cancellable = true,
            require = 0, remap = false)
    @SuppressWarnings({"unchecked", "rawtypes"})
    private void folio$lazyLayouts(Set<RecipeSorterStage> sorterStages,
                                        IFocusedRecipes<?> focusedRecipes,
                                        AbstractContainerMenu containerMenu,
                                        Player player,
                                        CallbackInfoReturnable<IRecipeLayoutList> cir) {
        try {
            // 开关关着 / 当前 JEI 版本不支持 → 完全按 JEI 原逻辑走
            if (!LazyPages.enabled()) return;

            List<?> recipes = focusedRecipes.getRecipes();
            if (recipes == null || recipes.size() <= LazyPages.MAX_EAGER) return;

            IRecipeCategory<?> category = focusedRecipes.getRecipeCategory();
            IFocusGroup focuses = state == null ? null : state.getFocuses();

            cir.setReturnValue(new PagedRecipeLayoutList(
                    category, (List) recipes, recipeManager, focuses, recipeLayoutFactory));
            FolioCleaner.LOGGER.debug("[Folio Cleaner] {} 条配方改用按页懒建布局（不再一次性建完）",
                    recipes.size());
        } catch (Throwable t) {
            // 出任何问题都退回 JEI 原逻辑：宁可慢，不能崩
            FolioCleaner.LOGGER.warn("[Folio Cleaner] 启用按页懒建布局失败，已回退", t);
        }
    }
}
