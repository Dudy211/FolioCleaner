package com.yuneko.jeicleaner;

import mezz.jei.api.recipe.RecipeType;
import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Recipe;

import java.lang.reflect.Method;
import java.util.Collection;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * 运行时配方过滤器（Mixin 兜底用）。
 *
 * <p>两类目标：
 * <ol>
 *   <li>普通 {@link Recipe}：按 id / 产物 / 原料匹配</li>
 *   <li>mod 自定义的 JEI 配方对象（比如 Iron's Spellbooks 的 {@code ArcaneAnvilJeiRecipe}，
 *       它根本不是 Recipe 接口的实现）：反射调它的 {@code getRecipeItems()} 拿到
 *       左输入 / 右输入 / 输出三组 ItemStack，再按物品匹配</li>
 * </ol>
 */
public final class RuntimeFilter {

    private static List<Rule> rules = List.of();
    private static int seenRevision = -1;
    private static final Map<Object, Boolean> CACHE = new IdentityHashMap<>();

    private RuntimeFilter() {
    }

    /** @return true = 保留这条配方 */
    public static boolean keep(Object recipe, Object jeiType) {
        if (recipe == null) return true;

        refresh();
        if (rules.isEmpty()) return true;

        Boolean cached = CACHE.get(recipe);
        if (cached != null) return cached;

        boolean hidden;
        try {
            if (recipe instanceof Recipe<?> vanilla) {
                var level = Minecraft.getInstance().level;
                if (level == null) return true;
                hidden = RecipeRemover.matches(vanilla, rules, level.registryAccess());
            } else {
                hidden = matchesCustom(recipe, jeiType);
            }
        } catch (Throwable t) {
            hidden = false;
        }
        if (CACHE.size() > 20000) CACHE.clear(); // 动态插件每次都造新对象，避免无限增长
        CACHE.put(recipe, !hidden);
        return !hidden;
    }

    /** 能否用反射拿到配方物品；拿不到就只能走 JEI 槽位（Cleaner 里） */
    public static boolean canInspect(Object recipe) {
        return recipe != null && invoke(recipe, "getRecipeItems") != null;
    }

    /**
     * 非 Recipe 对象的匹配：调 {@code getRecipeItems()}，按约定拿三组物品
     * （a = 主输入，b = 副输入，c = 输出）。
     */
    private static boolean matchesCustom(Object recipe, Object jeiType) {
        Object items = invoke(recipe, "getRecipeItems");
        if (items == null) {
            // 退一步：直接扫一遍字段，有些 mod 的配方对象没有 getRecipeItems
            return matchesFields(recipe, jeiType, null, null, null);
        }
        Object inputA = invoke(items, "a");
        Object inputB = invoke(items, "b");
        Object output = invoke(items, "c");
        return matchesFields(recipe, jeiType, inputA, inputB, output);
    }

    private static boolean matchesFields(Object recipe, Object jeiType,
                                         Object inputA, Object inputB, Object output) {
        String typeId = jeiType instanceof RecipeType<?> rt ? rt.getUid().toString() : null;

        for (Rule rule : rules) {
            if (rule == null || !rule.enabled) continue;
            if (!Rule.TYPE_ITEM.equals(rule.type)) continue;

            Item item = itemOf(rule.target);
            if (item == null) continue;

            for (Rule.Range range : rule.effectiveRanges()) {
                if (!typeMatches(typeId, range.type)) continue;

                if (rule.hideOutput && containsItem(output, item)) return true;
                if (!rule.hideIngredient) continue;

                boolean a = containsItem(inputA, item);
                boolean b = containsItem(inputB, item);
                if (!a && !b) continue;

                for (java.util.List<String> group : range.groups()) {
                    if (group.isEmpty()) return true; // 没有搭配 = 不限

                    boolean all = true;
                    for (String partner : group) {
                        boolean ok;
                        if (a ^ b) {
                            // 目标只在一边：搭配必须落在另一边
                            ok = a ? containsPartner(inputB, partner) : containsPartner(inputA, partner);
                        } else {
                            // 两边都含目标：任一侧接受即可
                            ok = containsPartner(inputA, partner) || containsPartner(inputB, partner);
                        }
                        if (!ok) {
                            all = false;
                            break;
                        }
                    }
                    if (all) return true; // 这一组「与」全部满足
                }
            }
        }
        return false;
    }

    private static Item itemOf(String id) {
        ResourceLocation rl = ResourceLocation.tryParse(id);
        if (rl == null) return null;
        Item item = BuiltInRegistries.ITEM.get(rl);
        return item == net.minecraft.world.item.Items.AIR ? null : item;
    }

    private static boolean containsItem(Object holder, Item item) {
        if (holder == null) return false;
        if (holder instanceof Collection<?> collection) {
            for (Object o : collection) {
                if (o instanceof ItemStack stack && !stack.isEmpty() && stack.getItem() == item) return true;
            }
            return false;
        }
        if (holder instanceof ItemStack stack) {
            return !stack.isEmpty() && stack.getItem() == item;
        }
        return false;
    }

    private static boolean typeMatches(String typeId, String typeFilter) {
        if (typeFilter == null || typeFilter.isBlank()) return true;
        if (typeId == null) return false;
        for (String part : typeFilter.split(",")) {
            if (part.trim().equals(typeId)) return true;
        }
        return false;
    }

    private static boolean containsAnyPartner(Object holder, java.util.List<String> partners) {
        if (partners == null || partners.isEmpty()) return true;
        for (String partner : partners) {
            if (containsPartner(holder, partner)) return true;
        }
        return false;
    }

    /** holder（Collection<ItemStack> / ItemStack）里是否有符合 partner（物品 id 或 #标签）的栈 */
    private static boolean containsPartner(Object holder, String partner) {
        if (holder == null || partner == null || partner.isBlank()) return false;
        try {
            if (partner.startsWith("#")) {
                ResourceLocation id = ResourceLocation.tryParse(partner.substring(1));
                if (id == null) return false;
                var tag = net.minecraft.tags.TagKey.create(
                        net.minecraft.core.registries.Registries.ITEM, id);
                if (holder instanceof Collection<?> collection) {
                    for (Object o : collection) {
                        if (o instanceof ItemStack stack && !stack.isEmpty() && stack.is(tag)) return true;
                    }
                    return false;
                }
                return holder instanceof ItemStack stack && !stack.isEmpty() && stack.is(tag);
            }
            Item item = itemOf(partner);
            return item != null && containsItem(holder, item);
        } catch (Throwable t) {
            return false;
        }
    }

    private static Object invoke(Object target, String name) {
        if (target == null) return null;
        try {
            Method method = target.getClass().getMethod(name);
            method.setAccessible(true);
            return method.invoke(target);
        } catch (Throwable t) {
            return null;
        }
    }

    private static void refresh() {
        int rev = Rules.revision();
        if (rev != seenRevision) {
            seenRevision = rev;
            CACHE.clear();
            rules = Rules.all();
        }
    }

    /** 清空缓存（规则变化时由 Mixin 侧调用也可以，这里靠 revision 自动处理） */
    public static void invalidate() {
        CACHE.clear();
        seenRevision = -1;
    }
}
