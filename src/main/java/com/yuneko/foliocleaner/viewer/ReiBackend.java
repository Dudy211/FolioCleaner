package com.yuneko.foliocleaner.viewer;

import com.yuneko.foliocleaner.FolioCleaner;
import com.yuneko.foliocleaner.Rules;
import me.shedaniel.rei.api.client.gui.widgets.Widget;
import me.shedaniel.rei.api.client.registry.category.CategoryRegistry;
import me.shedaniel.rei.api.client.registry.display.DisplayCategory;
import me.shedaniel.rei.api.client.registry.display.DisplayRegistry;
import me.shedaniel.rei.api.client.registry.entry.EntryRegistry;
import me.shedaniel.rei.api.common.category.CategoryIdentifier;
import me.shedaniel.rei.api.common.display.Display;
import me.shedaniel.rei.api.common.entry.EntryIngredient;
import me.shedaniel.rei.api.common.entry.EntryStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * REI 后端（Roughly Enough Items 12.x）。
 *
 * <p>能力的实际情况比原来的假设好很多 —— javap 逐个核过 12.1.785：
 * <ul>
 *   <li>{@code DisplayRegistry.getAll()} → {@code Map<CategoryIdentifier, List<Display>>}
 *       —— <b>能枚举全部配方</b></li>
 *   <li>{@code Display.getInputEntries() / getOutputEntries()} → {@code List<EntryIngredient>}
 *       —— <b>能做物品级匹配</b>（不再只能按配方 id 猜）</li>
 *   <li>{@code CategoryRegistry} 是 {@code Iterable<CategoryConfiguration<?>>}
 *       —— 分类名 / 图标 / 催化剂都有了</li>
 *   <li>{@code DisplayCategory.setupDisplay(display, rect)} → {@code List<Widget>}
 *       —— <b>能画预览</b></li>
 *   <li>{@code DisplayVisibilityPredicate} 每次渲染都问一次 —— 隐藏是<b>实时</b>的</li>
 * </ul>
 *
 * <p>三个「不在 REI jar 里」的类型只能反射：{@code me.shedaniel.math.Rectangle}
 * （独立小库）和 {@code dev.architectury.event.EventResult}（architectury）。
 * 反射失败只会让「预览」不可用，不影响别的能力。
 */
public final class ReiBackend implements RecipeViewerBackend, InvocationHandler {

    // 反射目标（不在 REI 的 jar 里，无法编译期引用）
    private static final String RECTANGLE = "me.shedaniel.math.Rectangle";
    private static final String EVENT_RESULT = "dev.architectury.event.EventResult";
    private static final String DISPLAY_VIEW = "me.shedaniel.rei.api.client.registry.display.DisplayCategoryView";
    private static final String VISIBILITY = "me.shedaniel.rei.api.client.registry.display.visibility.DisplayVisibilityPredicate";

    private DisplayRegistry displays;
    private CategoryRegistry categories;
    private EntryRegistry entries;

    private Object predicate;
    private Object passResult;
    private Object hideResult;

    private Class<?> rectangleClass;
    private Constructor<?> rectangleCtor;
    private Method setupDisplay;
    private Method widgetRender;

    /** display → 规则事实（每帧评估，必须缓存） */
    private final Map<Display, RuleMatcher.Facts> factsCache = new IdentityHashMap<>();
    private int factsRevision = Integer.MIN_VALUE;

    /** 我们摘掉的物品（规则变了要能放回去） */
    private final List<EntryStack<?>> removedEntries = new ArrayList<>();
    private int appliedRevision = Integer.MIN_VALUE;

    private boolean failed;

    // ------------------------------------------------------------ 生命周期

    private boolean boot() {
        if (ready()) return true;
        if (failed) return false;
        try {
            displays = DisplayRegistry.getInstance();
            categories = CategoryRegistry.getInstance();
            entries = EntryRegistry.getInstance();
        } catch (Throwable t) {
            // 还没初始化完，下个 tick 再试
            return false;
        }
        return ready();
    }

    @Override
    public String id() {
        return "rei";
    }

    @Override
    public String label() {
        return "REI";
    }

    @Override
    public boolean ready() {
        return displays != null && categories != null;
    }

    @Override
    public Caps caps() {
        return new Caps(true, rectangleCtor != null || prepareRenderGlue(), true, true);
    }

    @Override
    public void tick() {
        if (!ready()) {
            boot();
            return;
        }
        ensurePredicates();
        applyItemHidingIfNeeded();
        ensureItemsStillHidden();
    }

    // ------------------------------------------------------------ 浏览

    @Override
    public List<Category> categories() {
        if (!ready()) return List.of();
        List<Category> out = new ArrayList<>();
        try {
            for (Object raw : categories) {
                if (!(raw instanceof CategoryRegistry.CategoryConfiguration<?> cfg)) continue;
                DisplayCategory<?> category = cfg.getCategory();
                if (category == null) continue;
                CategoryIdentifier<?> id = category.getCategoryIdentifier();
                if (id == null || id.getIdentifier() == null) continue;

                String uid = id.getIdentifier().toString();
                out.add(new Category(uid, category.getTitle(), firstWorkstationItem(cfg), displayCount(id)));
            }
        } catch (Throwable t) {
            FolioCleaner.LOGGER.debug("[Folio Cleaner] REI 分类枚举失败：{}", String.valueOf(t));
        }
        out.sort((a, b) -> Long.compare(b.count(), a.count()));
        return out;
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private long displayCount(CategoryIdentifier<?> id) {
        try {
            List<Display> list = displays.get((CategoryIdentifier) id);
            return list == null ? 0 : list.size();
        } catch (Throwable t) {
            return 0;
        }
    }

    private ItemStack firstWorkstationItem(CategoryRegistry.CategoryConfiguration<?> cfg) {
        try {
            List<EntryIngredient> stations = cfg.getWorkstations();
            if (stations == null) return ItemStack.EMPTY;
            for (EntryIngredient ingredient : stations) {
                if (ingredient == null) continue;
                for (EntryStack<?> stack : ingredient) {
                    ItemStack item = itemOf(stack);
                    if (!item.isEmpty()) return item;
                }
            }
        } catch (Throwable ignored) {
        }
        return ItemStack.EMPTY;
    }

    @Override
    public List<Catalyst> catalysts() {
        if (!ready()) return List.of();
        Map<String, LinkedHashSet<String>> byItem = new HashMap<>();
        Map<String, ItemStack> stacks = new HashMap<>();
        try {
            for (Object raw : categories) {
                if (!(raw instanceof CategoryRegistry.CategoryConfiguration<?> cfg)) continue;
                DisplayCategory<?> category = cfg.getCategory();
                if (category == null || category.getCategoryIdentifier() == null
                        || category.getCategoryIdentifier().getIdentifier() == null) {
                    continue;
                }
                String typeUid = category.getCategoryIdentifier().getIdentifier().toString();

                List<EntryIngredient> stations = cfg.getWorkstations();
                if (stations == null) continue;
                for (EntryIngredient ingredient : stations) {
                    if (ingredient == null) continue;
                    for (EntryStack<?> stack : ingredient) {
                        ItemStack item = itemOf(stack);
                        if (item.isEmpty()) continue;
                        ResourceLocation key = BuiltInRegistries.ITEM.getKey(item.getItem());
                        if (key == null) continue;
                        String id = key.toString();
                        byItem.computeIfAbsent(id, k -> new LinkedHashSet<>()).add(typeUid);
                        stacks.putIfAbsent(id, item);
                    }
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

    private boolean renderGlueOk;
    /** 失败后 5 秒才重试一次（REI / architectury 可能比我们晚初始化，不能一次失败就永久放弃） */
    private long lastGlueTryMs;

    private boolean prepareRenderGlue() {
        if (renderGlueOk) return true;
        long now = System.currentTimeMillis();
        if (now - lastGlueTryMs < 5000L) return false;
        lastGlueTryMs = now;
        try {
            rectangleClass = Class.forName(RECTANGLE);
            for (Constructor<?> ctor : rectangleClass.getConstructors()) {
                Class<?>[] p = ctor.getParameterTypes();
                if (p.length == 4 && p[0] == int.class) {
                    rectangleCtor = ctor;
                    break;
                }
            }
            if (rectangleCtor == null) {
                for (Constructor<?> ctor : rectangleClass.getConstructors()) {
                    Class<?>[] p = ctor.getParameterTypes();
                    if (p.length == 4 && p[0] == double.class) {
                        rectangleCtor = ctor;
                        break;
                    }
                }
            }
            if (rectangleCtor == null) return false;

            setupDisplay = Class.forName(DISPLAY_VIEW).getMethod("setupDisplay",
                    Display.class, rectangleClass);
            widgetRender = Widget.class.getMethod("render",
                    GuiGraphics.class, rectangleClass, int.class, int.class, float.class);
            renderGlueOk = true;
            return true;
        } catch (Throwable t) {
            FolioCleaner.LOGGER.debug("[Folio Cleaner] REI 预览不可用（Rectangle 反射失败）：{}", String.valueOf(t));
            return false;
        }
    }

    private Object newRectangle(int x, int y, int w, int h) throws Exception {
        Class<?>[] p = rectangleCtor.getParameterTypes();
        if (p[0] == int.class) return rectangleCtor.newInstance(x, y, w, h);
        return rectangleCtor.newInstance((double) x, (double) y, (double) w, (double) h);
    }

    /** 配方 id → 预览（GUI 悬浮时每帧都会问，findDisplay 是全表扫描，必须缓存） */
    private final Map<String, Preview> previewCache = new java.util.HashMap<>();
    private long previewCacheSize = -1;

    @Override
    public Preview preview(String recipeId) {
        if (!ready() || !prepareRenderGlue()) return null;

        long size;
        try {
            size = displays.displaySize();
        } catch (Throwable t) {
            size = -1;
        }
        if (size != previewCacheSize) {
            previewCache.clear();
            previewCacheSize = size;
        }
        Preview cached = previewCache.get(recipeId);
        if (cached != null) return cached;

        try {
            ResourceLocation wanted = ResourceLocation.tryParse(recipeId);
            if (wanted == null) return null;

            Display display = findDisplay(wanted);
            if (display == null) return null;

            DisplayCategory<?> category = categoryOf(display);
            if (category == null) return null;

            int w = displayWidth(category, display);
            int h = category.getDisplayHeight();
            if (w <= 0 || h <= 0) return null;

            Object rect = newRectangle(0, 0, w, h);
            Object raw = setupDisplay.invoke(category, display, rect);
            if (!(raw instanceof List<?> list)) return null;

            List<Widget> widgets = new ArrayList<>(list.size());
            for (Object o : list) {
                if (o instanceof Widget widget) widgets.add(widget);
            }
            ReiPreview preview = new ReiPreview(widgets, rect, w, h);
            if (previewCache.size() > 32) previewCache.clear();
            previewCache.put(recipeId, preview);
            return preview;
        } catch (Throwable t) {
            return null;
        }
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static int displayWidth(DisplayCategory<?> category, Display display) {
        return ((DisplayCategory) category).getDisplayWidth(display);
    }

    private Display findDisplay(ResourceLocation id) {
        try {
            for (List<Display> list : displays.getAll().values()) {
                if (list == null) continue;
                for (Display d : list) {
                    var loc = d.getDisplayLocation();
                    if (loc != null && loc.isPresent() && id.equals(loc.get())) return d;
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private DisplayCategory<?> categoryOf(Display display) {
        try {
            CategoryIdentifier<?> id = display.getCategoryIdentifier();
            if (id == null) return null;
            for (Object raw : categories) {
                if (!(raw instanceof CategoryRegistry.CategoryConfiguration<?> cfg)) continue;
                DisplayCategory<?> cat = cfg.getCategory();
                if (cat != null && id.equals(cat.getCategoryIdentifier())) return cat;
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    /** 逐 widget 画，效果与 REI 配方界面里的那张卡一致 */
    private final class ReiPreview implements Preview {
        private final List<Widget> widgets;
        private final Object rect;
        private final int width;
        private final int height;

        ReiPreview(List<Widget> widgets, Object rect, int width, int height) {
            this.widgets = widgets;
            this.rect = rect;
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
                    if (widget == null) continue;
                    widgetRender.invoke(widget, gg, rect, mouseX - x, mouseY - y, 0f);
                }
            } catch (Throwable ignored) {
            } finally {
                gg.pose().popPose();
            }
        }
    }

    // ------------------------------------------------------------ 规则引擎

    /**
     * REI 在资源重载 / 换世界时会重建谓词列表，我们那条就没了。
     * 每个 tick 便宜地确认一次（List.contains 就几个元素），丢了就补回去。
     */
    private void ensurePredicates() {
        try {
            if (predicate == null) {
                installPredicate();
                return;
            }
            if (!displays.getVisibilityPredicates().contains(predicate)) {
                displays.registerVisibilityPredicate(
                        (me.shedaniel.rei.api.client.registry.display.visibility.DisplayVisibilityPredicate)
                                predicate);
                FolioCleaner.LOGGER.info("[Folio Cleaner] REI 谓词被重载清掉了，已重新挂上");
            }
            if (categoryPredicate != null
                    && !categories.getVisibilityPredicates().contains(categoryPredicate)) {
                categories.registerVisibilityPredicate(
                        (me.shedaniel.rei.api.client.registry.category.visibility.CategoryVisibilityPredicate)
                                categoryPredicate);
            }
        } catch (Throwable ignored) {
        }
    }

    private Object categoryPredicate;

    /**
     * 挂上 REI 的可见性谓词。用 {@link Proxy} 实现，因为接口返回值是
     * {@code dev.architectury.event.EventResult}（architectury 的类，编译期不可见）。
     */
    private void installPredicate() {
        if (predicate != null) return;
        try {
            Class<?> predicateType = Class.forName(VISIBILITY);
            Class<?> eventResult = Class.forName(EVENT_RESULT);
            passResult = eventResult.getMethod("pass").invoke(null);
            hideResult = eventResult.getMethod("interruptFalse").invoke(null);

            Object proxy = Proxy.newProxyInstance(predicateType.getClassLoader(),
                    new Class<?>[]{predicateType}, this);
            displays.registerVisibilityPredicate(
                    (me.shedaniel.rei.api.client.registry.display.visibility.DisplayVisibilityPredicate) proxy);
            predicate = proxy;
            installCategoryPredicate(predicateType);
            ViewerStatus.reiFilterInstalled = true;
            FolioCleaner.LOGGER.info("[Folio Cleaner] REI 后端已接管：可见性谓词已挂载（物品 / 分类 / 配方级均实时）");
        } catch (Throwable t) {
            predicate = null;
            ViewerStatus.reiFailed = true;
            FolioCleaner.LOGGER.warn("[Folio Cleaner] REI 谓词挂载失败：{}", String.valueOf(t));
        }
    }

    /** 分类可见性谓词（同一个 handler，靠方法名分发） */
    private void installCategoryPredicate(Class<?> displayPredicateType) {
        try {
            Class<?> categoryType = Class.forName(
                    "me.shedaniel.rei.api.client.registry.category.visibility.CategoryVisibilityPredicate");
            Object categoryProxy = Proxy.newProxyInstance(categoryType.getClassLoader(),
                    new Class<?>[]{categoryType}, this);
            categories.registerVisibilityPredicate(
                    (me.shedaniel.rei.api.client.registry.category.visibility.CategoryVisibilityPredicate)
                            categoryProxy);
            categoryPredicate = categoryProxy;
        } catch (Throwable t) {
            FolioCleaner.LOGGER.debug("[Folio Cleaner] REI 分类可见性谓词没挂上：{}", String.valueOf(t));
        }
    }

    @Override
    public Object invoke(Object proxy, Method method, Object[] args) {
        String name = method.getName();
        switch (name) {
            case "handleDisplay":
                return hides(args) ? hideResult : passResult;
            case "handleCategory":
                return hidesCategory(args) ? hideResult : passResult;
            case "getPriority":
                return 100.0d;
            case "compareTo":
                return 0;
            case "hashCode":
                return System.identityHashCode(proxy);
            case "equals":
                return args != null && args.length == 1 && proxy == args[0];
            case "toString":
                return "FolioCleanerReiBackend";
            default:
                return null;
        }
    }

    /** 分类级规则（{@code category}）：整类直接不可见，连分类按钮都不出现 */
    private boolean hidesCategory(Object[] args) {
        if (args == null || args.length < 1) return false;
        try {
            if (!(args[0] instanceof DisplayCategory<?> category)) return false;
            if (category.getCategoryIdentifier() == null
                    || category.getCategoryIdentifier().getIdentifier() == null) {
                return false;
            }
            return RuleMatcher.hiddenCategories()
                    .contains(category.getCategoryIdentifier().getIdentifier().toString());
        } catch (Throwable t) {
            return false;
        }
    }

    private boolean hides(Object[] args) {
        if (args == null || args.length < 2) return false;
        if (!(args[1] instanceof Display display)) return false;
        try {
            return RuleMatcher.hides(facts(display));
        } catch (Throwable t) {
            return false;
        }
    }

    /** 把 Display 翻译成规则求值器要的事实（带缓存：每帧都会被问一次） */
    private RuleMatcher.Facts facts(Display display) {
        int revision = Rules.revision();
        if (factsRevision != revision) {
            factsCache.clear();
            factsRevision = revision;
        }
        RuleMatcher.Facts cached = factsCache.get(display);
        if (cached != null) return cached;

        ResourceLocation location = null;
        try {
            var loc = display.getDisplayLocation();
            if (loc != null && loc.isPresent()) location = loc.get();
        } catch (Throwable ignored) {
        }

        String uid = null;
        try {
            CategoryIdentifier<?> id = display.getCategoryIdentifier();
            if (id != null && id.getIdentifier() != null) uid = id.getIdentifier().toString();
        } catch (Throwable ignored) {
        }

        Set<String> typeIds = new LinkedHashSet<>();
        if (uid != null) typeIds.add(uid);
        if (location != null) {
            try {
                var level = Minecraft.getInstance().level;
                if (level != null) {
                    var recipe = level.getRecipeManager().byKey(location).orElse(null);
                    if (recipe != null) {
                        ResourceLocation typeId = BuiltInRegistries.RECIPE_TYPE.getKey(recipe.getType());
                        if (typeId != null) typeIds.add(typeId.toString());
                    }
                }
            } catch (Throwable ignored) {
            }
        }

        List<Set<String>> inputs = new ArrayList<>();
        try {
            List<EntryIngredient> in = display.getInputEntries();
            if (in != null) {
                for (EntryIngredient ingredient : in) inputs.add(itemIds(ingredient));
            }
        } catch (Throwable ignored) {
        }

        Set<String> outputs = new HashSet<>();
        try {
            List<EntryIngredient> out = display.getOutputEntries();
            if (out != null) {
                for (EntryIngredient ingredient : out) outputs.addAll(itemIds(ingredient));
            }
        } catch (Throwable ignored) {
        }

        String mod = location != null ? location.getNamespace()
                : (uid != null && uid.contains(":") ? uid.substring(0, uid.indexOf(':')) : "");

        RuleMatcher.Facts facts = new RuleMatcher.Facts(
                location == null ? null : location.toString(), uid, mod, typeIds, inputs, outputs);

        if (factsCache.size() > 20000) factsCache.clear();
        factsCache.put(display, facts);
        return facts;
    }

    private static Set<String> itemIds(EntryIngredient ingredient) {
        Set<String> out = new LinkedHashSet<>();
        if (ingredient == null) return out;
        for (EntryStack<?> stack : ingredient) {
            ItemStack item = itemOf(stack);
            if (item.isEmpty()) continue;
            ResourceLocation id = BuiltInRegistries.ITEM.getKey(item.getItem());
            if (id != null) out.add(id.toString());
        }
        return out;
    }

    private static ItemStack itemOf(EntryStack<?> stack) {
        try {
            if (stack == null || stack.isEmpty()) return ItemStack.EMPTY;
            Object value = stack.getValue();
            if (value instanceof ItemStack item && !item.isEmpty()) return item;
        } catch (Throwable ignored) {
        }
        return ItemStack.EMPTY;
    }

    // ------------------------------------------------------------ 全部配方（含模组自建）

    private long recipeCacheSize = -1;
    private List<RecipeRef> recipeCache;

    @Override
    public List<RecipeRef> allRecipes() {
        if (!ready()) return List.of();
        long size;
        try {
            size = displays.displaySize();
        } catch (Throwable t) {
            return List.of();
        }
        if (recipeCache != null && size == recipeCacheSize) return recipeCache;

        List<RecipeRef> out = new ArrayList<>();
        try {
            for (List<Display> list : displays.getAll().values()) {
                if (list == null) continue;
                for (Display display : list) {
                    RecipeRef ref = refOf(display);
                    if (ref != null) out.add(ref);
                }
            }
        } catch (Throwable t) {
            FolioCleaner.LOGGER.debug("[Folio Cleaner] REI 配方枚举失败：{}", String.valueOf(t));
        }
        recipeCache = out;
        recipeCacheSize = size;
        return out;
    }

    /** 不经过 facts 缓存（枚举上万条时缓存会被反复撑爆） */
    private RecipeRef refOf(Display display) {
        try {
            ResourceLocation location = null;
            var loc = display.getDisplayLocation();
            if (loc != null && loc.isPresent()) location = loc.get();
            // 没有 id 的 display 给用户也没法建规则，跳过
            if (location == null) return null;

            String uid = null;
            try {
                CategoryIdentifier<?> id = display.getCategoryIdentifier();
                if (id != null && id.getIdentifier() != null) uid = id.getIdentifier().toString();
            } catch (Throwable ignored) {
            }

            List<Set<String>> inputs = new ArrayList<>();
            try {
                List<EntryIngredient> in = display.getInputEntries();
                if (in != null) for (EntryIngredient ingredient : in) inputs.add(itemIds(ingredient));
            } catch (Throwable ignored) {
            }

            Set<String> outputIds = new LinkedHashSet<>();
            ItemStack icon = ItemStack.EMPTY;
            try {
                List<EntryIngredient> out = display.getOutputEntries();
                if (out != null) {
                    for (EntryIngredient ingredient : out) {
                        outputIds.addAll(itemIds(ingredient));
                        if (icon.isEmpty()) {
                            for (EntryStack<?> stack : ingredient) {
                                ItemStack item = itemOf(stack);
                                if (!item.isEmpty()) {
                                    icon = item;
                                    break;
                                }
                            }
                        }
                    }
                }
            } catch (Throwable ignored) {
            }

            return new RecipeRef(location.toString(), uid, location.getNamespace(),
                    icon, inputs, outputIds);
        } catch (Throwable t) {
            return null;
        }
    }

    // ------------------------------------------------------------ 物品级隐藏

    /**
     * REI 在资源重载 / 重新过滤时可能把我们摘掉的条目加回来。
     * 这里用一个便宜的比较兜住：条数比「我们摘完之后」多了，就重推一次。
     */
    private void ensureItemsStillHidden() {
        try {
            if (expectedEntrySize < 0) return;
            if (RuleMatcher.hiddenItems().isEmpty()) return;
            if (entries == null) return;
            if (entries.size() <= expectedEntrySize) return;
            appliedRevision = Integer.MIN_VALUE;
            applyItemHidingIfNeeded();
        } catch (Throwable ignored) {
        }
    }

    private int expectedEntrySize = -1;

    private void applyItemHidingIfNeeded() {
        int revision = Rules.revision();
        if (revision == appliedRevision) return;
        appliedRevision = revision;

        Set<String> hidden = RuleMatcher.hiddenItems();
        try {
            // 先把上次摘掉的放回去（规则可能已经删了）
            if (!removedEntries.isEmpty()) {
                List<EntryStack<?>> back = new ArrayList<>(removedEntries);
                removedEntries.clear();
                entries.addEntries(back);
            }
            if (hidden.isEmpty()) {
                expectedEntrySize = -1;
                return;
            }

            entries.removeEntryIf((EntryStack<?> stack) -> {
                ItemStack item = itemOf(stack);
                if (item.isEmpty()) return false;
                ResourceLocation id = BuiltInRegistries.ITEM.getKey(item.getItem());
                if (id == null || !hidden.contains(id.toString())) return false;
                removedEntries.add(stack);
                return true;
            });
            expectedEntrySize = entries.size();
        } catch (Throwable t) {
            FolioCleaner.LOGGER.debug("[Folio Cleaner] REI 物品隐藏失败：{}", String.valueOf(t));
        }
    }

    // ------------------------------------------------------------ 预演 / 状态

    private int cachedRevision = Integer.MIN_VALUE;
    private long cachedDisplaySize = -1;
    private PlanPreview cachedPlan;

    @Override
    public PlanPreview planPreview() {
        if (!ready()) return null;
        int revision = Rules.revision();
        long size;
        try {
            size = displays.displaySize();
        } catch (Throwable t) {
            return null;
        }
        if (cachedPlan != null && revision == cachedRevision && size == cachedDisplaySize) return cachedPlan;

        int recipes = 0;
        Map<String, Integer> byMod = new HashMap<>();
        long deadline = System.currentTimeMillis() + 2000L;
        boolean truncated = false;

        try {
            outer:
            for (Map.Entry<CategoryIdentifier<?>, List<Display>> e : displays.getAll().entrySet()) {
                List<Display> list = e.getValue();
                if (list == null) continue;
                for (Display display : list) {
                    if (System.currentTimeMillis() > deadline) {
                        truncated = true;
                        break outer;
                    }
                    RuleMatcher.Facts facts = facts(display);
                    if (!RuleMatcher.hides(facts)) continue;
                    recipes++;
                    if (facts.modId() != null) byMod.merge(facts.modId(), 1, Integer::sum);
                }
            }
        } catch (Throwable ignored) {
        }

        int items = RuleMatcher.hiddenItems().size();
        int cats = RuleMatcher.hiddenCategories().size();
        if (truncated) {
            FolioCleaner.LOGGER.info("[Folio Cleaner] REI 预览扫描超过 2s 已截断（已统计 {} 条）", recipes);
        }
        cachedPlan = new PlanPreview(recipes, items, cats, byMod, false);
        cachedRevision = revision;
        cachedDisplaySize = size;
        return cachedPlan;
    }

    @Override
    public boolean hasPendingChanges() {
        // REI 的可见性是每次渲染现算 + 物品列表在 tick 里同步 → 永远是最新的
        return false;
    }

    @Override
    public List<String> diagnose() {
        List<String> out = new ArrayList<>();
        try {
            out.add("§7REI 就绪 §f" + ready());
            out.add("§7可见性谓词 §f" + (predicate != null ? "已挂" : "未挂")
                    + " §7分类谓词 §f" + (ViewerStatus.reiFilterInstalled ? "已挂" : "未挂"));
            out.add("§7配方总数 §f" + (displays == null ? 0 : displays.displaySize())
                    + " §7事实缓存 §f" + factsCache.size());
            out.add("§7物品隐藏 §f" + (expectedEntrySize < 0
                    ? "未生效" : "已摘 " + removedEntries.size() + " 条"));
            out.add("§7隐藏物品规则 §f" + RuleMatcher.hiddenItems().size()
                    + " §7分类规则 §f" + RuleMatcher.hiddenCategories().size());
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

    @Override
    public void requestApplyDelayed(int ticks) {
        // 实时：不需要延迟重算
    }

    @Override
    public void requestApply() {
        applyItemHidingIfNeeded();
    }

    @Override
    public void applyNow() {
        appliedRevision = Integer.MIN_VALUE;
        applyItemHidingIfNeeded();
    }

    /** 换世界 / 配方重载：display 换了对象，缓存全作废 */
    @Override
    public void invalidate() {
        factsCache.clear();
        previewCache.clear();
        previewCacheSize = -1;
        recipeCache = null;
        recipeCacheSize = -1;
        cachedPlan = null;
        cachedRevision = Integer.MIN_VALUE;
        appliedRevision = Integer.MIN_VALUE;
        removedEntries.clear();
        expectedEntrySize = -1;
    }
}
