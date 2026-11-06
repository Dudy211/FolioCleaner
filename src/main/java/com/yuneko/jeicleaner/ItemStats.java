package com.yuneko.jeicleaner;

import net.minecraft.client.Minecraft;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;

import java.util.ArrayList;
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
    private static Object builtFor;
    private static int totalRecipes;

    private ItemStats() {
    }

    public static void invalidate() {
        builtFor = null;
    }

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
        Object key = mc.level;
        if (builtFor == key) return;

        USAGE.clear();
        OUTPUT.clear();
        int count = 0;
        try {
            for (Recipe<?> recipe : mc.level.getRecipeManager().getRecipes()) {
                count++;
                try {
                    ItemStack result = recipe.getResultItem(mc.level.registryAccess());
                    if (result != null && !result.isEmpty()) {
                        OUTPUT.merge(result.getItem(), 1, Integer::sum);
                    }
                } catch (Throwable ignored) {
                }
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
                        USAGE.merge(stack.getItem(), 1, Integer::sum);
                    }
                }
            }
        } catch (Throwable t) {
            JeiCleaner.LOGGER.warn("[JEI Cleaner] 统计配方用量时出错", t);
        }
        totalRecipes = count;
        builtFor = key;
        JeiCleaner.LOGGER.debug("[JEI Cleaner] 统计完成：{} 条配方，{} 个物品有用途", count, USAGE.size());
    }
}
