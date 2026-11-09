package com.yuneko.foliocleaner;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraftforge.fml.loading.FMLPaths;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 「重复组」用户覆盖层的持久化（不是界面偏好，所以单独存一个文件）。
 *
 * <p>存在 {@code config/jeicleaner/dup-overrides.json}，结构人可读：
 * <pre>
 * {
 *   "groups": {
 *     "c:ingots/steel": {
 *       "extraTags":  ["..."],
 *       "extraItems": ["..."],
 *       "excluded":   ["..."],
 *       "canonical":  "modid:item"   // 可为 null / 缺省
 *     }
 *   }
 * }
 * </pre>
 */
public final class DupOverrides {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    private DupOverrides() {
    }

    public static Path file() {
        return FMLPaths.CONFIGDIR.get().resolve("foliocleaner").resolve("dup-overrides.json");
    }

    /**
     * 读 {@code config/jeicleaner/dup-overrides.json}。
     * 文件不存在 / 格式坏掉都返回空 map，绝不抛异常（宁可丢这一层修正，不能炸游戏）。
     */
    public static Map<String, DuplicateGroups.Override> load() {
        Path path = file();
        if (!Files.isRegularFile(path)) return new LinkedHashMap<>();
        try {
            return parse(Files.readString(path, StandardCharsets.UTF_8));
        } catch (Exception e) {
            FolioCleaner.LOGGER.warn("[Folio Cleaner] 读取重复组覆盖层失败（按空处理）", e);
            return new LinkedHashMap<>();
        }
    }

    /**
     * 解析一份覆盖层 JSON（同一份实现给「文件」和「网络包里带来的文本」用）。
     * 格式坏掉返回空 map，绝不抛。
     */
    public static Map<String, DuplicateGroups.Override> parse(String text) {
        Map<String, DuplicateGroups.Override> out = new LinkedHashMap<>();
        if (text == null || text.isBlank()) return out;
        try {
            JsonElement root = JsonParser.parseString(text);
            if (!root.isJsonObject()) return out;
            JsonObject obj = root.getAsJsonObject();
            if (!obj.has("groups") || !obj.get("groups").isJsonObject()) return out;
            JsonObject groups = obj.getAsJsonObject("groups");
            for (Map.Entry<String, JsonElement> e : groups.entrySet()) {
                if (!e.getValue().isJsonObject()) continue;
                JsonObject g = e.getValue().getAsJsonObject();
                String canonical = null;
                if (g.has("canonical") && g.get("canonical").isJsonPrimitive()) {
                    canonical = g.get("canonical").getAsString();
                }
                out.put(e.getKey(), new DuplicateGroups.Override(
                        stringList(g, "extraTags"),
                        stringList(g, "extraItems"),
                        stringList(g, "excluded"),
                        canonical,
                        stringList(g, "removedTags")));
            }
        } catch (Exception e) {
            FolioCleaner.LOGGER.warn("[Folio Cleaner] 解析重复组覆盖层失败（按空处理）", e);
            return new LinkedHashMap<>();
        }
        return out;
    }

    /** 写回同一个文件（父目录不存在会创建）。写失败只告警，不抛。 */
    public static void save(Map<String, DuplicateGroups.Override> map) {
        Path path = file();
        try {
            Files.createDirectories(path.getParent());
            Files.writeString(path, toJson(map), StandardCharsets.UTF_8);
        } catch (Exception e) {
            FolioCleaner.LOGGER.warn("[Folio Cleaner] 保存重复组覆盖层失败", e);
        }
    }

    /** 序列化成 JSON（写文件和「塞进网络包发给服务端」共用） */
    public static String toJson(Map<String, DuplicateGroups.Override> map) {
        JsonObject groups = new JsonObject();
        if (map != null) {
            for (Map.Entry<String, DuplicateGroups.Override> e : map.entrySet()) {
                if (e.getKey() == null || e.getValue() == null) continue;
                JsonObject g = new JsonObject();
                g.add("extraTags", stringArray(e.getValue().extraTags()));
                g.add("extraItems", stringArray(e.getValue().extraItems()));
                g.add("excluded", stringArray(e.getValue().excluded()));
                g.add("removedTags", stringArray(e.getValue().removedTags()));
                if (e.getValue().canonical() != null) {
                    g.addProperty("canonical", e.getValue().canonical());
                }
                groups.add(e.getKey(), g);
            }
        }
        JsonObject root = new JsonObject();
        root.add("groups", groups);
        return GSON.toJson(root);
    }

    /**
     * 当前覆盖层的 JSON 文本。
     * 专用服务器上没有玩家本地的 {@code dup-overrides.json}，所以多人服生成数据包时
     * 必须由客户端把这份文本一起发过去。
     */
    public static String exportJson() {
        return toJson(load());
    }

    // ---------------------------------------------------------------- 内部

    private static List<String> stringList(JsonObject obj, String field) {
        List<String> list = new ArrayList<>();
        if (obj.has(field) && obj.get(field).isJsonArray()) {
            for (JsonElement el : obj.getAsJsonArray(field)) {
                if (el.isJsonPrimitive()) list.add(el.getAsString());
            }
        }
        return list;
    }

    private static JsonArray stringArray(List<String> list) {
        JsonArray arr = new JsonArray();
        if (list != null) {
            for (String s : list) {
                if (s != null) arr.add(s);
            }
        }
        return arr;
    }
}
