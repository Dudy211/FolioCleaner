package com.yuneko.jeicleaner;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraftforge.fml.loading.FMLPaths;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 规则仓库。
 *
 * <p>两份文件：server.json（服主权威，登录时下发）与 client.json（玩家本地）。
 * 实际生效 = SERVER + CLIENT。
 */
public final class Rules {

    private static final Gson GSON = new GsonBuilder()
            .setPrettyPrinting()
            .disableHtmlEscaping()
            .create();

    private static final List<Rule> SERVER = new ArrayList<>();
    private static final List<Rule> CLIENT = new ArrayList<>();

    private static int revision;

    private Rules() {
    }

    public static int revision() {
        return revision;
    }

    private static void bump() {
        revision++;
    }

    // ---------------------------------------------------------------- 文件

    public static Path folder() {
        return FMLPaths.CONFIGDIR.get().resolve("jeicleaner");
    }

    public static Path serverFile() {
        return folder().resolve("server.json");
    }

    public static Path clientFile() {
        return folder().resolve("client.json");
    }

    private static Path legacyFile() {
        return FMLPaths.CONFIGDIR.get().resolve("jeicleaner.json");
    }

    // ---------------------------------------------------------------- 读取

    public static void loadServerRules() {
        SERVER.clear();
        bump();
        migrateLegacy();
        SERVER.addAll(readFile(serverFile()));
        if (!Files.isRegularFile(serverFile())) {
            writeFile(serverFile(), SERVER);
        }
    }

    public static void loadClientRules() {
        CLIENT.clear();
        bump();
        CLIENT.addAll(readFile(clientFile()));
        if (!Files.isRegularFile(clientFile())) {
            writeFile(clientFile(), CLIENT);
        }
    }

    public static void load() {
        loadServerRules();
        loadClientRules();
    }

    private static void migrateLegacy() {
        try {
            Path legacy = legacyFile();
            if (Files.isRegularFile(legacy) && !Files.isRegularFile(serverFile())) {
                Files.createDirectories(folder());
                Files.copy(legacy, serverFile());
                JeiCleaner.LOGGER.info("[JEI Cleaner] 已把旧配置迁移到 {}", serverFile());
            }
        } catch (IOException e) {
            JeiCleaner.LOGGER.warn("[JEI Cleaner] 迁移旧配置失败", e);
        }
    }

    private static List<Rule> readFile(Path path) {
        List<Rule> result = new ArrayList<>();
        if (!Files.isRegularFile(path)) return result;
        try {
            String text = Files.readString(path, StandardCharsets.UTF_8);
            result.addAll(parse(text));
        } catch (Exception e) {
            JeiCleaner.LOGGER.error("[JEI Cleaner] 读取 {} 失败", path, e);
        }
        return result;
    }

    private static void writeFile(Path path, List<Rule> rules) {
        try {
            Files.createDirectories(path.getParent());
            JsonObject root = new JsonObject();
            root.addProperty("_comment",
                    "JEI Cleaner 规则。type = item / mod / recipe / keep / category；"
                            + "action = hide / remove；ranges 为空表示不限类型。");
            JsonArray arr = new JsonArray();
            for (Rule rule : rules) arr.add(toJson(rule));
            root.add("rules", arr);
            Files.writeString(path, GSON.toJson(root), StandardCharsets.UTF_8);
        } catch (IOException e) {
            JeiCleaner.LOGGER.error("[JEI Cleaner] 写入 {} 失败", path, e);
        }
    }

    // ---------------------------------------------------------------- 查询

    /** 实际生效：服务端在前，然后是本地 */
    public static List<Rule> all() {
        List<Rule> merged = new ArrayList<>(SERVER.size() + CLIENT.size());
        merged.addAll(SERVER);
        merged.addAll(CLIENT);
        return Collections.unmodifiableList(merged);
    }

    public static List<Rule> serverRules() {
        return Collections.unmodifiableList(SERVER);
    }

    public static List<Rule> clientRules() {
        return Collections.unmodifiableList(CLIENT);
    }

    public static boolean isServerRule(int index) {
        return index >= 0 && index < SERVER.size();
    }

    public static int size() {
        return SERVER.size() + CLIENT.size();
    }

    public static int enabledCount() {
        int n = 0;
        for (Rule r : SERVER) if (r.enabled) n++;
        for (Rule r : CLIENT) if (r.enabled) n++;
        return n;
    }

    public static Rule get(int index) {
        if (index < 0) return null;
        if (index < SERVER.size()) return SERVER.get(index);
        int i = index - SERVER.size();
        return i < CLIENT.size() ? CLIENT.get(i) : null;
    }

    // ---------------------------------------------------------------- 修改（本地）

    public static boolean add(Rule rule) {
        if (rule == null || !rule.isValid()) return false;
        for (Rule existing : CLIENT) {
            if (existing.sameAs(rule)) return false;
        }
        CLIENT.add(rule.copy());
        bump();
        writeFile(clientFile(), CLIENT);
        return true;
    }

    public static void replaceOrAdd(Rule rule) {
        if (rule == null || !rule.isValid()) return;
        for (int i = 0; i < CLIENT.size(); i++) {
            Rule existing = CLIENT.get(i);
            if (existing.type.equals(rule.type)
                    && existing.target.equals(rule.target)
                    && existing.recipeType.equals(rule.recipeType)) {
                CLIENT.set(i, rule.copy());
                bump();
                writeFile(clientFile(), CLIENT);
                return;
            }
        }
        add(rule);
    }

    public static boolean updateAt(int index, Rule rule) {
        if (rule == null || !rule.isValid() || isServerRule(index)) return false;
        int i = index - SERVER.size();
        if (i < 0 || i >= CLIENT.size()) return false;
        CLIENT.set(i, rule.copy());
        bump();
        writeFile(clientFile(), CLIENT);
        return true;
    }

    public static boolean remove(int index) {
        if (isServerRule(index)) return false;
        int i = index - SERVER.size();
        if (i < 0 || i >= CLIENT.size()) return false;
        CLIENT.remove(i);
        bump();
        writeFile(clientFile(), CLIENT);
        return true;
    }

    public static boolean toggleEnabled(int index) {
        if (isServerRule(index)) return false;
        int i = index - SERVER.size();
        if (i < 0 || i >= CLIENT.size()) return false;
        CLIENT.get(i).enabled = !CLIENT.get(i).enabled;
        bump();
        writeFile(clientFile(), CLIENT);
        return true;
    }

    public static void clear() {
        if (CLIENT.isEmpty()) return;
        CLIENT.clear();
        bump();
        writeFile(clientFile(), CLIENT);
    }

    public static int clearTypeFilters() {
        int n = 0;
        for (Rule r : CLIENT) {
            if (r.hasTypeFilter()) {
                r.recipeType = "";
                n++;
            }
        }
        if (n > 0) {
            bump();
            writeFile(clientFile(), CLIENT);
        }
        return n;
    }

    /** 收到服务端下发的规则：整体替换 SERVER，不动本地那份 */
    public static void replaceFromServer(String json) {
        List<Rule> parsed;
        try {
            parsed = parse(json);
        } catch (Exception e) {
            JeiCleaner.LOGGER.error("[JEI Cleaner] 解析服务端规则失败", e);
            return;
        }
        SERVER.clear();
        SERVER.addAll(parsed);
        bump();
        JeiCleaner.LOGGER.info("[JEI Cleaner] 已接收服务端规则 {} 条", parsed.size());
    }

    /** 管理员提交：写 server.json 并替换内存 SERVER；调用方随后 apply + 广播 */
    public static void acceptServerRulesFromAdmin(String json) {
        List<Rule> parsed;
        try {
            parsed = parse(json);
        } catch (Exception e) {
            JeiCleaner.LOGGER.error("[JEI Cleaner] 管理员提交的规则解析失败", e);
            return;
        }
        SERVER.clear();
        SERVER.addAll(parsed);
        writeFile(serverFile(), SERVER);
        bump();
        JeiCleaner.LOGGER.info("[JEI Cleaner] 管理员更新服务端规则：{} 条", parsed.size());
    }

    public static void pushServerRules() {
        CleanerNetwork.CHANNEL.sendToServer(
                new CleanerNetwork.UpdateServerRulesPacket(exportJson()));
    }

    public static boolean updateServerAt(int index, Rule rule) {
        if (rule == null || !rule.isValid()) return false;
        if (index < 0 || index >= SERVER.size()) return false;
        SERVER.set(index, rule.copy());
        bump();
        return true;
    }

    public static boolean removeServerAt(int index) {
        if (index < 0 || index >= SERVER.size()) return false;
        SERVER.remove(index);
        bump();
        return true;
    }

    public static void addServerRule(Rule rule) {
        if (rule == null || !rule.isValid()) return;
        for (Rule existing : SERVER) {
            if (existing.type.equals(rule.type) && existing.target.equals(rule.target)
                    && existing.recipeType.equals(rule.recipeType)) {
                return;
            }
        }
        SERVER.add(rule.copy());
        bump();
    }

    // ---------------------------------------------------------------- 序列化

    public static String exportJson() {
        return toJsonText(SERVER, "服务端规则");
    }

    public static String exportAllJson() {
        List<Rule> merged = new ArrayList<>(SERVER);
        merged.addAll(CLIENT);
        return toJsonText(merged, "全部规则");
    }

    private static String toJsonText(List<Rule> list, String comment) {
        JsonObject root = new JsonObject();
        root.addProperty("_comment", "JEI Cleaner " + comment);
        JsonArray arr = new JsonArray();
        for (Rule rule : list) arr.add(toJson(rule));
        root.add("rules", arr);
        return GSON.toJson(root);
    }

    public static int importJson(String text) {
        if (text == null || text.isBlank()) return 0;
        List<Rule> parsed;
        try {
            parsed = parse(text);
        } catch (Exception e) {
            return 0;
        }
        int added = 0;
        for (Rule rule : parsed) {
            boolean exists = false;
            for (Rule existing : CLIENT) {
                if (existing.type.equals(rule.type) && existing.target.equals(rule.target)
                        && existing.recipeType.equals(rule.recipeType)) {
                    existing.hideOutput = rule.hideOutput;
                    existing.hideIngredient = rule.hideIngredient;
                    existing.hideItem = rule.hideItem;
                    existing.enabled = rule.enabled;
                    existing.action = rule.action;
                    exists = true;
                    break;
                }
            }
            if (!exists) {
                CLIENT.add(rule);
                added++;
            }
        }
        bump();
        writeFile(clientFile(), CLIENT);
        return added;
    }

    private static List<Rule> parse(String text) {
        List<Rule> result = new ArrayList<>();
        if (text == null || text.isBlank()) return result;
        JsonElement root = JsonParser.parseString(text);
        if (!root.isJsonObject()) return result;
        JsonArray arr = root.getAsJsonObject().has("rules")
                ? root.getAsJsonObject().getAsJsonArray("rules")
                : new JsonArray();
        for (JsonElement element : arr) {
            if (!element.isJsonObject()) continue;
            Rule rule = fromJson(element.getAsJsonObject());
            if (rule != null && rule.isValid()) result.add(rule);
        }
        return result;
    }

    private static JsonObject toJson(Rule rule) {
        JsonObject obj = new JsonObject();
        obj.addProperty("type", rule.type);
        obj.addProperty("target", rule.target);
        obj.addProperty("action", rule.action == null ? Rule.ACTION_HIDE : rule.action);

        JsonArray ranges = new JsonArray();
        for (Rule.Range range : rule.effectiveRanges()) {
            JsonObject r = new JsonObject();
            r.addProperty("type", range.type == null ? "" : range.type);

            JsonArray partners = new JsonArray();
            if (range.partners != null) {
                for (String p : range.partners) partners.add(p);
            }
            r.add("partners", partners);

            JsonArray groups = new JsonArray();
            for (java.util.List<String> group : range.groups()) {
                JsonArray g = new JsonArray();
                for (String p : group) g.add(p);
                groups.add(g);
            }
            r.add("partnerGroups", groups);
            ranges.add(r);
        }
        obj.add("ranges", ranges);
        obj.addProperty("recipeType", rule.recipeType == null ? "" : rule.recipeType);
        obj.addProperty("partner", rule.partner == null ? "" : rule.partner);
        obj.addProperty("enabled", rule.enabled);
        if (Rule.TYPE_ITEM.equals(rule.type)) {
            obj.addProperty("hideOutput", rule.hideOutput);
            obj.addProperty("hideIngredient", rule.hideIngredient);
            obj.addProperty("hideItem", rule.hideItem);
        }
        return obj;
    }

    private static Rule fromJson(JsonObject obj) {
        Rule rule = new Rule();
        rule.type = obj.has("type") ? obj.get("type").getAsString() : Rule.TYPE_ITEM;
        rule.target = obj.has("target") ? obj.get("target").getAsString() : "";
        rule.action = obj.has("action") ? obj.get("action").getAsString() : Rule.ACTION_HIDE;
        rule.recipeType = obj.has("recipeType") ? obj.get("recipeType").getAsString() : "";
        rule.partner = obj.has("partner") ? obj.get("partner").getAsString() : "";

        if (obj.has("ranges") && obj.get("ranges").isJsonArray()) {
            for (JsonElement element : obj.getAsJsonArray("ranges")) {
                if (!element.isJsonObject()) continue;
                JsonObject r = element.getAsJsonObject();
                Rule.Range range = new Rule.Range(
                        r.has("type") ? r.get("type").getAsString() : "");

                if (r.has("partners") && r.get("partners").isJsonArray()) {
                    for (JsonElement p : r.getAsJsonArray("partners")) {
                        String value = p.getAsString();
                        if (!value.isBlank()) range.partners.add(value);
                    }
                }
                if (r.has("partnerGroups") && r.get("partnerGroups").isJsonArray()) {
                    for (JsonElement ge : r.getAsJsonArray("partnerGroups")) {
                        if (!ge.isJsonArray()) continue;
                        List<String> group = new ArrayList<>();
                        for (JsonElement p : ge.getAsJsonArray()) {
                            String value = p.getAsString();
                            if (!value.isBlank()) group.add(value);
                        }
                        if (!group.isEmpty()) range.partnerGroups.add(group);
                    }
                }
                if (range.partnerGroups.isEmpty()) {
                    // 旧配置：每个 partner 各成一组（保持原来的「或」）
                    for (String partner : range.partners) {
                        List<String> group = new ArrayList<>();
                        group.add(partner);
                        range.partnerGroups.add(group);
                    }
                }
                if (range.partnerGroups.isEmpty()) range.partnerGroups.add(new ArrayList<>());
                range.syncPartners();
                rule.ranges.add(range);
            }
        }
        rule.ensureRanges();
        rule.enabled = !obj.has("enabled") || obj.get("enabled").getAsBoolean();
        rule.hideOutput = !obj.has("hideOutput") || obj.get("hideOutput").getAsBoolean();
        rule.hideIngredient = !obj.has("hideIngredient") || obj.get("hideIngredient").getAsBoolean();
        rule.hideItem = obj.has("hideItem") && obj.get("hideItem").getAsBoolean();
        return rule;
    }

    public static void save() {
        writeFile(clientFile(), CLIENT);
    }
}
