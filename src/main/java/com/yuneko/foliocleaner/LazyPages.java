package com.yuneko.foliocleaner;

import net.minecraftforge.fml.ModList;

/**
 * 「分页懒布局」的可用性探测 + 开关。
 *
 * <p>这个功能要 Mixin 进 JEI 的 {@code RecipeGuiLogic.createRecipeLayoutsWithButtons}，
 * 跟 JEI 版本强耦合：改名 / 改签名 / 重构都会让注入失效。所以运行期按「接口形状」自检一遍：
 * 可用则开关能点；不可用则自动灰掉，悬浮给出具体缺了哪一块。
 *
 * <p>探测全部走字符串 + 反射，任何一项失败只会让功能降级，不会崩游戏。
 */
public final class LazyPages {

    /** 超过这个条数就切换到按页懒建布局（再小全建也不疼，留着「可合成优先」排序更好） */
    public static final int MAX_EAGER = 128;

    private static Boolean supported;
    /** JEI 自己就按页懒建布局（JEI 18/19 起）→ 我们不应该再包一层 */
    private static Boolean builtin;
    private static String builtinWhere = "";
    private static String reason = "";
    private static String jeiVersion = "?";

    private LazyPages() {
    }

    /** 功能本身在当前 JEI 版本下是否可用（不看用户开关） */
    public static boolean supported() {
        probe();
        return Boolean.TRUE.equals(supported);
    }

    /** 当前 JEI **自带**按页懒布局 —— 界面上要说明「不需要我们做」（否则用户以为我们没实现） */
    public static boolean builtin() {
        probe();
        return Boolean.TRUE.equals(builtin);
    }

    /** 命中自带的那个类名（给 tooltip 用） */
    public static String builtinWhere() {
        probe();
        return builtinWhere;
    }

    /** 不可用的原因（可用时为空串） */
    public static String reason() {
        probe();
        return reason;
    }

    /** 实际是否生效 = 版本支持 且 用户开着 且 JEI 没自带 */
    public static boolean enabled() {
        return !builtin() && supported() && ClientSettings.lazyPages;
    }

    /** 当前 JEI 版本号，给界面显示用 */
    public static String jeiVersion() {
        probe();
        return jeiVersion;
    }

    private static synchronized void probe() {
        if (supported != null) return;

        try {
            ModList.get().getModContainerById("jei")
                    .ifPresent(c -> jeiVersion = c.getModInfo().getVersion().toString());
        } catch (Throwable ignored) {
        }

        // ⓪ 先看 JEI 是不是**自带**按页懒布局：自带了就别再包一层
        //    （也会避免\"明明 JEI 自己做了，界面却显示不可用\"这种让人以为我们没做的观感）
        probeBuiltin();
        if (builtin) {
            supported = Boolean.FALSE;
            reason = "当前 JEI（" + jeiVersion + "）自带按页懒布局，本模组的补丁不需要启用";
            FolioCleaner.LOGGER.info("[Folio Cleaner] JEI 自带按页懒布局（{}），跳过分页懒布局补丁", builtinWhere);
            return;
        }

        try {
            // ① 目标方法存在，并且是 4 个参数（Set, IFocusedRecipes, AbstractContainerMenu, Player）
            Class<?> logic = Class.forName("mezz.jei.gui.recipes.RecipeGuiLogic");
            boolean hasTarget = false;
            for (var m : logic.getDeclaredMethods()) {
                if (m.getName().equals("createRecipeLayoutsWithButtons") && m.getParameterCount() == 4) {
                    hasTarget = true;
                    break;
                }
            }
            if (!hasTarget) {
                fail("当前 JEI 的 RecipeGuiLogic 里找不到 createRecipeLayoutsWithButtons（可能改签名或重构）");
                return;
            }

            // ② 我们要实现的接口还在、方法还在
            Class<?> list = Class.forName("mezz.jei.gui.recipes.layouts.IRecipeLayoutList");
            list.getMethod("subList", int.class, int.class);

            // ③ 建布局用的 API 还在（5 参数、带 IScalableDrawable 的那个重载）
            Class<?> manager = Class.forName("mezz.jei.api.recipe.IRecipeManager");
            boolean hasCreate = false;
            for (var m : manager.getMethods()) {
                if (m.getName().equals("createRecipeLayoutDrawable") && m.getParameterCount() == 5) {
                    hasCreate = true;
                    break;
                }
            }
            if (!hasCreate) {
                fail("当前 JEI 的 IRecipeManager 没有 5 参数的 createRecipeLayoutDrawable");
                return;
            }

            // ④ 配方背景纹理的取法没变
            Class.forName("mezz.jei.common.Internal").getMethod("getTextures");

            supported = Boolean.TRUE;
            reason = "";
        } catch (ClassNotFoundException e) {
            fail("找不到类 " + e.getMessage() + "（JEI 未安装或版本不兼容）");
        } catch (NoSuchMethodException e) {
            fail("缺少方法 " + e.getMessage() + "（JEI 版本不兼容）");
        } catch (Throwable t) {
            fail("自检时出错：" + t.getClass().getSimpleName() + " " + t.getMessage());
        }
    }

    private static void fail(String why) {
        supported = Boolean.FALSE;
        reason = why;
        FolioCleaner.LOGGER.info("[Folio Cleaner] 分页懒布局不可用：{}", why);
    }

    /**
     * JEI 自己有没有按页懒建布局。
     *
     * <p>真机实测结论（JEI 15.20.8.132 与 15.74.0.234 各跑一次）：
     * <ul>
     *   <li>15.20.x：{@code RecipeGuiLogic#createRecipeLayoutsWithButtons(Set,IFocusedRecipes,menu,player)}
     *       会 {@code stream().map(createRecipeLayoutDrawable).toList()} 一次性建完 → 需要我们的补丁</li>
     *   <li>15.74+：那个方法没了，改成 {@code IRecipeLayoutList.create(Set,IFocusedRecipes,IFocusGroup,
     *       BookmarkList,IRecipeManager,factory,menu)} + {@code LazyRecipeLayoutList}
     *       （{@code LAYOUT_CACHE_SIZE} / {@code ensureResults} / {@code calculateNextResult}）
     *       —— 就是官方自己做了按页懒建 → 我们不插手</li>
     * </ul>
     *
     * <p>两条判据都要有：类名是最准的；其次看 {@code subList(int,int,menu)} 的形状（15.74 多了一个菜单参数），
     * 万一以后类名再改也认得出。
     */
    private static void probeBuiltin() {
        if (builtin != null) return;

        for (String candidate : new String[]{
                "mezz.jei.gui.recipes.layouts.LazyRecipeLayoutList",
                "mezz.jei.gui.recipes.lookups.LazyRecipeLayoutList",
                "mezz.jei.gui.recipes.LazyRecipeLayoutList"}) {
            try {
                Class.forName(candidate);
                builtin = Boolean.TRUE;
                builtinWhere = candidate;
                return;
            } catch (Throwable ignored) {
            }
        }

        try {
            Class<?> list = Class.forName("mezz.jei.gui.recipes.layouts.IRecipeLayoutList");
            for (var m : list.getMethods()) {
                if (m.getName().equals("subList") && m.getParameterCount() >= 3) {
                    builtin = Boolean.TRUE;
                    builtinWhere = "IRecipeLayoutList#subList(int,int,menu)";
                    return;
                }
            }
        } catch (Throwable ignored) {
        }

        builtin = Boolean.FALSE;
        builtinWhere = "";
    }
}
