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

import java.util.stream.Stream;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 把规则真正作用到 JEI 上。
 *
 * <p>做法：遍历客户端的全部配方，按规则挑出要隐藏的那些，然后按 recipe type 分组
 * 调用 {@link IRecipeManager#hideRecipes}。反向操作（删规则）用 {@code unhideRecipes} 还原。
 *
 * <p>所有调用都必须在客户端主线程；{@link #tick()} 由客户端 tick 事件驱动。
 */
public final class Cleaner {

    /** JEI 运行时；由 IModPlugin 回调写入 */
    public static volatile IJeiRuntime RUNTIME;

    /** 我们自己隐藏过的配方，用来在重算前还原 */
    private static final Map<RecipeType<?>, List<Object>> HIDDEN = new LinkedHashMap<>();
    /** 自定义 JEI 配方（不是原版 Recipe，比如 Iron's Spellbooks 的奥术铁砧） */
    private static final Map<RecipeType<?>, List<Object>> HIDDEN_CUSTOM = new LinkedHashMap<>();
    private static final List<ItemStack> HIDDEN_ITEMS = new ArrayList<>();
    private static final List<RecipeType<?>> HIDDEN_CATEGORIES = new ArrayList<>();

    /** 上一次应用的自检报告：每个配方类型命中多少、隐藏后 JEI 里还剩多少 */
    private static final List<String> REPORT = new ArrayList<>();

    public static List<String> report() {
        return REPORT;
    }

    /** -1 = 没有待办；>0 = 还要等几个 tick */
    private static int countdown = -1;

    /** 最近一次成功应用时的规则版本（用来判断有没有未同步的修改） */
    private static int appliedRevision = -1;

    // ---- 自定义 JEI 配方的分帧任务（避免一次性卡 10 秒） ----
    private static boolean customRunning;
    /** 待判断的 {分类, 配方} */
    private static List<Object[]> customWork;
    /** 命中的 {RecipeType, 配方} */
    private static List<Object[]> customHits;
    private static int customCursor;
    private static List<Rule> customRules;
    private static int customHidden;
    private static boolean customHidePhase;
    /** 本次同步开始时刻（用于统计总耗时） */
    private static long applyStartedAt;
    /** 布局槽位缓存：改规则后重算时不用重建 layout */
    private static final Map<Object, IRecipeSlotsView> SLOT_CACHE = new java.util.IdentityHashMap<>();

    /** 同步进度 0..1；不在同步时返回 -1（HUD 用） */
    public static float progress() {
        if (!customRunning) return -1f;
        int total = customWork == null ? 1 : Math.max(1, customWork.size());
        return Math.min(1f, (float) customCursor / total);
    }

    /** 预计这次同步会慢吗（有范围限定的物品规则 → 要处理自定义配方） */
    public static boolean expectSlowSync() {
        for (Rule rule : Rules.all()) {
            if (!rule.enabled || !Rule.TYPE_ITEM.equals(rule.type)) continue;
            for (Rule.Range range : rule.effectiveRanges()) {
                if (range.hasType()) return true;
            }
        }
        return false;
    }

    public static boolean isSyncing() {
        return customRunning;
    }

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

    /** 规则改过但还没应用（GUI 用它显示警告 + 同步按钮） */
    public static boolean hasPendingChanges() {
        return Rules.revision() != appliedRevision;
    }

    /** 手动同步：下一个 tick 应用当前规则 */
    public static void applyPending() {
        requestApply();
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
        tickCustomJob();
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

    /** @return 本次隐藏的配方数；-1 表示当前还不具备应用条件（没进世界 / JEI 没就绪） */
    public static synchronized int applyNow() {
        long start = System.nanoTime();
        applyStartedAt = start;
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
            appliedRevision = Rules.revision();
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

        startCustomJob(runtime, Rules.all());

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

        lastHiddenRecipes = recipeCount;
        lastHiddenItems = itemCount;
        lastApplyOk = true;
        lastApplyMs = (System.nanoTime() - start) / 1_000_000L;
        JeiCleaner.LOGGER.debug("[JEI Cleaner] 原版配方已隐藏 {} 条 / {} 个物品，用时 {}ms；自定义配方异步处理中={}",
                recipeCount, itemCount, lastApplyMs, customRunning);
        if (!customRunning) {
            appliedRevision = Rules.revision();
        }
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

    /**
     * 处理「不是原版 Recipe」的自定义 JEI 配方。
     *
     * <p>这类配方（例如 Iron's Spellbooks 的奥术铁砧）由 mod 自己的
     * {@code IRecipeCategory} 渲染，根本不进原版 {@code RecipeManager}，
     * 所以 {@code Selector} 看不到它们。
     *
     * <p>做法：遍历每个 JEI 分类的<b>全部配方（含已隐藏）</b>，
     * 先试反射 {@code getRecipeItems()}（快），失败就用 JEI 自己的布局槽位判断（通用）。
     * 命中后调 {@code hideRecipes} —— 和 JEI 内部隐藏用的是同一条路。
     */
    /**
     * 启动自定义配方的分帧任务。
     *
     * <p>按「配方」粒度分帧：每个 tick 最多处理一小批（至少 1 条），
     * 单条布局构建再慢也不会把一整个分类（几百条）压在同一个 tick 里。
     */
    private static void startCustomJob(IJeiRuntime runtime, List<Rule> rules) {
        customRunning = false;
        customWork = null;
        customHits = null;
        customCursor = 0;
        customHidden = 0;
        customHidePhase = false;
        customRules = rules;

        IRecipeManager manager = runtime.getRecipeManager();

        // 只处理「规则明确指定了范围类型」的分类；不限类型的规则不碰自定义配方。
        java.util.Set<String> explicitTypes = new java.util.HashSet<>();
        boolean unrestricted = false;
        for (Rule rule : rules) {
            if (rule == null || !rule.enabled || !Rule.TYPE_ITEM.equals(rule.type)) continue;
            for (Rule.Range range : rule.effectiveRanges()) {
                if (!range.hasType()) {
                    unrestricted = true;
                    continue;
                }
                for (String part : range.type.split(",")) {
                    String v = part.trim();
                    if (!v.isEmpty()) explicitTypes.add(v);
                }
            }
        }
        if (explicitTypes.isEmpty()) {
            if (unrestricted) {
                JeiCleaner.LOGGER.debug(
                        "[JEI Cleaner] 不限类型的物品规则不处理自定义 JEI 配方（性能考虑）；给规则加范围即可生效");
            }
            return;
        }

        List<IRecipeCategory<?>> all;
        try {
            all = manager.createRecipeCategoryLookup().get().toList();
        } catch (Throwable t) {
            return;
        }

        List<Object[]> work = new ArrayList<>();
        for (IRecipeCategory<?> category : all) {
            if (!explicitTypes.contains(category.getRecipeType().getUid().toString())) continue;
            try {
                @SuppressWarnings("rawtypes")
                List<?> recipes = manager.createRecipeLookup((RecipeType) category.getRecipeType())
                        .includeHidden().get().toList();
                for (Object recipe : recipes) {
                    if (recipe instanceof Recipe<?>) continue; // 原版配方由 Selector 处理
                    work.add(new Object[]{category, recipe});
                }
            } catch (Throwable ignored) {
            }
        }
        if (work.isEmpty()) return;

        customWork = work;
        customHits = new ArrayList<>();
        customRunning = true;
        JeiCleaner.LOGGER.debug("[JEI Cleaner] 开始分帧同步自定义配方，共 {} 条", work.size());
    }

    /** 每个 tick 推进一点：优先保证「至少处理 1 条」，总预算约 6ms */
    private static void tickCustomJob() {
        if (!customRunning) return;

        if (!customHidePhase) {
            long deadline = System.nanoTime() + 6_000_000L;
            int processed = 0;
            while (customCursor < customWork.size()) {
                if (processed >= 1 && System.nanoTime() >= deadline) break;
                if (processed >= 64) break;

                Object[] item = customWork.get(customCursor++);
                processed++;
                IRecipeCategory<?> category = (IRecipeCategory<?>) item[0];
                Object recipe = item[1];
                try {
                    if (shouldHideCustom(category, recipe, category.getRecipeType(), customRules)) {
                        customHits.add(new Object[]{category.getRecipeType(), recipe});
                    }
                } catch (Throwable ignored) {
                }
            }
            if (customCursor < customWork.size()) return;
            customHidePhase = true;
        }

        // 收尾：按类型批量隐藏（这一步很快）
        if (customHits != null && !customHits.isEmpty()) {
            Map<RecipeType<?>, List<Object>> grouped = new LinkedHashMap<>();
            for (Object[] hit : customHits) {
                RecipeType<?> type = (RecipeType<?>) hit[0];
                grouped.computeIfAbsent(type, k -> new ArrayList<>()).add(hit[1]);
            }
            for (Map.Entry<RecipeType<?>, List<Object>> entry : grouped.entrySet()) {
                try {
                    @SuppressWarnings("rawtypes")
                    IRecipeManager manager = RUNTIME.getRecipeManager();
                    manager.hideRecipes((RecipeType) entry.getKey(), (Collection) entry.getValue());
                    HIDDEN_CUSTOM.computeIfAbsent(entry.getKey(), k -> new ArrayList<>()).addAll(entry.getValue());
                    customHidden += entry.getValue().size();
                    JeiCleaner.LOGGER.info("[JEI Cleaner] 自定义配方 {} 隐藏 {} 条",
                            entry.getKey().getUid(), entry.getValue().size());
                } catch (Throwable t) {
                    JeiCleaner.LOGGER.warn("[JEI Cleaner] 隐藏自定义配方失败", t);
                }
            }
            customHits.clear();
        }

        customRunning = false;
        lastHiddenRecipes += customHidden;
        lastApplyOk = true;
        appliedRevision = Rules.revision();
        if (applyStartedAt > 0) {
            lastApplyMs = (System.nanoTime() - applyStartedAt) / 1_000_000L;
        }
        JeiCleaner.LOGGER.info("[JEI Cleaner] 自定义配方同步完成，共隐藏 {} 条", customHidden);
    }

    private static boolean shouldHideCustom(IRecipeCategory<?> category, Object recipe,
                                            RecipeType<?> type, List<Rule> rules) {
        if (RuntimeFilter.canInspect(recipe)) {
            return !RuntimeFilter.keep(recipe, type);
        }
        IRecipeSlotsView slots = SLOT_CACHE.get(recipe);
        if (slots == null) {
            IRecipeLayoutDrawable<?> layout = createLayout(category, recipe);
            if (layout == null) return false;
            slots = layout.getRecipeSlotsView();
            if (SLOT_CACHE.size() > 4000) SLOT_CACHE.clear();
            SLOT_CACHE.put(recipe, slots);
        }
        return matchesSlots(slots, type, rules);
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
