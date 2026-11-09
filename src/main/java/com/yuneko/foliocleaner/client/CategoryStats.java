package com.yuneko.foliocleaner.client;

import com.yuneko.foliocleaner.Cleaner;
import mezz.jei.api.recipe.IRecipeManager;
import mezz.jei.api.recipe.RecipeType;
import mezz.jei.api.runtime.IJeiRuntime;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;

/**
 * 「按分类」统计：每个 JEI 配方分类有多少条配方、催化剂是哪个物品。
 *
 * <p>为什么需要它：{@code ItemStats} 只遍历 {@code mc.level.getRecipeManager()}，
 * 那里面**只有真正的 MC Recipe**。奥术铁砧这种模组自建的 JEI 配方
 * （4000+ 条，配方对象不是 {@code Recipe} 实现）在那边一条都统计不到，
 * 于是「毒瘤排行」完全看不见它 —— 而这玩意偏偏是按 U 最容易炸的东西。
 *
 * <p>这里换一个角度：直接问 JEI「每个分类有多少条」。
 * {@code createRecipeLookup(type).includeHidden().get().count()} 对自定义分类同样成立，
 * 而且不需要建布局，比逐条解析便宜得多。
 */
public final class CategoryStats {

    /** 一个分类的一行 */
    public record Row(String uid, Component title, long count, ItemStack icon) {
    }

    private CategoryStats() {
    }

    /** 按配方条数降序；JEI 没就绪时返回空表 */
    public static List<Row> build() {
        List<Row> out = new ArrayList<>();
        IJeiRuntime runtime = Cleaner.RUNTIME;
        if (runtime == null) return out;

        IRecipeManager manager = runtime.getRecipeManager();
        try {
            manager.createRecipeCategoryLookup().includeHidden().get().forEach(category -> {
                try {
                    RecipeType<?> type = category.getRecipeType();
                    long count = manager.createRecipeLookup(type).includeHidden().get().count();
                    if (count <= 0) return;

                    ItemStack icon = ItemStack.EMPTY;
                    try {
                        icon = manager.createRecipeCatalystLookup(type).getItemStack()
                                .filter(s -> s != null && !s.isEmpty())
                                .findFirst().orElse(ItemStack.EMPTY);
                    } catch (Throwable ignored) {
                    }
                    out.add(new Row(type.getUid().toString(), category.getTitle(), count, icon));
                } catch (Throwable ignored) {
                }
            });
        } catch (Throwable ignored) {
        }

        out.sort((a, b) -> Long.compare(b.count(), a.count()));
        return out;
    }
}
