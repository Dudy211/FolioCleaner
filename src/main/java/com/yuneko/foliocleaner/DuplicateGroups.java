package com.yuneko.foliocleaner;

import net.minecraft.world.level.Level;
import net.minecraft.core.HolderSet;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 重复物品分组。**不依赖客户端类**：世界由调用方传进来（{@link Level}），
 * 所以单人客户端 / 专用服务器都能跑。
 * <ul>
 *   <li>客户端入口：{@code client/DupScan#groups()}</li>
 *   <li>服务端入口：{@code ServerUnify#groups(server, overridesJson)}</li>
 * </ul>
 *
 * <p>思路：靠物品 tag 反查。一个 tag 里装了 ≥2 个物品，基本就是"同一类东西被多个模组各加了一份"
 * （{@code c:ingots/steel} 里三个钢锭）。不靠名字相似度 —— 那个误判率太高。
 *
 * <p>三个概念要分清：
 * <ul>
 *   <li>{@code tags}：这组涉及的 tag（成员集合相同的多个 tag 合并成一组；一个 tag 是另一个
 *       子集的也合并，见任务书规则 4）</li>
 *   <li>{@code crossed}：组里有成员同时出现在别的组（UI 上要同色标出来）</li>
 *   <li>{@code canonical}：这一组的"代表物品"（用户指定优先，否则自动推）</li>
 * </ul>
 *
 * <p>缓存：扫描结果与配方引用次数都按世界缓存，到世界变化或调用 {@link #invalidate()} 为止
 * （ClientEvents 在配方更新 / 进世界时调用）。
 */
public final class DuplicateGroups {

    /** 一组"同一类物品" */
    public static final class Group {
        /** 组标识：优先用 tag 路径，如 "c:ingots/steel" */
        public final String key;
        /** 组内成员（已应用排除项后） */
        public final List<Item> items;
        /** 这组涉及的 tag id 字符串 */
        public final List<String> tags;
        /** 统一/代表物品；null = 用户没指定也没推出来 */
        public final Item canonical;
        /** 组内有成员同时属于别的组 */
        public final boolean crossed;
        /** 未排除的原始成员（= items + excluded，即剔除前的状态） */
        public final List<Item> allMembers;

        Group(String key, List<Item> items, List<String> tags, Item canonical,
              boolean crossed, List<Item> allMembers) {
            this.key = key;
            this.items = items;
            this.tags = tags;
            this.canonical = canonical;
            this.crossed = crossed;
            this.allMembers = allMembers;
        }
    }

    /**
     * 用户覆盖层（存 client-settings.json 的 dupOverrides），用简单类型，不引入 JSON 库。
     * 覆盖结构见字段注释；各列表字段允许为 null（按空处理）。
     */
    public record Override(
            List<String> extraTags,     // 追加到该组的 tag id
            List<String> extraItems,    // 追加到该组的物品 id
            List<String> excluded,      // 从该组剔除的物品 id
            String canonical,           // 指定代表物品 id；null = 不指定
            List<String> removedTags) { // 从该组移除的 tag id（自动分组判错时用）
        public static final Override EMPTY =
                new Override(List.of(), List.of(), List.of(), null, List.of());
    }

    /** 明显不是"同类材料"的 tag，扫到就跳过（原因见 {@link #isNoise}） */
    private static final String[] NOISE_SUFFIX = {"_logs", "_planks", "_wool", "_dye"};
    private static final String[] NOISE_WORDS = {"creative", "dummy", "placeholder"};

    /**
     * 单段路径里这些名字是**分类**（{@code forge:ingots} 装的是所有锭），不是「同一个东西被几个模组各加了一份」。
     * ★真机实测（Forge 1.20.1 纯净服）：不加这层，光 {@code forge:*} 就会扫出 579 个假组，
     * 然后统一功能会把上千条原版配方改写成引用这些大分类 tag —— 等于让铁锭配方接受金锭。
     */
    private static final Set<String> CATEGORY_ROOTS = new HashSet<>(java.util.Arrays.asList(
            "ingots", "ores", "gems", "dusts", "nuggets", "raw_materials", "storage_blocks",
            "tools", "armors", "crops", "seeds", "fences", "fence_gates", "chests",
            "glass", "glass_panes", "sandstone", "stone", "cobblestone", "heads", "dyes",
            "axes", "pickaxes", "shovels", "hoes", "swords", "bows", "arrows", "foods",
            "fishes", "logs", "planks", "wool", "boats", "signs", "doors", "buttons",
            "pressure_plates", "slabs", "stairs", "walls", "music_discs", "flowers", "saplings",
            "leaves", "bricks",
            // 中文命名空间（少量整合包会自己建）
            "锭", "矿", "宝石"));

    /** 一个"重复组"最多几个成员：超过就是分类 tag 被并进来了 */
    private static final int MAX_GROUP_MEMBERS = 8;

    /** 无覆盖扫描结果缓存：缓存到世界变化或 invalidate() */
    private static List<Group> rawCache;
    private static Object rawCacheLevel;
    /** 配方引用次数缓存（canonical 自动推用）：按世界缓存 */
    private static Map<Item, Integer> usageCache;
    private static Object usageCacheLevel;
    /** 每次 invalidate 自增：给「按不变内容缓存」的调用方当键（见 GroupSync） */
    private static int revision;

    private DuplicateGroups() {
    }

    /**
     * 扫描全包，返回所有"≥2 个物品"的组。结果缓存到世界/资源变化为止。
     */
    /**
     * 应用用户覆盖层后重建。
     *
     * @param level     从哪个世界/存档读 tag 和配方（客户端传 {@code Minecraft.getInstance().level}，
     *                  服务端传 {@code server.overworld()}）
     * @param overrides 组 key → 覆盖（可能为空 map，不要 null；传 null 也按空处理）
     *                  结果按 items.size() 降序、再按 key 升序。
     */
    public static List<Group> scan(Level level, Map<String, Override> overrides) {
        try {
            List<Group> raw = raw(level);
            if (overrides == null || overrides.isEmpty()) return raw;
            Map<Item, Integer> usage = recipeUsage(level);
            List<Group> out = new ArrayList<>(raw.size());
            for (Group g : raw) {
                Override ov = overrides.get(g.key);
                if (ov == null) {
                    out.add(g);
                    continue;
                }
                Set<Item> members = new LinkedHashSet<>(g.allMembers);
                for (String id : nullSafe(ov.extraItems())) {
                    Item item = itemById(id);
                    if (item != null) members.add(item);
                }
                List<String> tags = new ArrayList<>(g.tags);
                for (String gone : nullSafe(ov.removedTags())) tags.remove(gone);
                for (String tagId : nullSafe(ov.extraTags())) {
                    if (tagId == null || tagId.isBlank()) continue;
                    if (!tags.contains(tagId)) tags.add(tagId);
                    members.addAll(membersOfTag(tagId));
                }
                List<Item> allMembers = new ArrayList<>(members); // 剔除前的状态（含追加）
                for (String id : nullSafe(ov.excluded())) {
                    Item item = itemById(id);
                    if (item != null) members.remove(item);
                }
                if (members.size() < 2) continue;
                Item canonical;
                if (ov.canonical() != null && !ov.canonical().isBlank()) {
                    canonical = itemById(ov.canonical()); // 解析不到的 id → null，不自动猜
                } else {
                    canonical = guessCanonical(members, usage);
                }
                out.add(new Group(g.key, new ArrayList<>(members), tags, canonical, g.crossed, allMembers));
            }
            out.sort(Comparator.<Group>comparingInt(x -> -x.items.size()).thenComparing(x -> x.key));
            return out;
        } catch (Throwable t) {
            FolioCleaner.LOGGER.warn("[Folio Cleaner] 应用重复物品覆盖层失败", t);
            return List.of();
        }
    }

    /**
     * 让缓存失效（换世界、/reload 后由外部调用）。
     */
    public static void invalidate() {
        try {
            rawCache = null;
            rawCacheLevel = null;
            usageCache = null;
            usageCacheLevel = null;
            revision++;
        } catch (Throwable ignored) {
        }
    }

    /** 扫描结果的「代」号：invalidate 时自增，用来判断缓存是否还有效 */
    public static int revision() {
        return revision;
    }

    // ---------------------------------------------------------------- 内部

    /** 无覆盖的基础结果，按世界缓存 */
    private static List<Group> raw(Level level) {
        try {
            if (rawCache != null && rawCacheLevel == level) return rawCache;
            rawCacheLevel = level;
            rawCache = doScan(level);
            return rawCache;
        } catch (Throwable t) {
            FolioCleaner.LOGGER.warn("[Folio Cleaner] 扫描重复物品失败", t);
            return List.of();
        }
    }

    private static List<Group> doScan(Level level) {
        // 1) 收集候选 tag：成员数 ≥2，噪声 tag 跳过
        Map<String, Set<Item>> byTag = new LinkedHashMap<>();
        for (TagKey<Item> tag : BuiltInRegistries.ITEM.getTagNames().toList()) {
            String id = tag.location().toString();
            if (isNoise(id)) continue;
            Set<Item> members = membersOfTag(tag);
            if (isDuplicateCandidate(id, members)) byTag.put(id, members);
        }
        if (byTag.isEmpty()) return List.of();

        List<String> tagIds = new ArrayList<>(byTag.keySet());
        int n = tagIds.size();

        // 2) 合并：成员集合完全相同，或一个是另一个的子集（两边都 ≥2，规则 2 已保证）→ 同一组。
        //    子集合并是传递的（并查集）：A⊆B、B⊆C ⇒ A、B、C 同组。
        int[] parent = new int[n];
        for (int i = 0; i < n; i++) parent[i] = i;

        // item → 所属 tag 下标列表。要找「谁可能是 tag i 的超集」，
        // 只看包含 i 的「最稀有成员」的那些 tag（超集必须包含 i 的全部成员）。
        Map<Item, List<Integer>> containing = new HashMap<>();
        for (int i = 0; i < n; i++) {
            for (Item item : byTag.get(tagIds.get(i))) {
                containing.computeIfAbsent(item, k -> new ArrayList<>()).add(i);
            }
        }

        for (int i = 0; i < n; i++) {
            Set<Item> setI = byTag.get(tagIds.get(i));
            Item rarest = null;
            int rarestCount = Integer.MAX_VALUE;
            for (Item item : setI) {
                List<Integer> tags = containing.get(item);
                int count = tags == null ? 0 : tags.size();
                if (count < rarestCount) {
                    rarestCount = count;
                    rarest = item;
                    if (count <= 1) break; // 最稀有成员只在本 tag 出现，不可能存在超集
                }
            }
            if (rarestCount <= 1) continue;
            for (int j : containing.get(rarest)) {
                if (j == i) continue;
                Set<Item> setJ = byTag.get(tagIds.get(j));
                if (setJ.size() < setI.size()) continue;
                if (isSubset(setI, setJ)) union(parent, i, j);
            }
        }

        // 3) 按组聚合：root → (成员并集, tag 列表)
        Map<Integer, Set<Item>> membersByRoot = new LinkedHashMap<>();
        Map<Integer, List<String>> tagsByRoot = new LinkedHashMap<>();
        for (int i = 0; i < n; i++) {
            int root = find(parent, i);
            membersByRoot.computeIfAbsent(root, k -> new LinkedHashSet<>()).addAll(byTag.get(tagIds.get(i)));
            tagsByRoot.computeIfAbsent(root, k -> new ArrayList<>()).add(tagIds.get(i));
        }

        // crossed：某成员出现在 ≥2 个组里
        Map<Item, Integer> appears = new HashMap<>();
        for (Set<Item> members : membersByRoot.values()) {
            for (Item item : members) appears.merge(item, 1, Integer::sum);
        }

        Map<Item, Integer> usage = recipeUsage(level);
        List<Group> out = new ArrayList<>();
        for (Map.Entry<Integer, Set<Item>> e : membersByRoot.entrySet()) {
            Set<Item> members = e.getValue();
            if (members.size() < 2) continue; // 防御：不该发生（每个 tag 已 ≥2）
            // tag 之间的子集合并可能把好几个分类并成一大坨 → 超过上限的一律不要
            if (members.size() > MAX_GROUP_MEMBERS) continue;
            List<String> tags = new ArrayList<>(tagsByRoot.get(e.getKey()));
            tags.sort(String::compareTo);
            boolean crossed = false;
            for (Item item : members) {
                if (appears.getOrDefault(item, 0) > 1) {
                    crossed = true;
                    break;
                }
            }
            List<Item> list = new ArrayList<>(members);
            out.add(new Group(pickKey(tags), list, tags, guessCanonical(members, usage), crossed, new ArrayList<>(members)));
        }
        // 稳定排序：成员数降序、key 升序
        out.sort(Comparator.<Group>comparingInt(g -> -g.items.size()).thenComparing(g -> g.key));
        return out;
    }

    private static Set<Item> membersOfTag(String tagId) {
        if (tagId == null || tagId.isBlank()) return Set.of();
        try {
            ResourceLocation rl = ResourceLocation.tryParse(tagId);
            if (rl == null) return Set.of();
            return membersOfTag(TagKey.create(Registries.ITEM, rl));
        } catch (Throwable t) {
            return Set.of();
        }
    }

    private static Set<Item> membersOfTag(TagKey<Item> tag) {
        Set<Item> out = new LinkedHashSet<>();
        try {
            Optional<HolderSet.Named<Item>> named = BuiltInRegistries.ITEM.getTag(tag);
            if (named.isEmpty()) return out;
            for (var holder : named.get()) {
                Item item = holder.value();
                if (item != null && item != Items.AIR) out.add(item);
            }
        } catch (Throwable ignored) {
        }
        return out;
    }

    /**
     * 明显不是"同类材料"的 tag —— 为什么跳过：
     * <ul>
     *   <li>路径以 {@code _logs}/{@code _planks}/{@code _wool}/{@code _dye} 结尾的是同色/同族变体
     *       tag（如"所有深色橡木原木"），成员是不同材料，彼此不是重复；</li>
     *   <li>id 里含 {@code creative}/{@code dummy}/{@code placeholder} 的是模组给创造标签页、
     *       假物品、占位用途的 tag，跟材料分类无关。</li>
     * </ul>
     */
    /**
     * 这个 tag 值不值得当「重复组」看。
     *
     * <p>三条硬判据（都是真机踩出来的）：
     * <ol>
     *   <li>成员少于 2 → 不可能重复</li>
     *   <li>成员只来自**一个**命名空间 → 那是同一家的内部分类，不是「几个模组各加一份」</li>
     *   <li>成员超过 {@link #MAX_GROUP_MEMBERS} → 是分类 tag（所有锭 / 所有工具）</li>
     * </ol>
     * 另外单段路径（没有 {@code /}）且名字在 {@link #CATEGORY_ROOTS} 里的，也算分类。
     */
    private static boolean isDuplicateCandidate(String tagId, Set<Item> members) {
        if (members == null || members.size() < 2) return false;
        if (members.size() > MAX_GROUP_MEMBERS) return false;

        int colon = tagId.indexOf(':');
        String path = colon < 0 ? tagId : tagId.substring(colon + 1);
        if (path.indexOf('/') < 0 && CATEGORY_ROOTS.contains(path)) return false;

        Set<String> namespaces = new HashSet<>();
        for (Item item : members) {
            ResourceLocation id = BuiltInRegistries.ITEM.getKey(item);
            if (id != null) namespaces.add(id.getNamespace());
            if (namespaces.size() >= 2) break;
        }
        return namespaces.size() >= 2;
    }

    private static boolean isNoise(String tagId) {
        String lower = tagId.toLowerCase();
        // ★原版命名空间的 tag 一律跳过：它们是「结构性分类」（#minecraft:coals 是煤+木炭、
        //   #minecraft:small_flowers 是一堆花），不是「同一个物品被几个模组各加了一份」。
        //   把它们当重复组会让统一功能去改写原版配方，语义会被悄悄放宽。
        if (lower.startsWith("minecraft:")) return true;
        for (String word : NOISE_WORDS) {
            if (lower.contains(word)) return true;
        }
        int colon = lower.indexOf(':');
        String path = colon < 0 ? lower : lower.substring(colon + 1);
        for (String suffix : NOISE_SUFFIX) {
            if (path.endsWith(suffix)) return true;
        }
        return false;
    }

    /**
     * 组 key：优先 c:/forge: 命名空间（社区标准 tag 更可读），再取较短的路径。
     * 例：{c:ingots/steel, create:ingots/steel} → "c:ingots/steel"。
     */
    private static String pickKey(List<String> tags) {
        String best = null;
        int bestScore = Integer.MAX_VALUE;
        for (String tag : tags) {
            String ns = tag.substring(0, Math.max(0, tag.indexOf(':')));
            int score = tag.length();
            if ("c".equals(ns)) score -= 1000;
            else if ("forge".equals(ns)) score -= 500;
            if (score < bestScore) {
                bestScore = score;
                best = tag;
            }
        }
        return best == null ? "?" : best;
    }

    /**
     * 自动推代表物品：优先 minecraft 命名空间，其次被配方引用次数最多，
     * 最后字典序（物品 id 字符串，小的在前）。
     */
    private static Item guessCanonical(Collection<Item> members, Map<Item, Integer> usage) {
        if (members == null || members.isEmpty()) return null;
        List<Item> tier = new ArrayList<>(members.size());
        for (Item item : members) {
            if ("minecraft".equals(namespaceOf(item))) tier.add(item);
        }
        if (tier.isEmpty()) tier = new ArrayList<>(members);
        tier.sort((a, b) -> {
            int ua = usage == null ? 0 : usage.getOrDefault(a, 0);
            int ub = usage == null ? 0 : usage.getOrDefault(b, 0);
            if (ua != ub) return Integer.compare(ub, ua);
            return itemId(a).compareTo(itemId(b));
        });
        return tier.get(0);
    }

    /**
     * 配方引用次数粗算：每个物品作为原料出现在多少条配方里（同一条配方内重复槽位只算一次）。
     * 拿不到世界 / RecipeManager 时返回空 map —— 调用方退回字典序，不会 NPE。结果按世界缓存。
     */
    private static Map<Item, Integer> recipeUsage(Level level) {
        if (usageCache != null && usageCacheLevel == level) return usageCache;
        Map<Item, Integer> map = new HashMap<>();
        try {
            if (level != null) {
                var manager = level.getRecipeManager();
                if (manager != null) {
                    Item[] seen = new Item[16];
                    for (Recipe<?> recipe : manager.getRecipes()) {
                        int seenCount = 0;
                        for (Ingredient ingredient : recipe.getIngredients()) {
                            if (ingredient == null || ingredient.isEmpty()) continue;
                            for (ItemStack stack : ingredient.getItems()) {
                                if (stack == null || stack.isEmpty()) continue;
                                Item item = stack.getItem();
                                boolean dup = false;
                                for (int i = 0; i < seenCount; i++) {
                                    if (seen[i] == item) {
                                        dup = true;
                                        break;
                                    }
                                }
                                if (dup) continue;
                                if (seenCount == seen.length) seen = Arrays.copyOf(seen, seenCount * 2);
                                seen[seenCount++] = item;
                                map.merge(item, 1, Integer::sum);
                            }
                        }
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        usageCache = map;
        usageCacheLevel = level;
        return map;
    }

    private static Item itemById(String id) {
        if (id == null || id.isBlank()) return null;
        try {
            ResourceLocation rl = ResourceLocation.tryParse(id);
            if (rl == null) return null;
            Item item = BuiltInRegistries.ITEM.get(rl);
            return item == null || item == Items.AIR ? null : item;
        } catch (Throwable t) {
            return null;
        }
    }

    private static String itemId(Item item) {
        ResourceLocation id = BuiltInRegistries.ITEM.getKey(item);
        return id == null ? "" : id.toString();
    }

    private static String namespaceOf(Item item) {
        ResourceLocation id = BuiltInRegistries.ITEM.getKey(item);
        return id == null ? "" : id.getNamespace();
    }

    private static <T> List<T> nullSafe(List<T> list) {
        return list == null ? List.of() : list;
    }

    private static boolean isSubset(Set<Item> a, Set<Item> b) {
        for (Item item : a) {
            if (!b.contains(item)) return false;
        }
        return true;
    }

    private static int find(int[] parent, int i) {
        while (parent[i] != i) {
            parent[i] = parent[parent[i]]; // 路径减半
            i = parent[i];
        }
        return i;
    }

    private static void union(int[] parent, int a, int b) {
        int ra = find(parent, a);
        int rb = find(parent, b);
        if (ra != rb) parent[ra] = rb;
    }

    // ------------------------------------------------------------------------
    // 我做了哪些假设（供人类复核；任务书没有 §7 决策表，歧义处均按「最简实现」）：
    // 1. 规则 4 的子集合并按字面实现：A⊆B（两边都 ≥2）即同组，并取传递闭包（并查集）。
    //    后果：父 tag 会吸收子 tag —— 如 c:ingots 与 c:ingots/steel、c:ingots/iron
    //    会并进一个大组（成员 = 组内所有 tag 的并集）。如果只要「完全相同」的合并，
    //    删掉 doScan 里的 isSubset 调用（保留等集合同并）即可。
    // 2. 组 key（pickKey）的取舍：优先 c:/forge: 命名空间，再取较短路径 ——
    //    任务书只说「优先用 tag 路径」，没给并列时的规则。
    // 3. canonical 从排除后的 items 里选；override 指定的 canonical id 解析不到时
    //    取 null（不自动推）。
    // 4. allMembers = 剔除前的状态，但包含 extraItems/extraTags 追加的物品
    //    （即 items ∪ excluded）。
    // 5. 排除后 items 不足 2 个的组整体丢弃。
    // 6. crossed 按未应用覆盖的原始成员计算（随缓存），覆盖后不重算。
    // 7. 「被配方引用次数」= 作为原料出现的配方条数（同一配方内重复槽位只算一次）；
    //    RecipeManager 拿不到时全部按 0 计 → 退回物品 id 字符串字典序。
    // 8. overrides map 按组 key 匹配；世界/reload 后若分组变化，旧 key 可能匹配不到。
    // ------------------------------------------------------------------------
}
