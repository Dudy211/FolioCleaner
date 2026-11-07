package com.yuneko.jeicleaner.client;

import mezz.jei.api.gui.IRecipeLayoutDrawable;
import mezz.jei.api.recipe.IFocusGroup;
import mezz.jei.api.recipe.IRecipeManager;
import mezz.jei.api.recipe.category.IRecipeCategory;
import mezz.jei.common.Internal;
import mezz.jei.gui.recipes.IRecipeLayoutWithButtonsFactory;
import mezz.jei.gui.recipes.RecipeLayoutWithButtons;
import mezz.jei.gui.recipes.layouts.IRecipeLayoutList;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 「按页懒建布局」的配方列表。
 *
 * <p>JEI 原本的做法是：拿到某个分类的全部配方 → <b>为每一条都建一次
 * {@link IRecipeLayoutDrawable}</b> → 再交给 {@code LazySortedRecipeLayoutList} 排序/分页。
 * 名字里的 "Lazy" 只懒排序，不懒建布局。所以一个 1400 条的分类按 U 会一次性建 1400 个布局，
 * 内存直接爆掉（奥术铁砧 1400 条就是这么炸的）。
 *
 * <p>这个实现反过来：{@link #size()} 直接报配方总数（分页照常算），
 * 只有 {@link #subList(int, int)}（= 当前页）才真的建布局，并做一个小 LRU 缓存，
 * 翻回来不用重建。
 *
 * <p>代价：大列表不再支持「可合成优先」排序 —— 那个比较器要拿
 * {@code getRecipeSlotsView()} 逐条比对，本来就会逼出全量布局，正是爆内存的元凶。
 * 超过阈值（{@code RecipeGuiLogicMixin.MAX_EAGER}）的分类改用注册顺序。
 */
public final class PagedRecipeLayoutList<T> implements IRecipeLayoutList {

    /** 缓存多少页之外的布局：翻回去要重建，但不会无限涨内存 */
    private static final int CACHE_LIMIT = 120;

    private final IRecipeCategory<T> category;
    private final List<T> recipes;
    private final IRecipeManager recipeManager;
    private final IFocusGroup focuses;
    private final IRecipeLayoutWithButtonsFactory factory;

    /** 索引 → 已建好的布局，accessOrder=true 做 LRU */
    private final Map<Integer, RecipeLayoutWithButtons<?>> cache =
            new LinkedHashMap<>(32, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<Integer, RecipeLayoutWithButtons<?>> eldest) {
                    return size() > CACHE_LIMIT;
                }
            };

    public PagedRecipeLayoutList(IRecipeCategory<T> category,
                                 List<T> recipes,
                                 IRecipeManager recipeManager,
                                 IFocusGroup focuses,
                                 IRecipeLayoutWithButtonsFactory factory) {
        this.category = category;
        this.recipes = recipes;
        this.recipeManager = recipeManager;
        this.focuses = focuses;
        this.factory = factory;
    }

    @Override
    public int size() {
        return recipes.size();
    }

    @Override
    public List<RecipeLayoutWithButtons<?>> subList(int from, int to) {
        int a = Math.max(0, from);
        int b = Math.min(recipes.size(), to);
        if (b <= a) return List.of();
        List<RecipeLayoutWithButtons<?>> out = new ArrayList<>(b - a);
        for (int i = a; i < b; i++) {
            RecipeLayoutWithButtons<?> layout = layoutAt(i);
            if (layout != null) out.add(layout);
        }
        return out;
    }

    @Override
    public Optional<RecipeLayoutWithButtons<?>> findFirst() {
        if (recipes.isEmpty()) return Optional.empty();
        return Optional.ofNullable(layoutAt(0));
    }

    /**
     * 空实现：把 {@code null} 返回给顶层转调。
     * JEI 的 {@code RecipeGuiLayouts.tick(menu)} 会遍历「当前可见的」列表逐个
     * {@code RecipeLayoutWithButtons.tick(menu, player)}，这里再 tick 一次是重复动作。
     */
    @Override
    public void tick() {
    }

    /** 真正建一条布局（失败返回 null，该条这一页就不显示，不整页崩） */
    private RecipeLayoutWithButtons<?> layoutAt(int index) {
        RecipeLayoutWithButtons<?> cached = cache.get(index);
        if (cached != null) return cached;
        try {
            T recipe = recipes.get(index);
            // 这就是 JEI 自己在 lambda$createRecipeLayoutsWithButtons$1 里用的同一组参数
            // （createRecipeLayoutDrawable(category, recipe, focuses, 配方背景, maxWidth=4)）
            Optional<IRecipeLayoutDrawable<T>> opt = recipeManager.createRecipeLayoutDrawable(
                    category, recipe, focuses,
                    Internal.getTextures().getRecipeBackground(), 4);
            if (opt.isEmpty()) return null;
            RecipeLayoutWithButtons<?> layout = factory.create(opt.get());
            cache.put(index, layout);
            return layout;
        } catch (Throwable ignored) {
            return null;
        }
    }
}
