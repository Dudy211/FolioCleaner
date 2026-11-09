package com.yuneko.foliocleaner;

import net.minecraft.client.Minecraft;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 统计「每个物品出现在多少条配方里」。
 *
 * <p>用途：找毒瘤。某些「万能材料」会出现在几千条配方里，对着它按 JEI 的 U 键
 * 会把几千个配方页一次性布局出来，直接把游戏卡死。先看排行榜，再一键隐藏。
 */
public final class ItemStats {

    private static final Map<Item, Integer> USAGE = new HashMap<>();
    private static final Map<Item, Integer> OUTPUT = new HashMap<>();
    /** 不过滤规则的全量统计：用来判断「真·死物品」，不受用户隐藏影响 */
    private static final Map<Item, Integer> RAW_USAGE = new HashMap<>();
    private static final Map<Item, Integer> RAW_OUTPUT = new HashMap<>();
    private static Object builtFor;
    private static int totalRecipes;

    private ItemStats() {
    }

    public static void invalidate() {
        builtFor = null;
    }

    /** 统计结果是否已经算过（没算的时候 usage() 全是 0，界面别当成真数据用） */
    public static boolean ready() {
        return builtFor instanceof Integer;
    }

    /**
     * 「真·死物品」：**没有配方产出它，也没有任何配方拿它当原料**。
     *
     * <p>用的是不过滤规则的原始统计 —— 否则用户藏了几条规则，全包物品都会"看起来"没有用途，
     * 这个提示就没意义了。
     *
     * <p>注意：无配方 ≠ 无用。掉落物、任务奖励、结构战利品都不在配方表里，
     * 所以这里只做提示，不生成任何规则。
     */
    public static List<Item> deadItems() {
        List<Item> out = new ArrayList<>();
        if (!ready()) return out;
        for (var id : net.minecraft.core.registries.BuiltInRegistries.ITEM.keySet()) {
            Item item = net.minecraft.core.registries.BuiltInRegistries.ITEM.get(id);
            if (item == net.minecraft.world.item.Items.AIR) continue;
            if (RAW_USAGE.getOrDefault(item, 0) > 0) continue;
            if (RAW_OUTPUT.getOrDefault(item, 0) > 0) continue;
            out.add(item);
        }
        return out;
    }

    /** 死物品数量（界面只显示数字，不建列表） */
    public static int deadItemCount() {
        return ready() ? deadCount : 0;
    }

    private static int deadCount;

    public static int totalRecipes() {
        return totalRecipes;
    }

    public static int usage(Item item) {
        return USAGE.getOrDefault(item, 0);
    }

    public static int output(Item item) {
        return OUTPUT.getOrDefault(item, 0);
    }

    /** 按「作为原料的配方数」排序的物品列表（降序） */
    public static List<Item> topUsage(int limit) {
        List<Map.Entry<Item, Integer>> entries = new ArrayList<>(USAGE.entrySet());
        entries.sort((a, b) -> Integer.compare(b.getValue(), a.getValue()));
        List<Item> result = new ArrayList<>();
        for (Map.Entry<Item, Integer> e : entries) {
            if (result.size() >= limit) break;
            if (e.getValue() <= 0) break;
            result.add(e.getKey());
        }
        return result;
    }

    public static void build() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) {
            builtFor = null;
            return;
        }
        int sig = signature();
        if (builtFor instanceof Integer prev && prev == sig) return;

        USAGE.clear();
        OUTPUT.clear();
        // 只统计「现在还能看见的」配方：用户藏/删之后排行榜要立刻跟着变，
        // 否则显示的数字跟 JEI 里对不上，看着像没生效。
        List<Rule> rules = Rules.all();
        var registryAccess = mc.level.registryAccess();
        int count = 0;
        int skipped = 0;
        // 复用缓冲：同一条配方里重复出现的物品只计一次（两个槽都是木板 → 只算 1）
        Item[] seen = new Item[16];
        try {
            RAW_USAGE.clear();
            RAW_OUTPUT.clear();
            for (Recipe<?> recipe : mc.level.getRecipeManager().getRecipes()) {
                // 先做一遍「不过滤」的原始统计（死物品判定用），这一步和规则无关
                try {
                    ItemStack rawResult = recipe.getResultItem(registryAccess);
                    if (rawResult != null && !rawResult.isEmpty()) {
                        RAW_OUTPUT.merge(rawResult.getItem(), 1, Integer::sum);
                    }
                    for (Ingredient rawIng : recipe.getIngredients()) {
                        if (rawIng == null || rawIng.isEmpty()) continue;
                        for (ItemStack st : rawIng.getItems()) {
                            if (st != null && !st.isEmpty()) RAW_USAGE.merge(st.getItem(), 1, Integer::sum);
                        }
                    }
                } catch (Throwable ignored) {
                }

                try {
                    if (!rules.isEmpty()
                            && RecipeRemover.matches(recipe, rules, registryAccess, true)) {
                        skipped++;
                        continue;
                    }
                } catch (Throwable ignored) {
                }
                count++;
                try {
                    ItemStack result = recipe.getResultItem(mc.level.registryAccess());
                    if (result != null && !result.isEmpty()) {
                        OUTPUT.merge(result.getItem(), 1, Integer::sum);
                    }
                } catch (Throwable ignored) {
                }
                int seenCount = 0;
                for (Ingredient ingredient : recipe.getIngredients()) {
                    if (ingredient == null || ingredient.isEmpty()) continue;
                    ItemStack[] stacks;
                    try {
                        stacks = ingredient.getItems();
                    } catch (Throwable t) {
                        continue;
                    }
                    for (ItemStack stack : stacks) {
                        if (stack == null || stack.isEmpty()) continue;
                        Item item = stack.getItem();
                        boolean dup = false;
                        for (int i = 0; i < seenCount; i++) {
                            if (seen[i] == item) {
                                dup = true;
                                break;
                            }
                        }
                        if (dup) continue;
                        if (seenCount == seen.length) seen = Arrays.copyOf(seen, seenCount * 2);
                        seen[seenCount++] = item;
                        USAGE.merge(item, 1, Integer::sum);
                    }
                }
            }
        } catch (Throwable t) {
            FolioCleaner.LOGGER.warn("[Folio Cleaner] 统计配方用量时出错", t);
        }
        totalRecipes = count;
        deadCount = 0;
        try {
            for (var id : net.minecraft.core.registries.BuiltInRegistries.ITEM.keySet()) {
                Item it = net.minecraft.core.registries.BuiltInRegistries.ITEM.get(id);
                if (it == net.minecraft.world.item.Items.AIR) continue;
                if (RAW_USAGE.getOrDefault(it, 0) > 0) continue;
                if (RAW_OUTPUT.getOrDefault(it, 0) > 0) continue;
                deadCount++;
            }
        } catch (Throwable ignored) {
        }
        builtFor = sig;
        FolioCleaner.LOGGER.debug("[Folio Cleaner] 统计完成：{} 条可见配方（跳过 {} 条已隐藏/删除），{} 个物品有用途",
                count, skipped, USAGE.size());
    }

    /**
     * 统计结果的版本号：换世界或者规则改动都会变。
     *
     * <p>UI 侧拿它判断「要不要重排」—— 排行是几万条排序，不能每帧做。
     */
    public static int signature() {
        Minecraft mc = Minecraft.getInstance();
        int level = mc.level == null ? 0 : System.identityHashCode(mc.level);
        return level * 31 + Rules.revision();
    }
}
