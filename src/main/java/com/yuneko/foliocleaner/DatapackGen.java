package com.yuneko.foliocleaner;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 「统一」的实现之一：生成数据包（方案甲）。
 *
 * <p>第一步只做**tag 追加** —— 把组内非代表成员写进同一个 tag：
 * <pre>
 * data/c/tags/items/ingots/steel.json
 * { "replace": false, "values": ["mekanism:ingot_steel", "thermal:steel_ingot"] }
 * </pre>
 * {@code replace:false} 是**合并**语义，不会覆盖原文件。整合包里大量配方本来就写
 * {@code #c:ingots/steel}，所以这一步就能让它们互相通用，一条配方都不用改。
 *
 * <p>配方里「写死了某个具体物品」的情况，要等 {@code rewriteRecipes()}（TODO）。
 *
 * <p>本类只负责**写文件**，不碰服务器 / 不触发 reload —— 那些由上层决定，
 * 这样它也方便单测（给个临时目录就能跑）。
 *
 * <p>写过的文件记在 {@link UnifyManifest} 里：{@link #sync} 顺手清掉不再产出的陈旧文件，
 * {@link #rollback} 可以精确撤销（只删自己写过、且没被别人改过的）。
 */
public final class DatapackGen {

    /**
     * 生成结果。
     *
     * @param files   真正写盘（内容有变）的文件数
     * @param values  tag 追加的条目数 / 改写过的配方数（按调用方语义）
     * @param touched 内容有变、写盘了的文件（相对数据包根）
     * @param all     **本次应当存在**的全部文件（相对数据包根；含内容没变的）——
     *                清单 / 陈旧清理 / 冲突检测都以它为准
     * @param notes   跳过 / 失败的原因，给日志用
     */
    public record Result(int files, int values, List<String> touched, List<String> all, List<String> notes) {
    }

    /** 一次完整同步的结果 */
    public record Sync(int written, int pruned, List<String> keptModified, int entries,
                       Result tags, Result recipes) {
    }

    /** 撤销结果 */
    public record Rollback(int deleted, List<String> keptModified) {
    }

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    /**
     * 跨 viewer 的「隐藏物品」公共 tag —— JEI / REI / EMI 三家都认它
     * （REI 内置了按这个 tag 过滤的规则，EMI 也读它，JEI 同样支持）。
     * 所以「物品本体隐藏」这一层天然跨 viewer，不用逐个调它们的 API。
     */
    public static final String HIDDEN_FROM_VIEWERS_TAG = "c:hidden_from_recipe_viewers";

    private DatapackGen() {
    }

    /**
     * 把每组的成员写进它自己的 tag 文件。
     *
     * @param root   数据包根目录（里面应当有 pack.mcmeta 或 data/）
     * @param groups 重复组（key 必须是真实 tag id，如 {@code c:ingots/steel}）
     */
    public static Result writeTagFiles(Path root, List<DuplicateGroups.Group> groups) {
        List<String> touched = new ArrayList<>();
        List<String> all = new ArrayList<>();
        List<String> notes = new ArrayList<>();
        int values = 0;

        if (root == null || groups == null || groups.isEmpty()) {
            return new Result(0, 0, touched, all, notes);
        }

        for (DuplicateGroups.Group g : groups) {
            try {
                if (g == null || g.key == null || g.items == null || g.items.size() < 2) continue;
                ResourceLocation tagId = ResourceLocation.tryParse(g.key);
                if (tagId == null) {
                    notes.add("跳过非法 tag id：" + g.key);
                    continue;
                }

                JsonArray arr = new JsonArray();
                int n = 0;
                for (Item item : g.items) {
                    ResourceLocation id = BuiltInRegistries.ITEM.getKey(item);
                    if (id == null || "minecraft".equals(id.getPath()) && "air".equals(id.getPath())) continue;
                    arr.add(id.toString());
                    n++;
                }
                if (n < 2) continue;

                JsonObject body = new JsonObject();
                body.addProperty("replace", false);
                body.add("values", arr);

                Path file = root.resolve("data")
                        .resolve(tagId.getNamespace())
                        .resolve("tags/items")
                        .resolve(tagId.getPath() + ".json");
                String rel = root.relativize(file).toString().replace('\\', '/');
                if (!all.contains(rel)) all.add(rel);
                if (writeIfChanged(file, GSON.toJson(body))) touched.add(rel);
                values += n;
            } catch (Throwable t) {
                notes.add("生成失败：" + (g == null ? "?" : g.key) + " (" + t.getClass().getSimpleName() + ")");
            }
        }
        return new Result(touched.size(), values, touched, all, notes);
    }

    /**
     * 跨 viewer 隐藏：把所有「物品本体被隐藏」的物品写进
     * {@code data/c/tags/items/hidden_from_recipe_viewers.json}。
     *
     * <p>{@code replace:false} 是合并语义 —— 别的模组/数据包往同一个 tag 里加的东西不会被覆盖。
     * JEI / REI / EMI 都认这个 tag（见 {@code VIEWER_API_MATRIX.md}），
     * 代价是**要 /reload 之后才生效**，而且只能覆盖「整个物品」，管不到单条配方。
     *
     * @param itemIds 物品 id（空 / null 时返回空结果 —— 上层会顺手把旧文件清掉）
     */
    public static Result writeHiddenTag(Path root, List<String> itemIds) {
        List<String> touched = new ArrayList<>();
        List<String> all = new ArrayList<>();
        List<String> notes = new ArrayList<>();
        if (root == null || itemIds == null || itemIds.isEmpty()) {
            return new Result(0, 0, touched, all, notes);
        }
        try {
            ResourceLocation tagId = ResourceLocation.tryParse(HIDDEN_FROM_VIEWERS_TAG);
            if (tagId == null) return new Result(0, 0, touched, all, notes);

            JsonArray arr = new JsonArray();
            for (String id : itemIds) {
                if (id == null || id.isBlank()) continue;
                arr.add(id);
            }
            if (arr.size() == 0) return new Result(0, 0, touched, all, notes);

            JsonObject body = new JsonObject();
            body.addProperty("replace", false);
            body.add("values", arr);

            Path file = root.resolve("data")
                    .resolve(tagId.getNamespace())
                    .resolve("tags/items")
                    .resolve(tagId.getPath() + ".json");
            String rel = root.relativize(file).toString().replace('\\', '/');
            all.add(rel);
            if (writeIfChanged(file, GSON.toJson(body))) touched.add(rel);
            return new Result(touched.size(), arr.size(), touched, all, notes);
        } catch (Throwable t) {
            notes.add("生成跨 viewer 隐藏 tag 失败：" + t.getClass().getSimpleName());
            return new Result(0, 0, touched, all, notes);
        }
    }

    /**
     * 把配方里「写死了某个组内物品」的地方改成引用 tag。
     *
     * <pre>
     * 原：{ "item": "mekanism:ingot_steel" }
     * 改：{ "tag": "c:ingots/steel" }
     * </pre>
     *
     * <p>只改输入槽（JSON 里任何带 {@code item} 的对象），**不动 count / nbt** —— 那两种换成 tag
     * 语义会变（数量、NBT 匹配），按设计直接跳过并计入「未处理」。
     *
     * @param crossTag 交叉物品（同时属于多个组）是否用「双 tag 临时 tag」包装
     */
    public static Result rewriteRecipes(net.minecraft.server.packs.resources.ResourceManager rm,
                                        Path root,
                                        List<DuplicateGroups.Group> groups,
                                        boolean crossTag) {
        List<String> touched = new ArrayList<>();
        List<String> all = new ArrayList<>();
        List<String> notes = new ArrayList<>();
        if (rm == null || root == null || groups == null || groups.isEmpty()) {
            return new Result(0, 0, touched, all, notes);
        }

        // 物品 id → 该用哪个 tag
        java.util.Map<String, String> itemToTag = new java.util.HashMap<>();
        java.util.Map<String, java.util.List<String>> itemGroups = new java.util.HashMap<>();
        for (DuplicateGroups.Group g : groups) {
            if (g == null || g.key == null || g.items == null) continue;
            if (net.minecraft.resources.ResourceLocation.tryParse(g.key) == null) continue;
            for (Item item : g.items) {
                var id = BuiltInRegistries.ITEM.getKey(item);
                if (id == null) continue;
                itemGroups.computeIfAbsent(id.toString(), k -> new ArrayList<>()).add(g.key);
            }
        }
        int crossed = 0;
        for (var e : itemGroups.entrySet()) {
            if (e.getValue().size() == 1) {
                itemToTag.put(e.getKey(), e.getValue().get(0));
            } else if (crossTag) {
                // 双 tag 临时 tag：接受两类的全部成员
                String synthetic = "foliocleaner:crossed/" + Integer.toHexString(Math.abs(e.getKey().hashCode()));
                JsonArray values = new JsonArray();
                for (String tag : e.getValue()) values.add("#" + tag);
                JsonObject body = new JsonObject();
                body.addProperty("replace", false);
                body.add("values", values);
                try {
                    Path f = root.resolve("data/foliocleaner/tags/items/crossed/"
                            + synthetic.substring(synthetic.indexOf('/') + 1) + ".json");
                    String rel = root.relativize(f).toString().replace('\\', '/');
                    if (!all.contains(rel)) all.add(rel);
                    if (writeIfChanged(f, GSON.toJson(body))) touched.add(rel);
                } catch (Throwable ignored) {
                }
                itemToTag.put(e.getKey(), synthetic);
                crossed++;
            } else {
                notes.add("交叉物品未处理：" + e.getKey());
            }
        }
        if (itemToTag.isEmpty()) return new Result(touched.size(), 0, touched, all, notes);

        int changed = 0;
        try {
            var found = rm.listResources("recipes", p -> p.getPath().endsWith(".json"));
            for (var entry : found.entrySet()) {
                var loc = entry.getKey();
                try {
                    String text;
                    try (var in = entry.getValue().open()) {
                        text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
                    }
                    var rootEl = JsonParser.parseString(text);
                    if (!rootEl.isJsonObject()) continue;
                    if (!rewriteNode(rootEl, itemToTag)) continue;

                    Path out = root.resolve("data").resolve(loc.getNamespace()).resolve(loc.getPath());
                    String rel = root.relativize(out).toString().replace('\\', '/');
                    if (!all.contains(rel)) all.add(rel);
                    if (writeIfChanged(out, GSON.toJson(rootEl))) {
                        touched.add(rel);
                        changed++;
                    }
                } catch (Throwable t) {
                    notes.add("解析失败：" + loc + " (" + t.getClass().getSimpleName() + ")");
                }
            }
        } catch (Throwable t) {
            notes.add("读取配方列表失败：" + t.getClass().getSimpleName());
        }
        return new Result(changed, crossed, touched, all, notes);
    }

    /** 递归替换；返回是否改动过 */
    private static boolean rewriteNode(com.google.gson.JsonElement el, java.util.Map<String, String> itemToTag) {
        boolean changed = false;
        if (el == null) return false;
        if (el.isJsonArray()) {
            for (com.google.gson.JsonElement child : el.getAsJsonArray()) {
                if (rewriteNode(child, itemToTag)) changed = true;
            }
            return changed;
        }
        if (!el.isJsonObject()) return false;
        JsonObject obj = el.getAsJsonObject();

        // 带 nbt / count 的不动（换成 tag 语义会变）
        if (obj.has("item") && obj.get("item").isJsonPrimitive()
                && !obj.has("nbt") && !obj.has("count")) {
            String id = obj.get("item").getAsString();
            String tag = itemToTag.get(id);
            if (tag != null) {
                obj.remove("item");
                obj.addProperty("tag", tag);
                return true;
            }
        }
        for (var key : new ArrayList<>(obj.keySet())) {
            if (rewriteNode(obj.get(key), itemToTag)) changed = true;
        }
        return changed;
    }

    /** 确保数据包根目录成型：补 pack.mcmeta 和 data/ */
    public static void ensurePackRoot(Path root) {
        try {
            Files.createDirectories(root.resolve("data"));
            Path meta = root.resolve("pack.mcmeta");
            if (!Files.isRegularFile(meta)) {
                JsonObject pack = new JsonObject();
                pack.addProperty("pack_format", 15);
                pack.addProperty("description", "Folio Cleaner · 统一规则");
                JsonObject top = new JsonObject();
                top.add("pack", pack);
                Files.writeString(meta, GSON.toJson(top), StandardCharsets.UTF_8);
            }
        } catch (Throwable t) {
            FolioCleaner.LOGGER.warn("[Folio Cleaner] 创建数据包目录失败", t);
        }
    }

    /**
     * 一次完整的「统一」同步：**生成 → 清陈旧 → 写清单**。
     *
     * <p>上层（{@code Cleaner.applyUnify}）只需要调这一个方法。清单见 {@link UnifyManifest}。
     *
     * <p>陈旧文件（上次生成过、这次不再产出）按清单精确删除，删之前校验 SHA-1：
     * 对不上说明文件被人改过 → 保留并在 {@code keptModified} 里报出来，**绝不静默覆盖别人的修改**。
     */
    public static Sync sync(Path root,
                            net.minecraft.server.packs.resources.ResourceManager rm,
                            List<DuplicateGroups.Group> groups,
                            boolean crossTag) {
        return sync(root, rm, groups, crossTag, List.of());
    }

    /**
     * 同上，另外把「物品本体隐藏」写进跨 viewer 的公共 tag。
     *
     * @param hiddenItems 被隐藏的物品 id（{@code Rules#hiddenItemIds()}）；空列表 = 不写 / 清掉旧文件
     */
    public static Sync sync(Path root,
                            net.minecraft.server.packs.resources.ResourceManager rm,
                            List<DuplicateGroups.Group> groups,
                            boolean crossTag,
                            List<String> hiddenItems) {
        Result empty = new Result(0, 0, List.of(), List.of(), List.of());
        if (root == null) return new Sync(0, 0, List.of(), 0, empty, empty);

        ensurePackRoot(root);
        UnifyManifest.Data old = UnifyManifest.load(root);

        Result tags = writeTagFiles(root, groups);
        Result recipes = rewriteRecipes(rm, root, groups, crossTag);
        Result hidden = writeHiddenTag(root, hiddenItems);

        // 用 Set 去重 + 快速查找（改写过的配方可能上千条，ArrayList.contains 会变成 O(n²)）
        java.util.LinkedHashSet<String> target = new java.util.LinkedHashSet<>(tags.all());
        for (String p : recipes.all()) {
            if (p != null) target.add(p);
        }
        for (String p : hidden.all()) {
            if (p != null) target.add(p);
        }

        // ---- 1) 清陈旧：清单里有、这次没产出的文件
        int pruned = 0;
        List<String> kept = new ArrayList<>();
        for (UnifyManifest.Entry e : old.entries) {
            if (e == null || e.path.isBlank() || target.contains(e.path)) continue;
            try {
                Path f = root.resolve(e.path);
                if (!Files.isRegularFile(f)) continue;
                if (!e.sha1.isEmpty()) {
                    String cur = UnifyManifest.sha1(Files.readAllBytes(f));
                    if (!cur.equals(e.sha1)) {
                        kept.add(e.path);
                        continue;
                    }
                }
                if (Files.deleteIfExists(f)) {
                    pruned++;
                    pruneEmptyParents(root, f.getParent());
                }
            } catch (Throwable t) {
                kept.add(e.path);
            }
        }

        // ---- 2) 重写清单（指纹按磁盘上的实际内容算，这样下次校验才准）
        List<UnifyManifest.Entry> entries = new ArrayList<>(target.size());
        for (String rel : target) {
            String sha = "";
            try {
                Path f = root.resolve(rel);
                if (Files.isRegularFile(f)) sha = UnifyManifest.sha1(Files.readAllBytes(f));
            } catch (Throwable ignored) {
            }
            entries.add(new UnifyManifest.Entry(rel, kindOf(rel), sha));
        }
        UnifyManifest.save(root, new UnifyManifest.Data(UnifyManifest.FORMAT,
                UnifyManifest.modVersion(), System.currentTimeMillis(), entries));

        int written = tags.files() + recipes.files() + hidden.files();
        return new Sync(written, pruned, kept, entries.size(), tags, recipes);
    }

    /**
     * 全部撤销：按清单删掉所有我们生成过的文件（SHA-1 校验不通过的一律保留），
     * 然后删掉清单本身。**只动清单里列过的文件**，用户自己放进数据包的东西不会受影响。
     */
    public static Rollback rollback(Path root) {
        if (root == null) return new Rollback(0, List.of());
        UnifyManifest.Data m = UnifyManifest.load(root);
        int deleted = 0;
        List<String> kept = new ArrayList<>();
        for (UnifyManifest.Entry e : m.entries) {
            if (e == null || e.path.isBlank()) continue;
            try {
                Path f = root.resolve(e.path);
                if (!Files.isRegularFile(f)) continue;
                if (!e.sha1.isEmpty()) {
                    String cur = UnifyManifest.sha1(Files.readAllBytes(f));
                    if (!cur.equals(e.sha1)) {
                        kept.add(e.path);
                        continue;
                    }
                }
                if (Files.deleteIfExists(f)) {
                    deleted++;
                    pruneEmptyParents(root, f.getParent());
                }
            } catch (Throwable t) {
                kept.add(e.path);
            }
        }
        try {
            if (kept.isEmpty()) {
                Files.deleteIfExists(UnifyManifest.file(root));
            } else {
                // 保留的（被改过的）文件继续记账，下次还能再撤
                List<UnifyManifest.Entry> entries = new ArrayList<>();
                for (UnifyManifest.Entry e : m.entries) {
                    if (kept.contains(e.path)) entries.add(e);
                }
                UnifyManifest.save(root, new UnifyManifest.Data(UnifyManifest.FORMAT,
                        UnifyManifest.modVersion(), System.currentTimeMillis(), entries));
            }
        } catch (Throwable ignored) {
        }
        return new Rollback(deleted, kept);
    }

    /** 路径分类（日志 / 统计用） */
    private static String kindOf(String rel) {
        if (rel == null) return "";
        if (rel.startsWith("data/foliocleaner/tags/items/crossed/")) return "cross";
        if (rel.contains("/tags/items/")) return "tag";
        if (rel.contains("/recipes/")) return "recipe";
        return "other";
    }

    /** 删完文件后清掉空目录（只清 {@code data/} 之下，不碰数据包根和 pack.mcmeta 所在层） */
    private static void pruneEmptyParents(Path root, Path dir) {
        try {
            Path stop = root.resolve("data");
            Path cur = dir;
            while (cur != null && cur.startsWith(stop) && !cur.equals(stop)) {
                try (DirectoryStream<Path> ds = Files.newDirectoryStream(cur)) {
                    if (ds.iterator().hasNext()) return;
                }
                Files.deleteIfExists(cur);
                cur = cur.getParent();
            }
        } catch (Throwable ignored) {
        }
    }

    /** 清掉我们自己生成过的 tag 文件（按组 key 反推路径，不扫目录乱删） */
    public static int removeTagFiles(Path root, List<String> tagIds) {
        int n = 0;
        if (root == null || tagIds == null) return 0;
        for (String key : tagIds) {
            try {
                ResourceLocation id = ResourceLocation.tryParse(key);
                if (id == null) continue;
                Path file = root.resolve("data")
                        .resolve(id.getNamespace())
                        .resolve("tags/items")
                        .resolve(id.getPath() + ".json");
                if (Files.deleteIfExists(file)) n++;
            } catch (Throwable ignored) {
            }
        }
        return n;
    }

    /** 只在内容变了才写；返回是否真的写了 */
    private static boolean writeIfChanged(Path file, String content) throws IOException {
        if (Files.isRegularFile(file)) {
            try {
                String old = Files.readString(file, StandardCharsets.UTF_8);
                if (normalize(old).equals(normalize(content))) return false;
            } catch (Throwable ignored) {
            }
        }
        Files.createDirectories(file.getParent());
        Files.writeString(file, content, StandardCharsets.UTF_8);
        return true;
    }

    /** 比较时忽略空白差异，避免格式变化也触发重写 */
    private static String normalize(String json) {
        try {
            var el = JsonParser.parseString(json);
            return GSON.toJson(el);
        } catch (Throwable t) {
            return json;
        }
    }

    /** 目录里已经有哪些我们生成的 tag 文件（撤销时用；目前保留给后续 manifest 用） */
    public static List<String> listGenerated(Path root) {
        List<String> out = new ArrayList<>();
        if (root == null) return out;
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(root.resolve("data"))) {
            for (Path ns : ds) {
                if (!Files.isDirectory(ns)) continue;
                Path tags = ns.resolve("tags/items");
                if (!Files.isDirectory(tags)) continue;
                try (DirectoryStream<Path> fs = Files.newDirectoryStream(tags, "*.json")) {
                    for (Path f : fs) {
                        out.add(root.relativize(f).toString().replace('\\', '/'));
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return out;
    }
}
