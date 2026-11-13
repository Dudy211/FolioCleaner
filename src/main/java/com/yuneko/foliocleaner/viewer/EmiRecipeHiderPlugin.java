package com.yuneko.foliocleaner.viewer;

import com.yuneko.foliocleaner.FolioCleaner;
import com.yuneko.foliocleaner.Rules;
import dev.emi.emi.api.EmiEntrypoint;
import dev.emi.emi.api.EmiPlugin;
import dev.emi.emi.api.EmiRegistry;
import net.minecraft.resources.ResourceLocation;

/**
 * EMI 的「配方级隐藏」适配器。
 *
 * <p>⚠️ EMI 的接口只有**初始化期**能移除配方：
 * <pre>
 * dev.emi.emi.api.EmiRegistry#removeRecipes(Predicate&lt;EmiRecipe&gt;)
 * dev.emi.emi.api.EmiRegistry#removeRecipes(ResourceLocation)
 * dev.emi.emi.api.recipe.EmiRecipe#getId()
 * </pre>
 * 运行期 {@code EmiApi} 只有 getter，所以**改完规则要重开游戏**（EMI 那边不会自己刷新）。
 * 界面上会如实标出来。
 *
 * <p>用编译期依赖（{@code compile_only_depend/emi.jar}）+ {@code @EmiEntrypoint} 注解接进来 ——
 * 这是 EMI 官方给的插件入口，比反射去抢它的内部注册表可靠。
 * 没装 EMI 时 Forge/EMI 都不会加载这个类，不会触发 {@code NoClassDefFoundError}。
 */
@EmiEntrypoint
public final class EmiRecipeHiderPlugin implements EmiPlugin {

    @Override
    public void register(EmiRegistry registry) {
        try {
            // 插件注册发生在模组初始化阶段，规则可能还没读进来 → 补一次
            if (Rules.size() == 0) {
                Rules.load();
            }
            // ★用 Set 去重：EMI 可能对同一配方多次求值，直接 ++ 会把数字算高
            java.util.Set<ResourceLocation> removed = new java.util.HashSet<>();
            // ★判断口径与运行期完全一致（同一份 RuleMatcher + EmiFacts）：
            //   以前这里只按配方 id / modid，物品级和分类级规则在 EMI 里是失效的。
            registry.removeRecipes(recipe -> {
                try {
                    if (recipe == null) return false;
                    ResourceLocation id = recipe.getId();
                    if (id == null) return false;
                    com.yuneko.foliocleaner.viewer.RuleMatcher.Facts facts =
                            com.yuneko.foliocleaner.viewer.EmiFacts.of(recipe);
                    if (facts == null || !com.yuneko.foliocleaner.viewer.RuleMatcher.hides(facts)) return false;
                    removed.add(id);
                    return true;
                } catch (Throwable t) {
                    return false;
                }
            });
            // ★物品级隐藏：推到 EMI 的插件过滤链（bake=false —— EMI 紧接着自己会 bakeFiltered，
            //   这里抢着 bake 反而可能和它的重载线程打架）
            EmiBackend.pushItemFilter(false);

            ViewerStatus.emiPluginRan = true;
            ViewerStatus.emiRemoved = removed.size();
            FolioCleaner.LOGGER.info("[Folio Cleaner] 已向 EMI 注册配方过滤器（移除 {} 条；规则改动需重开游戏）",
                    removed.size());
        } catch (Throwable t) {
            FolioCleaner.LOGGER.warn("[Folio Cleaner] 注册 EMI 配方过滤器失败", t);
        }
    }
}
