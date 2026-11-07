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
 * <p>拆成两份文件：
 * <ul>
 *   <li>{@code config/jeicleaner/server.json} —— <b>服务端权威规则</b>。服主配置，
 *       服务端据此真删配方（action=remove），并在玩家登录时整份下发给客户端。</li>
 *   <li>{@code config/jeicleaner/client.json} —— <b>客户端本地规则</b>。玩家自己加的额外隐藏，
 *       只影响自己，不会覆盖服务端下发的那份。</li>
 * </ul>
 *
 * <p>客户端实际生效 = 服务端规则 + 本地规则（合并，服务端在前）。
 * GUI 只会编辑本地那份，服务端下发的部分在界面上是只读的。
 */
public final class Rules {

    private static final Gson GSON = new GsonBuilder()
            .setPrettyPrinting()
            .disableHtmlEscaping()
            .create();

    /** 服务端权威规则（专用服务器读 server.json；客户端由网络包填充） */
    private static final List<Rule> SERVER = new ArrayList<>();
    /** 客户端本地规则（client.json，GUI 编辑的是这份） */
    private static final List<Rule> CLIENT = new ArrayList<>();

    /** 每次规则变动 +1 */
    private static int revision;
    /** 规则内容指纹，{@link #bump()} 时重算；空规则列表的值是 7 */
    private static int contentHash = 7;

    private Rules() {
    }

    public static int revision() {
        return revision;
    }

    private static void bump() {
        revision++;
        contentHash = computeContentHash();
    }

    /**
     * 规则「内容」的指纹（不含 revision）。
     *
     * <p>典型场景：加一条规则又立刻删掉 —— revision 变了，但内容回到原样，
     * 这时不应该提示「未同步」。
     */
    public static int contentHash() {
        return contentHash;
    }

    private static int computeContentHash() {
        int h = 7;
        for (Rule rule : all()) {
            h = h * 31 + (rule == null ? 0 : rule.contentHash());
        }
        return h;
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

    /** 旧版单文件路径，用于自动迁移 */
    private static Path legacyFile() {
        return FMLPaths.CONFIGDIR.get().resolve("jeicleaner.json");
    }

    // ---------------------------------------------------------------- 读取

    /** 只读服务端那份（服务端删除配方时用） */
    public static void loadServerRules() {
        SERVER.clear();
        bump();
        migrateLegacy();
        SERVER.addAll(readFile(serverFile()));
        if (!Files.isRegularFile(serverFile())) {
            writeFile(serverFile(), SERVER);
        }
    }

    /** 只读客户端那份（GUI 编辑的那份） */
    public static void loadClientRules() {
        CLIENT.clear();
        bump();
        CLIENT.addAll(readFile(clientFile()));
        if (!Files.isRegularFile(clientFile())) {
            writeFile(clientFile(), CLIENT);
        }
    }

    /** 双端通用：两份都读 */
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
                            + "action = hide（JEI 隐藏）/ remove（服务端真删）；recipeType 留空表示不限工作台。");
            JsonArray arr = new JsonArray();
            for (Rule rule : rules) arr.add(toJson(rule));
            root.add("rules", arr);
            Files.writeString(path, GSON.toJson(root), StandardCharsets.UTF_8);
        } catch (IOException e) {
            JeiCleaner.LOGGER.error("[JEI Cleaner] 写入 {} 失败", path, e);
        }
    }

    // ---------------------------------------------------------------- 查询

    /** 实际生效的规则：本地 + 服务端（服务端在前，方便看哪些是强制的） */
    public static List<Rule> all() {
        List<Rule> merged = new ArrayList<>(SERVER.size() + CLIENT.size());
        merged.addAll(SERVER);
        merged.addAll(CLIENT);
        return Collections.unmodifiableList(merged);
    }

    /** 服务端那份（真删配方用） */
    public static List<Rule> serverRules() {
        return Collections.unmodifiableList(SERVER);
    }

    /** 本地那份（GUI 编辑用） */
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

    /** 按 all() 的下标取 —— 前 SERVER.size() 条是服务端规则 */
    public static Rule get(int index) {
        if (index < 0) return null;
        if (index < SERVER.size()) return SERVER.get(index);
        int i = index - SERVER.size();
        return i < CLIENT.size() ? CLIENT.get(i) : null;
    }

    // ---------------------------------------------------------------- 修改（只动本地那份）

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

    /** 只允许删除本地规则；服务端下发的不让改 */
    /** 按 all() 的下标原地替换一条本地规则（改 recipeType 也不会变成新增） */
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

    /** 把所有规则的「工作台范围」清空（只作用于本地规则） */
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

    /**
     * 服务端收到管理员提交的规则：写进 server.json，并替换内存里的 SERVER。
     * 调用方负责随后执行 {@code RecipeRemover.apply} 和广播。
     */
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

    /** 管理员模式下，把本地编辑结果同步给服务端（由 GUI 调用） */
    public static void pushServerRules() {
        CleanerNetwork.CHANNEL.sendToServer(
                new CleanerNetwork.UpdateServerRulesPacket(exportJson()));
    }

    /** 直接改 SERVER 列表里的某一条（管理员 GUI 用，改完调用 {@link #pushServerRules()}） */
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
        // 新模型：多组范围（旧字段同时写一份，供旧版本 / 指令读取）
        JsonArray ranges = new JsonArray();
        for (Rule.Range range : rule.effectiveRanges()) {
            JsonObject r = new JsonObject();
            r.addProperty("type", range.type == null ? "" : range.type);
            JsonArray partners = new JsonArray();
            if (range.partners != null) {
                for (String p : range.partners) partners.add(p);
            }
            r.add("partners", partners);

            // 新：外层「或」，内层「与」
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

    // 兼容旧调用
    public static void save() {
        writeFile(clientFile(), CLIENT);
    }
}
