package com.yuneko.jeicleaner;

import mezz.jei.api.recipe.IRecipeManager;
import mezz.jei.api.recipe.RecipeType;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 把规则列表编译成若干过滤器，用来快速判断「这条配方该不该隐藏」。
 *
 * <p>配方类型过滤走 <b>RecipeType 对象比较</b>而不是字符串比较：
 * 有些 mod（比如 Iron's Spellbooks）的 JEI category uid 和原版 RecipeType 的注册名不一致，
 * 只比字符串会导致规则永远匹配不上。
 */
public final class Selector {

    /** 编译后的一组范围：类型集合（null = 不限）+ 搭配分组（外层或 / 内层与） */
    private static final class CompiledRange {
        final Set<RecipeType<?>> types;
        final List<List<String>> groups;

        CompiledRange(Set<RecipeType<?>> types, List<List<String>> groups) {
            this.types = types;
            this.groups = groups == null ? List.of() : groups;
        }
    }

    private static final class ItemFilter {
        final Item item;
        final boolean output;
        final boolean ingredient;
        final List<CompiledRange> ranges;

        ItemFilter(Item item, boolean output, boolean ingredient, List<CompiledRange> ranges) {
            this.item = item;
            this.output = output;
            this.ingredient = ingredient;
            this.ranges = ranges;
        }
    }

    private static final class ModFilter {
        final String modid;
        final List<CompiledRange> ranges;

        ModFilter(String modid, List<CompiledRange> ranges) {
            this.modid = modid;
            this.ranges = ranges;
        }
    }

    private final IRecipeManager manager;
    private final List<ItemFilter> itemFilters = new ArrayList<>();
    private final List<ModFilter> modFilters = new ArrayList<>();
    private final Set<ResourceLocation> recipeIds = new HashSet<>();
    private final Set<Item> hideItems = new LinkedHashSet<>();
    private final Set<String> keepMods = new HashSet<>();
    private final Set<RecipeType<?>> hiddenCategories = new LinkedHashSet<>();

    private final Map<Class<?>, RecipeType<?>> classToType = new LinkedHashMap<>();
    private final Map<Recipe<?>, RecipeType<?>> recipeToType = new java.util.IdentityHashMap<>();

    private Selector(IRecipeManager manager) {
        this.manager = manager;
    }

    public static Selector of(List<Rule> rules, IRecipeManager manager) {
        Selector sel = new Selector(manager);
        for (Rule rule : rules) {
            if (rule == null || !rule.isValid() || !rule.enabled) continue;
            switch (rule.type) {
                case Rule.TYPE_MOD:
                    sel.modFilters.add(new ModFilter(rule.target, sel.compileRanges(rule.effectiveRanges())));
                    break;
                case Rule.TYPE_RECIPE: {
                    ResourceLocation id = ResourceLocation.tryParse(rule.target);
                    if (id != null) sel.recipeIds.add(id);
                    break;
                }
                case Rule.TYPE_KEEP:
                    sel.keepMods.add(rule.target);
                    break;
                case Rule.TYPE_CATEGORY: {
                    ResourceLocation id = ResourceLocation.tryParse(rule.target);
                    if (id != null) {
                        sel.manager.getRecipeType(id).ifPresent(sel.hiddenCategories::add);
                    }
                    break;
                }
                case Rule.TYPE_ITEM:
                default: {
                    ResourceLocation id = ResourceLocation.tryParse(rule.target);
                    if (id == null) break;
                    Item item = BuiltInRegistries.ITEM.get(id);
                    if (item == null || item == Items.AIR) break;
                    if (rule.hideOutput || rule.hideIngredient) {
                        sel.itemFilters.add(new ItemFilter(item, rule.hideOutput, rule.hideIngredient,
                                sel.compileRanges(rule.effectiveRanges())));
                    }
                    if (rule.hideItem) sel.hideItems.add(item);
                    break;
                }
            }
        }
        return sel;
    }

    /** 把规则里逗号分隔的类型 id 解析成 JEI 的 RecipeType 对象；解析不出来就当作「不限」 */
    private Set<RecipeType<?>> resolveTypes(String recipeType) {
        if (recipeType == null || recipeType.isBlank()) return null;
        Set<RecipeType<?>> set = new LinkedHashSet<>();
        for (String part : recipeType.split(",")) {
            ResourceLocation id = ResourceLocation.tryParse(part.trim());
            if (id == null) continue;
            manager.getRecipeType(id).ifPresent(set::add);
        }
        return set.isEmpty() ? null : set;
    }

    public Set<Item> hideItems() {
        return hideItems;
    }

    public Set<RecipeType<?>> hiddenCategories() {
        return hiddenCategories;
    }

    public boolean isEmpty() {
        return itemFilters.isEmpty() && modFilters.isEmpty() && recipeIds.isEmpty() && hideItems.isEmpty()
                && keepMods.isEmpty() && hiddenCategories.isEmpty();
    }

    public boolean matches(Recipe<?> recipe, net.minecraft.core.RegistryAccess registryAccess) {
        if (!recipeIds.isEmpty() && recipeIds.contains(recipe.getId())) return true;

        RecipeType<?> actualType = typeOf(recipe);
        if (actualType != null && hiddenCategories.contains(actualType)) return true;

        // 白名单模式：只要配方不是白名单模组产出的，一律隐藏
        if (!keepMods.isEmpty() && !keepMods.contains(recipe.getId().getNamespace())) return true;

        for (ModFilter filter : modFilters) {
            if (!recipe.getId().getNamespace().equals(filter.modid)) continue;
            for (CompiledRange range : filter.ranges) {
                if (typeOk(actualType, range) && recipeHasPartner(recipe, range)) return true;
            }
        }

        for (ItemFilter filter : itemFilters) {
            for (CompiledRange range : filter.ranges) {
                if (!typeOk(actualType, range)) continue;
                if (filter.output && matchesOutput(recipe, filter.item, registryAccess)) return true;
                if (filter.ingredient && matchesIngredient(recipe, filter.item, range.groups)) return true;
            }
        }
        return false;
    }

    private List<CompiledRange> compileRanges(List<Rule.Range> raw) {
        List<CompiledRange> out = new ArrayList<>();
        if (raw != null) {
            for (Rule.Range range : raw) {
                List<List<String>> groups = new ArrayList<>();
                for (List<String> group : range.groups()) groups.add(new ArrayList<>(group));
                out.add(new CompiledRange(resolveTypes(range.type), groups));
            }
        }
        if (out.isEmpty()) out.add(new CompiledRange(null, List.of()));
        return out;
    }

    /** 配方类型是否落在这组范围里（types == null 表示不限） */
    private static boolean typeOk(RecipeType<?> actualType, CompiledRange range) {
        return range.types == null || (actualType != null && range.types.contains(actualType));
    }

    /** 搭配分组：外层「或」，内层「与」；空组 = 不限 */
    private static boolean recipeHasPartner(Recipe<?> recipe, CompiledRange range) {
        for (List<String> group : range.groups) {
            if (group.isEmpty()) return true;
            boolean all = true;
            for (String partner : group) {
                boolean found = false;
                for (Ingredient ingredient : recipe.getIngredients()) {
                    if (ingredient == null || ingredient.isEmpty()) continue;
                    if (ingredientAccepts(ingredient, partner)) {
                        found = true;
                        break;
                    }
                }
                if (!found) {
                    all = false;
                    break;
                }
            }
            if (all) return true;
        }
        return false;
    }

    private static boolean matchesOutput(Recipe<?> recipe, Item item,
                                         net.minecraft.core.RegistryAccess registryAccess) {
        ItemStack result = safeResult(recipe, registryAccess);
        return !result.isEmpty() && result.getItem() == item;
    }

    /**
     * 配方是否「把 item 当原料」。
     *
     * <p>groups 非空时还要求<b>其它格子</b>接受它们（与/或都支持）。
     */
    private static boolean matchesIngredient(Recipe<?> recipe, Item item, List<List<String>> groups) {
        ItemStack probe = new ItemStack(item);
        List<Ingredient> ingredients = recipe.getIngredients();

        int itemSlot = -1;
        for (int i = 0; i < ingredients.size(); i++) {
            Ingredient ingredient = ingredients.get(i);
            if (ingredient == null || ingredient.isEmpty()) continue;
            try {
                if (ingredient.test(probe)) {
                    itemSlot = i;
                    break;
                }
            } catch (Throwable ignored) {
                // 个别 mod 的 Ingredient 实现可能有异常，跳过即可
            }
        }
        if (itemSlot < 0) return false;
        if (groups == null || groups.isEmpty()) return true;

        for (List<String> group : groups) {
            if (group.isEmpty()) return true;
            boolean all = true;
            for (String partner : group) {
                boolean found = false;
                for (int i = 0; i < ingredients.size(); i++) {
                    if (i == itemSlot) continue;
                    Ingredient ingredient = ingredients.get(i);
                    if (ingredient == null || ingredient.isEmpty()) continue;
                    if (ingredientAccepts(ingredient, partner)) {
                        found = true;
                        break;
                    }
                }
                if (!found) {
                    all = false;
                    break;
                }
            }
            if (all) return true;
        }
        return false;
    }

    /** 这个 Ingredient 是否接受 partner（物品 id 或 #标签） */
    public static boolean ingredientAccepts(Ingredient ingredient, String partner) {
        if (ingredient == null || ingredient.isEmpty() || partner == null || partner.isBlank()) return false;
        try {
            if (partner.startsWith("#")) {
                ResourceLocation id = ResourceLocation.tryParse(partner.substring(1));
                if (id == null) return false;
                TagKey<Item> tag = TagKey.create(Registries.ITEM, id);
                for (ItemStack stack : ingredient.getItems()) {
                    if (!stack.isEmpty() && stack.is(tag)) return true;
                }
                return false;
            }
            ResourceLocation id = ResourceLocation.tryParse(partner);
            if (id == null) return false;
            Item item = BuiltInRegistries.ITEM.get(id);
            if (item == null || item == Items.AIR) return false;
            return ingredient.test(new ItemStack(item));
        } catch (Throwable t) {
            return false;
        }
    }

    private static ItemStack safeResult(Recipe<?> recipe, net.minecraft.core.RegistryAccess registryAccess) {
        try {
            ItemStack stack = recipe.getResultItem(registryAccess);
            return stack == null ? ItemStack.EMPTY : stack;
        } catch (Exception e) {
            return ItemStack.EMPTY;
        }
    }

    // ------------------------------------------------------------ 类型解析

    /**
     * 找 recipe 对应的 JEI RecipeType。
     * 先按原版 RecipeType 的注册名查；查不到再按 recipe 的类名兜底匹配。
     */
    public RecipeType<?> typeOf(Recipe<?> recipe) {
        RecipeType<?> cached = recipeToType.get(recipe);
        if (cached != null) return cached;

        RecipeType<?> result = null;
        ResourceLocation typeId = BuiltInRegistries.RECIPE_TYPE.getKey(recipe.getType());
        if (typeId != null) {
            var byId = manager.getRecipeType(typeId);
            if (byId.isPresent() && byId.get().getRecipeClass().isInstance(recipe)) {
                result = byId.get();
            }
        }
        if (result == null) {
            Map<Class<?>, RecipeType<?>> cache = classCache();
            result = cache.get(recipe.getClass());
            if (result == null) {
                for (var entry : cache.entrySet()) {
                    if (entry.getKey().isInstance(recipe)) {
                        result = entry.getValue();
                        break;
                    }
                }
            }
        }
        recipeToType.put(recipe, result);
        return result;
    }

    private Map<Class<?>, RecipeType<?>> classCache() {
        if (!classToType.isEmpty()) return classToType;
        try {
            for (var category : manager.createRecipeCategoryLookup().get().toList()) {
                var type = category.getRecipeType();
                classToType.putIfAbsent(type.getRecipeClass(), type);
            }
        } catch (Throwable ignored) {
        }
        return classToType;
    }

    /** 给外部（诊断命令）用 */
    public static RecipeType<?> jeiTypeFor(IRecipeManager manager, Recipe<?> recipe) {
        return of(List.of(), manager).typeOf(recipe);
    }
}
