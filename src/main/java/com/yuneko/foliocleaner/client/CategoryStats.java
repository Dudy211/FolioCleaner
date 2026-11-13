package com.yuneko.foliocleaner.client;

import com.yuneko.foliocleaner.viewer.Backends;
import com.yuneko.foliocleaner.viewer.RecipeViewerBackend;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;

/**
 * 「按分类」统计：每个配方分类有多少条配方、催化剂是哪个物品。
 *
 * <p>为什么需要它：{@code ItemStats} 只遍历 {@code mc.level.getRecipeManager()}，
 * 那里面**只有真正的 MC Recipe**。奥术铁砧这种模组自建的配方
 * （4000+ 条，配方对象不是 {@code Recipe} 实现）在那边一条都统计不到，
 * 于是「毒瘤排行」完全看不见它 —— 而这玩意偏偏是按 U 最容易炸的东西。
 *
 * <p>这里换一个角度：直接问**当前配方浏览器**「每个分类有多少条」。
 * 具体怎么问由 {@link RecipeViewerBackend#categories()} 决定
 * （JEI 用 {@code createRecipeLookup().includeHidden()}，REI 数 {@code DisplayRegistry}，
 * EMI 数 {@code EmiRecipeManager.getRecipes(category)}），本类只做数据整形。
 */
public final class CategoryStats {

    /** 一个分类的一行 */
    public record Row(String uid, Component title, long count, ItemStack icon) {
    }

    private CategoryStats() {
    }

    /** 按配方条数降序；没有可用的浏览器时返回空表 */
    public static List<Row> build() {
        RecipeViewerBackend backend = Backends.primary();
        if (backend == null) return new ArrayList<>();

        List<Row> out = new ArrayList<>();
        try {
            for (RecipeViewerBackend.Category c : backend.categories()) {
                if (c.count() <= 0) continue;
                out.add(new Row(c.uid(), c.title(), c.count(),
                        c.icon() == null ? ItemStack.EMPTY : c.icon()));
            }
        } catch (Throwable ignored) {
        }
        out.sort((a, b) -> Long.compare(b.count(), a.count()));
        return out;
    }
}
