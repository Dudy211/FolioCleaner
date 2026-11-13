package com.yuneko.foliocleaner.viewer;

import com.yuneko.foliocleaner.Rule;
import com.yuneko.foliocleaner.Rules;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 与具体配方浏览器无关的**规则求值器**。
 *
 * <p>JEI 那边有 {@code Selector}（绑定 {@code Recipe} 对象与 JEI 的 {@code RecipeType}），
 * REI / EMI 拿不到那些对象，只能提供「一条配方的长相」——
 * {@link Facts} 就是那个长相：配方 id、分类 id、命名空间、每个输入槽能接受的物品、产物。
 *
 * <p>语义和 {@code Selector} 严格对齐（否则同一套规则在三家 viewer 里结果不一样，
 * 那种 bug 用户根本没法理解）：
 * <ul>
 *   <li>{@code recipe} → id 完全相同</li>
 *   <li>{@code category} → 分类 id 命中</li>
 *   <li>{@code keep} → 不在白名单的命名空间全隐藏</li>
 *   <li>{@code mod} → 命名空间相同 + 范围（类型 / 搭配）满足</li>
 *   <li>{@code item} → 范围满足 +（产物命中 或 原料命中且搭配满足）</li>
 * </ul>
 *
 * <p>只认 {@code action=hide}：{@code remove} 是服务端的事（{@code RecipeRemover}），
 * 和客户端浏览器无关。
 */
public final class RuleMatcher {

    /** 一条配方在某个 viewer 眼里的样子 */
    public record Facts(String recipeId,
                        String categoryUid,
                        String modId,
                        Set<String> typeIds,
                        List<Set<String>> inputs,
                        Set<String> outputs) {
    }

    private record Range(Set<String> types, List<List<String>> groups) {
    }

    private record ItemFilter(String itemId, boolean output, boolean ingredient, List<Range> ranges) {
    }

    private record ModFilter(String modId, List<Range> ranges) {
    }

    private record Compiled(Set<String> recipeIds, Set<String> keep,
                            Set<String> categories, Set<String> hiddenItems,
                            List<ItemFilter> itemFilters, List<ModFilter> modFilters) {
    }

    /**
     * ★这是渲染热路径：REI 每帧会对**每一个** display 调一次 {@link #hides}。
     * 所以先做一次无锁快路径（volatile 读），只有规则真的变了才进 synchronized。
     */
    private static volatile int cachedRevision = Integer.MIN_VALUE;
    private static volatile Compiled cached;

    private RuleMatcher() {
    }

    private static Compiled compiled() {
        Compiled c = cached;
        if (c != null && Rules.revision() == cachedRevision) return c;
        return compile();
    }

    private static synchronized Compiled compile() {
        int revision = Rules.revision();
        if (cached != null && revision == cachedRevision) return cached;

        Set<String> recipeIds = new HashSet<>();
        Set<String> keep = new HashSet<>();
        Set<String> categories = new HashSet<>();
        Set<String> hiddenItems = new HashSet<>();
        List<ItemFilter> items = new ArrayList<>();
        List<ModFilter> mods = new ArrayList<>();

        for (Rule rule : Rules.all()) {
            if (rule == null || !rule.enabled || !rule.isValid()) continue;
            // 只有「隐藏」和浏览器有关；删除走服务端
            if (!Rule.ACTION_HIDE.equals(rule.action)) continue;
            if (rule.target == null || rule.target.isBlank()) continue;

            switch (rule.type) {
                case Rule.TYPE_RECIPE -> recipeIds.add(rule.target);
                case Rule.TYPE_KEEP -> keep.add(rule.target);
                case Rule.TYPE_CATEGORY -> categories.add(rule.target);
                case Rule.TYPE_MOD -> mods.add(new ModFilter(rule.target, compileRanges(rule)));
                case Rule.TYPE_ITEM -> {
                    items.add(new ItemFilter(rule.target, rule.hideOutput, rule.hideIngredient,
                            compileRanges(rule)));
                    if (rule.hideItem) hiddenItems.add(rule.target);
                }
                default -> {
                }
            }
        }

        cached = new Compiled(recipeIds, keep, categories, hiddenItems, items, mods);
        cachedRevision = revision;
        return cached;
    }

    private static List<Range> compileRanges(Rule rule) {
        List<Range> out = new ArrayList<>();
        List<Rule.Range> raw = rule.effectiveRanges();
        if (raw != null) {
            for (Rule.Range range : raw) {
                Set<String> types = new LinkedHashSet<>();
                if (range.hasType()) {
                    for (String part : range.type.split(",")) {
                        String t = part.trim();
                        if (!t.isEmpty()) types.add(t);
                    }
                }
                List<List<String>> groups = new ArrayList<>();
                for (List<String> group : range.groups()) groups.add(new ArrayList<>(group));
                out.add(new Range(types.isEmpty() ? null : types, groups));
            }
        }
        if (out.isEmpty()) out.add(new Range(null, List.of()));
        return out;
    }

    /** 这条配方该不该隐藏 */
    public static boolean hides(Facts facts) {
        if (facts == null) return false;
        Compiled c = compiled();

        if (facts.recipeId() != null && c.recipeIds.contains(facts.recipeId())) return true;

        if (facts.categoryUid() != null && c.categories.contains(facts.categoryUid())) return true;

        String mod = facts.modId() == null ? "" : facts.modId();
        if (!c.keep.isEmpty() && !c.keep.contains(mod)) return true;

        for (ModFilter filter : c.modFilters) {
            if (!mod.equals(filter.modId)) continue;
            for (Range range : filter.ranges) {
                if (typeOk(facts, range) && partnerOk(facts, range.groups, -1)) return true;
            }
        }

        for (ItemFilter filter : c.itemFilters) {
            for (Range range : filter.ranges) {
                if (!typeOk(facts, range)) continue;
                if (filter.output && facts.outputs() != null && facts.outputs().contains(filter.itemId)) {
                    return true;
                }
                if (filter.ingredient) {
                    int slot = slotOf(facts, filter.itemId);
                    if (slot >= 0 && partnerOk(facts, range.groups, slot)) return true;
                }
            }
        }
        return false;
    }

    /**
     * 有没有「会隐藏**配方**」的规则（不含只藏物品本体的）。
     * 用来判断某个 viewer 是否真的需要重建配方索引。
     */
    public static boolean hasRecipeRules() {
        Compiled c = compiled();
        if (!c.recipeIds.isEmpty() || !c.categories.isEmpty() || !c.keep.isEmpty()) return true;
        if (!c.modFilters.isEmpty()) return true;
        for (ItemFilter f : c.itemFilters) {
            if (f.output || f.ingredient) return true;
        }
        return false;
    }

    /** 整个分类是否被隐藏（category 规则） */
    public static boolean hiddenCategory(String uid) {
        return uid != null && compiled().categories.contains(uid);
    }

    /** 需要从物品列表里摘掉的物品 id（hideItem 规则） */
    public static Set<String> hiddenItems() {
        return compiled().hiddenItems;
    }

    /** 某类规则涉及到的分类 id（REI 的 CategoryVisibilityPredicate 用） */
    public static Set<String> hiddenCategories() {
        return compiled().categories;
    }

    // ------------------------------------------------------------ 内部

    private static boolean typeOk(Facts facts, Range range) {
        if (range.types == null) return true;
        if (facts.typeIds() != null) {
            for (String id : facts.typeIds()) {
                if (id != null && range.types.contains(id)) return true;
            }
        }
        return false;
    }

    private static int slotOf(Facts facts, String itemId) {
        if (facts.inputs() == null || itemId == null) return -1;
        for (int i = 0; i < facts.inputs().size(); i++) {
            Set<String> slot = facts.inputs().get(i);
            if (slot != null && slot.contains(itemId)) return i;
        }
        return -1;
    }

    /** 搭配分组：外层「或」，内层「与」；{@code skipSlot} 是被主物品占用的槽 */
    private static boolean partnerOk(Facts facts, List<List<String>> groups, int skipSlot) {
        if (groups == null || groups.isEmpty()) return true;
        for (List<String> group : groups) {
            if (group.isEmpty()) return true;
            boolean all = true;
            for (String partner : group) {
                if (!slotAccepts(facts, partner, skipSlot)) {
                    all = false;
                    break;
                }
            }
            if (all) return true;
        }
        return false;
    }

    private static boolean slotAccepts(Facts facts, String partner, int skipSlot) {
        if (facts.inputs() == null || partner == null || partner.isBlank()) return false;
        for (int i = 0; i < facts.inputs().size(); i++) {
            if (i == skipSlot) continue;
            Set<String> slot = facts.inputs().get(i);
            if (slot == null || slot.isEmpty()) continue;
            for (String id : slot) {
                if (partner.equals(id)) return true;
                if (partner.startsWith("#") && itemHasTag(id, partner.substring(1))) return true;
            }
        }
        return false;
    }

    private static boolean itemHasTag(String itemId, String tagId) {
        try {
            ResourceLocation tag = ResourceLocation.tryParse(tagId);
            ResourceLocation item = ResourceLocation.tryParse(itemId);
            if (tag == null || item == null) return false;
            Item it = BuiltInRegistries.ITEM.get(item);
            if (it == null || it == Items.AIR) return false;
            return new ItemStack(it).is(TagKey.create(Registries.ITEM, tag));
        } catch (Throwable t) {
            return false;
        }
    }
}
