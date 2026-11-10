package com.yuneko.foliocleaner;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraftforge.fml.loading.FMLPaths;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 客户端界面偏好（不是规则）。
 *
 * <p>存在 {@code config/jeicleaner/client-settings.json}，重启后保持。
 */
public final class ClientSettings {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    /** 属性台：产物开关 */
    public static boolean defaultOutput = true;
    /** 属性台：原料开关 */
    public static boolean defaultIngredient = true;
    /** 属性台：hide / remove */
    public static String defaultAction = Rule.ACTION_HIDE;
    /** 属性台：当前范围（空 = 全部，逗号分隔可多选） */
    public static String defaultRecipeType = "";
    /** 属性台：范围显示名 */
    public static String defaultRecipeTypeLabel = "";
    /** 属性台：搭配（空 = 不限；物品 id 或 #标签） */
    public static String defaultPartner = "";
    /** 属性台：搭配显示名 */
    public static String defaultPartnerLabel = "";
    /** 属性台：完整的「多组范围」JSON（优先级高于上面两个旧字段） */
    public static String defaultRangesJson = "";

    /** 是否记住搜索栏内容 */
    public static boolean rememberSearch = true;
    /** 标题栏显示「无配方物品」数量提示（只提示，不生成任何规则） */
    public static boolean hintDeadItems = false;
    /** 点「同步」时跳过预览弹窗直接执行（熟练用户用；预览里勾「不再显示」也会打开它） */
    public static boolean skipPreview = false;
    /**
     * 统一的实现方式（三选一）：
     * <ul>
     *   <li>{@code datapack}：生成数据包改写配方（最干净，只覆盖有 json 的配方）</li>
     *   <li>{@code rewrite}：运行时改配方对象（覆盖更广，部分配方改不了）</li>
     *   <li>{@code match}：运行时补匹配（覆盖一切，需双端安装，全局行为改变）</li>
     * </ul>
     */
    public static String unifyMode = "rewrite";
    /** 生成数据包后自动 /reload（默认开；多人服由 OP 手动执行） */
    public static boolean autoReload = true;
    /** 交叉物品用「双 tag 临时 tag」包装（默认开） */
    public static boolean dupCrossTag = true;
    /** 分页懒布局：大分类只建当前页的布局（见 {@link LazyPages}） */
    public static boolean lazyPages = true;
    /** 规则改完自动同步；关掉后必须点「同步」按钮才生效（默认关，避免编辑时反复全量扫描） */
    public static boolean autoSync = false;
    /** 大分类「预算内排序」：只排前 128 条，顺序只在前几页准确，但构建量有硬上限（默认关） */
    public static boolean lazySort = false;
    /** 上次的搜索词 */
    public static String lastSearch = "";
    /** 上次的标签页 ITEM / MOD / RECIPE */
    public static String lastTab = "ITEM";

    /** 调试面板上次的位置（-1 = 还没保存过） */
    public static int debugPanelX = -1;
    public static int debugPanelY = -1;
    /** 调试面板上次是开还是关（进游戏自动恢复；改完不用每次重按 F7） */
    public static boolean debugPanelOpen = false;

    /** 调试面板：在任何容器界面上叠加槽位编号 */
    public static boolean debugOverlay = false;
    /** 适配器演练模式：命中只打日志，不清结果槽（作者验证用） */
    public static boolean adapterDryRun = false;

    /** 被关掉的合成守卫适配器 id（空 = 全开） */
    public static final java.util.List<String> disabledGuards = new java.util.ArrayList<>();

    /** 当前玩家是不是管理员（由服务端下发） */
    public static boolean admin;
    /** 管理员上次选的编辑目标：false=客户端，true=服务端 */
    public static boolean editServer;

    private static boolean dirty;

    private ClientSettings() {
    }

    public static Path file() {
        return FMLPaths.CONFIGDIR.get().resolve("foliocleaner").resolve("client-settings.json");
    }

    public static void load() {
        Path path = file();
        if (!Files.isRegularFile(path)) return;
        try {
            String text = Files.readString(path, StandardCharsets.UTF_8);
            var root = JsonParser.parseString(text);
            if (!root.isJsonObject()) return;
            JsonObject obj = root.getAsJsonObject();
            if (obj.has("defaultOutput")) defaultOutput = obj.get("defaultOutput").getAsBoolean();
            if (obj.has("defaultIngredient")) defaultIngredient = obj.get("defaultIngredient").getAsBoolean();
            if (obj.has("defaultAction")) defaultAction = obj.get("defaultAction").getAsString();
            if (obj.has("defaultRecipeType")) defaultRecipeType = obj.get("defaultRecipeType").getAsString();
            if (obj.has("defaultRecipeTypeLabel")) defaultRecipeTypeLabel = obj.get("defaultRecipeTypeLabel").getAsString();
            if (obj.has("defaultPartner")) defaultPartner = obj.get("defaultPartner").getAsString();
            if (obj.has("defaultPartnerLabel")) defaultPartnerLabel = obj.get("defaultPartnerLabel").getAsString();
            if (obj.has("defaultRangesJson")) defaultRangesJson = obj.get("defaultRangesJson").getAsString();
            if (obj.has("rememberSearch")) rememberSearch = obj.get("rememberSearch").getAsBoolean();
            if (obj.has("hintDeadItems")) hintDeadItems = obj.get("hintDeadItems").getAsBoolean();
            if (obj.has("skipPreview")) skipPreview = obj.get("skipPreview").getAsBoolean();
            if (obj.has("unifyMode")) unifyMode = obj.get("unifyMode").getAsString();
            // 三种模式都实现了；只有拼错的值才归一
            if (!"datapack".equals(unifyMode) && !"rewrite".equals(unifyMode) && !"match".equals(unifyMode)) {
                FolioCleaner.LOGGER.info("[Folio Cleaner] 未知的统一实现方式 {}，回退到 rewrite", unifyMode);
                unifyMode = "rewrite";
            }
            if (obj.has("autoReload")) autoReload = obj.get("autoReload").getAsBoolean();
            if (obj.has("dupCrossTag")) dupCrossTag = obj.get("dupCrossTag").getAsBoolean();
            if (obj.has("lazyPages")) lazyPages = obj.get("lazyPages").getAsBoolean();
            if (obj.has("autoSync")) autoSync = obj.get("autoSync").getAsBoolean();
            if (obj.has("lazySort")) lazySort = obj.get("lazySort").getAsBoolean();
            if (obj.has("lastSearch")) lastSearch = obj.get("lastSearch").getAsString();
            if (obj.has("lastTab")) lastTab = obj.get("lastTab").getAsString();
            if (obj.has("editServer")) editServer = obj.get("editServer").getAsBoolean();
            if (obj.has("debugOverlay")) debugOverlay = obj.get("debugOverlay").getAsBoolean();
            if (obj.has("adapterDryRun")) adapterDryRun = obj.get("adapterDryRun").getAsBoolean();
            if (obj.has("debugPanelX")) debugPanelX = obj.get("debugPanelX").getAsInt();
            if (obj.has("debugPanelY")) debugPanelY = obj.get("debugPanelY").getAsInt();
            if (obj.has("debugPanelOpen")) debugPanelOpen = obj.get("debugPanelOpen").getAsBoolean();
            disabledGuards.clear();
            if (obj.has("disabledGuards") && obj.get("disabledGuards").isJsonArray()) {
                for (var el : obj.getAsJsonArray("disabledGuards")) {
                    if (el.isJsonPrimitive()) disabledGuards.add(el.getAsString());
                }
            }
        } catch (Exception e) {
            FolioCleaner.LOGGER.warn("[Folio Cleaner] 读取界面设置失败", e);
        }
    }

    public static void save() {
        Path path = file();
        try {
            Files.createDirectories(path.getParent());
            JsonObject obj = new JsonObject();
            obj.addProperty("defaultOutput", defaultOutput);
            obj.addProperty("defaultIngredient", defaultIngredient);
            obj.addProperty("defaultAction", defaultAction);
            obj.addProperty("defaultRecipeType", defaultRecipeType);
            obj.addProperty("defaultRecipeTypeLabel", defaultRecipeTypeLabel);
            obj.addProperty("defaultPartner", defaultPartner);
            obj.addProperty("defaultPartnerLabel", defaultPartnerLabel);
            obj.addProperty("defaultRangesJson", defaultRangesJson);
            obj.addProperty("rememberSearch", rememberSearch);
            obj.addProperty("hintDeadItems", hintDeadItems);
            obj.addProperty("skipPreview", skipPreview);
            obj.addProperty("unifyMode", unifyMode);
            obj.addProperty("autoReload", autoReload);
            obj.addProperty("dupCrossTag", dupCrossTag);
            obj.addProperty("lazyPages", lazyPages);
            obj.addProperty("autoSync", autoSync);
            obj.addProperty("lazySort", lazySort);
            obj.addProperty("lastSearch", lastSearch);
            obj.addProperty("lastTab", lastTab);
            obj.addProperty("editServer", editServer);
            obj.addProperty("debugOverlay", debugOverlay);
            obj.addProperty("adapterDryRun", adapterDryRun);
            obj.addProperty("debugPanelX", debugPanelX);
            obj.addProperty("debugPanelY", debugPanelY);
            obj.addProperty("debugPanelOpen", debugPanelOpen);
            var disabledArr = new com.google.gson.JsonArray();
            for (String id : disabledGuards) disabledArr.add(id);
            obj.add("disabledGuards", disabledArr);
            Files.writeString(path, GSON.toJson(obj), StandardCharsets.UTF_8);
            dirty = false;
        } catch (Exception e) {
            FolioCleaner.LOGGER.warn("[Folio Cleaner] 保存界面设置失败", e);
        }
    }

    public static void touch() {
        dirty = true;
    }

    public static void saveIfDirty() {
        if (dirty) save();
    }
}
