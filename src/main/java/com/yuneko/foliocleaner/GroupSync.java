package com.yuneko.foliocleaner;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 重复组的**跨端同步格式**。
 *
 * <h2>为什么需要它</h2>
 * {@code rewrite} / {@code match} 两种统一模式都会改「原料能接受哪些物品」。
 * 客户端改了 → JEI 里显示这个配方收 X 物品；服务端改了 → 真的收。
 * 两边如果各自扫出一份**不一样**的重复组（典型成因：客户端的
 * {@code dup-overrides.json} 覆盖层在专用服务器上不存在），就会出现
 * 「客户端说能合、服务端说不能合」的幽灵配方。
 *
 * <p>所以登录时服务端把自己的组数据随 {@code SyncRulesPacket} 一起发下来，
 * 客户端在连服务器时**以服务端为准**（{@link UnifyRuntime#setServerGroups}）。
 *
 * <h2>格式</h2>
 * 一行一组，{@code key<TAB>id,id,id}；成员按 id 排序、组按 key 排序（保证两端算出的
 * 文本逐字节相同 → 指纹可比）。{@code \t} 不会出现在资源位置里，所以是安全分隔符。
 */
public final class GroupSync {

    /** 空 = 没有组数据（客户端自行扫描） */
    public static final String NONE = "";

    /** 编码缓存：内容没变就不重编（登录 / 广播会反复调用） */
    private static String cachedText = NONE;
    /** 缓存键 = 世界身份 + 扫描代 + 覆盖层内容哈希（见 {@link #fromLevel}） */
    private static int cachedKey;
    private static boolean haveCache;

    private GroupSync() {
    }

    /**
     * 服务端入口：扫一次 + 编码（带缓存）。
     *
     * <p>缓存键不用「列表实例」—— 带覆盖层时 {@code scan()} 每次都返回新列表，实例判断永远不命中，
     * 于是每次玩家登录/广播都要重扫一遍 + 重编码（大整合包几百组）。改成
     * **世界身份 + 扫描代 + 覆盖层哈希** 之后，内容不变就直接返回上次的字符串。
     *
     * @param level     服务端世界（拿 tag 和配方）
     * @param overrides 服务端自己的覆盖层（{@link DupOverrides#load()}）
     */
    public static String fromLevel(Level level, Map<String, DuplicateGroups.Override> overrides) {
        if (level == null) return NONE;
        int key = System.identityHashCode(level) * 31
                + DuplicateGroups.revision() * 17
                + overridesHash(overrides);
        if (haveCache && key == cachedKey) return cachedText;
        try {
            List<DuplicateGroups.Group> groups = DuplicateGroups.scan(level, overrides);
            String text = encode(groups);
            cachedKey = key;
            cachedText = text;
            haveCache = true;
            return text;
        } catch (Throwable t) {
            FolioCleaner.LOGGER.warn("[Folio Cleaner] 编码重复组失败（客户端将自行扫描）", t);
            return NONE;
        }
    }

    /** 覆盖层的内容哈希（key + 每个成员列表），用于缓存失效判断 */
    private static int overridesHash(Map<String, DuplicateGroups.Override> overrides) {
        if (overrides == null || overrides.isEmpty()) return 0;
        int h = overrides.size();
        for (Map.Entry<String, DuplicateGroups.Override> e : overrides.entrySet()) {
            h = h * 31 + e.getKey().hashCode();
            DuplicateGroups.Override o = e.getValue();
            if (o == null) continue;
            h = h * 31 + hashList(o.extraTags()) + hashList(o.extraItems())
                    + hashList(o.excluded()) + hashList(o.removedTags())
                    + (o.canonical() == null ? 0 : o.canonical().hashCode());
        }
        return h;
    }

    private static int hashList(List<String> list) {
        if (list == null) return 0;
        int h = list.size();
        for (String s : list) h = h * 31 + (s == null ? 0 : s.hashCode());
        return h;
    }

    public static void invalidate() {
        cachedText = NONE;
        haveCache = false;
    }

    // ---------------------------------------------------------------- 编码 / 解码

    /** 组列表 → 文本（只保留 key + 成员 id；canonical / tags 只影响界面） */
    public static String encode(List<DuplicateGroups.Group> groups) {
        if (groups == null || groups.isEmpty()) return NONE;
        List<String> lines = new ArrayList<>(groups.size());
        for (DuplicateGroups.Group g : groups) {
            if (g == null || g.items == null || g.items.size() < 2) continue;
            List<String> ids = new ArrayList<>(g.items.size());
            for (Item item : g.items) {
                String id = itemId(item);
                if (!id.isEmpty()) ids.add(id);
            }
            if (ids.size() < 2) continue;
            ids.sort(String::compareTo);
            lines.add((g.key == null ? "?" : g.key) + '\t' + String.join(",", ids));
        }
        if (lines.isEmpty()) return NONE;
        lines.sort(Comparator.naturalOrder());
        return String.join("\n", lines);
    }

    /** 文本 → 物品 → 组 key */
    public static Map<Item, String> itemToGroup(String text) {
        Map<Item, String> out = new java.util.HashMap<>();
        for (Map.Entry<String, List<Item>> e : groups(text).entrySet()) {
            for (Item item : e.getValue()) out.put(item, e.getKey());
        }
        return out;
    }

    /** 文本 → 组 key → 成员 */
    public static Map<String, List<Item>> groups(String text) {
        Map<String, List<Item>> out = new LinkedHashMap<>();
        if (text == null || text.isBlank()) return out;
        for (String line : text.split("\n")) {
            int tab = line.indexOf('\t');
            if (tab <= 0 || tab == line.length() - 1) continue;
            String key = line.substring(0, tab);
            Set<Item> members = new LinkedHashSet<>();
            for (String id : line.substring(tab + 1).split(",")) {
                Item item = itemById(id);
                if (item != null) members.add(item);
            }
            if (members.size() < 2) continue;
            out.put(key, new ArrayList<>(members));
        }
        return out;
    }

    public static int groupCount(String text) {
        return groups(text).size();
    }

    /** 组数据的短指纹（sha1 前 16 位）—— 只用来打日志 / 给指令看，不参与判定 */
    public static String fingerprint(String text) {
        if (text == null || text.isEmpty()) return "-";
        try {
            MessageDigest sha = MessageDigest.getInstance("SHA-1");
            byte[] d = sha.digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(16);
            for (int i = 0; i < 8; i++) sb.append(String.format("%02x", d[i]));
            return sb.toString();
        } catch (Throwable t) {
            return Integer.toHexString(text.hashCode());
        }
    }

    // ---------------------------------------------------------------- 内部

    private static Item itemById(String id) {
        if (id == null || id.isBlank()) return null;
        try {
            ResourceLocation rl = ResourceLocation.tryParse(id.trim());
            if (rl == null) return null;
            Item item = BuiltInRegistries.ITEM.get(rl);
            return item == null || item == Items.AIR ? null : item;
        } catch (Throwable t) {
            return null;
        }
    }

    private static String itemId(Item item) {
        if (item == null) return "";
        try {
            ResourceLocation id = BuiltInRegistries.ITEM.getKey(item);
            return id == null ? "" : id.toString();
        } catch (Throwable t) {
            return "";
        }
    }
}
