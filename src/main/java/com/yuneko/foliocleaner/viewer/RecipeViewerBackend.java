package com.yuneko.foliocleaner.viewer;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

import java.util.List;
import java.util.Set;
import java.util.Map;

/**
 * 「一种配方浏览器」的完整后端。
 *
 * <p>这一层存在的唯一理由：<b>界面（{@code CleanerScreen}）不许出现任何
 * {@code mezz.jei.*} / {@code me.shedaniel.*} / {@code dev.emi.*} 的类型</b>。
 * 否则没装那个 viewer 的玩家连类都加载不了（test53 的真实崩溃就是这么来的）。
 *
 * <p>能力差别很大，所以每个能力都单独回答「我行不行」：
 * <ul>
 *   <li>{@link Caps#browse()} —— 能枚举分类 / 配方条数 / 催化剂（毒瘤排行用）</li>
 *   <li>{@link Caps#preview()} —— 能在悬浮框里画出一条配方的布局</li>
 *   <li>{@link Caps#apply()} —— 有一套能把规则真正作用到该 viewer 的引擎</li>
 *   <li>{@link Caps#live()} —— 改完规则立刻生效（否则要重开游戏）</li>
 * </ul>
 *
 * <p>引擎类方法都给了默认实现（不支持就是"什么都不做"），这样新后端只要
 * 实现浏览部分也能跑起来。
 */
public interface RecipeViewerBackend {

    /** 内部 id：{@code jei} / {@code rei} / {@code emi} */
    String id();

    /** 界面上显示的名字（JEI / REI / EMI） */
    String label();

    /** 运行期是否真的可用（viewer 已初始化完成） */
    boolean ready();

    Caps caps();

    // ------------------------------------------------------------ 浏览

    /** 所有配方分类 + 每类的配方条数（按条数降序）。不支持返回空表。 */
    default List<Category> categories() {
        return List.of();
    }

    /** 「这类工作台用什么物品当图标」—— 范围弹窗里的催化剂列表。 */
    default List<Catalyst> catalysts() {
        return List.of();
    }

    /**
     * 该 viewer 眼里的**全部配方**（包含模组自建的、不是 {@code Recipe} 实现的那种）。
     *
     * <p>为什么必须由 viewer 提供：{@code mc.level.getRecipeManager()} 里只有真正的
     * {@code Recipe}；奥术铁砧那类「自己注册一个分类 + 4000 条自定义配方」的东西
     * 在里面一条都没有 —— 配方页看不见它，毒瘤排行也统计不到它。
     *
     * <p>可能很贵（上万条），实现方自己缓存；GUI 只在「配方页 / 毒瘤页」调用。
     */
    default List<RecipeRef> allRecipes() {
        return List.of();
    }

    /**
     * 构建一条配方的预览。拿不到（未注册 / 不是该 viewer 的配方）返回 null。
     *
     * @param recipeId 配方 id（{@code namespace:path}）
     */
    default Preview preview(String recipeId) {
        return null;
    }

    // ------------------------------------------------------------ 规则引擎

    /** 客户端每 tick 驱动（分帧任务、延迟重算）。 */
    default void tick() {
    }

    /** 换世界 / 配方重载：丢掉所有与配方对象绑定的缓存。 */
    default void invalidate() {
    }

    /** 预演一次同步会发生什么（只读） */
    default PlanPreview planPreview() {
        return null;
    }

    default void requestApplyDelayed(int ticks) {
    }

    default void requestApply() {
    }

    default void applyNow() {
    }

    /** 有改动还没同步 */
    default boolean hasPendingChanges() {
        return false;
    }

    default boolean isSyncing() {
        return false;
    }

    /** 同步进度 0..1；不在同步时返回 -1 */
    default float progress() {
        return -1f;
    }

    default boolean expectSlowSync() {
        return false;
    }

    /**
     * 这个后端的运行时状态（给 {@code /foliocleaner diagnose} 用）。
     * 没有客户端可调试时，这就是唯一的现场。
     */
    default List<String> diagnose() {
        return List.of();
    }

    /** 上一次应用的自检报告（逐行，已带 § 颜色） */
    default List<String> report() {
        return List.of();
    }

    /**
     * 「点同步时该告诉用户什么」。
     *
     * <p>返回非 null 表示这个后端的规则**不能在这里立即生效** —— 比如 EMI 的配方移除
     * 只发生在插件初始化期，改完规则必须重开游戏。界面会直接把这句话显示出来，
     * 而不是假装同步了一下。
     */
    default String applyHint() {
        return null;
    }

    default Status status() {
        return Status.NONE;
    }

    // ------------------------------------------------------------ 数据结构

    record Caps(boolean browse, boolean preview, boolean apply, boolean live) {
        public static final Caps NONE = new Caps(false, false, false, false);
        public static final Caps FULL = new Caps(true, true, true, true);
    }

    /**
     * 一个配方分类。
     *
     * @param uid   分类 id（JEI 的 {@code RecipeType.getUid()} / REI 的 CategoryIdentifier / EMI 的 category id）
     * @param title 显示名
     * @param icon  催化剂物品（可能 EMPTY）
     * @param count 该分类有多少条配方
     */
    record Category(String uid, Component title, ItemStack icon, long count) {
    }

    /**
     * viewer 视角下的一条配方（够列表显示 + 用量统计就行）。
     *
     * @param id 配方 id（可能为空串：少数自定义配方根本没有 id）
     */
    record RecipeRef(String id, String categoryUid, String modId,
                     ItemStack output, List<Set<String>> inputs, Set<String> outputIds) {
    }

    /** 一个催化剂物品 + 它能开哪些工作台（逗号分隔的 uid） */
    record Catalyst(ItemStack stack, String name, String typeUids) {
    }

    /**
     * 一条配方的可绘制预览。
     *
     * <p>实现方负责把内容画到 {@code (x, y)}（左上角），尺寸由 {@link #width()} /
     * {@link #height()} 给出。坐标原语必须是 {@code GuiGraphics} 的绝对坐标。
     */
    interface Preview {
        int width();

        int height();

        /** 画在 (x, y)；mouseX/mouseY 是屏幕绝对坐标（悬浮提示用） */
        void render(GuiGraphics gg, int x, int y, int mouseX, int mouseY);
    }

    /** 一次同步的预演结果 */
    record PlanPreview(int recipes, int items, int categories,
                       Map<String, Integer> byMod, boolean customJob) {
        public static final PlanPreview EMPTY = new PlanPreview(0, 0, 0, Map.of(), false);

        public boolean isEmpty() {
            return recipes == 0 && items == 0 && categories == 0;
        }
    }

    /** 上一次同步的结果 */
    record Status(int hiddenRecipes, int hiddenItems, long ms, boolean ok) {
        public static final Status NONE = new Status(0, 0, -1L, false);
    }
}
