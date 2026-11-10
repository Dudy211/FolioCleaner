package com.yuneko.foliocleaner;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 「统一」的**运行时实现**（方案乙 / 方案丙）—— 不写数据包、不需要 /reload。
 *
 * <h2>两种模式</h2>
 * <table>
 *   <tr><th>模式</th><th>做法</th><th>代价</th></tr>
 *   <tr>
 *     <td>{@code rewrite}（改配方）</td>
 *     <td>反射替换 {@link Ingredient} 里**缓存的物品数组**：原物品 ∪ 同组成员。
 *         因为 {@code Ingredient#test} 和 {@code #getItems} 读的都是这个缓存数组，
 *         所以「匹配」和「显示」同时变宽，等价于"把配方里的原料换成了宽集合"。</td>
 *     <td>规则变化时**一次性**；之后每次匹配就是原版的数组循环，**零额外开销**。</td>
 *   </tr>
 *   <tr>
 *     <td>{@code match}（运行时）</td>
 *     <td>不动配方对象，只为「含同组成员」的原料预计算一个可接受物品集合，
 *         由 {@code IngredientMixin} 在 {@code test()} 开头查表。</td>
 *     <td>每次匹配多一次 {@link IdentityHashMap} 查表；显示保持原样（更保守）。</td>
 *   </tr>
 * </table>
 *
 * <h2>为什么 rewrite 是最省的</h2>
 * {@code Ingredient#test} 的实现是「遍历 {@code getItems()} 数组比对物品」，
 * 而 {@code getItems()} 返回的是懒加载缓存字段。把那个字段换成更宽的数组之后，
 * 匹配成本**和原版完全一样**（数组变长一点点），没有任何额外分支。
 *
 * <p>⚠️ 只在**单人 / 集成服务器**生效：配方匹配发生在服务端，专用服务器需要服主那边也执行
 * （目前走「数据包」模式，或用 {@code /folioserver} 生成）。
 */
public final class UnifyRuntime {

    /** B2 的总开关：mixin 第一行只读这个布尔，关着时零成本 */
    private static volatile boolean matchActive;
    /** B1 是否已经把配方改宽（用于撤销） */
    private static boolean rewriteApplied;

    /** 物品 → 组 id */
    private static Map<Item, String> itemToGroup = Map.of();
    /** 组 id → 成员物品 */
    private static Map<String, List<Item>> groupItems = Map.of();
    /** 被我们改过的 Ingredient → 原始物品数组（撤销用，identity） */
    private static final Map<Ingredient, ItemStack[]> ORIGINAL = new IdentityHashMap<>();
    /** B2：原料 → 它能额外接受的物品集合（identity） */
    private static final Map<Ingredient, Set<Item>> EXTRA = new IdentityHashMap<>();

    private static Field itemsField;
    private static boolean fieldBroken;
    private static int appliedRevision = Integer.MIN_VALUE;
    private static String appliedMode = "";

    /**
     * 服务端下发的重复组（{@link GroupSync} 文本）；{@code null} = 没收到（单人 / 老服务端）。
     *
     * <p><b>连服务器时以它为准</b>：{@code rewrite} / {@code match} 改的是「原料能接受哪些物品」，
     * 双端各扫一份组就可能不一致（客户端的 {@code dup-overrides.json} 在专用服务器上不存在），
     * 那会变成「界面说能合、服务端说不能」。所以有服务端那份就用服务端那份。
     */
    private static String serverGroups;

    private UnifyRuntime() {
    }

    /** mixin 用：这条原料能不能额外接受这个物品 */
    public static boolean accepts(Object ingredient, ItemStack stack) {
        if (!matchActive || stack == null || stack.isEmpty()) return false;
        Set<Item> extra = EXTRA.get(ingredient);
        return extra != null && extra.contains(stack.getItem());
    }

    public static boolean matchActive() {
        return matchActive;
    }

    /**
     * 收到服务端下发的重复组（登录 / 广播时）。空文本 = 服务端没给，客户端自行扫描。
     *
     * <p>换了数据源必须让 {@link #applyIfNeeded} 重跑一次，所以把上次的 revision 清掉。
     */
    public static void setServerGroups(String text) {
        String next = (text == null || text.isBlank()) ? null : text;
        if (java.util.Objects.equals(next, serverGroups)) return;
        serverGroups = next;
        appliedRevision = Integer.MIN_VALUE;
        if (next != null) {
            FolioCleaner.LOGGER.info("[Folio Cleaner] 已收到服务端重复组：{} 组（{}），统一以服务端为准",
                    GroupSync.groupCount(next), GroupSync.fingerprint(next));
        }
        // 组数据可能在「登录后的那次 apply」之后才到（两个包谁先谁后不确定），
        // 所以这里主动再排一次，别让第一次同步用的是本地扫出来的组。
        try {
            com.yuneko.foliocleaner.Cleaner.requestApplyDelayed(2);
        } catch (Throwable ignored) {
        }
    }

    /** 当前用的是不是服务端那份组数据（界面/指令展示用） */
    public static boolean usingServerGroups() {
        return serverGroups != null;
    }

    /** 当前生效的组数 / 服务端下发的组数 / 被改动的原料条数（属性台展示用） */
    public static int groupCount() {
        return groupItems.size();
    }

    public static int serverGroupCount() {
        return serverGroups == null ? 0 : GroupSync.groupCount(serverGroups);
    }

    public static int affectedCount() {
        return rewriteApplied ? ORIGINAL.size() : EXTRA.size();
    }

    /** 规则变了就重建一次（幂等；同 revision 直接返回） */
    public static void applyIfNeeded(String mode) {
        int revision = Rules.revision() * 31 + (ClientSettings.dupCrossTag ? 1 : 0);
        if (revision == appliedRevision && mode.equals(appliedMode)) return;
        appliedRevision = revision;
        appliedMode = mode;
        try {
            buildGroups();
            restore();
            if ("rewrite".equals(mode)) {
                rewriteApplied = widenAllRecipes();
                matchActive = false;
            } else if ("match".equals(mode)) {
                buildExtraSets();
                matchActive = true;
            } else {
                matchActive = false;
            }
            FolioCleaner.LOGGER.info("[Folio Cleaner] 统一（{}）：{} 组 / 改了 {} 条原料",
                    mode, groupItems.size(), rewriteApplied ? ORIGINAL.size() : EXTRA.size());
        } catch (Throwable t) {
            matchActive = false;
            rewriteApplied = false;
            FolioCleaner.LOGGER.warn("[Folio Cleaner] 统一（{}）失败，已回退", mode, t);
        }
    }

    /**
     * **专用服务器**上应用（B1/B2）。
     *
     * <p>以前这两个模式只在单人可用，因为分组要用 {@code Minecraft.getInstance().level}。
     * 现在改成把服务端世界直接递进来 —— 配方匹配本来就发生在服务端，
     * 在服务端改宽之后：B1 会连"发给客户端的配方"一起变（{@code Ingredient#values} 没动，
     * 但匹配走得是缓存数组，所以客户端仍按原样显示）→ 这也是为什么要求**双端都装**。
     */
    public static void applyOnServer(net.minecraft.server.MinecraftServer server, String mode) {
        if (server == null) return;
        if (!"rewrite".equals(mode) && !"match".equals(mode)) {
            // 切到「数据包」/关掉：服务端这边也不能留着旧的加宽
            deactivate();
            return;
        }
        try {
            buildGroupsFrom(server.overworld());
            restore();
            int changed;
            if ("rewrite".equals(mode)) {
                changed = widenRecipes(server.overworld().getRecipeManager());
                rewriteApplied = changed > 0;
                matchActive = false;
            } else {
                collectExtras(server.overworld().getRecipeManager());
                matchActive = true;
                changed = EXTRA.size();
            }
            FolioCleaner.LOGGER.info("[Folio Cleaner] 服务端统一（{}）：{} 组 / 影响 {} 条原料",
                    mode, groupItems.size(), changed);
        } catch (Throwable t) {
            matchActive = false;
            rewriteApplied = false;
            FolioCleaner.LOGGER.warn("[Folio Cleaner] 服务端统一（{}）失败", mode, t);
        }
    }

    /** 撤销所有改动（关掉统一 / 规则清空时） */
    public static void restore() {
        matchActive = false;
        if (!ORIGINAL.isEmpty()) {
            try {
                for (Map.Entry<Ingredient, ItemStack[]> e : ORIGINAL.entrySet()) {
                    setItems(e.getKey(), e.getValue());
                }
            } catch (Throwable ignored) {
            }
        }
        ORIGINAL.clear();
        EXTRA.clear();
        rewriteApplied = false;
    }

    /**
     * 彻底关掉运行时统一（切到「数据包」/ 关掉功能时调）。
     *
     * <p>只调 {@link #restore()} 不够：那个 memo（{@code appliedRevision} / {@code appliedMode}）还留着，
     * 之后切回 {@code rewrite} 且规则没变时会撞上「同 revision → 直接 return」，**配方再也不会被加宽**。
     */
    public static void deactivate() {
        restore();
        appliedRevision = Integer.MIN_VALUE;
        appliedMode = "";
    }

    // ---------------------------------------------------------------- 组

    private static void buildGroups() {
        buildGroupsFrom(null);
    }

    private static void buildGroupsFrom(net.minecraft.world.level.Level level) {
        // 连服务器时优先用服务端那份（双端一致；见 setServerGroups 的注释）
        if (serverGroups != null) {
            Map<String, List<Item>> byGroup = GroupSync.groups(serverGroups);
            Map<Item, String> byItem = new java.util.HashMap<>();
            for (Map.Entry<String, List<Item>> e : byGroup.entrySet()) {
                for (Item item : e.getValue()) byItem.put(item, e.getKey());
            }
            itemToGroup = byItem;
            groupItems = byGroup;
            return;
        }
        Map<Item, String> byItem = new java.util.HashMap<>();
        Map<String, List<Item>> byGroup = new java.util.LinkedHashMap<>();
        try {
            List<DuplicateGroups.Group> groups = level == null
                    ? com.yuneko.foliocleaner.client.DupScan.groups()
                    : DuplicateGroups.scan(level, com.yuneko.foliocleaner.DupOverrides.load());
            for (DuplicateGroups.Group g : groups) {
                if (g == null || g.key == null || g.items == null || g.items.size() < 2) continue;
                List<Item> members = new ArrayList<>(g.items);
                if (members.size() < 2) continue;
                byGroup.put(g.key, members);
                for (Item item : members) byItem.put(item, g.key);
            }
        } catch (Throwable t) {
            FolioCleaner.LOGGER.debug("[Folio Cleaner] 取重复组失败（运行时统一按空处理）", t);
        }
        itemToGroup = byItem;
        groupItems = byGroup;
    }

    // ---------------------------------------------------------------- B1 改配方

    private static boolean widenAllRecipes() {
        var mc = net.minecraft.client.Minecraft.getInstance();
        int changed = 0;
        // 客户端那份（JEI/REI 显示 + 单人时的配方书）
        if (mc.level != null) {
            changed += widenRecipes(mc.level.getRecipeManager());
        }
        // 单人时服务端那份才是真正判定合成的地方
        var server = mc.getSingleplayerServer();
        if (server != null) {
            try {
                changed += widenRecipes(server.overworld().getRecipeManager());
            } catch (Throwable ignored) {
            }
        }
        return changed > 0;
    }

    private static int widenRecipes(net.minecraft.world.item.crafting.RecipeManager manager) {
        int changed = 0;
        try {
            for (Recipe<?> recipe : manager.getRecipes()) {
                for (Ingredient ingredient : recipe.getIngredients()) {
                    if (widen(ingredient)) changed++;
                }
            }
        } catch (Throwable t) {
            FolioCleaner.LOGGER.debug("[Folio Cleaner] 遍历配方失败", t);
        }
        return changed;
    }

    /** 把一条原料的物品集合换成「原物品 ∪ 同组成员」；返回是否真的改了 */
    private static boolean widen(Ingredient ingredient) {
        if (ingredient == null || ingredient == Ingredient.EMPTY) return false;
        ItemStack[] base = ORIGINAL.get(ingredient);
        if (base == null) {
            try {
                base = ingredient.getItems();
            } catch (Throwable t) {
                return false;
            }
        }
        if (base == null || base.length == 0) return false;

        LinkedHashSet<ItemStack> out = new LinkedHashSet<>();
        for (ItemStack stack : base) {
            if (stack != null && !stack.isEmpty()) out.add(stack);
        }
        boolean hit = false;
        for (ItemStack stack : base) {
            if (stack == null || stack.isEmpty()) continue;
            String group = itemToGroup.get(stack.getItem());
            if (group == null) continue;
            hit = true;
            for (Item member : groupItems.getOrDefault(group, List.of())) {
                out.add(new ItemStack(member));
            }
        }
        if (!hit) return false;

        ItemStack[] widened = out.toArray(new ItemStack[0]);
        if (widened.length <= base.length) return false;   // 没变宽，不用动
        if (setItems(ingredient, widened)) {
            ORIGINAL.putIfAbsent(ingredient, base);
            return true;
        }
        return false;
    }

    // ---------------------------------------------------------------- B2 运行时

    private static void buildExtraSets() {
        var mc = net.minecraft.client.Minecraft.getInstance();
        if (mc.level != null) collectExtras(mc.level.getRecipeManager());
        var server = mc.getSingleplayerServer();
        if (server != null) {
            try {
                collectExtras(server.overworld().getRecipeManager());
            } catch (Throwable ignored) {
            }
        }
    }

    private static void collectExtras(net.minecraft.world.item.crafting.RecipeManager manager) {
        try {
            for (Recipe<?> recipe : manager.getRecipes()) {
                for (Ingredient ingredient : recipe.getIngredients()) {
                    if (ingredient == null || ingredient == Ingredient.EMPTY) continue;
                    if (EXTRA.containsKey(ingredient)) continue;
                    ItemStack[] base;
                    try {
                        base = ingredient.getItems();
                    } catch (Throwable t) {
                        continue;
                    }
                    Set<Item> extra = null;
                    for (ItemStack stack : base) {
                        if (stack == null || stack.isEmpty()) continue;
                        String group = itemToGroup.get(stack.getItem());
                        if (group == null) continue;
                        if (extra == null) extra = new java.util.HashSet<>();
                        extra.addAll(groupItems.getOrDefault(group, List.of()));
                    }
                    if (extra != null && !extra.isEmpty()) EXTRA.put(ingredient, extra);
                }
            }
        } catch (Throwable ignored) {
        }
    }

    // ---------------------------------------------------------------- 反射

    /**
     * {@code Ingredient} 里那个懒加载的物品数组缓存。
     * 开发期（official 映射）叫 {@code itemStacks}，运行期（SRG）叫 {@code f_43903_}。
     */
    private static Field itemsField() {
        if (itemsField != null || fieldBroken) return itemsField;
        for (String name : new String[]{"itemStacks", "f_43903_"}) {
            try {
                Field f = Ingredient.class.getDeclaredField(name);
                f.setAccessible(true);
                itemsField = f;
                return f;
            } catch (Throwable ignored) {
            }
        }
        fieldBroken = true;
        FolioCleaner.LOGGER.warn("[Folio Cleaner] 找不到 Ingredient 的物品缓存字段，B1 改配方不可用");
        return null;
    }

    private static boolean setItems(Ingredient ingredient, ItemStack[] items) {
        Field f = itemsField();
        if (f == null) return false;
        try {
            f.set(ingredient, items);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }
}
