package com.yuneko.foliocleaner.viewer;

import net.minecraftforge.fml.ModList;

import java.util.ArrayList;
import java.util.List;

/**
 * 「装的是哪个配方浏览器」的**统一能力模型**。
 *
 * <p>2.0.0-test57 起，能力不再写死成「只有 JEI 能这样那样」—— 每个能力都去问
 * {@link Backends#primary()} 当前后端，因为 REI / EMI 现在也能做浏览和预览了：
 * <ul>
 *   <li><b>JEI</b>：配方列表 / 布局 / 分类 / 原料全都能拿到</li>
 *   <li><b>REI</b>：{@code DisplayRegistry.getAll()} 能枚举全部分类与配方，
 *       {@code getInputEntries/getOutputEntries} 能做物品级匹配，{@code setupDisplay} 能画预览</li>
 *   <li><b>EMI</b>：{@code EmiRecipeManager} 有 getCategories/getRecipes/getRecipesByInput，
 *       {@code addWidgets} 能画预览</li>
 * </ul>
 *
 * <p>所以界面上不该再假装「只有 JEI 才有图形界面」—— 改为按实际后端如实显示。
 */
public final class Viewers {

    public static final String JEI = "jei";
    public static final String REI = "roughlyenoughitems";
    public static final String EMI = "emi";

    private Viewers() {
    }

    private static boolean loaded(String modId) {
        try {
            return ModList.get().isLoaded(modId);
        } catch (Throwable t) {
            return false;
        }
    }

    /** 通用探测（{@link Backends} 用；三个常量之外也能查） */
    public static boolean loadedRaw(String modId) {
        return loaded(modId);
    }

    public static boolean hasJei() {
        return loaded(JEI);
    }

    public static boolean hasRei() {
        return loaded(REI);
    }

    public static boolean hasEmi() {
        return loaded(EMI);
    }

    /** 装了哪些，按固定顺序 */
    public static List<String> installed() {
        List<String> out = new ArrayList<>(3);
        if (hasJei()) out.add("JEI");
        if (hasRei()) out.add("REI");
        if (hasEmi()) out.add("EMI");
        return out;
    }

    public static String summary() {
        List<String> list = installed();
        return list.isEmpty() ? "都没有" : String.join(" / ", list);
    }

    // ---------------------------------------------------------------- 能力（问后端）

    /** 图形界面（规则编辑 / 预览 / 毒瘤排行 / 分类统计） */
    public static boolean guiAvailable() {
        return Backends.browseAvailable();
    }

    /** 按「配方 id / modid」隐藏：三家都支持 */
    public static boolean recipeIdHiding() {
        return hasJei() || hasRei() || hasEmi();
    }

    /** 按「物品 / 分类 / 工作台范围」隐藏：取决于主后端的能力 */
    public static boolean itemLevelHiding() {
        RecipeViewerBackend b = Backends.primary();
        return b != null && b.caps().apply();
    }

    /** 物品级跨 viewer（{@code c:hidden_from_recipe_viewers} 数据包 tag），三家都认 */
    public static boolean crossViewerItemTag() {
        return true;
    }

    /**
     * 给指令 / 界面用的人话清单（每行一条，已带 § 颜色）。
     */
    public static List<String> capabilityLines() {
        List<String> out = new ArrayList<>();
        out.add("§7已装：§f" + summary());

        RecipeViewerBackend b = Backends.primary();
        if (b != null) {
            out.add("§7当前后端：§f" + b.label()
                    + (b.ready() ? "§a（就绪）" : "§e（等待初始化）")
                    + "§8 · " + capsText(b.caps()));
        }

        if (guiAvailable()) {
            out.add("§a✔ 图形界面§8（/foliocleaner）· 规则编辑 / 预览 / 毒瘤排行 / 分类统计");
        } else {
            out.add("§c✘ 图形界面§8 —— 没有可用的配方浏览器；可直接编辑 config/foliocleaner/client.json");
        }

        out.add(recipeIdHiding()
                ? "§a✔ 按配方 id / modid 隐藏§8（" + summary() + "）"
                : "§c✘ 按配方 id / modid 隐藏");

        RecipeViewerBackend.Caps caps = b == null ? RecipeViewerBackend.Caps.NONE : b.caps();
        out.add(caps.apply()
                ? "§a✔ 按物品 / 分类 / 工作台范围隐藏§8（" + b.label() + "）"
                : "§c✘ 按物品 / 分类 / 工作台范围隐藏§8（当前后端不提供）");
        out.add(caps.live()
                ? "§a✔ 改完立刻生效§8（无需重开游戏）"
                : "§e… 改完规则需要重开游戏才生效");
        out.add("§a✔ 物品级跨 viewer tag §8（c:hidden_from_recipe_viewers，要生成数据包 + /reload）");

        if (hasRei()) {
            out.add(ViewerStatus.reiFilterInstalled ? "§a✔ REI 谓词已挂（实时）"
                    : ViewerStatus.reiFailed ? "§c✘ REI 谓词挂载失败（版本差异，看日志）"
                    : "§e… REI 谓词待挂（进一次世界会自动装）");
        }
        if (hasEmi()) {
            out.add(ViewerStatus.emiPluginRan
                    ? "§a✔ EMI 初始化期已注册（移除 " + ViewerStatus.emiRemoved + " 条）"
                    : "§c✘ EMI 插件未运行（看日志）");
        }
        return out;
    }

    private static String capsText(RecipeViewerBackend.Caps caps) {
        List<String> parts = new ArrayList<>(4);
        if (caps.browse()) parts.add("浏览");
        if (caps.preview()) parts.add("预览");
        if (caps.apply()) parts.add("应用");
        if (caps.live()) parts.add("实时");
        return parts.isEmpty() ? "无能力" : String.join(" / ", parts);
    }
}
