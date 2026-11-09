package com.yuneko.foliocleaner;

import net.minecraft.resources.ResourceLocation;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 「这条配方该不该被隐藏」的**通用判断**，给别家配方浏览器（REI / EMI）的适配器用。
 *
 * <p>JEI 那边有完整的 {@code Selector}（能按原料/产物/范围匹配），但 REI / EMI 的接口只给到
 * 「配方 id」这一层（REI 是 {@code Display#getDisplayLocation()}，EMI 是 {@code EmiRecipe#getId()}），
 * 所以这里只做**能确定**的三件事：
 * <ul>
 *   <li>{@code recipe} 规则 → 配方 id 完全相同</li>
 *   <li>{@code mod} 规则 → 命名空间（modid）相同</li>
 *   <li>{@code keep} 白名单 → 不在白名单里的命名空间全隐藏（和 {@code Selector} 一个语义）</li>
 * </ul>
 *
 * <p>⚠️ 故意不猜：`item` / `category` 规则在这里一律不命中 ——
 * 它们需要看槽位或 RecipeType，靠 id 猜会误伤。物品级隐藏另外走
 * {@code c:hidden_from_recipe_viewers} 公共 tag（三家都认）。
 */
public final class HiddenRecipes {

    /** 规则 revision → 展开后的三个集合（REI 每条 display 每帧都会问一次，不能现算） */
    private static int cachedRevision = Integer.MIN_VALUE;
    private static Set<String> cachedRecipeIds = Set.of();
    private static Set<String> cachedMods = Set.of();
    private static Set<String> cachedKeep = Set.of();

    private HiddenRecipes() {
    }

    private static void ensure() {
        int revision = Rules.revision();
        if (revision == cachedRevision) return;
        Set<String> ids = new LinkedHashSet<>();
        Set<String> mods = new LinkedHashSet<>();
        Set<String> keep = new LinkedHashSet<>();
        for (Rule rule : Rules.all()) {
            if (rule == null || !rule.enabled) continue;
            if (!Rule.ACTION_HIDE.equals(rule.action)) continue;
            if (rule.target == null || rule.target.isBlank()) continue;
            switch (rule.type) {
                case Rule.TYPE_RECIPE -> ids.add(rule.target);
                case Rule.TYPE_MOD -> mods.add(rule.target);
                case Rule.TYPE_KEEP -> keep.add(rule.target);
                default -> {
                    // item / category：id 层面判断不了，交给 JEI 那套（和物品 tag）
                }
            }
        }
        cachedRecipeIds = ids;
        cachedMods = mods;
        cachedKeep = keep;
        cachedRevision = revision;
    }

    public static boolean hides(ResourceLocation id) {
        if (id == null) return false;
        ensure();
        if (cachedRecipeIds.contains(id.toString())) return true;
        String namespace = id.getNamespace();
        if (cachedMods.contains(namespace)) return true;
        // 白名单模式：不在白名单里的模组配方全部隐藏（和 Selector 同语义）
        return !cachedKeep.isEmpty() && !cachedKeep.contains(namespace);
    }

    /** 有没有任何会让别家 viewer 也隐藏的规则（用来决定要不要提示用户） */
    public static boolean anyViewerRule() {
        for (Rule rule : Rules.all()) {
            if (rule == null || !rule.enabled) continue;
            if (!Rule.ACTION_HIDE.equals(rule.action)) continue;
            if (Rule.TYPE_RECIPE.equals(rule.type) || Rule.TYPE_MOD.equals(rule.type)
                    || Rule.TYPE_KEEP.equals(rule.type)) {
                return true;
            }
        }
        return false;
    }
}
