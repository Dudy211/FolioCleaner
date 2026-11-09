package com.yuneko.foliocleaner.client;

import com.yuneko.foliocleaner.ClientSettings;
import mezz.jei.api.gui.IRecipeLayoutDrawable;
import mezz.jei.api.gui.drawable.IScalableDrawable;
import mezz.jei.api.recipe.IFocusGroup;
import mezz.jei.api.recipe.IRecipeManager;
import mezz.jei.api.recipe.category.IRecipeCategory;
import mezz.jei.common.Internal;
import mezz.jei.gui.recipes.IRecipeLayoutWithButtonsFactory;
import mezz.jei.gui.recipes.RecipeLayoutWithButtons;
import mezz.jei.gui.recipes.RecipeSortUtil;
import mezz.jei.gui.recipes.layouts.IRecipeLayoutList;
import mezz.jei.gui.recipes.layouts.RecipeLayoutDrawableErrored;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 「按页懒建布局」的配方列表。
 *
 * <p>JEI 15 原本的做法是：拿到某个分类的全部配方 → <b>为每一条都建一次
 * {@link IRecipeLayoutDrawable}</b> → 再交给 {@code LazySortedRecipeLayoutList} 排序/分页。
 * 名字里的 "Lazy" 只懒排序，不懒建布局。所以一个 1400 条的分类按 U 会一次性建 1400 个布局，
 * 内存直接爆掉（奥术铁砧 1400 条就是这么炸的）。
 *
 * <p>这个实现反过来：{@link #size()} 直接报配方总数（分页照常算），
 * 只有 {@link #subList(int, int)}（= 当前页）才真的建布局，并做 LRU 缓存。
 * 缓存上限 128 是照抄 JEI 19 的 {@code LazyRecipeLayoutList.LAYOUT_CACHE_SIZE}。
 *
 * <p><b>关于排序</b>：JEI 的「可合成优先」比较器要读 <code>getRecipeSlotsView()</code>，
 * 每比较一次都得有布局，所以原本只能全量建。这里改成<b>预算内排序</b>：
 * 只建前 {@link #SORT_BUDGET} 条、只对这 {@link #SORT_BUDGET} 条排序，
 * 后面的按注册顺序接上去。顺序只在前几页准确，但构建量有硬上限 —— 永远不会 OOM。
 * 这个功能默认关闭，在「优化设置 → 大分类排序」里开。
 */
public final class PagedRecipeLayoutList<T> implements IRecipeLayoutList {

    /** 与 JEI 19 的 LAYOUT_CACHE_SIZE 对齐 */
    private static final int CACHE_LIMIT = 128;
    /** 参与排序的条数上限：= 缓存上限，保证排完序的这些正好还在缓存里 */
    private static final int SORT_BUDGET = 128;
    /** 少于这个条数就不折腾排序了（本来也不卡） */
    private static final int SORT_MIN = 64;

    private final IRecipeCategory<T> category;
    private final List<T> recipes;
    private final IRecipeManager recipeManager;
    private final IFocusGroup focuses;
    private final IRecipeLayoutWithButtonsFactory factory;

    /** 显示顺序：显示位次 → 配方下标；null = 还没排过 / 不排序 */
    private int[] order;
    private boolean orderTried;

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
        ensureOrder();
        int a = Math.max(0, from);
        int b = Math.min(recipes.size(), to);
        if (b <= a) return List.of();
        List<RecipeLayoutWithButtons<?>> out = new ArrayList<>(b - a);
        for (int i = a; i < b; i++) {
            RecipeLayoutWithButtons<?> layout = layoutAt(order == null ? i : order[i]);
            if (layout != null) out.add(layout);
        }
        return out;
    }

    @Override
    public Optional<RecipeLayoutWithButtons<?>> findFirst() {
        ensureOrder();
        if (recipes.isEmpty()) return Optional.empty();
        return Optional.ofNullable(layoutAt(order == null ? 0 : order[0]));
    }

    /**
     * 空实现：{@code RecipeGuiLayouts.tick(menu)} 会遍历可见列表逐个
     * {@code RecipeLayoutWithButtons.tick(menu, player)}，这里再 tick 一次是重复动作。
     */
    @Override
    public void tick() {
    }

    /**
     * 预算内排序：只对前 {@link #SORT_BUDGET} 条建布局并排序，其余保持注册顺序。
     *
     * <p>为什么不做全量排序：比较器 {@code RecipeSortUtil.getCraftableComparator()}
     * 要读槽位视图，`List.sort` 是 O(N log N) 次比较，4000 条就是上万次 —— 等于把布局全逼出来。
     */
    private void ensureOrder() {
        if (orderTried) return;
        orderTried = true;
        if (!ClientSettings.lazySort) return;
        int n = recipes.size();
        if (n <= SORT_MIN) return;

        int budget = Math.min(n, SORT_BUDGET);
        List<RecipeLayoutWithButtons<?>> built = new ArrayList<>(budget);
        for (int i = 0; i < budget; i++) {
            RecipeLayoutWithButtons<?> layout = layoutAt(i);
            if (layout == null) {
                order = null; // 有建不出来的，别排了，保持注册顺序更稳
                return;
            }
            built.add(layout);
        }

        try {
            Integer[] idx = new Integer[budget];
            for (int i = 0; i < budget; i++) idx[i] = i;
            var comparator = RecipeSortUtil.getCraftableComparator();
            Arrays.sort(idx, (x, yy) -> comparator.compare(built.get(x), built.get(yy)));

            int[] ord = new int[n];
            for (int i = 0; i < budget; i++) ord[i] = idx[i];
            for (int i = budget; i < n; i++) ord[i] = i;
            order = ord;
        } catch (Throwable t) {
            order = null;
        }
    }

    /** 真正建一条布局；建失败用「错误占位布局」顶上，避免整页错位 */
    private RecipeLayoutWithButtons<?> layoutAt(int index) {
        RecipeLayoutWithButtons<?> cached = cache.get(index);
        if (cached != null) return cached;
        if (index < 0 || index >= recipes.size()) return null;
        try {
            T recipe = recipes.get(index);
            IScalableDrawable background = Internal.getTextures().getRecipeBackground();
            IRecipeLayoutDrawable<T> drawable;
            Optional<IRecipeLayoutDrawable<T>> opt =
                    recipeManager.createRecipeLayoutDrawable(category, recipe, focuses, background, 4);
            if (opt.isPresent()) {
                drawable = opt.get();
            } else {
                // 和 JEI 一样退化成“错误布局”，而不是把这条从页里抠掉（那会让页码和内容错位）
                drawable = new RecipeLayoutDrawableErrored<>(category, recipe, background, 4);
            }
            RecipeLayoutWithButtons<?> layout = factory.create(drawable);
            cache.put(index, layout);
            return layout;
        } catch (Throwable ignored) {
            return null;
        }
    }
}
