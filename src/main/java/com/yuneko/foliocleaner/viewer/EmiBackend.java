package com.yuneko.foliocleaner.viewer;

import com.yuneko.foliocleaner.FolioCleaner;
import com.yuneko.foliocleaner.Rules;
import dev.emi.emi.api.EmiApi;
import dev.emi.emi.api.recipe.EmiRecipe;
import dev.emi.emi.api.recipe.EmiRecipeCategory;
import dev.emi.emi.api.recipe.EmiRecipeManager;
import dev.emi.emi.api.stack.EmiIngredient;
import dev.emi.emi.api.widget.Widget;
import dev.emi.emi.api.widget.WidgetHolder;
import dev.emi.emi.api.stack.EmiStack;
import dev.emi.emi.data.EmiData;
import dev.emi.emi.registry.EmiRecipes;
import dev.emi.emi.registry.EmiStackList;
import dev.emi.emi.runtime.EmiReloadManager;
import dev.emi.emi.runtime.EmiHidden;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/**
 * EMI 后端。
 *
 * <p>能力（javap 核过 1.1.24）：
 * <ul>
 *   <li>{@code EmiApi.getRecipeManager()} → {@code getCategories() / getRecipes() /
 *       getRecipes(category) / getRecipe(id) / getRecipesByInput / getRecipesByOutput}</li>
 *   <li>{@code EmiRecipe.addWidgets(WidgetHolder)} → <b>能画预览</b>（widget 直接是 Renderable）</li>
 *   <li>配方级隐藏：{@code EmiRegistry.removeRecipes} <b>只在插件初始化期</b>生效
 *       （运行期只能改 {@code EmiRecipes.invalidators} 再 {@code bake()}，
 *       而 {@code bake()} 会把数据包配方再 addAll 一遍 → 会重复，不能这么干）</li>
 * </ul>
 *
 * <p>所以这个后端只能<b>浏览 + 预览</b>；规则怎么应用由 {@link EmiRecipeHiderPlugin}
 * 在初始化期完成。界面会明确提示「改规则要重开游戏」，不假装同步。
 */
public final class EmiBackend implements RecipeViewerBackend {

    @Override
    public String id() {
        return "emi";
    }

    @Override
    public String label() {
        return "EMI";
    }

    @Override
    public boolean ready() {
        try {
            return EmiApi.getRecipeManager() != null;
        } catch (Throwable t) {
            return false;
        }
    }

    @Override
    public Caps caps() {
        // browse / preview / apply / live（能不能运行期重烘焙取决于有没有数据包配方）
        return new Caps(true, true, true, runtimeRebakeSafe());
    }

    /**
     * 运行期重烘焙是否安全。
     *
     * <p>{@code EmiRecipes.bake()} 会把 {@code EmiData.recipes} 里的数据包配方
     * <b>再 addAll 一遍</b>，还会往 {@code invalidators} 里再塞一个谓词。
     * 所以只有在「没有数据包配方 / 数据包过滤」时才可以重复调用 ——
     * 绝大多数整合包都是这个情况。
     */
    public static boolean runtimeRebakeSafe() {
        try {
            if (!EmiReloadManager.isLoaded()) return false;
            return EmiData.recipes.isEmpty() && EmiData.recipeFilters.isEmpty();
        } catch (Throwable t) {
            return false;
        }
    }

    @Override
    public String applyHint() {
        if (runtimeRebakeSafe()) return null;          // 能实时重烘焙，别吓唬人
        if (!RuleMatcher.hasRecipeRules()) return null; // 只有物品规则，物品是实时的
        return "EMI：物品隐藏已生效；配方隐藏要重开游戏（这个包里有 EMI 数据包配方，不能安全地重建索引）";
    }

    private EmiRecipeManager manager() {
        try {
            return EmiApi.getRecipeManager();
        } catch (Throwable t) {
            return null;
        }
    }

    // ------------------------------------------------------------ 浏览

    @Override
    public List<Category> categories() {
        EmiRecipeManager manager = manager();
        if (manager == null) return List.of();

        List<Category> out = new ArrayList<>();
        try {
            for (EmiRecipeCategory category : manager.getCategories()) {
                if (category == null || category.getId() == null) continue;
                long count = 0;
                try {
                    List<EmiRecipe> recipes = manager.getRecipes(category);
                    count = recipes == null ? 0 : recipes.size();
                } catch (Throwable ignored) {
                }
                if (count <= 0) continue;
                out.add(new Category(category.getId().toString(), category.getName(),
                        firstWorkstation(manager, category), count));
            }
        } catch (Throwable t) {
            FolioCleaner.LOGGER.debug("[Folio Cleaner] EMI 分类枚举失败：{}", String.valueOf(t));
        }
        out.sort((a, b) -> Long.compare(b.count(), a.count()));
        return out;
    }

    private ItemStack firstWorkstation(EmiRecipeManager manager, EmiRecipeCategory category) {
        try {
            List<EmiIngredient> stations = manager.getWorkstations(category);
            if (stations == null) return ItemStack.EMPTY;
            for (EmiIngredient ingredient : stations) {
                ItemStack item = EmiFacts.firstItem(ingredient);
                if (!item.isEmpty()) return item;
            }
        } catch (Throwable ignored) {
        }
        return ItemStack.EMPTY;
    }

    @Override
    public List<Catalyst> catalysts() {
        EmiRecipeManager manager = manager();
        if (manager == null) return List.of();

        Map<String, LinkedHashSet<String>> byItem = new HashMap<>();
        Map<String, ItemStack> stacks = new HashMap<>();
        try {
            for (EmiRecipeCategory category : manager.getCategories()) {
                if (category == null || category.getId() == null) continue;
                String typeUid = category.getId().toString();
                List<EmiIngredient> stations = manager.getWorkstations(category);
                if (stations == null) continue;
                for (EmiIngredient ingredient : stations) {
                    ItemStack item = EmiFacts.firstItem(ingredient);
                    if (item.isEmpty()) continue;
                    String id = net.minecraft.core.registries.BuiltInRegistries.ITEM
                            .getKey(item.getItem()).toString();
                    byItem.computeIfAbsent(id, k -> new LinkedHashSet<>()).add(typeUid);
                    stacks.putIfAbsent(id, item);
                }
            }
        } catch (Throwable t) {
            return List.of();
        }

        List<Catalyst> out = new ArrayList<>();
        for (Map.Entry<String, LinkedHashSet<String>> e : byItem.entrySet()) {
            ItemStack stack = stacks.get(e.getKey());
            if (stack == null || stack.isEmpty()) continue;
            out.add(new Catalyst(stack, stack.getHoverName().getString(), String.join(",", e.getValue())));
        }
        out.sort((a, b) -> a.name().compareToIgnoreCase(b.name()));
        return out;
    }

    // ------------------------------------------------------------ 预览

    /** 配方 id → 预览（GUI 悬浮时每帧都问；addWidgets 每次都会 new 一堆 widget，必须缓存） */
    private final java.util.Map<String, Preview> previewCache = new java.util.HashMap<>();

    @Override
    public Preview preview(String recipeId) {
        EmiRecipeManager manager = manager();
        if (manager == null) return null;
        Preview cached = previewCache.get(recipeId);
        if (cached != null) return cached;
        try {
            net.minecraft.resources.ResourceLocation id = net.minecraft.resources.ResourceLocation.tryParse(recipeId);
            if (id == null) return null;

            EmiRecipe recipe = manager.getRecipe(id);
            if (recipe == null) return null;

            int w = recipe.getDisplayWidth();
            int h = recipe.getDisplayHeight();
            if (w <= 0 || h <= 0) return null;

            Holder holder = new Holder(w, h);
            recipe.addWidgets(holder);
            EmiPreview preview = new EmiPreview(holder.widgets, w, h);
            if (previewCache.size() > 32) previewCache.clear();
            previewCache.put(recipeId, preview);
            return preview;
        } catch (Throwable t) {
            return null;
        }
    }

    /** 只会收集，不会画：EMI 的 widget 自己知道该画在哪 */
    private static final class Holder implements WidgetHolder {
        private final int width;
        private final int height;
        private final List<Widget> widgets = new ArrayList<>();

        Holder(int width, int height) {
            this.width = width;
            this.height = height;
        }

        @Override
        public int getWidth() {
            return width;
        }

        @Override
        public int getHeight() {
            return height;
        }

        @Override
        public <T extends Widget> T add(T widget) {
            if (widget != null) widgets.add(widget);
            return widget;
        }
    }

    private static final class EmiPreview implements Preview {
        private final List<Widget> widgets;
        private final int width;
        private final int height;

        EmiPreview(List<Widget> widgets, int width, int height) {
            this.widgets = widgets;
            this.width = width;
            this.height = height;
        }

        @Override
        public int width() {
            return width;
        }

        @Override
        public int height() {
            return height;
        }

        @Override
        public void render(GuiGraphics gg, int x, int y, int mouseX, int mouseY) {
            gg.pose().pushPose();
            gg.pose().translate(x, y, 0);
            try {
                for (Widget widget : widgets) {
                    widget.render(gg, mouseX - x, mouseY - y, 0f);
                }
            } catch (Throwable ignored) {
            } finally {
                gg.pose().popPose();
            }
        }
    }

    // ------------------------------------------------------------ 规则状态

    @Override
    public void tick() {
        pushItemFilter(true);
        // EMI 重载会把 invalidators 清空 → 我们那个谓词会丢，这里补回来（只需一次 bake）
        try {
            if (RuleMatcher.hasRecipeRules() && runtimeRebakeSafe()
                    && !(recipeInvalidator != null && EmiRecipes.invalidators.contains(recipeInvalidator))) {
                applyRecipeFilter(true);
            }
        } catch (Throwable ignored) {
        }
        if (countdown > 0 && --countdown == 0) applyAll();
    }

    private int countdown = -1;

    /** 把「能做的」全做掉：物品过滤 + （安全时）重建配方索引 */
    private void applyAll() {
        pushItemFilter(true);
        applyRecipeFilter(true);
    }

    @Override
    public PlanPreview planPreview() {
        EmiRecipeManager manager = manager();
        if (manager == null) return null;

        int revision = Rules.revision();
        if (cachedPlan != null && revision == cachedRevision) return cachedPlan;

        int recipes = 0;
        Map<String, Integer> byMod = new HashMap<>();
        long deadline = System.currentTimeMillis() + 2000L;
        try {
            List<EmiRecipe> all = manager.getRecipes();
            if (all != null) {
                for (EmiRecipe recipe : all) {
                    if (System.currentTimeMillis() > deadline) break;
                    RuleMatcher.Facts facts = EmiFacts.of(recipe);
                    if (facts == null || !RuleMatcher.hides(facts)) continue;
                    recipes++;
                    if (facts.modId() != null && !facts.modId().isEmpty()) {
                        byMod.merge(facts.modId(), 1, Integer::sum);
                    }
                }
            }
        } catch (Throwable ignored) {
        }

        cachedPlan = new PlanPreview(recipes, RuleMatcher.hiddenItems().size(),
                RuleMatcher.hiddenCategories().size(), byMod, false);
        cachedRevision = revision;
        return cachedPlan;
    }

    private int cachedRevision = Integer.MIN_VALUE;
    private PlanPreview cachedPlan;

    @Override
    public List<String> diagnose() {
        List<String> out = new ArrayList<>();
        try {
            EmiRecipeManager manager = manager();
            out.add("§7EMI 就绪 §f" + (manager != null));
            out.add("§7运行期重烘焙安全 §f" + runtimeRebakeSafe()
                    + " §8（数据包配方 " + EmiData.recipes.size() + " / 过滤 " + EmiData.recipeFilters.size() + "）");
            out.add("§7EmiStackList 总数 §f" + EmiStackList.stacks.size()
                    + " §7过滤后 §f" + EmiStackList.filteredStacks.size()
                    + " §8（差值 = 被隐藏的）");
            boolean itemInstalled = itemFilter != null && EmiHidden.pluginDisabledFilters.contains(itemFilter);
            out.add("§7物品过滤 §f" + (itemInstalled ? "已装" : "未装")
                    + " §7目标 §f" + RuleMatcher.hiddenItems().size()
                    + " §8（pluginDisabledFilters 共 " + EmiHidden.pluginDisabledFilters.size() + " 条）");
            boolean invInstalled = recipeInvalidator != null && EmiRecipes.invalidators.contains(recipeInvalidator);
            out.add("§7配方过滤 §f" + (invInstalled ? "已装" : "未装")
                    + " §7规则版本 §f" + Rules.revision() + " §7已应用 §f" + appliedRecipeRevision
                    + " §8（invalidators 共 " + EmiRecipes.invalidators.size() + " 条）");
        } catch (Throwable t) {
            out.add("§cdiagnose 出错：" + t);
        }
        return out;
    }

    @Override
    public Status status() {
        PlanPreview plan = cachedPlan;
        return new Status(plan == null ? 0 : plan.recipes(), plan == null ? 0 : plan.items(), 0L, ready());
    }

    // ------------------------------------------------------------ 配方级隐藏（安全时可实时）

    private static java.util.function.Predicate<EmiRecipe> recipeInvalidator;
    private static int appliedRecipeRevision = Integer.MIN_VALUE;

    /**
     * 把规则做成一个 {@code EmiRecipes.invalidators} 谓词，然后重建索引。
     *
     * <p>只在 {@link #runtimeRebakeSafe()} 为真时才动 —— 否则宁可按“重启生效”处理，
     * 也不要把玩家的数据包配方塞成双份。
     */
    private void applyRecipeFilter(boolean bake) {
        int revision = Rules.revision();
        boolean installed = recipeInvalidator != null
                && EmiRecipes.invalidators.contains(recipeInvalidator);
        if (revision == appliedRecipeRevision && installed) return;
        if (!runtimeRebakeSafe()) return;

        try {
            if (recipeInvalidator != null) {
                EmiRecipes.invalidators.remove(recipeInvalidator);
                recipeInvalidator = null;
            }
            appliedRecipeRevision = revision;

            if (!RuleMatcher.hasRecipeRules()) {
                if (bake) EmiRecipes.bake();
                return;
            }

            java.util.function.Predicate<EmiRecipe> filter = recipe -> {
                try {
                    RuleMatcher.Facts facts = EmiFacts.of(recipe);
                    return facts != null && RuleMatcher.hides(facts);
                } catch (Throwable t) {
                    return false;
                }
            };
            EmiRecipes.invalidators.add(filter);
            recipeInvalidator = filter;

            if (bake) EmiRecipes.bake();
        } catch (Throwable t) {
            FolioCleaner.LOGGER.warn("[Folio Cleaner] EMI 运行期重建配方索引失败：{}", String.valueOf(t));
        }
    }

    // ------------------------------------------------------------ 全部配方（含模组自建）

    private int recipeCacheKey = Integer.MIN_VALUE;
    private List<RecipeRef> recipeCache;

    @Override
    public List<RecipeRef> allRecipes() {
        EmiRecipeManager manager = manager();
        if (manager == null) return List.of();
        List<EmiRecipe> all;
        try {
            all = manager.getRecipes();
        } catch (Throwable t) {
            return List.of();
        }
        if (all == null) return List.of();

        int key = System.identityHashCode(manager) * 31 + all.size();
        if (recipeCache != null && key == recipeCacheKey) return recipeCache;

        List<RecipeRef> out = new ArrayList<>(all.size());
        for (EmiRecipe recipe : all) {
            RecipeRef ref = refOf(recipe);
            if (ref != null) out.add(ref);
        }
        recipeCache = out;
        recipeCacheKey = key;
        return out;
    }

    private RecipeRef refOf(EmiRecipe recipe) {
        try {
            net.minecraft.resources.ResourceLocation id = recipe.getId();
            if (id == null) return null;

            String categoryUid = null;
            try {
                if (recipe.getCategory() != null && recipe.getCategory().getId() != null) {
                    categoryUid = recipe.getCategory().getId().toString();
                }
            } catch (Throwable ignored) {
            }

            List<java.util.Set<String>> inputs = new ArrayList<>();
            try {
                List<EmiIngredient> in = recipe.getInputs();
                if (in != null) {
                    for (EmiIngredient ingredient : in) {
                        java.util.Set<String> ids = new java.util.LinkedHashSet<>();
                        if (ingredient != null) {
                            for (EmiStack stack : ingredient.getEmiStacks()) {
                                String item = EmiFacts.idOf(stack);
                                if (item != null) ids.add(item);
                            }
                        }
                        inputs.add(ids);
                    }
                }
            } catch (Throwable ignored) {
            }

            java.util.Set<String> outputIds = new java.util.LinkedHashSet<>();
            ItemStack icon = ItemStack.EMPTY;
            try {
                List<EmiStack> out = recipe.getOutputs();
                if (out != null) {
                    for (EmiStack stack : out) {
                        String item = EmiFacts.idOf(stack);
                        if (item != null) outputIds.add(item);
                        if (icon.isEmpty()) {
                            try {
                                ItemStack st = stack.getItemStack();
                                if (st != null && !st.isEmpty()) icon = st;
                            } catch (Throwable ignored) {
                            }
                        }
                    }
                }
            } catch (Throwable ignored) {
            }

            return new RecipeRef(id.toString(), categoryUid, id.getNamespace(), icon, inputs, outputIds);
        } catch (Throwable t) {
            return null;
        }
    }

    // ------------------------------------------------------------ 物品级隐藏（实时）

    private static java.util.function.Predicate<EmiStack> itemFilter;
    private static java.util.Set<String> itemFilterIds = java.util.Set.of();

    @Override
    public void requestApply() {
        applyAll();
    }

    @Override
    public void applyNow() {
        applyAll();
    }

    @Override
    public void requestApplyDelayed(int ticks) {
        countdown = Math.max(1, ticks);
    }

    /**
     * 把「要隐藏的物品」推给 EMI 的**插件过滤链**（{@code EmiHidden.pluginDisabledFilters}）。
     *
     * <p>为什么用这条链而不是 {@code hiddenStacks}：{@code hiddenStacks} 是玩家自己在 EMI 里
     * 点「隐藏」用的集合，还会被 {@code EmiPersistentData.save()} 写进 emi.json ——
     * 我们往里塞东西会污染玩家自己的状态，而且清不干净。{@code pluginDisabledFilters}
     * 是 EMI 留给插件的那条（{@code isDisabled()} 会逐个 test），{@code EmiHidden.clear()}
     * 时一起清掉，正好符合「每次重载重新推一次」的节奏。
     *
     * <p>{@code EmiStackList.bakeFiltered()} 是 EMI 自己点隐藏时也调的那句
     * （{@code EmiHidden.setVisibility} 末尾就是它），所以这是官方刷新路径。
     *
     * @param bake false 表示「现在是 EMI 的注册/重载阶段，bake 会由它自己接着做」，别抢
     */
    public static void pushItemFilter(boolean bake) {
        try {
            java.util.Set<String> hidden = RuleMatcher.hiddenItems();
            boolean installed = itemFilter != null
                    && EmiHidden.pluginDisabledFilters.contains(itemFilter);
            if (installed && hidden.equals(itemFilterIds)) return;

            if (itemFilter != null) EmiHidden.pluginDisabledFilters.remove(itemFilter);
            itemFilter = null;
            itemFilterIds = java.util.Set.of();

            if (!hidden.isEmpty()) {
                // ★isDisabled() 每帧会给**每一个** stack 调一次这个谓词，
                //   所以这里预先解析成 ResourceLocation 集合，谓词里只做一次 contains
                //   （用 EmiStack.getId()，它直接返回注册名，不构造 ItemStack）
                java.util.Set<net.minecraft.resources.ResourceLocation> snapshot = new java.util.HashSet<>();
                for (String raw : hidden) {
                    net.minecraft.resources.ResourceLocation id =
                            net.minecraft.resources.ResourceLocation.tryParse(raw);
                    if (id != null) snapshot.add(id);
                }
                java.util.function.Predicate<EmiStack> filter = stack -> {
                    try {
                        return stack != null && snapshot.contains(stack.getId());
                    } catch (Throwable t) {
                        return false;
                    }
                };
                EmiHidden.pluginDisabledFilters.add(filter);
                itemFilter = filter;
                itemFilterIds = new java.util.HashSet<>(hidden);
            }

            if (bake) EmiStackList.bakeFiltered();
        } catch (Throwable t) {
            FolioCleaner.LOGGER.debug("[Folio Cleaner] EMI 物品隐藏推送失败：{}", String.valueOf(t));
        }
    }

    @Override
    public boolean hasPendingChanges() {
        if (!runtimeRebakeSafe()) return RuleMatcher.hasRecipeRules();  // 只能重启生效
        if (!RuleMatcher.hasRecipeRules()) return false;                // 只有物品规则：实时
        boolean installed = recipeInvalidator != null
                && EmiRecipes.invalidators.contains(recipeInvalidator);
        return !installed || appliedRecipeRevision != Rules.revision();
    }

    @Override
    public void invalidate() {
        previewCache.clear();
        cachedPlan = null;
        cachedRevision = Integer.MIN_VALUE;
        recipeCache = null;
        recipeCacheKey = Integer.MIN_VALUE;
    }
}
