package com.yuneko.foliocleaner;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;

/**
 * 「统一数据包」的清单（manifest）：记住**我们自己写过哪些文件**，以及写进去时的内容指纹。
 *
 * <p>为什么需要它：
 * <ul>
 *   <li><b>精确回滚</b>：没有清单时只能「按组 key 反推路径」或「扫目录全删」——
 *       前者漏掉改写过的配方，后者会误删用户自己放进去的文件。</li>
 *   <li><b>清陈旧文件</b>：某个重复组消失（模组更新 / 用户排除到不足 2 个）后，
 *       上次生成的 tag / 配方副本会永远留在数据包里继续生效。</li>
 *   <li><b>不误删用户改过的文件</b>：指纹对不上就保留并报告（宁可不删，也不能悄悄吃掉别人的修改）。</li>
 * </ul>
 *
 * <p>文件位于数据包根下 {@code jeicleaner-manifest.json}（不在 {@code data/} 里，
 * 原版加载器不会读它）。格式人可读、可手删（删了等于「下次重新全量生成、不再清陈旧」）。
 */
public final class UnifyManifest {

    public static final String FILE_NAME = "foliocleaner-manifest.json";
    /** 清单格式版本；结构改了就 +1，读旧版直接当空处理（宁可全量重建） */
    public static final int FORMAT = 1;

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    /** 一个我们写进数据包的文件 */
    public static final class Entry {
        /** 相对数据包根的路径，{@code /} 分隔 */
        public final String path;
        /** tag / recipe / cross（只用于日志和统计） */
        public final String kind;
        /** 写入时的 SHA-1；空串 = 不知道（旧清单 / 计算失败），删除时不做校验 */
        public final String sha1;

        public Entry(String path, String kind, String sha1) {
            this.path = path == null ? "" : path;
            this.kind = kind == null ? "" : kind;
            this.sha1 = sha1 == null ? "" : sha1;
        }
    }

    /** 整份清单 */
    public static final class Data {
        public final int format;
        public final String modVersion;
        public final long updatedAt;
        public final List<Entry> entries;

        public Data(int format, String modVersion, long updatedAt, List<Entry> entries) {
            this.format = format;
            this.modVersion = modVersion == null ? "" : modVersion;
            this.updatedAt = updatedAt;
            this.entries = entries == null ? List.of() : entries;
        }

        public static final Data EMPTY = new Data(FORMAT, "", 0L, List.of());
    }

    private UnifyManifest() {
    }

    public static Path file(Path root) {
        return root == null ? null : root.resolve(FILE_NAME);
    }

    /** 读清单。不存在 / 格式坏 / 版本不符 → 返回空（绝不抛，宁可丢清单不能炸游戏）。 */
    public static Data load(Path root) {
        Path path = file(root);
        if (path == null || !Files.isRegularFile(path)) return Data.EMPTY;
        try {
            String text = Files.readString(path, StandardCharsets.UTF_8);
            JsonElement rootEl = JsonParser.parseString(text);
            if (!rootEl.isJsonObject()) return Data.EMPTY;
            JsonObject obj = rootEl.getAsJsonObject();
            int format = obj.has("format") ? obj.get("format").getAsInt() : 0;
            if (format != FORMAT) {
                FolioCleaner.LOGGER.info("[Folio Cleaner] 统一数据包清单格式 {} != {}，按空处理", format, FORMAT);
                return Data.EMPTY;
            }
            String version = obj.has("mod_version") ? obj.get("mod_version").getAsString() : "";
            long updated = obj.has("updated_at") ? obj.get("updated_at").getAsLong() : 0L;
            List<Entry> entries = new ArrayList<>();
            if (obj.has("entries") && obj.get("entries").isJsonArray()) {
                for (JsonElement el : obj.getAsJsonArray("entries")) {
                    if (!el.isJsonObject()) continue;
                    JsonObject e = el.getAsJsonObject();
                    String p = e.has("path") ? e.get("path").getAsString() : "";
                    if (p.isBlank()) continue;
                    entries.add(new Entry(p,
                            e.has("kind") ? e.get("kind").getAsString() : "",
                            e.has("sha1") ? e.get("sha1").getAsString() : ""));
                }
            }
            return new Data(format, version, updated, entries);
        } catch (Exception e) {
            FolioCleaner.LOGGER.warn("[Folio Cleaner] 读取统一数据包清单失败（按空处理）", e);
            return Data.EMPTY;
        }
    }

    /** 写清单；失败只告警。 */
    public static void save(Path root, Data data) {
        Path path = file(root);
        if (path == null) return;
        try {
            Files.createDirectories(path.getParent());
            JsonObject obj = new JsonObject();
            obj.addProperty("format", FORMAT);
            obj.addProperty("generator", FolioCleaner.MODID);
            obj.addProperty("mod_version", data.modVersion);
            obj.addProperty("updated_at", data.updatedAt);
            JsonArray arr = new JsonArray();
            for (Entry e : data.entries) {
                if (e == null || e.path.isBlank()) continue;
                JsonObject o = new JsonObject();
                o.addProperty("path", e.path);
                o.addProperty("kind", e.kind);
                o.addProperty("sha1", e.sha1);
                arr.add(o);
            }
            obj.add("entries", arr);
            Files.writeString(path, GSON.toJson(obj), StandardCharsets.UTF_8);
        } catch (Exception e) {
            FolioCleaner.LOGGER.warn("[Folio Cleaner] 写统一数据包清单失败", e);
        }
    }

    /**
     * 清单里记的文件是不是都还在。
     * 玩家手动删了数据包里的东西（或用了「全删」却又想重建）时，靠它发现「该补一次」。
     * 清单为空 → 返回 true（没东西要补）。
     */
    public static boolean allPresent(Path root) {
        Data m = load(root);
        if (m.entries.isEmpty()) return true;
        for (Entry e : m.entries) {
            try {
                if (!Files.isRegularFile(root.resolve(e.path))) return false;
            } catch (Throwable t) {
                return false;
            }
        }
        return true;
    }

    /** 当前模组版本（清单里只是个参考信息，拿不到就写 dev） */
    public static String modVersion() {
        try {
            return net.minecraftforge.fml.ModList.get()
                    .getModContainerById(FolioCleaner.MODID)
                    .map(c -> c.getModInfo().getVersion().toString())
                    .orElse("dev");
        } catch (Throwable t) {
            return "dev";
        }
    }

    /** SHA-1 十六进制小写；算不出来返回空串（调用方按「不校验」处理） */
    public static String sha1(byte[] bytes) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-1");
            byte[] digest = md.digest(bytes);
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) sb.append(Character.forDigit((b >> 4) & 0xF, 16))
                    .append(Character.forDigit(b & 0xF, 16));
            return sb.toString();
        } catch (Throwable t) {
            return "";
        }
    }
}
