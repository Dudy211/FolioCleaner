package com.yuneko.foliocleaner;

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

    /** 最近一次成功应用时的规则版本（单调递增，只用来刷新缓存） */
    private static int appliedRevision = -1;
    /** 最近一次成功应用时的规则**内容**指纹：改完又改回来就算「已同步」 */
    private static int appliedFingerprint = Integer.MIN_VALUE;

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

    /** 同步预览：只算不改，告诉用户"如果现在同步会发生什么" */
    public record Preview(int recipes, int items, int categories,
                          java.util.Map<String, Integer> byMod, boolean customJob) {
    }

    private static Preview cachedPreview;
    private static int cachedPreviewHash = Integer.MIN_VALUE;

    /**
     * 预演一次同步（只读）。规则内容没变时复用上次结果。
     *
     * <p>这一步会遍历全表配方（大包 300~600ms），所以只在点「同步」时调。
     */
    public static Preview previewNow() {
        IJeiRuntime runtime = RUNTIME;
        Minecraft mc = Minecraft.getInstance();
        if (runtime == null || mc.level == null) return null;

        int hash = Rules.contentHash();
        if (cachedPreview != null && cachedPreviewHash == hash) return cachedPreview;

        long t0 = System.nanoTime();
        Selector selector = Selector.of(Rules.all(), runtime.getRecipeManager());
        if (selector.isEmpty()) {
            cachedPreview = new Preview(0, 0, 0, java.util.Map.of(), false);
            cachedPreviewHash = hash;
            return cachedPreview;
        }

        var ra = mc.level.registryAccess();
        int recipes = 0;
        java.util.Map<String, Integer> byMod = new java.util.TreeMap<>();
        for (Recipe<?> recipe : mc.level.getRecipeManager().getRecipes()) {
            boolean matched;
            try {
                matched = selector.matches(recipe, ra);
            } catch (Throwable t) {
                continue;
            }
            if (!matched) continue;
            recipes++;
            byMod.merge(recipe.getId().getNamespace(), 1, Integer::sum);
        }

        // ★JEI 自建配方（奥术铁砧那种，对象不是 Recipe 实现）不在 getRecipeManager() 里，
        //   上面那圈一条也数不到 → 二次确认会显示「没有配方被影响」。这里单独问 JEI 要计数。
        int custom = countCustomRecipes(runtime, byMod);
        if (custom > 0) {
            recipes += custom;
            FolioCleaner.LOGGER.info("[Folio Cleaner] 预览：另有 {} 条 JEI 自建配方会被影响", custom);
        }

        java.util.List<java.util.Map.Entry<String, Integer>> list = new java.util.ArrayList<>(byMod.entrySet());
        list.sort((a, b) -> Integer.compare(b.getValue(), a.getValue()));
        java.util.Map<String, Integer> ordered = new java.util.LinkedHashMap<>();
        for (var e : list) ordered.put(e.getKey(), e.getValue());

        int items = selector.hideItems().size();
        int cats = selector.hiddenCategories().size();
        cachedPreview = new Preview(recipes, items, cats, ordered, expectSlowSync());
        cachedPreviewHash = hash;
        FolioCleaner.LOGGER.debug("[Folio Cleaner] 预览计算完成：{} 条配方 / {} 物品 / {} 分类，用时 {}ms",
                recipes, items, cats, (System.nanoTime() - t0) / 1_000_000L);
        return cachedPreview;
    }

    /**
     * 统计「被规则影响的 JEI 自建配方」条数（同时并入 byMod 的模组明细）。
     *
     * <p>为什么单独算：{@code mc.level.getRecipeManager()} 里只有真正的 MC {@code Recipe}；
     * 模组自建的 JEI 配方（奥术铁砧之类，4000+ 条）**一条都不在里面**，
     * 所以同步前预览会显示「没有配方被影响」，但实际是生效的。
     *
     * <p>判断口径和运行时完全一致（{@link RuntimeFilter#keep} 就是界面里用的那套），
     * 所以预览和实际不会再出现"说没影响其实影响了"的分歧。
     */
    private static int countCustomRecipes(IJeiRuntime runtime, java.util.Map<String, Integer> byMod) {
        int n = 0;
        int budget = 20000;      // 防御：超大整合包别把预览拖成几秒
        try {
            var manager = runtime.getRecipeManager();
            java.util.List<mezz.jei.api.recipe.RecipeType<?>> types = new java.util.ArrayList<>();
            manager.createRecipeCategoryLookup().includeHidden().get().forEach(category -> {
                try {
                    types.add(category.getRecipeType());
                } catch (Throwable ignored) {
                }
            });
            for (var type : types) {
                if (budget <= 0) break;
                java.util.List<?> list;
                try {
                    list = manager.createRecipeLookup(type).includeHidden().get().toList();
                } catch (Throwable t) {
                    continue;
                }
                for (Object recipe : list) {
                    if (budget-- <= 0) break;
                    if (recipe instanceof Recipe<?>) continue;   // 真正的 MC 配方，上面已经统计过
                    boolean hidden;
                    try {
                        hidden = !RuntimeFilter.keep(recipe, type);
                    } catch (Throwable t) {
                        hidden = false;
                    }
                    if (!hidden) continue;
                    n++;
                    byMod.merge(namespaceOf(recipe), 1, Integer::sum);
                }
            }
        } catch (Throwable ignored) {
        }
        return n;
    }

    /** 自建配方的模组命名空间（它们都有 getId()，反射拿一下；没有就归到 jei:custom） */
    private static String namespaceOf(Object recipe) {
        try {
            Object id = recipe.getClass().getMethod("getId").invoke(recipe);
            if (id instanceof net.minecraft.resources.ResourceLocation rl) return rl.getNamespace();
        } catch (Throwable ignored) {
        }
        return "jei:custom";
    }

    /**
     * 统一（方案甲）：把重复组的成员写进 tag 数据包。
     *
     * <p>只在「单人 / 集成服务器」下工作：拿得到 {@code getSingleplayerServer()} 才能写世界目录并触发 reload。
     * 多人服的客户端没有服务器实例 —— 这里直接跳过（后续用网络包让服主端执行）。
     */
    /** 上次生成统一数据包时的组内容指纹（没变就不重复扫全表配方） */
    private static int lastUnifyHash = Integer.MIN_VALUE;

    public static void applyUnify() {
        String mode = ClientSettings.unifyMode;
        // 方案乙 / 方案丙：运行时改配方 / 运行时补匹配（不写数据包，不用 /reload）
        if ("rewrite".equals(mode) || "match".equals(mode)) {
            UnifyRuntime.applyIfNeeded(mode);
            return;
        }
        // ★不是运行时模式：必须先把运行时改动还原掉。
        //   以前这里直接 return —— 从 rewrite 切到 datapack 后，被改宽的原料数组还留着，
        //   等于「两套统一同时生效」，而且要重开游戏才恢复。
        UnifyRuntime.deactivate();
        if (!"datapack".equals(mode)) return;
        Minecraft mc = Minecraft.getInstance();
        var server = mc.getSingleplayerServer();
        if (server == null) return;

        java.util.List<DuplicateGroups.Group> groups;
        try {
            groups = com.yuneko.foliocleaner.client.DupScan.groups();
        } catch (Throwable t) {
            return;
        }
        // 注意：分组为空**不等于**该清空数据包 —— scan() 失败也会返回空列表。
        // 想清理残留请用 /foliocleaner unify rollback（或重复页属性台的「撤销」）。
        if (groups.isEmpty()) return;

        // 组内容没变就不重复生成（生成要扫全表配方，几千条 json 不算便宜）
        int hash = 7;
        for (DuplicateGroups.Group g : groups) {
            hash = hash * 31 + (g.key == null ? 0 : g.key.hashCode());
            hash = hash * 31 + (g.items == null ? 0 : g.items.size());
        }
        hash = hash * 31 + (ClientSettings.dupCrossTag ? 1 : 0);
        if (hash == lastUnifyHash) return;
        final int fingerprint = hash;

        server.execute(() -> {
            try {
                java.nio.file.Path root = unifyRoot(server);
                // 清单被别人删了（或第一次从旧版本升级上来）→ 强制重建一次，否则指纹相同就永远不补
                // 清单丢了、或清单里记的文件被删掉 → 必须重建一次（否则指纹相同就永远不补）
                boolean needRebuild = !java.nio.file.Files.isRegularFile(UnifyManifest.file(root))
                        || !UnifyManifest.allPresent(root);
                if (fingerprint == lastUnifyHash && !needRebuild) return;

                DatapackGen.Sync sync = DatapackGen.sync(
                        root, server.getResourceManager(), groups, ClientSettings.dupCrossTag,
                        com.yuneko.foliocleaner.Rules.hiddenItemIds());

                if (sync.written() > 0 || sync.pruned() > 0) {
                    FolioCleaner.LOGGER.info(
                            "[Folio Cleaner] 统一数据包已更新：写 {} 个文件（tag {} / 配方 {}，交叉物品 {}）"
                                    + " / 清理陈旧 {} 个 / 清单共 {} 个条目",
                            sync.written(), sync.tags().files(), sync.recipes().files(),
                            sync.recipes().values(), sync.pruned(), sync.entries());
                    for (String note : sync.recipes().notes()) {
                        FolioCleaner.LOGGER.info("[Folio Cleaner] 未处理：{}", note);
                    }
                    for (String kept : sync.keptModified()) {
                        FolioCleaner.LOGGER.warn("[Folio Cleaner] 这个文件被手工改过，没有删除：{}", kept);
                    }
                    lastUnifyHash = fingerprint;
                    if (ClientSettings.autoReload) {
                        server.reloadResources(server.getPackRepository().getSelectedIds());
                        FolioCleaner.LOGGER.info("[Folio Cleaner] 已自动 /reload");
                    }
                } else {
                    lastUnifyHash = fingerprint;
                }
            } catch (Throwable t) {
                FolioCleaner.LOGGER.warn("[Folio Cleaner] 生成统一数据包失败", t);
            }
        });
    }

    // ------------------------------------------------------------------ 统一数据包（指令用）

    /** 数据包根目录：{@code <存档>/datapacks/foliocleaner}（实现在 {@link ServerUnify}，双端共用） */
    public static java.nio.file.Path unifyRoot(net.minecraft.server.MinecraftServer server) {
        return ServerUnify.root(server);
    }

    /** 丢掉指纹缓存，让下一次 applyUnify 一定重新生成 */
    public static void resetUnifyCache() {
        lastUnifyHash = Integer.MIN_VALUE;
    }

    /** 规则改过但还没应用（GUI 用它显示警告 + 同步按钮） */
    public static boolean hasPendingChanges() {
        // 比内容而不是比 revision：加一条规则再删掉，内容回到原样，不该再提示未同步
        return Rules.contentHash() != appliedFingerprint;
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
                FolioCleaner.LOGGER.error("[Folio Cleaner] 应用规则时出错", t);
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

        long t0 = System.nanoTime();
        restoreRecipes(runtime);
        long tRestore = System.nanoTime();

        IRecipeManager jeiRecipes = runtime.getRecipeManager();
        Selector selector = Selector.of(Rules.all(), jeiRecipes);
        long tSelector = System.nanoTime();
        if (selector.isEmpty()) {
            lastHiddenRecipes = 0;
            lastHiddenItems = 0;
            lastApplyOk = true;
            lastApplyMs = (System.nanoTime() - start) / 1_000_000L;
            appliedRevision = Rules.revision();
            appliedFingerprint = Rules.contentHash();
            cachedPreview = null;
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

        long tScan = System.nanoTime();

        for (Map.Entry<RecipeType<?>, List<Object>> entry : grouped.entrySet()) {
            try {
                hideGroup(jeiRecipes, entry.getKey(), entry.getValue());
                HIDDEN.computeIfAbsent(entry.getKey(), k -> new ArrayList<>()).addAll(entry.getValue());
            } catch (Throwable t) {
                FolioCleaner.LOGGER.warn("[Folio Cleaner] 隐藏配方类型 {} 失败", entry.getKey().getUid(), t);
            }
        }
        long tHide = System.nanoTime();

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
            FolioCleaner.LOGGER.info("[Folio Cleaner] {} 命中 {} 条，隐藏后剩余 {} 条",
                    entry.getKey().getUid(), asked, remain);
        }

        // ---- 物品：只处理「这次真的变了」的那部分 ----
        // add/removeIngredientsAtRuntime 会重建 JEI 的物品索引 + 搜索后缀树（几秒级），
        // 每次编辑都全量还原再全量藏 = 白重建两遍，这是卡顿的大头。
        int itemCount = 0;
        List<ItemStack> wanted = new ArrayList<>();
        for (var item : selector.hideItems()) {
            wanted.add(new ItemStack(item));
        }
        List<ItemStack> toHide = new ArrayList<>();
        for (ItemStack st : wanted) {
            if (!holdsItem(HIDDEN_ITEMS, st)) toHide.add(st);
        }
        List<ItemStack> toShow = new ArrayList<>();
        for (ItemStack st : HIDDEN_ITEMS) {
            if (!holdsItem(wanted, st)) toShow.add(st);
        }
        if (!toHide.isEmpty()) {
            try {
                runtime.getIngredientManager().removeIngredientsAtRuntime(
                        mezz.jei.api.constants.VanillaTypes.ITEM_STACK, toHide);
                HIDDEN_ITEMS.addAll(toHide);
                itemCount = toHide.size();
            } catch (Throwable t) {
                FolioCleaner.LOGGER.warn("[Folio Cleaner] 隐藏物品失败", t);
            }
        }
        if (!toShow.isEmpty()) {
            try {
                runtime.getIngredientManager().addIngredientsAtRuntime(
                        mezz.jei.api.constants.VanillaTypes.ITEM_STACK, toShow);
                HIDDEN_ITEMS.removeAll(toShow);
            } catch (Throwable t) {
                FolioCleaner.LOGGER.warn("[Folio Cleaner] 取消隐藏物品失败", t);
            }
        }
        long tItems = System.nanoTime();

        // 整个配方分类隐藏（只有显式添加 category 规则时才会走到这里）
        for (RecipeType<?> type : selector.hiddenCategories()) {
            try {
                jeiRecipes.hideRecipeCategory(type);
                HIDDEN_CATEGORIES.add(type);
            } catch (Throwable t) {
                FolioCleaner.LOGGER.debug("[Folio Cleaner] 隐藏分类 {} 失败: {}", type.getUid(), t.toString());
            }
        }

        long tEnd = System.nanoTime();
        try {
            applyUnify();
        } catch (Throwable ignored) {
        }

        lastHiddenRecipes = recipeCount;
        lastHiddenItems = itemCount;
        lastApplyOk = true;
        lastApplyMs = (tEnd - start) / 1_000_000L;
        // INFO 级别：默认日志就能看到，用来定位「卡几秒」到底卡在哪一段
        FolioCleaner.LOGGER.info(
                "[Folio Cleaner] 同步耗时 {}ms = 还原 {} + 选择器 {} + 扫描 {} + 隐藏 {} + 物品 {} + 分类/收尾 {}",
                lastApplyMs,
                (tRestore - t0) / 1_000_000L,
                (tSelector - tRestore) / 1_000_000L,
                (tScan - tSelector) / 1_000_000L,
                (tHide - tScan) / 1_000_000L,
                (tItems - tHide) / 1_000_000L,
                (tEnd - tItems) / 1_000_000L);
        FolioCleaner.LOGGER.debug("[Folio Cleaner] 原版配方已隐藏 {} 条 / {} 个物品，用时 {}ms；自定义配方异步处理中={}",
                recipeCount, itemCount, lastApplyMs, customRunning);
        if (!customRunning) {
            appliedRevision = Rules.revision();
            appliedFingerprint = Rules.contentHash();
            cachedPreview = null;
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
                FolioCleaner.LOGGER.debug(
                        "[Folio Cleaner] 不限类型的物品规则不处理自定义 JEI 配方（性能考虑）；给规则加范围即可生效");
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
        FolioCleaner.LOGGER.debug("[Folio Cleaner] 开始分帧同步自定义配方，共 {} 条", work.size());
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
                    FolioCleaner.LOGGER.info("[Folio Cleaner] 自定义配方 {} 隐藏 {} 条",
                            entry.getKey().getUid(), entry.getValue().size());
                } catch (Throwable t) {
                    FolioCleaner.LOGGER.warn("[Folio Cleaner] 隐藏自定义配方失败", t);
                }
            }
            customHits.clear();
        }

        customRunning = false;
        lastHiddenRecipes += customHidden;
        lastApplyOk = true;
        appliedRevision = Rules.revision();
        appliedFingerprint = Rules.contentHash();
            cachedPreview = null;
        if (applyStartedAt > 0) {
            lastApplyMs = (System.nanoTime() - applyStartedAt) / 1_000_000L;
        }
        FolioCleaner.LOGGER.info("[Folio Cleaner] 自定义配方同步完成，共隐藏 {} 条", customHidden);
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
        restoreRecipes(runtime);
        if (runtime != null && !HIDDEN_ITEMS.isEmpty()) {
            try {
                runtime.getIngredientManager().addIngredientsAtRuntime(
                        mezz.jei.api.constants.VanillaTypes.ITEM_STACK, new ArrayList<>(HIDDEN_ITEMS));
            } catch (Throwable t) {
                FolioCleaner.LOGGER.debug("[Folio Cleaner] 还原隐藏物品失败: {}", t.toString());
            }
        }
        HIDDEN_ITEMS.clear();
    }

    /**
     * 只还原「配方 / 自定义配方 / 分类」这三块（都是 Set 操作，很便宜）。
     *
     * <p>物品**故意不在这里还原**：{@code addIngredientsAtRuntime} /
     * {@code removeIngredientsAtRuntime} 会触发 JEI 重建物品列表和搜索后缀树，
     * 是几秒级的大活。每次编辑都「全还原 + 全重藏」= 白重建两遍。
     * 物品改用差集单独处理，见 {@code applyNow()} 里那段。
     */
    private static synchronized void restoreRecipes(IJeiRuntime runtime) {
        if (runtime != null && !HIDDEN.isEmpty()) {
            IRecipeManager manager = runtime.getRecipeManager();
            for (Map.Entry<RecipeType<?>, List<Object>> entry : HIDDEN.entrySet()) {
                try {
                    unhideGroup(manager, entry.getKey(), entry.getValue());
                } catch (Throwable t) {
                    FolioCleaner.LOGGER.debug("[Folio Cleaner] 还原配方类型 {} 失败: {}",
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
                    FolioCleaner.LOGGER.debug("[Folio Cleaner] 还原自定义配方 {} 失败: {}",
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
                    FolioCleaner.LOGGER.debug("[Folio Cleaner] 还原分类 {} 失败: {}", type.getUid(), t.toString());
                }
            }
        }
        HIDDEN_CATEGORIES.clear();
    }

    /** 物品栈列表里有没有同一个物品（HIDDEN_ITEMS 里存的都是默认栈，比 item 就够） */
    private static boolean holdsItem(List<ItemStack> list, ItemStack stack) {
        for (ItemStack s : list) {
            if (s.getItem() == stack.getItem()) return true;
        }
        return false;
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
