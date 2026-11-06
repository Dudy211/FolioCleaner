package com.yuneko.jeicleaner;

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
    /** 属性台：当前范围（空 = 全部） */
    public static String defaultRecipeType = "";
    /** 属性台：范围显示名 */
    public static String defaultRecipeTypeLabel = "";
    /** 属性台：搭配（旧字段，第一组的第一个） */
    public static String defaultPartner = "";
    /** 属性台：搭配显示名 */
    public static String defaultPartnerLabel = "";
    /** 属性台：完整的「多组范围」JSON（优先级高于上面两个旧字段） */
    public static String defaultRangesJson = "";

    /** 是否记住搜索栏内容 */
    public static boolean rememberSearch = true;
    /** 上次的搜索词 */
    public static String lastSearch = "";
    /** 上次的标签页 ITEM / MOD / RECIPE */
    public static String lastTab = "ITEM";

    /** 当前玩家是不是管理员（由服务端下发） */
    public static boolean admin;
    /** 管理员上次选的编辑目标：false=客户端，true=服务端 */
    public static boolean editServer;

    private static boolean dirty;

    private ClientSettings() {
    }

    public static Path file() {
        return FMLPaths.CONFIGDIR.get().resolve("jeicleaner").resolve("client-settings.json");
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
            if (obj.has("lastSearch")) lastSearch = obj.get("lastSearch").getAsString();
            if (obj.has("lastTab")) lastTab = obj.get("lastTab").getAsString();
            if (obj.has("editServer")) editServer = obj.get("editServer").getAsBoolean();
        } catch (Exception e) {
            JeiCleaner.LOGGER.warn("[JEI Cleaner] 读取界面设置失败", e);
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
            obj.addProperty("lastSearch", lastSearch);
            obj.addProperty("lastTab", lastTab);
            obj.addProperty("editServer", editServer);
            Files.writeString(path, GSON.toJson(obj), StandardCharsets.UTF_8);
            dirty = false;
        } catch (Exception e) {
            JeiCleaner.LOGGER.warn("[JEI Cleaner] 保存界面设置失败", e);
        }
    }

    public static void touch() {
        dirty = true;
    }

    public static void saveIfDirty() {
        if (dirty) save();
    }
}
