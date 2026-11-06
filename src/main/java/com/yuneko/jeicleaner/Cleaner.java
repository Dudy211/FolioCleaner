package com.yuneko.jeicleaner;

import mezz.jei.api.gui.IRecipeLayoutDrawable;
import mezz.jei.api.gui.ingredient.IRecipeSlotView;
import mezz.jei.api.gui.ingredient.IRecipeSlotsView;
import mezz.jei.api.ingredients.IIngredientType;
import mezz.jei.api.recipe.IFocus;
import mezz.jei.api.recipe.IFocusGroup;
import mezz.jei.api.recipe.IRecipeManager;
import mezz.jei.api.recipe.RecipeIngredientRole;
import mezz.jei.api.recipe.RecipeType;
import mezz.jei.api.recipe.category.IRecipeCategory;
import mezz.jei.api.runtime.IJeiRuntime;
import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeManager;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * 把规则真正作用到 JEI 上。
 *
 * <p>遍历客户端全部配方，按规则挑出要隐藏的，按 recipe type 分组调
 * {@link IRecipeManager#hideRecipes}；反向操作（删规则）用 unhideRecipes 还原。
 * 另外处理「不是原版 Recipe」的自定义 JEI 配方（奥术铁砧等）。
 *
 * <p>所有调用都必须在客户端主线程；{@link #tick()} 由客户端 tick 驱动。
 */
public final class Cleaner {

    public static volatile IJeiRuntime RUNTIME;

    /** 我们自己隐藏过的配方，用来在重算前还原 */
    private static final Map<RecipeType<?>, List<Object>> HIDDEN = new LinkedHashMap<>();
    /** 自定义 JEI 配方（不是原版 Recipe） */
    private static final Map<RecipeType<?>, List<Object>> HIDDEN_CUSTOM = new LinkedHashMap<>();
    private static final List<ItemStack> HIDDEN_ITEMS = new ArrayList<>();
    private static final List<RecipeType<?>> HIDDEN_CATEGORIES = new ArrayList<>();

    /** 上一次应用的自检报告 */
    private static final List<String> REPORT = new ArrayList<>();

    public static List<String> report() {
        return REPORT;
    }

    /** -1 = 没有待办；>0 = 还要等几个 tick */
    private static int countdown = -1;

    private static int lastHiddenRecipes;
    private static int lastHiddenItems;
    private static long lastApplyMs;
    private static boolean lastApplyOk;

    private Cleaner() {
    }

    public static int lastHiddenRecipes() {
        return lastHiddenRecipes;
    }

    public static int lastHiddenItems() {
        return lastHiddenItems;
    }

    public static long lastApplyMs() {
        return lastApplyMs;
    }

    public static boolean lastApplyOk() {
        return lastApplyOk;
    }

    public static boolean hasRuntime() {
        return RUNTIME != null;
    }

    /** 规则变了：下一个 tick 重新应用 */
    public static void requestApply() {
        countdown = 1;
    }

    /** 例如刚进世界，给 JEI 一点时间重建配方索引 */
    public static void requestApplyDelayed(int ticks) {
        countdown = Math.max(countdown, Math.max(1, ticks));
    }

    public static void tick() {
        if (countdown <= 0) return;
        countdown--;
        if (countdown == 0) {
            countdown = -1;
            try {
                applyNow();
            } catch (Throwable t) {
                lastApplyOk = false;
                JeiCleaner.LOGGER.error("[JEI Cleaner] 应用规则时出错", t);
            }
        }
    }

    /** @return 本次隐藏的配方数；-1 表示还不具备条件（没进世界 / JEI 没就绪） */
    public static synchronized int applyNow() {
        long start = System.nanoTime();
        IJeiRuntime runtime = RUNTIME;
        Minecraft mc = Minecraft.getInstance();
        if (runtime == null || mc.level == null) {
            lastApplyOk = false;
            return -1;
        }

        restore(runtime);

        IRecipeManager jeiRecipes = runtime.getRecipeManager();
        Selector selector = Selector.of(Rules.all(), jeiRecipes);
        if (selector.isEmpty()) {
            lastHiddenRecipes = 0;
            lastHiddenItems = 0;
            lastApplyOk = true;
            lastApplyMs = (System.nanoTime() - start) / 1_000_000L;
            return 0;
        }

        RecipeManager recipeManager = mc.level.getRecipeManager();
        net.minecraft.core.RegistryAccess registryAccess = mc.level.registryAccess();

        Map<RecipeType<?>, List<Object>> grouped = new LinkedHashMap<>();
        int recipeCount = 0;
        for (Recipe<?> recipe : recipeManager.getRecipes()) {
            boolean matched;
            try {
                matched = selector.matches(recipe, registryAccess);
            } catch (Throwable t) {
                continue;
            }
            if (!matched) continue;
            RecipeType<?> type = selector.typeOf(recipe);
            if (type == null) continue; // 该配方类型没在 JEI 里注册，跳过
            grouped.computeIfAbsent(type, k -> new ArrayList<>()).add(recipe);
            recipeCount++;
        }

        for (Map.Entry<RecipeType<?>, List<Object>> entry : grouped.entrySet()) {
            try {
                hideGroup(jeiRecipes, entry.getKey(), entry.getValue());
                HIDDEN.computeIfAbsent(entry.getKey(), k -> new ArrayList<>()).addAll(entry.getValue());
            } catch (Throwable t) {
                JeiCleaner.LOGGER.warn("[JEI Cleaner] 隐藏配方类型 {} 失败", entry.getKey().getUid(), t);
            }
        }

        int customCount = applyCustomRecipes(runtime, Rules.all());

        // 自检：隐藏之后 JEI 里这个类型还剩多少条（不含 hidden）
        REPORT.clear();
        for (Map.Entry<RecipeType<?>, List<Object>> entry : grouped.entrySet()) {
            long remain;
            try {
                remain = jeiRecipes.createRecipeLookup(entry.getKey()).get().count();
            } catch (Throwable t) {
                remain = -1;
            }
            int asked = entry.getValue().size();
            boolean ok = remain == 0;
            REPORT.add((ok ? "§a✔ " : "§c✘ ") + entry.getKey().getUid()
                    + " §7命中 " + asked + " 条，隐藏后剩余 " + (remain < 0 ? "?" : remain) + " 条");
            JeiCleaner.LOGGER.info("[JEI Cleaner] {} 命中 {} 条，隐藏后剩余 {} 条",
                    entry.getKey().getUid(), asked, remain);
        }

        int itemCount = 0;
        if (!selector.hideItems().isEmpty()) {
            List<ItemStack> stacks = new ArrayList<>();
            for (var item : selector.hideItems()) {
                stacks.add(new ItemStack(item));
            }
            try {
                runtime.getIngredientManager().removeIngredientsAtRuntime(
                        mezz.jei.api.constants.VanillaTypes.ITEM_STACK, stacks);
                HIDDEN_ITEMS.addAll(stacks);
                itemCount = stacks.size();
            } catch (Throwable t) {
                JeiCleaner.LOGGER.warn("[JEI Cleaner] 隐藏物品失败", t);
            }
        }

        // 整个配方分类隐藏（只有显式添加 category 规则时才会走到这里）
        for (RecipeType<?> type : selector.hiddenCategories()) {
            try {
                jeiRecipes.hideRecipeCategory(type);
                HIDDEN_CATEGORIES.add(type);
            } catch (Throwable t) {
                JeiCleaner.LOGGER.debug("[JEI Cleaner] 隐藏分类 {} 失败: {}", type.getUid(), t.toString());
            }
        }

        lastHiddenRecipes = recipeCount + customCount;
        lastHiddenItems = itemCount;
        lastApplyOk = true;
        lastApplyMs = (System.nanoTime() - start) / 1_000_000L;
        JeiCleaner.LOGGER.debug("[JEI Cleaner] 已隐藏 {} 条配方（原版 {} + 自定义 {}）/ {} 个物品，用时 {}ms",
                recipeCount + customCount, recipeCount, customCount, itemCount, lastApplyMs);
        return recipeCount;
    }

    /** 空 focus：构建布局时不需要搜索焦点 */
    private static final IFocusGroup EMPTY_FOCUS = new IFocusGroup() {
        @Override
        public boolean isEmpty() {
            return true;
        }

        @Override
        public List<IFocus<?>> getAllFocuses() {
            return List.of();
        }

        @Override
        public Stream<IFocus<?>> getFocuses(RecipeIngredientRole role) {
            return Stream.empty();
        }

        @Override
        public <T> Stream<IFocus<T>> getFocuses(IIngredientType<T> type) {
            return Stream.empty();
        }

        @Override
        public <T> Stream<IFocus<T>> getFocuses(IIngredientType<T> type, RecipeIngredientRole role) {
            return Stream.empty();
        }
    };

    /** 处理不是原版 Recipe 的自定义 JEI 配方（奥术铁砧等） */
    @SuppressWarnings({"rawtypes", "unchecked"})
    private static int applyCustomRecipes(IJeiRuntime runtime, List<Rule> rules) {
        IRecipeManager manager = runtime.getRecipeManager();
        List<IRecipeCategory<?>> categories;
        try {
            categories = manager.createRecipeCategoryLookup().get().toList();
        } catch (Throwable t) {
            return 0;
        }

        int total = 0;
        for (IRecipeCategory<?> category : categories) {
            RecipeType<?> type = category.getRecipeType();
            List<Object> all;
            try {
                all = (List<Object>) manager.createRecipeLookup((RecipeType) type)
                        .includeHidden().get().toList();
            } catch (Throwable t) {
                continue;
            }

            List<Object> toHide = new ArrayList<>();
            for (Object recipe : all) {
                if (recipe instanceof Recipe<?>) continue; // 原版配方由 Selector 处理
                try {
                    if (shouldHideCustom(category, recipe, type, rules)) toHide.add(recipe);
                } catch (Throwable ignored) {
                }
            }
            if (toHide.isEmpty()) continue;

            try {
                manager.hideRecipes((RecipeType) type, (Collection) toHide);
                HIDDEN_CUSTOM.computeIfAbsent(type, k -> new ArrayList<>()).addAll(toHide);
                total += toHide.size();
                JeiCleaner.LOGGER.info("[JEI Cleaner] 自定义配方 {} 隐藏 {} 条",
                        type.getUid(), toHide.size());
            } catch (Throwable t) {
                JeiCleaner.LOGGER.warn("[JEI Cleaner] 隐藏自定义配方 {} 失败", type.getUid(), t);
            }
        }
        return total;
    }

    private static boolean shouldHideCustom(IRecipeCategory<?> category, Object recipe,
                                            RecipeType<?> type, List<Rule> rules) {
        if (RuntimeFilter.canInspect(recipe)) {
            return !RuntimeFilter.keep(recipe, type);
        }
        IRecipeLayoutDrawable<?> layout = createLayout(category, recipe);
        if (layout == null) return false;
        return matchesSlots(layout.getRecipeSlotsView(), type, rules);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static IRecipeLayoutDrawable<?> createLayout(IRecipeCategory<?> category, Object recipe) {
        try {
            Object drawable = RUNTIME.getRecipeManager()
                    .createRecipeLayoutDrawable((IRecipeCategory) category, recipe, EMPTY_FOCUS)
                    .orElse(null);
            return (IRecipeLayoutDrawable<?>) drawable;
        } catch (Throwable t) {
            return null;
        }
    }

    /** 按 JEI 布局的输入/输出槽位判断（任何能显示的配方都适用） */
    private static boolean matchesSlots(IRecipeSlotsView slots, RecipeType<?> type, List<Rule> rules) {
        if (slots == null) return false;
        String typeId = type.getUid().toString();

        List<ItemStack> outputs = new ArrayList<>();
        List<List<ItemStack>> inputs = new ArrayList<>();

        for (IRecipeSlotView slot : slots.getSlotViews()) {
            List<ItemStack> stacks;
            try {
                stacks = slot.getItemStacks().toList();
            } catch (Throwable t) {
                continue;
            }
            if (stacks.isEmpty()) continue;
            RecipeIngredientRole role = slot.getRole();
            if (role == RecipeIngredientRole.OUTPUT) {
                outputs.addAll(stacks);
            } else if (role == RecipeIngredientRole.INPUT) {
                inputs.add(stacks);
            }
        }

        for (Rule rule : rules) {
            if (rule == null || !rule.enabled || !Rule.TYPE_ITEM.equals(rule.type)) continue;
            ResourceLocation id = ResourceLocation.tryParse(rule.target);
            if (id == null) continue;
            Item item = BuiltInRegistries.ITEM.get(id);
            if (item == null || item == Items.AIR) continue;

            for (Rule.Range range : rule.effectiveRanges()) {
                if (!typeMatches(typeId, range.type)) continue;

                if (rule.hideOutput && containsItem(outputs, item)) return true;
                if (!rule.hideIngredient) continue;

                int targetSlot = -1;
                for (int i = 0; i < inputs.size(); i++) {
                    if (containsItem(inputs.get(i), item)) {
                        targetSlot = i;
                        break;
                    }
                }
                if (targetSlot < 0) continue;

                for (List<String> group : range.groups()) {
                    if (group.isEmpty()) return true;
                    boolean all = true;
                    for (String partner : group) {
                        boolean found = false;
                        for (int i = 0; i < inputs.size(); i++) {
                            if (i == targetSlot) continue;
                            if (slotAccepts(inputs.get(i), partner)) {
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
            }
        }
        return false;
    }

    private static boolean typeMatches(String typeId, String filter) {
        if (filter == null || filter.isBlank()) return true;
        for (String part : filter.split(",")) {
            if (part.trim().equals(typeId)) return true;
        }
        return false;
    }

    private static boolean containsItem(List<ItemStack> stacks, Item item) {
        for (ItemStack stack : stacks) {
            if (!stack.isEmpty() && stack.getItem() == item) return true;
        }
        return false;
    }

    private static boolean slotAccepts(List<ItemStack> stacks, String partner) {
        if (stacks == null || stacks.isEmpty() || partner == null || partner.isBlank()) return false;
        try {
            if (partner.startsWith("#")) {
                ResourceLocation id = ResourceLocation.tryParse(partner.substring(1));
                if (id == null) return false;
                TagKey<Item> tag = TagKey.create(Registries.ITEM, id);
                for (ItemStack stack : stacks) {
                    if (!stack.isEmpty() && stack.is(tag)) return true;
                }
                return false;
            }
            ResourceLocation id = ResourceLocation.tryParse(partner);
            if (id == null) return false;
            Item item = BuiltInRegistries.ITEM.get(id);
            if (item == null || item == Items.AIR) return false;
            return containsItem(stacks, item);
        } catch (Throwable t) {
            return false;
        }
    }

    /** JEI 卸载 / 重载前，把它还原成我们没动过的样子 */
    public static synchronized void restore(IJeiRuntime runtime) {
        if (runtime != null && !HIDDEN.isEmpty()) {
            IRecipeManager manager = runtime.getRecipeManager();
            for (Map.Entry<RecipeType<?>, List<Object>> entry : HIDDEN.entrySet()) {
                try {
                    unhideGroup(manager, entry.getKey(), entry.getValue());
                } catch (Throwable t) {
                    JeiCleaner.LOGGER.debug("[JEI Cleaner] 还原配方类型 {} 失败: {}",
                            entry.getKey().getUid(), t.toString());
                }
            }
        }
        HIDDEN.clear();

        if (runtime != null && !HIDDEN_CUSTOM.isEmpty()) {
            IRecipeManager manager = runtime.getRecipeManager();
            for (Map.Entry<RecipeType<?>, List<Object>> entry : HIDDEN_CUSTOM.entrySet()) {
                try {
                    unhideGroup(manager, entry.getKey(), entry.getValue());
                } catch (Throwable t) {
                    JeiCleaner.LOGGER.debug("[JEI Cleaner] 还原自定义配方 {} 失败: {}",
                            entry.getKey().getUid(), t.toString());
                }
            }
        }
        HIDDEN_CUSTOM.clear();

        if (runtime != null && !HIDDEN_CATEGORIES.isEmpty()) {
            IRecipeManager manager = runtime.getRecipeManager();
            for (RecipeType<?> type : HIDDEN_CATEGORIES) {
                try {
                    manager.unhideRecipeCategory(type);
                } catch (Throwable t) {
                    JeiCleaner.LOGGER.debug("[JEI Cleaner] 还原分类 {} 失败: {}", type.getUid(), t.toString());
                }
            }
        }
        HIDDEN_CATEGORIES.clear();

        if (runtime != null && !HIDDEN_ITEMS.isEmpty()) {
            try {
                runtime.getIngredientManager().addIngredientsAtRuntime(
                        mezz.jei.api.constants.VanillaTypes.ITEM_STACK, new ArrayList<>(HIDDEN_ITEMS));
            } catch (Throwable t) {
                JeiCleaner.LOGGER.debug("[JEI Cleaner] 还原隐藏物品失败: {}", t.toString());
            }
        }
        HIDDEN_ITEMS.clear();
    }

    /** runtime 消失（JEI 重载）时调用 */
    public static synchronized void onRuntimeGone() {
        HIDDEN.clear();
        HIDDEN_CUSTOM.clear();
        HIDDEN_ITEMS.clear();
        HIDDEN_CATEGORIES.clear();
        RUNTIME = null;
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static void hideGroup(IRecipeManager manager, RecipeType<?> type, List<Object> recipes) {
        manager.hideRecipes((RecipeType) type, (Collection) recipes);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static void unhideGroup(IRecipeManager manager, RecipeType<?> type, List<Object> recipes) {
        manager.unhideRecipes((RecipeType) type, (Collection) recipes);
    }
}
