package com.yuneko.jeicleaner.client;

import com.yuneko.jeicleaner.Cleaner;
import com.yuneko.jeicleaner.ClientSettings;
import com.yuneko.jeicleaner.ItemStats;
import com.yuneko.jeicleaner.LazyPages;
import com.yuneko.jeicleaner.client.CategoryStats;
import com.yuneko.jeicleaner.Rule;
import com.yuneko.jeicleaner.Rules;
import mezz.jei.api.gui.IRecipeLayoutDrawable;
import mezz.jei.api.ingredients.IIngredientType;
import mezz.jei.api.recipe.IFocus;
import mezz.jei.api.recipe.IFocusGroup;
import mezz.jei.api.recipe.IRecipeManager;
import mezz.jei.api.recipe.RecipeIngredientRole;
import mezz.jei.api.recipe.RecipeType;
import mezz.jei.api.recipe.category.IRecipeCategory;
import mezz.jei.api.runtime.IJeiRuntime;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Renderable;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.Recipe;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;

/**
 * JEI Cleaner 主界面。
 *
 * <p>左侧是候选列表（物品 / 模组 / 配方）；中间「属性台」设置新规则的默认属性
 * （产物 / 原料开关 + 隐藏 / 删除模式）；右侧是已有规则，点行尾 × 删除、
 * 点行其他位置打开编辑弹窗。顶部「范围」按钮可以选择只作用于某一种工作台（配方类型）。
 */
public class CleanerScreen extends Screen {

    private enum Tab {
        ITEM("物品"), MOD("模组"), RECIPE("配方"), USAGE("毒瘤");

        final String label;

        Tab(String label) {
            this.label = label;
        }
    }

    private record Entry(Tab tab, String id, Component name, String sub, ItemStack stack) {
    }

    private static final int ROW_H = 22;
    private static final int RULE_ROW_H = 18;
    /** 中间属性台固定宽度 */
    private static final int MID_W = 96;
    /** 规则编辑弹窗尺寸 */
    private static final int EDIT_W = 220;
    private static final int EDIT_H = 140;

    private static final int COLOR_PANEL = 0xF0181C26;
    private static final int COLOR_EDGE = 0xFF3E4A63;
    private static final int COLOR_HEADER = 0xFF242C3E;
    private static final int COLOR_HOVER = 0x33FFC46B;
    private static final int COLOR_ALT = 0x16FFFFFF;
    private static final int COLOR_TEXT = 0xFFF0F3FA;
    /** 副标题 / 说明文字：比斑马纹底色明显亮，避免糊在一起 */
    private static final int COLOR_DIM = 0xFFAEB8CC;
    /** 更弱的提示（不许与行底色接近） */
    private static final int COLOR_FAINT = 0xFF7C879C;
    /** 数值 / 范围这类信息文字 */
    private static final int COLOR_INFO = 0xFF8FC7FF;
    private static final int COLOR_ACCENT = 0xFFFFC46B;
    private static final int COLOR_OK = 0xFF7CD992;
    private static final int COLOR_OFF = 0xFF7C879C;

    private Tab tab = Tab.ITEM;
    private EditBox search;
    private String searchText = "";

    private final List<Entry> entries = new ArrayList<>();
    private final List<Entry> filtered = new ArrayList<>();
    /** 属性台可点击区域（每帧绘制时重算，点击时按 action 派发） */
    private final List<PropHit> propHits = new ArrayList<>();
    /** 配方预览缓存：配方 id → JEI 布局（hover 每帧复用，避免反复构建） */
    private final Map<String, IRecipeLayoutDrawable<?>> previewCache = new HashMap<>();

    /** 空 focus：构建预览布局时不需要任何搜索焦点 */
    private static final IFocusGroup EMPTY_FOCUS = new IFocusGroup() {
        @Override
        public boolean isEmpty() {
            return true;
        }

        @Override
        public List<IFocus<?>> getAllFocuses() {
            return List.of();
        }

        @Override
        public Stream<IFocus<?>> getFocuses(RecipeIngredientRole role) {
            return Stream.empty();
        }

        @Override
        public <T> Stream<IFocus<T>> getFocuses(IIngredientType<T> type) {
            return Stream.empty();
        }

        @Override
        public <T> Stream<IFocus<T>> getFocuses(IIngredientType<T> type, RecipeIngredientRole role) {
            return Stream.empty();
        }
    };
    private boolean entriesLoaded;
    private Tab entriesLoadedFor;

    private int listScroll;
    private int ruleScroll;

    /** 新规则默认值（属性台）：产物开关 / 原料开关 / 隐藏或删除 */
    private boolean defaultOutput = true;
    private boolean includeIngredient = true;
    private String defaultAction = Rule.ACTION_HIDE;

    /** 设置弹窗 */
    private boolean settingsOpen;
    /** 优化设置弹窗（性能相关，和界面偏好分开） */
    private boolean optimizeOpen;
    /** 范围弹窗当前是在给哪条规则改（-1 = 属性台默认值） */
    private int pickerForRule = -1;

    /** 管理员：当前编辑目标是不是服务端那份 */
    private boolean editServer;
    /** 添加物品规则时是否把物品本体也从 JEI 摘掉（底部「物品」按钮） */
    private boolean hideItemToo = false;

    /** true = 模组页点击添加的是「白名单」规则（只保留这个模组） */
    private boolean keepMode = false;

    /** 物品页排序：false = 按 id，true = 按「被多少条配方使用」降序 */
    private boolean sortByUsage = false;

    // ---------------------------------------------------------------- 毒瘤排行（页签）

    /** 排行只留前 N 名：藏掉一个，下次重排会自动补上新的，不用一次列几万个 */
    private static final int TOP_LIMIT = 100;
    /** 毒瘤页点一行生成什么规则：hide / remove（管理员可切） */
    private String topAction = Rule.ACTION_HIDE;
    /** 毒瘤页两种视角：0 = 按物品（只统计 MC 配方），1 = 按分类（含模组自建配方） */
    private int usageMode;
    /** 分类视角的缓存 */
    private List<CategoryStats.Row> usageCategories = List.of();
    /** 上次加载排行时的 ItemStats.signature()，用来判断要不要重排 */
    private int usageSig;

    /** 空 = 不限配方类型；可以是逗号分隔的多个类型（按工作方块选择时） */
    private String selectedType = "";
    /** 范围按钮上显示的短名 */
    private String selectedLabel = "";
    private boolean typePickerOpen;
    /** 0 = 按配方类型；1 = 按工作方块 */
    private int pickerMode;
    private int typeScroll;
    private Button typeButton;
    /** 范围编辑器：多组「类型 + 搭配」（替换原来的单范围 + 单搭配） */
    /** 列表滑条拖动状态 */
    private boolean draggingListBar;
    private boolean draggingRuleBar;
    private boolean draggingPartnerBar;

    private boolean rangeEditorOpen;
    /** -1 = 编辑属性台模板；>=0 = 编辑某条规则 */
    private int rangeEditorFor = -1;
    private int rangeEditorScroll;
    /** 打开类型 / 搭配选择器时，作用到第几组（范围组） */
    private int rangePickerIndex;
    /** 搭配选择器：-1 = 新建一个「或」组；>=0 = 加入第几个「与」组 */
    private int partnerTargetGroup = -1;
    /** 属性台模板：新规则默认用的范围组 */
    private final List<Rule.Range> defaultRanges = new ArrayList<>();

    /** 搭配（第二个范围）选择弹窗 */
    private boolean partnerPickerOpen;
    private int partnerScroll;
    /** 0 = 类别(#标签)  1 = 物品 */
    private int partnerTab;
    private int partnerForRule = -1;
    private EditBox partnerSearch;
    private String partnerSearchText = "";
    private static List<PartnerRow> partnerRowCache;

    /** 搭配候选：value 空 = 不限；#xxx = 物品标签；其余 = 物品 id */
    private record PartnerRow(String value, String label, String sub, ItemStack stack) {
    }

    /** 范围弹窗里的搜索框（不进 renderables，手动渲染与点击，关闭时挪到屏外） */
    private EditBox pickerSearch;
    private String pickerSearchText = "";
    /** 规则编辑弹窗：打开时显示并编辑 ruleEditIndex 指向的规则 */
    private boolean ruleEditOpen;
    private int ruleEditIndex;
    private static List<String> recipeTypes;
    private static List<CatalystEntry> catalystCache;
    private static Object catalystCacheKey;

    private record CatalystEntry(ItemStack stack, String name, String types) {
    }

    private record Row(String label, String types, ItemStack stack, boolean selected, int kind) {
    }

    private String status = "";
    private long statusUntil;

    private Button tabItemButton;
    private Button tabModButton;
    private Button tabRecipeButton;
    private Button tabUsageButton;
    private Button ingredientButton;
    private Button itemButton;

    public CleanerScreen() {
        super(Component.literal("JEI Cleaner"));
    }

    // ---------------------------------------------------------------- 布局

    private int panelW() {
        return Math.min(this.width - 8, 640);
    }

    private int panelH() {
        return Math.min(this.height - 8, 380);
    }

    private int panelX() {
        return (this.width - panelW()) / 2;
    }

    private int panelY() {
        return (this.height - panelH()) / 2;
    }

    private int listX() {
        return panelX() + 8;
    }

    /** 左栏：原来的 55% 改成 44%（中间固定 96px 属性台） */
    private int listW() {
        return Math.max(40, (int) ((panelW() - 26) * 0.44));
    }

    private int listY() {
        return panelY() + 62;
    }

    private int listH() {
        return Math.max(40, panelH() - 62 - 30);
    }

    /** 中间属性台左缘 */
    private int midX() {
        return listX() + listW() + 8;
    }

    private int ruleX() {
        return midX() + MID_W + 8;
    }

    private int ruleW() {
        return Math.max(30, panelX() + panelW() - 8 - ruleX());
    }

    private int footerY() {
        return panelY() + panelH() - 24;
    }

    private int visibleRows() {
        return Math.max(1, listH() / ROW_H);
    }

    private int visibleRuleRows() {
        return Math.max(1, listH() / RULE_ROW_H);
    }

    // ---------------------------------------------------------------- 初始化

    @Override
    protected void init() {
        super.init();

        // 从 client-settings.json 恢复界面偏好
        this.defaultOutput = ClientSettings.defaultOutput;
        this.includeIngredient = ClientSettings.defaultIngredient;
        this.defaultAction = ClientSettings.defaultAction;
        this.editServer = ClientSettings.admin && ClientSettings.editServer;
        if (this.selectedType.isEmpty() && !ClientSettings.defaultRecipeType.isEmpty()) {
            this.selectedType = ClientSettings.defaultRecipeType;
            this.selectedLabel = ClientSettings.defaultRecipeTypeLabel;
        }
        if (defaultRanges.isEmpty() && !ClientSettings.defaultRangesJson.isBlank()) {
            parseRanges(ClientSettings.defaultRangesJson, defaultRanges);
        }
        if (defaultRanges.isEmpty()) {
            Rule.Range first = new Rule.Range(selectedType);
            if (ClientSettings.defaultPartner != null && !ClientSettings.defaultPartner.isBlank()) {
                first.partnerGroups.add(new ArrayList<>(List.of(ClientSettings.defaultPartner)));
                first.syncPartners();
            }
            defaultRanges.add(first);
        }
        if (ClientSettings.rememberSearch && this.searchText.isEmpty()) {
            this.searchText = ClientSettings.lastSearch;
        }
        int tabY = panelY() + 22;
        // 4 个页签挤一行：36 宽 + 2 间隙，4 格用到 158px
        // （手机上 GUI scale 4 时 panelW 只有 262，留够右边两个按钮的位置）
        int tabW = 36;
        int tabGap = 2;
        int tx = panelX() + 8;

        this.tabItemButton = addRenderableWidget(Button.builder(Component.literal(Tab.ITEM.label), b -> switchTab(Tab.ITEM))
                .bounds(tx, tabY, tabW, 16).build());
        this.tabModButton = addRenderableWidget(Button.builder(Component.literal(Tab.MOD.label), b -> switchTab(Tab.MOD))
                .bounds(tx + (tabW + tabGap), tabY, tabW, 16).build());
        this.tabRecipeButton = addRenderableWidget(Button.builder(Component.literal(Tab.RECIPE.label), b -> switchTab(Tab.RECIPE))
                .bounds(tx + (tabW + tabGap) * 2, tabY, tabW, 16).build());
        this.tabUsageButton = addRenderableWidget(Button.builder(Component.literal(Tab.USAGE.label), b -> switchTab(Tab.USAGE))
                .bounds(tx + (tabW + tabGap) * 3, tabY, tabW, 16).build());

        int settingsW = 34;
        int settingsX = panelX() + panelW() - 8 - settingsW;

        if (ClientSettings.admin) {
            addRenderableWidget(Button.builder(editTargetLabel(), b -> {
                editServer = !editServer;
                ClientSettings.editServer = editServer;
                ClientSettings.touch();
                b.setMessage(editTargetLabel());
            }).bounds(settingsX - 3 - 52, tabY, 52, 16).build());
        }

        addRenderableWidget(Button.builder(Component.literal("设置"), b -> {
            settingsOpen = !settingsOpen;
            if (settingsOpen) {
                closeTypePicker();
                closePartnerPicker();
                ruleEditOpen = false;
            }
        }).bounds(settingsX, tabY, settingsW, 16).build());

        int optimizeW = 56;
        addRenderableWidget(Button.builder(Component.literal("优化设置"), b -> {
            optimizeOpen = !optimizeOpen;
            if (optimizeOpen) {
                settingsOpen = false;
                closeTypePicker();
                closePartnerPicker();
                ruleEditOpen = false;
                rangeEditorOpen = false;
            }
        }).bounds(settingsX - 3 - optimizeW, tabY, optimizeW, 16).build());


        this.search = new EditBox(this.font, listX(), panelY() + 42, listW(), 16, Component.literal("搜索"));
        this.search.setHint(Component.literal("搜索名称 / 拼音首字母 / @modid …"));
        this.search.setMaxLength(80);
        this.search.setValue(searchText);
        this.search.setResponder(text -> {
            searchText = text;
            rebuildFiltered();
        });
        addRenderableWidget(this.search);

        // 范围弹窗里的搜索框：关闭时放在屏外，打开时定位到弹窗内
        this.pickerSearch = new EditBox(this.font, 0, -200, 210, 14, Component.literal("搜索"));
        this.pickerSearch.setHint(Component.literal("搜索类型 / 方块 …"));
        this.pickerSearch.setMaxLength(80);
        this.pickerSearch.setValue(pickerSearchText);
        // 加进 children（键盘事件才会转发给它），但故意不加进 renderables —— 列表渲染时手动画
        this.addWidget(this.pickerSearch);
        this.pickerSearch.setResponder(text -> {
            pickerSearchText = text;
            typeScroll = 0;
        });
        if (typePickerOpen) positionPickerSearch();

        // 搭配弹窗的搜索框：同样故意不加进 renderables
        this.partnerSearch = new EditBox(this.font, 0, -200, 210, 14, Component.literal("搜索搭配"));
        this.partnerSearch.setHint(Component.literal("搜索物品 / 标签 …"));
        this.partnerSearch.setMaxLength(80);
        this.partnerSearch.setValue(partnerSearchText);
        this.addWidget(this.partnerSearch);
        this.partnerSearch.setResponder(text -> {
            partnerSearchText = text;
            partnerScroll = 0;
        });
        if (partnerPickerOpen) positionPartnerSearch();

        int fy = footerY();
        this.ingredientButton = addRenderableWidget(Button.builder(ingredientLabel(), b -> {
            includeIngredient = !includeIngredient;
            b.setMessage(ingredientLabel());
        }).bounds(listX(), fy, 62, 18).build());

        this.itemButton = addRenderableWidget(Button.builder(itemLabel(), b -> {
            hideItemToo = !hideItemToo;
            b.setMessage(itemLabel());
        }).bounds(listX() + 66, fy, 62, 18).build());

        addRenderableWidget(Button.builder(sortLabel(), b -> {
            sortByUsage = !sortByUsage;
            b.setMessage(sortLabel());
            entriesLoaded = false;
            ensureEntries();
            rebuildFiltered();
        }).bounds(listX() + 132, fy, 66, 18).build());

        int rx = panelX() + panelW() - 8;
        addRenderableWidget(Button.builder(Component.literal("§e同步"), b -> {
            if (!Cleaner.hasPendingChanges()) {
                setStatus("规则已经是最新的");
                return;
            }
            Cleaner.applyPending();
            setStatus("正在同步规则…");
        }).bounds(rx - 49 * 5 - 16, fy, 46, 18).build());

        addRenderableWidget(Button.builder(Component.literal("导出"), b -> {
            Minecraft.getInstance().keyboardHandler.setClipboard(Rules.exportJson());
            setStatus("规则已复制到剪贴板");
        }).bounds(rx - 49 * 4 - 12, fy, 46, 18).build());

        addRenderableWidget(Button.builder(Component.literal("清空"), b -> {
            Rules.clear();
            setStatus("已清空全部规则");
        }).bounds(rx - 49 * 3 - 8, fy, 46, 18).build());

        addRenderableWidget(Button.builder(Component.literal("重读"), b -> {
            Rules.load();
            entriesLoaded = false;
            setStatus("已重新读取 " + Rules.size() + " 条规则");
        }).bounds(rx - 49 * 2 - 4, fy, 46, 18).build());

        addRenderableWidget(Button.builder(Component.literal("刷新"), b -> {
            entriesLoaded = false;
            Cleaner.requestApply();
            setStatus("已重新计算");
        }).bounds(rx - 49, fy, 46, 18).build());

        updateTabButtons();
        ensureEntries();
        rebuildFiltered();
    }

    private Component ingredientLabel() {
        return Component.literal("原料:" + (includeIngredient ? "§a开" : "§7关"));
    }

    private Component itemLabel() {
        return Component.literal("物品:" + (hideItemToo ? "§a隐" : "§7显"));
    }

    private Component typeLabel() {
        String text;
        if (!selectedLabel.isEmpty()) {
            text = selectedLabel.length() > 11 ? selectedLabel.substring(0, 10) + "…" : selectedLabel;
        } else if (!selectedType.isEmpty()) {
            text = shortType(selectedType);
        } else {
            text = "全部";
        }
        return Component.literal("§7范围: §f" + text + " ▾");
    }

    private void refreshTypeButton() {
        if (typeButton != null) typeButton.setMessage(typeLabel());
    }

    private Component sortLabel() {
        return Component.literal("排序:" + (sortByUsage ? "§d用途" : "§7名称"));
    }

    private Component editTargetLabel() {
        return Component.literal("§7改:" + (editServer ? "§c服务端" : "§a客户端"));
    }

    private Component keepLabel() {
        return Component.literal("模组:" + (keepMode ? "§d仅保留" : "§c隐藏"));
    }

    private static String shortType(String id) {
        String s = id;
        int idx = s.indexOf(':');
        if (idx >= 0) s = s.substring(idx + 1);
        return s.length() > 13 ? s.substring(0, 12) + "…" : s;
    }

    private void switchTab(Tab newTab) {
        if (this.tab == newTab) return;
        this.tab = newTab;
        this.listScroll = 0;
        this.searchText = "";
        if (search != null) search.setValue("");
        updateTabButtons();
        ensureEntries();
        rebuildFiltered();
    }

    private void updateTabButtons() {
        if (tabItemButton != null) tabItemButton.active = tab != Tab.ITEM;
        if (tabModButton != null) tabModButton.active = tab != Tab.MOD;
        if (tabRecipeButton != null) tabRecipeButton.active = tab != Tab.RECIPE;
        if (tabUsageButton != null) tabUsageButton.active = tab != Tab.USAGE;
        // 「原料 / 物品」只在物品页有意义：毒瘤页的动作是固定的（只按原料下手），
        // 留着这两个开关反而误导，收起
        boolean itemTab = tab == Tab.ITEM;
        if (ingredientButton != null) ingredientButton.visible = itemTab;
        if (itemButton != null) itemButton.visible = itemTab;
    }

    private void setStatus(String message) {
        this.status = message;
        this.statusUntil = System.currentTimeMillis() + 4000L;
    }

    // ---------------------------------------------------------------- 数据

    private void ensureEntries() {
        if (entriesLoaded && entriesLoadedFor == tab) return;
        entries.clear();
        previewCache.clear();
        entriesLoadedFor = tab;
        switch (tab) {
            case ITEM -> loadItems();
            case USAGE -> loadUsage();
            case MOD -> loadMods();
            case RECIPE -> loadRecipes();
        }
        entriesLoaded = true;
        listScroll = 0;
    }

    private void loadItems() {
        // 只有「按用量排序」才需要全量统计；平时不白算这一遍（几万条配方 × 每条规则匹配）
        if (sortByUsage) ItemStats.build();
        List<Entry> list = new ArrayList<>();
        for (ResourceLocation id : BuiltInRegistries.ITEM.keySet()) {
            Item item = BuiltInRegistries.ITEM.get(id);
            if (item == Items.AIR) continue;
            ItemStack stack = new ItemStack(item);
            int used = ItemStats.ready() ? ItemStats.usage(item) : 0;
            String sub = used > 0 ? id + "  U:" + used : id.toString();
            list.add(new Entry(Tab.ITEM, id.toString(),
                    Component.literal(stack.getHoverName().getString()), sub, stack));
        }
        if (sortByUsage) {
            list.sort((a, b) -> Integer.compare(
                    ItemStats.usage(b.stack().getItem()), ItemStats.usage(a.stack().getItem())));
        } else {
            list.sort(Comparator.comparing(Entry::id));
        }
        entries.addAll(list);
    }

    /**
     * 毒瘤排行：按「被多少条可见配方当原料使用」降序。
     *
     * <p>只统计当前还看得见的配方（{@link ItemStats#build()} 里已经排除被规则藏/删的），
     * 所以藏掉一个毒瘤后，它自己会掉出榜，下一名自动补上来。
     */
    private void loadUsage() {
        if (usageMode == 1) {
            loadUsageCategories();
            return;
        }
        ItemStats.build();
        usageSig = ItemStats.signature();
        List<Entry> list = new ArrayList<>();
        for (Item item : ItemStats.topUsage(TOP_LIMIT)) {
            String id = BuiltInRegistries.ITEM.getKey(item).toString();
            ItemStack stack = new ItemStack(item);
            int used = ItemStats.usage(item);
            String sub = "用于 " + used + " 条配方 · 产出 " + ItemStats.output(item);
            list.add(new Entry(Tab.USAGE, id,
                    Component.literal(stack.getHoverName().getString()), sub, stack));
        }
        entries.addAll(list);
    }

    /**
     * 分类视角：直接问 JEI「每个分类有多少条配方」。
     *
     * <p>为什么需要这个视角：{@code ItemStats} 只能看到 {@code mc.level.getRecipeManager()}，
     * 那里只有真正的 MC Recipe。奥术铁砧那 4000+ 条是模组自建的 JEI 配方
     * （不是 {@code Recipe} 实现），物品视角里一条都统计不到，而它恰恰是按 U 最容易炸的。
     */
    private void loadUsageCategories() {
        usageCategories = CategoryStats.build();
        usageSig = ItemStats.signature();
        List<Entry> list = new ArrayList<>();
        for (CategoryStats.Row row : usageCategories) {
            list.add(new Entry(Tab.USAGE, row.uid(), row.title(),
                    row.count() + " 条配方", row.icon()));
        }
        entries.addAll(list);
    }

    private void switchUsageMode(int mode) {
        if (usageMode == mode) return;
        usageMode = mode;
        entriesLoaded = false;
        ensureEntries();
        rebuildFiltered();
        setStatus(mode == 0
                ? "毒瘤 · 按物品：只统计 MC 配方（数据包/原版）"
                : "毒瘤 · 按分类：包含模组自建的 JEI 配方");
    }

    private void loadMods() {
        Map<String, Integer> counts = new HashMap<>();
        for (ResourceLocation id : BuiltInRegistries.ITEM.keySet()) {
            if (BuiltInRegistries.ITEM.get(id) == Items.AIR) continue;
            counts.merge(id.getNamespace(), 1, Integer::sum);
        }
        List<Entry> list = new ArrayList<>();
        for (Map.Entry<String, Integer> e : counts.entrySet()) {
            String modid = e.getKey();
            String display = modid;
            try {
                var container = net.minecraftforge.fml.ModList.get().getModContainerById(modid);
                if (container.isPresent()) {
                    display = container.get().getModInfo().getDisplayName();
                }
            } catch (Throwable ignored) {
            }
            list.add(new Entry(Tab.MOD, modid, Component.literal(display),
                    modid + " · " + e.getValue() + " 个物品", null));
        }
        list.sort(Comparator.comparing(Entry::id));
        entries.addAll(list);
    }

    private void loadRecipes() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return;
        List<Entry> list = new ArrayList<>();
        for (Recipe<?> recipe : mc.level.getRecipeManager().getRecipes()) {
            ResourceLocation id = recipe.getId();
            ResourceLocation typeId = BuiltInRegistries.RECIPE_TYPE.getKey(recipe.getType());
            String type = typeId == null ? "?" : typeId.toString();
            ItemStack result = ItemStack.EMPTY;
            try {
                result = recipe.getResultItem(mc.level.registryAccess());
            } catch (Throwable ignored) {
            }
            Component name = result == null || result.isEmpty()
                    ? Component.literal(type)
                    : result.getHoverName();
            list.add(new Entry(Tab.RECIPE, id.toString(), name, id + "  [" + type + "]", result));
        }
        list.sort(Comparator.comparing(Entry::id));
        entries.addAll(list);
    }

    /**
     * 统一搜索（需求 4）：查询可含 {@code @modid} 前缀过滤，
     * 命中的条目按等级 0(@modid) / 1(拼音首字母) / 2(英文 id) / 3(中文名称) 分组，
     * 组内保持原顺序。
     */
    /** 按当前编辑目标落盘：管理员切到服务端就提交给服务端，否则只写本地 */
    private void applyNewRule(Rule rule) {
        if (!ClientSettings.admin) rule.action = Rule.ACTION_HIDE;
        if (editServer && ClientSettings.admin) {
            Rules.addServerRule(rule);
            Rules.pushServerRules();
        } else {
            Rules.replaceOrAdd(rule);
        }
    }

    private void rebuildFiltered() {
        ensureEntries();
        filtered.clear();
        String q = searchText == null ? "" : searchText.trim().toLowerCase(Locale.ROOT);
        String modFilter = null;
        if (q.startsWith("@")) {
            modFilter = q.substring(1).trim();
            q = q.substring(1).trim().toLowerCase(Locale.ROOT);
        }
        if (modFilter == null && q.isEmpty()) {
            filtered.addAll(entries);
            listScroll = 0;
            return;
        }
        List<Entry> t0 = new ArrayList<>();
        List<Entry> t1 = new ArrayList<>();
        List<Entry> t2 = new ArrayList<>();
        List<Entry> t3 = new ArrayList<>();
        for (Entry e : entries) {
            switch (entryTier(e, q, modFilter)) {
                case 0 -> t0.add(e);
                case 1 -> t1.add(e);
                case 2 -> t2.add(e);
                case 3 -> t3.add(e);
                default -> { }
            }
        }
        filtered.addAll(t0);
        filtered.addAll(t1);
        filtered.addAll(t2);
        filtered.addAll(t3);
        listScroll = 0;
    }

    /** 条目命中等级：-1 = 不命中。modFilter 非空时先按 modid 精确/前缀过滤 */
    private static int entryTier(Entry e, String q, String modFilter) {
        if (modFilter != null && !modFilter.isEmpty()) {
            String mod = modOf(e.id());
            if (!mod.equals(modFilter) && !mod.startsWith(modFilter)) return -1;
        }
        if (q.isEmpty()) return 0; // 纯 @modid 查询
        // 拼音档：纯中文查询不走拼音
        if (!isPureChinese(q) && PinyinIndex.initialsMatch(e.name().getString(), q)) return 1;
        // 英文档：物品 id / 英文名（id 冒号后最后一段）/ modid
        String id = e.id().toLowerCase(Locale.ROOT);
        String en = englishName(e.id()).toLowerCase(Locale.ROOT);
        if (id.contains(q) || en.contains(q)) return 2;
        // 中文档：显示名包含
        if (e.name().getString().toLowerCase(Locale.ROOT).contains(q)) return 3;
        return -1;
    }

    /** id 的命名空间（modid） */
    private static String modOf(String id) {
        int i = id.indexOf(':');
        return i >= 0 ? id.substring(0, i) : id;
    }

    /** 英文名：id 冒号后的最后一段（minecraft:diamond_sword → diamond_sword） */
    private static String englishName(String id) {
        int i = id.lastIndexOf(':');
        return i >= 0 ? id.substring(i + 1) : id;
    }

    /** 查询是否全部由汉字组成（纯中文查询时拼音档无效） */
    private static boolean isPureChinese(String s) {
        if (s.isEmpty()) return false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c < 0x4E00 || c > 0x9FA5) return false;
        }
        return true;
    }

    private boolean hasRule(String type, String target) {
        for (Rule r : Rules.all()) {
            if (!r.enabled || !r.type.equals(type) || !r.target.equals(target)) continue;
            if (sameRangeShape(r.effectiveRanges(), defaultRanges)) return true;
        }
        return false;
    }

    /** 两组范围是否「形状相同」（类型 + 搭配列表都一样） */
    private static boolean sameRangeShape(List<Rule.Range> a, List<Rule.Range> b) {
        if (a.size() != b.size()) return false;
        for (int i = 0; i < a.size(); i++) {
            if (!a.get(i).sameAs(b.get(i))) return false;
        }
        return true;
    }

    /** 模板深拷，至少一组 */
    private static List<Rule.Range> copyRanges(List<Rule.Range> source) {
        List<Rule.Range> out = new ArrayList<>();
        if (source != null) {
            for (Rule.Range range : source) out.add(range.copy());
        }
        if (out.isEmpty()) out.add(new Rule.Range());
        return out;
    }

    /** 点击候选项 = 按属性台默认值添加对应规则 */
    private void addFromEntry(Entry entry) {
        boolean remove = Rule.ACTION_REMOVE.equals(defaultAction);
        switch (entry.tab()) {
            case ITEM -> {
                Rule rule = new Rule(Rule.TYPE_ITEM, entry.id());
                rule.hideOutput = defaultOutput;
                rule.hideIngredient = includeIngredient;
                rule.hideItem = hideItemToo;
                rule.action = ClientSettings.admin ? defaultAction : Rule.ACTION_HIDE;
                rule.ranges = copyRanges(defaultRanges);
                rule.syncLegacy();
                if (!rule.hideOutput && !rule.hideIngredient && !rule.hideItem) {
                    setStatus("请至少开启一项：产物 / 原料 / 物品");
                    return;
                }
                applyNewRule(rule);
                setStatus("已隐藏：" + entry.name().getString()
                        + (selectedType.isEmpty() ? "" : "（限 " + shortType(selectedType) + "）")
                       );
            }
            case MOD -> {
                Rule rule = new Rule(keepMode ? Rule.TYPE_KEEP : Rule.TYPE_MOD, entry.id());
                rule.action = defaultAction;
                if (!keepMode) {
                    rule.ranges = copyRanges(defaultRanges);
                    rule.syncLegacy();
                }
                applyNewRule(rule);
                setStatus(keepMode
                        ? "已加入白名单：" + entry.id() + "（其余模组配方将被隐藏）"
                        : "已隐藏模组：" + entry.id());
            }
            case USAGE -> {
                if (usageMode == 1) {
                    // 分类视角：整类隐藏（可撤销，不做服务端删除）
                    Rule rule = new Rule(Rule.TYPE_CATEGORY, entry.id());
                    rule.action = Rule.ACTION_HIDE;
                    if (editServer && ClientSettings.admin) {
                        Rules.addServerRule(rule);
                        Rules.pushServerRules();
                    } else if (!Rules.add(rule)) {
                        setStatus("已经有这条分类规则了：" + entry.name().getString());
                        return;
                    }
                    setStatus("已隐藏分类：" + entry.name().getString() + "（右栏可撤销）");
                    return;
                }
                // 物品视角：只按「原料」下手，产物配方和物品本体都留着
                Rule rule = new Rule(Rule.TYPE_ITEM, entry.id());
                rule.hideOutput = false;
                rule.hideIngredient = true;
                rule.hideItem = false;
                rule.action = ClientSettings.admin ? topAction : Rule.ACTION_HIDE;
                List<Rule.Range> ranges = new ArrayList<>();
                ranges.add(new Rule.Range()); // 空类型 = 不限工作台
                rule.ranges = ranges;
                rule.syncLegacy();
                if (editServer && ClientSettings.admin) {
                    Rules.addServerRule(rule);
                    Rules.pushServerRules();
                } else if (!Rules.add(rule)) {
                    setStatus("已经有同一套设置了：" + entry.name().getString());
                    return;
                }
                boolean removed = Rule.ACTION_REMOVE.equals(rule.action);
                setStatus((removed ? "已删除用到它的配方：" : "已隐藏用到它的配方：")
                        + entry.name().getString());
            }
            case RECIPE -> {
                Rule rule = new Rule(Rule.TYPE_RECIPE, entry.id());
                rule.action = defaultAction;
                applyNewRule(rule);
                setStatus("已隐藏配方：" + entry.id());
            }
        }
    }

    // ---------------------------------------------------------------- 渲染

    @Override
    public void render(GuiGraphics gg, int mouseX, int mouseY, float partialTick) {
        this.renderBackground(gg);
        ensureEntries();

        // 毒瘤页：规则一变就重排（藏掉一个，下一名要自动补上来）
        if (tab == Tab.USAGE && entriesLoaded && entriesLoadedFor == Tab.USAGE
                && ItemStats.signature() != usageSig) {
            entriesLoaded = false;
            ensureEntries();
            rebuildFiltered();
        }

        // 弹窗打开时整个主界面都不画：
        // 属性台的「隐藏/删除」是直接 drawString 的，不在 widget 列表里，光靠遮罩挡不住。
        boolean modal = settingsOpen || optimizeOpen || ruleEditOpen || rangeEditorOpen
                || partnerPickerOpen || typePickerOpen;
        if (!modal) {
            drawPanel(gg);
            drawCandidateList(gg, mouseX, mouseY);
            drawPropPanel(gg, mouseX, mouseY);
            drawRuleList(gg, mouseX, mouseY);
            drawFooter(gg);

            for (Renderable renderable : this.renderables) {
                // Screen 默认的 renderWidgets 会过滤 visible，手写循环必须自己判，
                // 否则 visible=false 的按钮只挡点击、照样画出来
                if (renderable instanceof net.minecraft.client.gui.components.AbstractWidget widget
                        && !widget.visible) {
                    continue;
                }
                renderable.render(gg, mouseX, mouseY, partialTick);
            }
        }

        if (optimizeOpen) {
            drawOptimize(gg, mouseX, mouseY);
        } else if (settingsOpen) {
            drawSettings(gg, mouseX, mouseY);
        } else if (ruleEditOpen) {
            drawRuleEdit(gg, mouseX, mouseY);
        } else if (partnerPickerOpen) {
            drawPartnerPicker(gg, mouseX, mouseY);
            this.partnerSearch.render(gg, mouseX, mouseY, partialTick);
        } else if (typePickerOpen) {
            drawTypePicker(gg, mouseX, mouseY);
            // 搜索框不在 renderables 里，必须在弹窗背景之后手动渲染
            this.pickerSearch.render(gg, mouseX, mouseY, partialTick);
        } else if (rangeEditorOpen) {
            drawRangeEditor(gg, mouseX, mouseY);
        } else {
            drawHoverTooltip(gg, mouseX, mouseY);
        }
    }

    private void drawPanel(GuiGraphics gg) {
        int x = panelX(), y = panelY(), w = panelW(), h = panelH();
        gg.fill(x, y, x + w, y + h, COLOR_PANEL);
        gg.renderOutline(x, y, w, h, COLOR_EDGE);
        gg.fill(x + 1, y + 1, x + w - 1, y + 18, COLOR_HEADER);

        gg.drawString(this.font, "§eJEI Cleaner §8· §7配方显示管理", x + 8, y + 6, COLOR_TEXT, false);

        String counter = "规则 " + Rules.enabledCount() + "/" + Rules.size();
        int cw = this.font.width(counter);
        int cx = x + w - 10 - cw;
        gg.drawString(this.font, counter, cx, y + 6, COLOR_DIM, false);

        if (Cleaner.hasPendingChanges()) {
            String warn = Cleaner.isSyncing() ? "同步中" : "未同步·点同步";
            int ww = this.font.width(warn);
            gg.drawString(this.font, warn, cx - 10 - ww, y + 6, COLOR_ACCENT, false);
        }
    }

    private void drawCandidateList(GuiGraphics gg, int mouseX, int mouseY) {
        int x = listX(), y = listY(), w = listW(), h = listH();
        gg.fill(x - 1, y - 1, x + w + 1, y + h + 1, 0x30FFFFFF);

        if ((tab == Tab.RECIPE || tab == Tab.USAGE) && Minecraft.getInstance().level == null) {
            gg.drawString(this.font, "§7需要先进入世界，才能读取配方列表。", x + 4, y + 4, COLOR_DIM, false);
            return;
        }

        int rows = visibleRows();
        int maxScroll = Math.max(0, filtered.size() - rows);
        if (listScroll > maxScroll) listScroll = maxScroll;
        if (listScroll < 0) listScroll = 0;

        gg.enableScissor(x, y, x + w, y + h);
        for (int i = 0; i < rows; i++) {
            int index = listScroll + i;
            if (index >= filtered.size()) break;
            Entry entry = filtered.get(index);
            int rowY = y + i * ROW_H;
            boolean hovered = mouseX >= x && mouseX <= x + w && mouseY >= rowY && mouseY < rowY + ROW_H;
            if (hovered) gg.fill(x, rowY, x + w, rowY + ROW_H, COLOR_HOVER);
            else if (i % 2 == 1) gg.fill(x, rowY, x + w, rowY + ROW_H, COLOR_ALT);

            String ruleType = entry.tab() == Tab.MOD ? Rule.TYPE_MOD
                    : entry.tab() == Tab.RECIPE ? Rule.TYPE_RECIPE : Rule.TYPE_ITEM;
            boolean hidden = entry.tab() == Tab.MOD
                    ? hasRule(Rule.TYPE_MOD, entry.id()) || hasRule(Rule.TYPE_KEEP, entry.id())
                    : entry.tab() == Tab.USAGE
                    ? (usageMode == 1 ? hasRule(Rule.TYPE_CATEGORY, entry.id())
                                      : hasIngredientRule(entry.id()))
                    : hasRule(ruleType, entry.id());

            int textX = x + 4;
            if (entry.stack() != null && !entry.stack().isEmpty()) {
                gg.renderItem(entry.stack(), x + 3, rowY + 3);
                textX = x + 24;
            }

            gg.drawString(this.font, trim(entry.name().getString(), x + w - textX - 14),
                    textX, rowY + 2, hidden ? COLOR_ACCENT : COLOR_TEXT, false);
            gg.drawString(this.font, trim(entry.sub(), x + w - textX - 14),
                    textX, rowY + 12, COLOR_DIM, false);

            if (hidden) {
                gg.drawString(this.font, "§e✓", x + w - 12, rowY + 7, COLOR_ACCENT, false);
            }
        }
        gg.disableScissor();

        if (maxScroll > 0) {
            int barH = Math.max(12, (int) ((float) rows / filtered.size() * h));
            int barY = y + (int) ((float) listScroll / maxScroll * (h - barH));
            gg.fill(x + w - 3, barY, x + w, barY + barH, 0x80FFFFFF);
        }
    }

    // ---------------------------------------------------------------- 中间属性台

    /**
     * 中间「属性台」：按当前页签显示对应设置。
     *
     * <ul>
     *   <li>物品页：产物 / 原料 / 范围 / 隐藏·删除 + 教程</li>
     *   <li>模组页：范围 / 隐藏·删除 + 教程（产物、原料对整模组没有意义）</li>
     *   <li>配方页：只有灰字教程（按 id 精确隐藏一条，属性台用不上）</li>
     * </ul>
     *
     * <p>可点击区域由 {@link #propHits} 记录，点击时按 action 派发 ——
     * 绘制与命中判定共用一份坐标，不会出现「画在这里、点在那里」。
     */
    private void drawPropPanel(GuiGraphics gg, int mouseX, int mouseY) {
        int x = midX(), y = listY(), w = MID_W, h = listH();
        propHits.clear();
        gg.fill(x, y, x + w, y + h, 0x18FFFFFF);
        gg.renderOutline(x, y, w, h, COLOR_EDGE);

        Rule edit = ruleEditOpen ? Rules.get(ruleEditIndex) : null;
        boolean clickable = !ruleEditOpen && !settingsOpen;

        gg.drawString(this.font, "属性台", x + 4, y + 4, COLOR_ACCENT, false);
        gg.fill(x + 4, y + 15, x + w - 4, y + 16, COLOR_EDGE);

        switch (tab) {
            case ITEM -> drawPropItem(gg, x, y, h, edit, mouseX, mouseY, clickable);
            case USAGE -> drawPropUsage(gg, x, y, h, mouseX, mouseY, clickable);
            case MOD -> drawPropMod(gg, x, y, h, edit, mouseX, mouseY, clickable);
            case RECIPE -> drawPropRecipe(gg, x, y, h);
        }
    }

    private void drawPropItem(GuiGraphics gg, int x, int y, int h, Rule edit,
                              int mouseX, int mouseY, boolean clickable) {
        boolean output = edit != null ? edit.hideOutput : defaultOutput;
        boolean ingredient = edit != null ? edit.hideIngredient : includeIngredient;
        boolean remove = edit != null
                ? Rule.ACTION_REMOVE.equals(edit.action)
                : Rule.ACTION_REMOVE.equals(defaultAction);
        drawPropRow(gg, x, y + 20, "产物", output, mouseX, mouseY, clickable, "output");
        drawPropRow(gg, x, y + 36, "原料", ingredient, mouseX, mouseY, clickable, "ingredient");

        gg.fill(x + 4, y + 52, x + MID_W - 4, y + 53, COLOR_EDGE);

        // 范围 + 搭配：合并成一行摘要，点击打开「范围与搭配」编辑器
        List<Rule.Range> ranges = edit != null ? edit.effectiveRanges() : defaultRanges;
        int partnerCount = 0;
        for (Rule.Range range : ranges) {
            partnerCount += range.partnerCount();
        }
        String summary = ranges.size() + " 组";
        if (partnerCount > 0) summary += " · " + partnerCount + " 搭配";
        boolean active = ranges.size() > 1 || partnerCount > 0
                || (ranges.size() == 1 && ranges.get(0).hasType());

        int ry = y + 56;
        if (clickable && inRect(mouseX, mouseY, x + 4, ry, MID_W - 8, 14)) {
            gg.fill(x + 4, ry, x + MID_W - 4, ry + 14, COLOR_HOVER);
        }
        if (clickable) propHits.add(new PropHit(x + 4, ry, MID_W - 8, 14, "ranges"));
        gg.drawString(this.font, "范围", x + 6, ry + 3, COLOR_TEXT, false);
        gg.drawString(this.font, trim(summary, MID_W - 42), x + 32, ry + 3,
                active ? COLOR_INFO : COLOR_FAINT, false);
        gg.drawString(this.font, "▸", x + MID_W - 12, ry + 3, COLOR_FAINT, false);

        gg.fill(x + 4, y + 74, x + MID_W - 4, y + 75, COLOR_EDGE);

        int by = y + 78;
        if (ClientSettings.admin) {
            boolean hoverHide = clickable && inRect(mouseX, mouseY, x + 4, by, 44, 16);
            boolean hoverRemove = clickable && inRect(mouseX, mouseY, x + 50, by, 44, 16);
            drawToggleBtn(gg, x + 4, by, 44, 16, "隐藏", !remove, hoverHide);
            drawToggleBtn(gg, x + 50, by, 44, 16, "删除", remove, hoverRemove);
            if (clickable) {
                propHits.add(new PropHit(x + 4, by, 44, 16, "hide"));
                propHits.add(new PropHit(x + 50, by, 44, 16, "remove"));
            }
        } else {
            drawToggleBtn(gg, x + 4, by, MID_W - 8, 16, "隐藏", true, false);
        }

        gg.fill(x + 4, y + 100, x + MID_W - 4, y + 101, COLOR_EDGE);
        if (h >= 124) {
            gg.drawString(this.font, "点击左侧条目", x + 4, y + 106, COLOR_FAINT, false);
            gg.drawString(this.font, "套用这套属性", x + 4, y + 116, COLOR_FAINT, false);
        }
    }

    /** 属性台里的「值」行（搭配）：点击打开选择器 */
    private void drawPropValueRow(GuiGraphics gg, int x, int y, String label, String value, boolean active,
                                  boolean clickable, int mouseX, int mouseY, String action) {
        if (clickable && inRect(mouseX, mouseY, x + 4, y, MID_W - 8, 14)) {
            gg.fill(x + 4, y, x + MID_W - 4, y + 14, COLOR_HOVER);
        }
        if (clickable) propHits.add(new PropHit(x + 4, y, MID_W - 8, 14, action));
        gg.drawString(this.font, label, x + 6, y + 3,
                clickable ? COLOR_TEXT : COLOR_FAINT, false);
        gg.drawString(this.font, trim(value, MID_W - 40), x + 32, y + 3,
                active ? COLOR_INFO : COLOR_FAINT, false);
    }

    /** 一组「与」搭配的显示名：A & B */
    private static String groupText(List<String> group) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < group.size(); i++) {
            if (i > 0) sb.append("&");
            sb.append(partnerDisplay(group.get(i)));
        }
        return sb.toString();
    }

    /** 一组范围的显示：类型 [A&B | C] */
    private static String rangeText(Rule.Range range) {
        String type = range.hasType() ? shortType(range.type) : "全部";
        if (range.partnerCount() == 0) return type;

        StringBuilder sb = new StringBuilder(type).append(" [");
        List<List<String>> groups = range.groups();
        for (int i = 0; i < groups.size(); i++) {
            if (i > 0) sb.append(" | ");
            sb.append(groupText(groups.get(i)));
        }
        return sb.append(']').toString();
    }

    /** 整条规则的范围摘要（多组时只显示组数） */
    private static String rangesSummary(Rule rule) {
        List<Rule.Range> ranges = rule.effectiveRanges();
        if (ranges.size() == 1) return rangeText(ranges.get(0));
        return ranges.size() + "组";
    }

    /** 搭配的显示名：不限 / #标签短名 / 物品中文名 */
    private static String partnerDisplay(String partner) {
        if (partner == null || partner.isBlank()) return "不限";
        if (partner.startsWith("#")) {
            ResourceLocation id = ResourceLocation.tryParse(partner.substring(1));
            return id == null ? partner : "#" + id.getPath();
        }
        ResourceLocation id = ResourceLocation.tryParse(partner);
        Item item = id == null ? null : BuiltInRegistries.ITEM.get(id);
        if (item != null && item != Items.AIR) {
            return new ItemStack(item).getHoverName().getString();
        }
        return partner;
    }

    /** 毒瘤页的属性台：视角切换（物品 / 分类）+ 动作说明 + 计数 */
    private void drawPropUsage(GuiGraphics gg, int x, int y, int h,
                               int mouseX, int mouseY, boolean clickable) {
        // 视角切换
        drawToggleBtn(gg, x + 4, y + 21, 44, 16, "物品", usageMode == 0,
                clickable && inRect(mouseX, mouseY, x + 4, y + 21, 44, 16));
        drawToggleBtn(gg, x + 50, y + 21, 44, 16, "分类", usageMode == 1,
                clickable && inRect(mouseX, mouseY, x + 50, y + 21, 44, 16));
        if (clickable) {
            propHits.add(new PropHit(x + 4, y + 21, 44, 16, "usageItem"));
            propHits.add(new PropHit(x + 50, y + 21, 44, 16, "usageCat"));
        }

        gg.fill(x + 4, y + 43, x + MID_W - 4, y + 44, COLOR_EDGE);

        boolean itemMode = usageMode == 0;
        gg.drawString(this.font, "点一行 =", x + 6, y + 47, COLOR_TEXT, false);
        gg.drawString(this.font, itemMode ? "隐藏「用到" : "隐藏整个", x + 6, y + 57, COLOR_DIM, false);
        gg.drawString(this.font, itemMode ? "它」的配方" : "配方分类", x + 6, y + 67, COLOR_DIM, false);
        gg.drawString(this.font, itemMode ? "产物不动" : "规则里可撤销", x + 6, y + 79, COLOR_FAINT, false);

        gg.fill(x + 4, y + 93, x + MID_W - 4, y + 94, COLOR_EDGE);

        if (itemMode) {
            boolean remove = Rule.ACTION_REMOVE.equals(topAction);
            int by = y + 97;
            if (ClientSettings.admin) {
                drawToggleBtn(gg, x + 4, by, 44, 16, "隐藏", !remove,
                        clickable && inRect(mouseX, mouseY, x + 4, by, 44, 16));
                drawToggleBtn(gg, x + 50, by, 44, 16, "删除", remove,
                        clickable && inRect(mouseX, mouseY, x + 50, by, 44, 16));
                if (clickable) {
                    propHits.add(new PropHit(x + 4, by, 44, 16, "hide"));
                    propHits.add(new PropHit(x + 50, by, 44, 16, "remove"));
                }
            } else {
                drawToggleBtn(gg, x + 4, by, MID_W - 8, 16, "隐藏", true, false);
            }
        } else {
            gg.drawString(this.font, "分类视角", x + 6, y + 97, COLOR_INFO, false);
            gg.drawString(this.font, "只隐藏", x + 6, y + 109, COLOR_FAINT, false);
            gg.drawString(this.font, "不删除", x + 6, y + 119, COLOR_FAINT, false);
        }

        if (h >= 160) {
            gg.fill(x + 4, y + 133, x + MID_W - 4, y + 134, COLOR_EDGE);
            if (itemMode) {
                gg.drawString(this.font, "可见配方", x + 6, y + 137, COLOR_FAINT, false);
                gg.drawString(this.font, String.valueOf(ItemStats.totalRecipes()),
                        x + 6, y + 148, COLOR_INFO, false);
                gg.drawString(this.font, "只统计 MC", x + 6, y + 161, COLOR_FAINT, false);
                gg.drawString(this.font, "配方", x + 6, y + 171, COLOR_FAINT, false);
            } else {
                gg.drawString(this.font, "分类", x + 6, y + 137, COLOR_FAINT, false);
                gg.drawString(this.font, String.valueOf(usageCategories.size()),
                        x + 6, y + 148, COLOR_INFO, false);
                gg.drawString(this.font, "含自建", x + 6, y + 161, COLOR_FAINT, false);
                gg.drawString(this.font, "配方", x + 6, y + 171, COLOR_FAINT, false);
            }
        }
    }

    private void drawPropMod(GuiGraphics gg, int x, int y, int h, Rule edit,
                             int mouseX, int mouseY, boolean clickable) {
        boolean remove = edit != null
                ? Rule.ACTION_REMOVE.equals(edit.action)
                : Rule.ACTION_REMOVE.equals(defaultAction);

        // 范围（多组时只显示摘要）
        String rangeText;
        if (edit != null) {
            rangeText = rangesSummary(edit);
        } else {
            List<Rule.Range> d = currentRanges();
            rangeText = d.size() == 1 ? rangeText(d.get(0)) : d.size() + " 组";
        }
        int ry = y + 22;
        if (clickable && inRect(mouseX, mouseY, x + 4, ry, MID_W - 8, 14)) {
            gg.fill(x + 4, ry, x + MID_W - 4, ry + 14, COLOR_HOVER);
        }
        if (clickable) propHits.add(new PropHit(x + 4, ry, MID_W - 8, 14, "range"));
        gg.drawString(this.font, "范围", x + 6, ry + 3, COLOR_TEXT, false);
        gg.drawString(this.font, trim(rangeText, MID_W - 42), x + 32, ry + 3, COLOR_INFO, false);

        gg.fill(x + 4, y + 40, x + MID_W - 4, y + 41, COLOR_EDGE);

        int by = y + 46;
        if (ClientSettings.admin) {
            boolean hoverHide = clickable && inRect(mouseX, mouseY, x + 4, by, 44, 16);
            boolean hoverRemove = clickable && inRect(mouseX, mouseY, x + 50, by, 44, 16);
            drawToggleBtn(gg, x + 4, by, 44, 16, "隐藏", !remove, hoverHide);
            drawToggleBtn(gg, x + 50, by, 44, 16, "删除", remove, hoverRemove);
            if (clickable) {
                propHits.add(new PropHit(x + 4, by, 44, 16, "hide"));
                propHits.add(new PropHit(x + 50, by, 44, 16, "remove"));
            }
        } else {
            drawToggleBtn(gg, x + 4, by, MID_W - 8, 16, "隐藏", true, false);
        }

        gg.fill(x + 4, y + 68, x + MID_W - 4, y + 69, COLOR_EDGE);

        int ky = y + 72;
        if (clickable && inRect(mouseX, mouseY, x + 4, ky, MID_W - 8, 14)) {
            gg.fill(x + 4, ky, x + MID_W - 4, ky + 14, COLOR_HOVER);
        }
        if (clickable) propHits.add(new PropHit(x + 4, ky, MID_W - 8, 14, "keep"));
        gg.drawString(this.font, "白名单", x + 6, ky + 3, COLOR_TEXT, false);
        gg.drawString(this.font, keepMode ? "●" : "○", x + MID_W - 22, ky + 3,
                keepMode ? COLOR_OK : COLOR_OFF, false);

        gg.fill(x + 4, y + 88, x + MID_W - 4, y + 89, COLOR_EDGE);
        if (h >= 146) {
            String[] tips = keepMode
                    ? new String[]{"白名单模式", "点击 = 只保留", "该模组", "", "其余全部隐藏"}
                    : new String[]{"点击左侧模组", "隐藏它全部配方", "", "产物/原料", "对整模组无效"};
            int ty = y + 94;
            for (String tip : tips) {
                if (tip.isEmpty()) {
                    ty += 4;
                    continue;
                }
                gg.drawString(this.font, trim(tip, MID_W - 8), x + 4, ty, COLOR_FAINT, false);
                ty += 10;
            }
        }
    }

    private void drawPropRecipe(GuiGraphics gg, int x, int y, int h) {
        gg.fill(x + 4, y + 22, x + MID_W - 4, y + 23, COLOR_EDGE);
        String[] tips = {
                "配方规则按 id",
                "精确隐藏一条",
                "",
                "不受属性台",
                "（产物/原料/",
                " 范围）影响",
                "",
                "点击左侧配方",
                "即可添加",
        };
        int ty = y + 28;
        int max = Math.max(1, (h - 34) / 10);
        int drawn = 0;
        for (String tip : tips) {
            if (drawn >= max) break;
            if (tip.isEmpty()) {
                ty += 4;
                continue;
            }
            gg.drawString(this.font, trim(tip, MID_W - 8), x + 4, ty, COLOR_FAINT, false);
            ty += 10;
            drawn++;
        }
        if (h >= 150) {
            boolean remove = Rule.ACTION_REMOVE.equals(defaultAction);
            gg.drawString(this.font, "当前:", x + 4, y + h - 26, COLOR_FAINT, false);
            gg.drawString(this.font, remove ? "删除" : "隐藏",
                    x + 4, y + h - 16, remove ? 0xFFFF9A9A : COLOR_OK, false);
        }
    }

    /** 属性台里的可点击区域 */
    private record PropHit(int x, int y, int w, int h, String action) {
    }

    /** 属性台里的「产物 / 原料」一行：左侧名称，右侧 ●=开 / ○=关 */
    private void drawPropRow(GuiGraphics gg, int x, int y, String label, boolean on,
                             int mouseX, int mouseY, boolean clickable, String action) {
        if (clickable && inRect(mouseX, mouseY, x + 4, y, MID_W - 8, 14)) {
            gg.fill(x + 4, y, x + MID_W - 4, y + 14, COLOR_HOVER);
        }
        if (clickable) propHits.add(new PropHit(x + 4, y, MID_W - 8, 14, action));
        gg.drawString(this.font, label, x + 8, y + 3, COLOR_TEXT, false);
        gg.drawString(this.font, on ? "●" : "○", x + MID_W - 22, y + 3,
                on ? COLOR_OK : COLOR_OFF, false);
    }

    /** 小按钮：选中 = 蓝底白字，未选中 = 灰底（可悬停高亮） */
    private void drawToggleBtn(GuiGraphics gg, int x, int y, int w, int h, String label,
                               boolean selected, boolean hovered) {
        int bg = selected ? 0xFF3A5A8A : (hovered ? 0x60FFFFFF : 0x30FFFFFF);
        gg.fill(x, y, x + w, y + h, bg);
        gg.drawCenteredString(this.font, label, x + w / 2, y + 3, selected ? 0xFFFFFFFF : COLOR_DIM);
    }

    private void scrollListFromMouse(double mouseY) {
        int rows = visibleRows();
        int maxScroll = Math.max(0, filtered.size() - rows);
        if (maxScroll <= 0) return;
        float ratio = (float) ((mouseY - listY()) / (double) Math.max(1, listH()));
        listScroll = Math.max(0, Math.min(maxScroll, Math.round(ratio * maxScroll)));
    }

    private void scrollRuleFromMouse(double mouseY) {
        int rows = visibleRuleRows();
        int maxScroll = Math.max(0, Rules.size() - rows);
        if (maxScroll <= 0) return;
        float ratio = (float) ((mouseY - listY()) / (double) Math.max(1, listH()));
        ruleScroll = Math.max(0, Math.min(maxScroll, Math.round(ratio * maxScroll)));
    }

    private void scrollPartnerFromMouse(double mouseY) {
        int visible = Math.max(1, (pickerH() - 44) / PARTNER_ROW_H);
        List<PartnerRow> rows = filteredPartnerRows();
        int maxScroll = Math.max(0, rows.size() - visible);
        if (maxScroll <= 0) return;
        int listTop = pickerY() + 38;
        float ratio = (float) ((mouseY - listTop) / (double) Math.max(1, visible * PARTNER_ROW_H));
        partnerScroll = Math.max(0, Math.min(maxScroll, Math.round(ratio * maxScroll)));
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        if (draggingListBar) {
            scrollListFromMouse(mouseY);
            return true;
        }
        if (draggingRuleBar) {
            scrollRuleFromMouse(mouseY);
            return true;
        }
        if (draggingPartnerBar) {
            scrollPartnerFromMouse(mouseY);
            return true;
        }
        return super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        draggingListBar = false;
        draggingRuleBar = false;
        draggingPartnerBar = false;
        return super.mouseReleased(mouseX, mouseY, button);
    }

    private static boolean inRect(int mx, int my, int x, int y, int w, int h) {
        return mx >= x && mx <= x + w && my >= y && my <= y + h;
    }

    /** 属性台点击：按当前页签的可点击区域派发（坐标与绘制共用一份） */
    private void handlePropPanelClick(double mouseX, double mouseY) {
        for (PropHit hit : propHits) {
            if (!inRect((int) mouseX, (int) mouseY, hit.x(), hit.y(), hit.w(), hit.h())) continue;
            switch (hit.action()) {
                case "output" -> {
                    defaultOutput = !defaultOutput;
                    ClientSettings.defaultOutput = defaultOutput;
                    ClientSettings.touch();
                }
                case "ingredient" -> {
                    includeIngredient = !includeIngredient;
                    ClientSettings.defaultIngredient = includeIngredient;
                    ClientSettings.touch();
                }
                case "ranges" -> openRangeEditor(-1);
                case "usageItem" -> switchUsageMode(0);
                case "usageCat" -> switchUsageMode(1);
                case "keep" -> {
                    keepMode = !keepMode;
                    setStatus(keepMode ? "模组页：点击 = 加入白名单（其余全部隐藏）" : "模组页：点击 = 隐藏该模组");
                }
                case "hide" -> {
                    if (!ClientSettings.admin) return; // 普通玩家只能用「隐藏」
                    if (tab == Tab.USAGE) {
                        topAction = Rule.ACTION_HIDE;
                        return;
                    }
                    defaultAction = Rule.ACTION_HIDE;
                    ClientSettings.defaultAction = defaultAction;
                    ClientSettings.touch();
                }
                case "remove" -> {
                    if (!ClientSettings.admin) return;
                    if (tab == Tab.USAGE) {
                        topAction = Rule.ACTION_REMOVE;
                        return;
                    }
                    defaultAction = Rule.ACTION_REMOVE;
                    ClientSettings.defaultAction = defaultAction;
                    ClientSettings.touch();
                }
                default -> {
                }
            }
            return;
        }
    }

    /** 打开范围选择器；ruleIndex = -1 表示改属性台默认值 */
    private void openTypePickerFor(int ruleIndex) {
        pickerForRule = ruleIndex;
        if (partnerPickerOpen) closePartnerPicker();
        if (typePickerOpen) closeTypePicker();
        typePickerOpen = true;
        typeScroll = 0;
        positionPickerSearch();
    }

    // ------------------------------------------------------------ 搭配选择（第二个范围）

    private void openPartnerPicker(int ruleIndex) {
        partnerForRule = ruleIndex;
        closeTypePicker();
        partnerPickerOpen = true;
        partnerScroll = 0;
        partnerSearchText = "";
        if (partnerSearch != null) {
            partnerSearch.setValue("");
            positionPartnerSearch();
            this.setFocused(partnerSearch);
        }
    }

    private void closePartnerPicker() {
        partnerPickerOpen = false;
        if (partnerSearch != null) {
            partnerSearch.setFocused(false);
            partnerSearch.setPosition(0, -200);
        }
        this.setFocused(null);
    }

    private void positionPartnerSearch() {
        if (partnerSearch != null) {
            partnerSearch.setPosition(pickerX() + Math.max(0, (pickerW() - 210) / 2), pickerY() + 20);
        }
    }

    /** 当前搭配值（属性台默认值 / 某条规则） */
    private String currentPartnerValue() {
        if (partnerForRule >= 0 && partnerForRule < Rules.size()) {
            Rule r = Rules.get(partnerForRule);
            if (r != null) return r.partner == null ? "" : r.partner;
        }
        return ClientSettings.defaultPartner == null ? "" : ClientSettings.defaultPartner;
    }

    /** 搭配候选：不限 → 物品标签 → 物品 */
    private static List<PartnerRow> partnerRows() {
        if (partnerRowCache != null) return partnerRowCache;
        List<PartnerRow> rows = new ArrayList<>();
        rows.add(new PartnerRow("", "不限", "", ItemStack.EMPTY));

        try {
            List<TagKey<Item>> tags = BuiltInRegistries.ITEM.getTagNames()
                    .sorted(Comparator.comparing((TagKey<Item> tag) -> tag.location().toString()))
                    .toList();
            for (TagKey<Item> tag : tags) {
                int count = 0;
                try {
                    var holders = BuiltInRegistries.ITEM.getTag(tag);
                    if (holders.isPresent()) count = holders.get().size();
                } catch (Throwable ignored) {
                }
                if (count <= 0) continue;
                ResourceLocation id = tag.location();
                rows.add(new PartnerRow("#" + id, "#" + id.getPath(),
                        id.getNamespace() + " · " + count + " 项", ItemStack.EMPTY));
            }
        } catch (Throwable ignored) {
        }

        List<ResourceLocation> ids = new ArrayList<>(BuiltInRegistries.ITEM.keySet());
        ids.sort(Comparator.comparing(ResourceLocation::toString));
        for (ResourceLocation id : ids) {
            Item item = BuiltInRegistries.ITEM.get(id);
            if (item == Items.AIR) continue;
            ItemStack stack = new ItemStack(item);
            rows.add(new PartnerRow(id.toString(), stack.getHoverName().getString(), id.toString(), stack));
        }

        partnerRowCache = rows;
        return rows;
    }

    /** #标签 -> 物品列表缓存 */
    private static final Map<String, List<Item>> TAG_ITEMS = new HashMap<>();

    private static List<Item> tagItems(String tagPath) {
        List<Item> cached = TAG_ITEMS.get(tagPath);
        if (cached != null) return cached;
        List<Item> list = new ArrayList<>();
        try {
            ResourceLocation rl = ResourceLocation.tryParse(tagPath);
            if (rl != null) {
                var holders = BuiltInRegistries.ITEM.getTag(TagKey.create(Registries.ITEM, rl));
                if (holders.isPresent()) {
                    for (var holder : holders.get()) {
                        Item item = holder.value();
                        if (item != Items.AIR) list.add(item);
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        TAG_ITEMS.put(tagPath, list);
        return list;
    }

    private List<PartnerRow> filteredPartnerRows() {
        List<PartnerRow> out = new ArrayList<>();
        String q = partnerSearchText == null ? "" : partnerSearchText.trim().toLowerCase(Locale.ROOT);
        for (PartnerRow row : partnerRows()) {
            // 「不限」永远在
            if (row.value().isEmpty()) {
                out.add(row);
                continue;
            }
            boolean tag = row.value().startsWith("#");
            if (partnerTab == 0 && !tag) continue;
            if (partnerTab == 1 && tag) continue;

            if (q.isEmpty()
                    || row.label().toLowerCase(Locale.ROOT).contains(q)
                    || row.sub().toLowerCase(Locale.ROOT).contains(q)) {
                out.add(row);
            }
        }
        return out;
    }

    private static final int PARTNER_ROW_H = 16;

    private void drawPartnerPicker(GuiGraphics gg, int mouseX, int mouseY) {
        gg.flush();
        gg.fill(0, 0, this.width, this.height, 0xF0121218);

        int x = pickerX(), y = pickerY(), w = pickerW(), h = pickerH();
        gg.fill(x, y, x + w, y + h, 0xF51C1C26);
        gg.renderOutline(x, y, w, h, COLOR_EDGE);

        gg.drawString(this.font, "搭配", x + 6, y + 5, COLOR_ACCENT, false);
        // 两个页签：类别 / 物品
        int tabY = y + 3;
        int tabW = 56;
        int tab1X = x + 44;
        int tab2X = tab1X + tabW + 4;
        gg.fill(tab1X, tabY, tab1X + tabW, tabY + 14, partnerTab == 0 ? 0xFF3A5A8A : 0x30FFFFFF);
        gg.drawCenteredString(this.font, "类别", tab1X + tabW / 2, tabY + 3,
                partnerTab == 0 ? 0xFFFFFFFF : COLOR_DIM);
        gg.fill(tab2X, tabY, tab2X + tabW, tabY + 14, partnerTab == 1 ? 0xFF3A5A8A : 0x30FFFFFF);
        gg.drawCenteredString(this.font, "物品", tab2X + tabW / 2, tabY + 3,
                partnerTab == 1 ? 0xFFFFFFFF : COLOR_DIM);

        gg.fill(x + 4, y + 17, x + w - 4, y + 18, COLOR_EDGE);
        gg.drawString(this.font, "点「不限」取消", x + w - 62, y + 5, COLOR_FAINT, false);

        List<PartnerRow> rows = filteredPartnerRows();
        int listY = y + 38;
        int visible = Math.max(1, (y + h - 6 - listY) / PARTNER_ROW_H);
        int maxScroll = Math.max(0, rows.size() - visible);
        partnerScroll = Math.max(0, Math.min(maxScroll, partnerScroll));

        String current = currentPartnerValue();
        PartnerRow hoverRow = null;

        gg.enableScissor(x + 2, listY, x + w - 2, listY + visible * PARTNER_ROW_H);
        for (int i = 0; i < visible; i++) {
            int idx = partnerScroll + i;
            if (idx >= rows.size()) break;
            PartnerRow row = rows.get(idx);
            int ry = listY + i * PARTNER_ROW_H;
            boolean hovered = mouseX >= x + 2 && mouseX <= x + w - 2 && mouseY >= ry && mouseY < ry + PARTNER_ROW_H;
            boolean selected = row.value().equals(current);
            if (hovered) {
                hoverRow = row;
                gg.fill(x + 2, ry, x + w - 2, ry + PARTNER_ROW_H, COLOR_HOVER);
            } else if (selected) {
                gg.fill(x + 2, ry, x + w - 2, ry + PARTNER_ROW_H, 0x334EA1FF);
            }

            int tx = x + 6;
            if (row.stack() != null && !row.stack().isEmpty()) {
                gg.renderItem(row.stack(), x + 3, ry);
                tx = x + 22;
            }
            gg.drawString(this.font, trim(row.label(), x + w - tx - 8), tx, ry + 4,
                    selected ? COLOR_ACCENT : COLOR_TEXT, false);
        }
        gg.disableScissor();

        // 悬浮 #标签：预览里面有哪些物品（每 2 秒翻页）
        if (hoverRow != null && hoverRow.value().startsWith("#")) {
            String tagPath = hoverRow.value().substring(1);
            List<Item> members = tagItems(tagPath);
            List<Component> lines = new ArrayList<>();
            lines.add(Component.literal("§e#" + tagPath + " §7(" + members.size() + " 项)"));
            int pageSize = 10;
            int pages = Math.max(1, (members.size() + pageSize - 1) / pageSize);
            int page = (int) ((System.currentTimeMillis() / 2000L) % pages);
            for (int i = page * pageSize; i < Math.min(members.size(), (page + 1) * pageSize); i++) {
                lines.add(Component.literal("§f" + new ItemStack(members.get(i)).getHoverName().getString()));
            }
            if (pages > 1) {
                lines.add(Component.literal("§8第 " + (page + 1) + "/" + pages + " 页 · 自动轮播"));
            }
            if (members.isEmpty()) {
                lines.add(Component.literal("§8（这个标签里没有物品）"));
            }
            gg.renderComponentTooltip(this.font, lines, mouseX, mouseY);
        }

        if (maxScroll > 0) {
            int barH = Math.max(10, (int) ((float) visible / rows.size() * (visible * PARTNER_ROW_H)));
            int barY = listY + (int) ((float) partnerScroll / maxScroll
                    * (visible * PARTNER_ROW_H - barH));
            gg.fill(x + w - 3, barY, x + w, barY + barH, 0x80FFFFFF);
        }
    }

    private boolean handlePartnerPickerClick(double mouseX, double mouseY) {
        int x = pickerX(), y = pickerY(), w = pickerW(), h = pickerH();
        if (mouseX < x || mouseX > x + w || mouseY < y || mouseY > y + h) {
            closePartnerPicker();
            return true;
        }
        // 页签切换
        int tabY = y + 3;
        int tabW = 56;
        int tab1X = x + 44;
        int tab2X = tab1X + tabW + 4;
        if (mouseY >= tabY && mouseY <= tabY + 14) {
            if (mouseX >= tab1X && mouseX <= tab1X + tabW) {
                partnerTab = 0;
                partnerScroll = 0;
                return true;
            }
            if (mouseX >= tab2X && mouseX <= tab2X + tabW) {
                partnerTab = 1;
                partnerScroll = 0;
                return true;
            }
        }

        // 右侧滑条：点住拖
        if (mouseX >= x + w - 5 && mouseX <= x + w && mouseY >= y + 38 && mouseY <= y + h - 6) {
            draggingPartnerBar = true;
            scrollPartnerFromMouse(mouseY);
            return true;
        }

        int listY = y + 38;
        if (mouseY >= listY) {
            int idx = partnerScroll + (int) ((mouseY - listY) / PARTNER_ROW_H);
            List<PartnerRow> rows = filteredPartnerRows();
            if (idx >= 0 && idx < rows.size()) {
                PartnerRow row = rows.get(idx);
                applyPartner(row.value(), row.label());
            }
        }
        return true;
    }

    private void applyPartner(String value, String label) {
        if (rangePickerIndex < 0 || rangePickerIndex >= defaultRanges.size()) {
            closePartnerPicker();
            return;
        }
        Rule.Range range = defaultRanges.get(rangePickerIndex);
        if (value == null || value.isEmpty()) {
            // 「不限」：清空这一组范围的所有搭配
            range.partnerGroups.clear();
            range.partnerGroups.add(new ArrayList<>());
            range.syncPartners();
            setStatus("范围 " + (rangePickerIndex + 1) + "：搭配不限");
        } else {
            List<List<String>> groups = range.groups();
            int gi = partnerTargetGroup;
            boolean and = gi >= 0 && gi < groups.size();
            if (!and) {
                groups.add(new ArrayList<>());
                gi = groups.size() - 1;
            }
            List<String> group = groups.get(gi);
            if (!group.contains(value)) group.add(value);
            range.syncPartners();
            setStatus("范围 " + (rangePickerIndex + 1) + " 添加搭配（"
                    + (and ? "与" : "或") + "）：" + label);
        }
        List<String> flat = range.partners;
        ClientSettings.defaultPartner = flat.isEmpty() ? "" : flat.get(0);
        ClientSettings.defaultPartnerLabel = flat.isEmpty() ? "" : partnerDisplay(flat.get(0));
        ClientSettings.touch();
        closePartnerPicker();
    }

    // ------------------------------------------------------------ 范围编辑器（多组「类型 + 搭配」）

    private int rangeW() {
        return Math.min(panelW() - 20, 340);
    }

    private int rangeH() {
        return Math.min(panelH() - 20, 320);
    }

    private int rangeX() {
        return panelX() + (panelW() - rangeW()) / 2;
    }

    private int rangeY() {
        return panelY() + (panelH() - rangeH()) / 2;
    }

    private record RangeHit(int x, int y, int w, int h, String action, int range, int group, int partner) {
    }

    private final List<RangeHit> rangeHits = new ArrayList<>();

    private static final int RANGE_ROW_H = 13;
    private static final int RANGE_PARTNER_H = 11;
    private static final int RANGE_GAP = 3;

    private void openRangeEditor(int ignored) {
        closeTypePicker();
        closePartnerPicker();
        rangeEditorOpen = true;
        rangeEditorScroll = 0;
        rangeHits.clear();
    }

    private void closeRangeEditor() {
        rangeEditorOpen = false;
        rangeHits.clear();
    }

    /** 当前编辑的范围列表（属性台模板） */
    private List<Rule.Range> currentRanges() {
        return defaultRanges;
    }

    private int rangeContentHeight() {
        int total = 0;
        for (Rule.Range range : currentRanges()) {
            total += RANGE_ROW_H;
            for (java.util.List<String> group : range.groups()) {
                if (group.isEmpty()) continue;
                // 成员 + 「添加（与）」+ 上下边框
                total += group.size() * RANGE_PARTNER_H + RANGE_PARTNER_H + 6;
            }
            total += RANGE_PARTNER_H + RANGE_GAP; // 末尾「添加（或）」
        }
        return total;
    }

    private void openTypePickerForRange(int rangeIndex) {
        rangePickerIndex = rangeIndex;
        pickerForRule = -1;
        typePickerOpen = true;
        typeScroll = 0;
        positionPickerSearch();
    }

    private void openPartnerPickerForRange(int rangeIndex, int groupIndex) {
        rangePickerIndex = rangeIndex;
        partnerTargetGroup = groupIndex;
        partnerTab = 0;
        if (partnerPickerOpen) closePartnerPicker();
        partnerPickerOpen = true;
        partnerScroll = 0;
        partnerSearchText = "";
        if (partnerSearch != null) {
            partnerSearch.setValue("");
            positionPartnerSearch();
            this.setFocused(partnerSearch);
        }
    }

    private void drawRangeEditor(GuiGraphics gg, int mouseX, int mouseY) {
        rangeHits.clear();
        gg.flush();
        gg.fill(0, 0, this.width, this.height, 0xF0121218);

        int x = rangeX(), y = rangeY(), w = rangeW(), h = rangeH();
        gg.fill(x, y, x + w, y + h, 0xF51C1C26);
        gg.renderOutline(x, y, w, h, COLOR_EDGE);

        gg.drawString(this.font, "范围与搭配", x + 6, y + 5, COLOR_ACCENT, false);
        gg.drawString(this.font, "框内「与」· 框外「或」", x + 78, y + 5, COLOR_FAINT, false);
        gg.fill(x + 4, y + 18, x + w - 4, y + 19, COLOR_EDGE);

        int listTop = y + 22;
        int listBottom = y + h - 26;
        int contentH = rangeContentHeight();
        int viewH = listBottom - listTop;
        int maxScroll = Math.max(0, contentH - viewH);
        rangeEditorScroll = Math.max(0, Math.min(maxScroll, rangeEditorScroll));

        List<Rule.Range> ranges = currentRanges();
        int cy = listTop - rangeEditorScroll;

        gg.enableScissor(x + 2, listTop, x + w - 2, listBottom);
        for (int i = 0; i < ranges.size(); i++) {
            Rule.Range range = ranges.get(i);
            int rowTop = cy;

            // ---- 范围组头（点它改配方类型）
            boolean hoverHeader = mouseX >= x + 4 && mouseX <= x + w - 4
                    && mouseY >= rowTop && mouseY < rowTop + RANGE_ROW_H;
            if (hoverHeader) gg.fill(x + 4, rowTop, x + w - 4, rowTop + RANGE_ROW_H, COLOR_HOVER);
            gg.drawString(this.font, (i + 1) + ".", x + 6, rowTop + 2, COLOR_FAINT, false);
            String typeName = range.hasType() ? shortType(range.type) : "全部类型";
            gg.drawString(this.font, trim(typeName, w - 60), x + 24, rowTop + 2,
                    range.hasType() ? COLOR_INFO : COLOR_TEXT, false);
            boolean canDelete = ranges.size() > 1;
            gg.drawString(this.font, "×", x + w - 14, rowTop + 2,
                    canDelete ? 0xFFFF6B6B : COLOR_OFF, false);
            rangeHits.add(new RangeHit(x + 4, rowTop, w - 20, RANGE_ROW_H, "type", i, -1, -1));
            if (canDelete) {
                rangeHits.add(new RangeHit(x + w - 18, rowTop, 16, RANGE_ROW_H, "delRange", i, -1, -1));
            }

            // ---- 每个「与」组一个框：成员 + 框内「添加（与）」，空组不显示
            int py = rowTop + RANGE_ROW_H;
            List<List<String>> groups = range.groups();
            boolean anyGroup = false;
            for (int gi = 0; gi < groups.size(); gi++) {
                List<String> group = groups.get(gi);
                if (group.isEmpty()) continue;
                anyGroup = true;

                int boxTop = py;
                int boxH = group.size() * RANGE_PARTNER_H + RANGE_PARTNER_H + 4;
                gg.renderOutline(x + 20, boxTop, w - 30, boxH, 0xFF4A5670);

                int iy = boxTop + 2;
                for (int p = 0; p < group.size(); p++) {
                    String value = group.get(p);
                    boolean hoverP = mouseX >= x + 22 && mouseX <= x + w - 6
                            && mouseY >= iy && mouseY < iy + RANGE_PARTNER_H;
                    if (hoverP) gg.fill(x + 22, iy, x + w - 6, iy + RANGE_PARTNER_H, COLOR_HOVER);
                    gg.drawString(this.font, "•", x + 26, iy + 1, COLOR_FAINT, false);
                    gg.drawString(this.font, trim(partnerDisplay(value), w - 74), x + 36, iy + 1,
                            COLOR_TEXT, false);
                    gg.drawString(this.font, "×", x + w - 14, iy + 1, 0xFFFF6B6B, false);
                    rangeHits.add(new RangeHit(x + w - 18, iy, 16, RANGE_PARTNER_H,
                            "delPartner", i, gi, p));
                    iy += RANGE_PARTNER_H;
                }

                // 框内「添加（与）」：加到本组
                boolean hoverAnd = mouseX >= x + 22 && mouseX <= x + w - 6
                        && mouseY >= iy && mouseY < iy + RANGE_PARTNER_H;
                if (hoverAnd) gg.fill(x + 22, iy, x + w - 6, iy + RANGE_PARTNER_H, COLOR_HOVER);
                gg.drawString(this.font, "+ 添加（与）", x + 26, iy + 1, COLOR_INFO, false);
                rangeHits.add(new RangeHit(x + 22, iy, w - 30, RANGE_PARTNER_H,
                        "addAnd", i, gi, -1));

                py = boxTop + boxH + 2;
            }

            // ---- 有组时末尾只有「或」；空范围时并排「与 / 或」
            if (anyGroup) {
                boolean hoverOr = mouseX >= x + 22 && mouseX <= x + w - 6
                        && mouseY >= py && mouseY < py + RANGE_PARTNER_H;
                if (hoverOr) gg.fill(x + 22, py, x + w - 6, py + RANGE_PARTNER_H, COLOR_HOVER);
                gg.drawString(this.font, "+ 添加（或）", x + 26, py + 1, COLOR_ACCENT, false);
                rangeHits.add(new RangeHit(x + 22, py, w - 30, RANGE_PARTNER_H, "addOr", i, -1, -1));
            } else {
                int bw = Math.max(40, (w - 44) / 2);
                boolean hoverAnd = mouseX >= x + 22 && mouseX <= x + 22 + bw
                        && mouseY >= py && mouseY < py + RANGE_PARTNER_H;
                gg.fill(x + 22, py, x + 22 + bw, py + RANGE_PARTNER_H,
                        hoverAnd ? 0x66FFFFFF : 0x30FFFFFF);
                gg.drawString(this.font, "+ 添加（与）", x + 26, py + 1, COLOR_INFO, false);
                rangeHits.add(new RangeHit(x + 22, py, bw, RANGE_PARTNER_H, "addAnd", i, -1, -1));

                boolean hoverOr = mouseX >= x + 26 + bw && mouseX <= x + 26 + bw * 2
                        && mouseY >= py && mouseY < py + RANGE_PARTNER_H;
                gg.fill(x + 26 + bw, py, x + 26 + bw * 2, py + RANGE_PARTNER_H,
                        hoverOr ? 0x66FFFFFF : 0x30FFFFFF);
                gg.drawString(this.font, "+ 添加（或）", x + 30 + bw, py + 1, COLOR_ACCENT, false);
                rangeHits.add(new RangeHit(x + 26 + bw, py, bw, RANGE_PARTNER_H, "addOr", i, -1, -1));
            }
            py += RANGE_PARTNER_H;

            cy = py + RANGE_GAP;
        }
        gg.disableScissor();

        if (maxScroll > 0) {
            int barH = Math.max(12, (int) ((float) viewH / contentH * viewH));
            int barY = listTop + (int) ((float) rangeEditorScroll / maxScroll * (viewH - barH));
            gg.fill(x + w - 3, barY, x + w, barY + barH, 0x80FFFFFF);
        }

        int by = y + h - 22;
        boolean hoverAddRange = inRect(mouseX, mouseY, x + 6, by, 84, 18);
        gg.fill(x + 6, by, x + 90, by + 18, hoverAddRange ? 0x66FFFFFF : 0x30FFFFFF);
        gg.drawString(this.font, "+ 添加范围", x + 14, by + 5, COLOR_TEXT, false);
        rangeHits.add(new RangeHit(x + 6, by, 84, 18, "addRange", -1, -1, -1));

        boolean hoverDone = inRect(mouseX, mouseY, x + w - 58, by, 52, 18);
        gg.fill(x + w - 58, by, x + w - 6, by + 18, hoverDone ? 0xFF3A5A8A : 0x30FFFFFF);
        gg.drawCenteredString(this.font, "完成", x + w - 32, by + 5,
                hoverDone ? 0xFFFFFFFF : COLOR_DIM);
        rangeHits.add(new RangeHit(x + w - 58, by, 52, 18, "done", -1, -1, -1));
    }

    private boolean handleRangeEditorClick(double mouseX, double mouseY) {
        int x = rangeX(), y = rangeY(), w = rangeW(), h = rangeH();
        if (mouseX < x || mouseX > x + w || mouseY < y || mouseY > y + h) {
            closeRangeEditor();
            return true;
        }
        for (RangeHit hit : rangeHits) {
            if (!inRect((int) mouseX, (int) mouseY, hit.x(), hit.y(), hit.w(), hit.h())) continue;
            switch (hit.action()) {
                case "type" -> openTypePickerForRange(hit.range());
                case "addAnd" -> openPartnerPickerForRange(hit.range(), hit.group());
                case "addOr" -> openPartnerPickerForRange(hit.range(), -1);
                case "delPartner" -> {
                    List<Rule.Range> ranges = currentRanges();
                    if (hit.range() >= 0 && hit.range() < ranges.size()) {
                        List<List<String>> groups = ranges.get(hit.range()).groups();
                        if (hit.group() >= 0 && hit.group() < groups.size()) {
                            List<String> group = groups.get(hit.group());
                            if (hit.partner() >= 0 && hit.partner() < group.size()) {
                                group.remove(hit.partner());
                                ranges.get(hit.range()).syncPartners();
                                ClientSettings.touch();
                            }
                        }
                    }
                }
                case "delRange" -> {
                    List<Rule.Range> ranges = currentRanges();
                    if (ranges.size() > 1 && hit.range() >= 0 && hit.range() < ranges.size()) {
                        ranges.remove(hit.range());
                        ClientSettings.touch();
                    }
                }
                case "addRange" -> {
                    currentRanges().add(new Rule.Range());
                    ClientSettings.touch();
                    rangeEditorScroll = Math.max(0, rangeContentHeight() - (rangeH() - 48));
                }
                case "done" -> {
                    syncTemplateToSettings();
                    closeRangeEditor();
                }
                default -> {
                }
            }
            return true;
        }
        return true;
    }

    /** 把模板第一组同步回旧字段（界面显示 / 持久化） */
    private void syncTemplateToSettings() {
        if (defaultRanges.isEmpty()) defaultRanges.add(new Rule.Range());
        Rule.Range first = defaultRanges.get(0);
        selectedType = first.type == null ? "" : first.type;
        ClientSettings.defaultRecipeType = selectedType;
        ClientSettings.defaultRecipeTypeLabel = first.hasType() ? shortType(first.type) : "";
        ClientSettings.defaultPartner = first.partners.isEmpty() ? "" : first.partners.get(0);
        ClientSettings.defaultPartnerLabel = ClientSettings.defaultPartner.isEmpty()
                ? "" : partnerDisplay(ClientSettings.defaultPartner);
        ClientSettings.defaultRangesJson = serializeRanges(defaultRanges);
        ClientSettings.touch();
    }

    private static String serializeRanges(List<Rule.Range> ranges) {
        com.google.gson.JsonArray arr = new com.google.gson.JsonArray();
        for (Rule.Range range : ranges) {
            com.google.gson.JsonObject obj = new com.google.gson.JsonObject();
            obj.addProperty("type", range.type == null ? "" : range.type);
            com.google.gson.JsonArray groups = new com.google.gson.JsonArray();
            for (List<String> group : range.groups()) {
                com.google.gson.JsonArray g = new com.google.gson.JsonArray();
                for (String partner : group) g.add(partner);
                groups.add(g);
            }
            obj.add("partnerGroups", groups);
            arr.add(obj);
        }
        return arr.toString();
    }

    private static void parseRanges(String json, List<Rule.Range> out) {
        try {
            com.google.gson.JsonElement root = com.google.gson.JsonParser.parseString(json);
            if (!root.isJsonArray()) return;
            for (com.google.gson.JsonElement element : root.getAsJsonArray()) {
                if (!element.isJsonObject()) continue;
                com.google.gson.JsonObject obj = element.getAsJsonObject();
                Rule.Range range = new Rule.Range(
                        obj.has("type") ? obj.get("type").getAsString() : "");
                if (obj.has("partnerGroups") && obj.get("partnerGroups").isJsonArray()) {
                    for (com.google.gson.JsonElement ge : obj.getAsJsonArray("partnerGroups")) {
                        if (!ge.isJsonArray()) continue;
                        List<String> group = new ArrayList<>();
                        for (com.google.gson.JsonElement p : ge.getAsJsonArray()) {
                            String value = p.getAsString();
                            if (!value.isBlank()) group.add(value);
                        }
                        if (!group.isEmpty()) range.partnerGroups.add(group);
                    }
                }
                if (range.partnerGroups.isEmpty()) range.partnerGroups.add(new ArrayList<>());
                range.syncPartners();
                out.add(range);
            }
        } catch (Throwable ignored) {
        }
    }

    private void drawRuleList(GuiGraphics gg, int mouseX, int mouseY) {
        int x = ruleX(), y = listY(), w = ruleW(), h = listH();
        gg.fill(x - 1, y - 1, x + w + 1, y + h + 1, 0x30FFFFFF);

        List<Rule> all = Rules.all();
        if (all.isEmpty()) {
            gg.drawString(this.font, "§8点击左侧条目即可添加规则", x + 4, y + 4, COLOR_DIM, false);
            return;
        }

        int rows = visibleRuleRows();
        int maxScroll = Math.max(0, all.size() - rows);
        if (ruleScroll > maxScroll) ruleScroll = maxScroll;
        if (ruleScroll < 0) ruleScroll = 0;

        gg.enableScissor(x, y, x + w, y + h);
        for (int i = 0; i < rows; i++) {
            int index = ruleScroll + i;
            if (index >= all.size()) break;
            Rule rule = all.get(index);
            int rowY = y + i * RULE_ROW_H;
            boolean hovered = mouseX >= x && mouseX <= x + w && mouseY >= rowY && mouseY < rowY + RULE_ROW_H;
            if (hovered) gg.fill(x, rowY, x + w, rowY + RULE_ROW_H, COLOR_HOVER);

            boolean serverRule = Rules.isServerRule(index);
            String typeTag = switch (rule.type) {
                case Rule.TYPE_MOD -> "§b[模]";
                case Rule.TYPE_RECIPE -> "§d[方]";
                case Rule.TYPE_KEEP -> "§d[留]";
                case Rule.TYPE_CATEGORY -> "§9[类]";
                default -> "§a[物]";
            };
            String color = rule.enabled ? "§f" : "§8";
            String src = serverRule ? "§9[服]" : "§8[本]";
            String text = src + typeTag + color + " " + trim(rule.target, w - 52);
            gg.drawString(this.font, text, x + 3, rowY + 4, rule.enabled ? COLOR_TEXT : COLOR_OFF, false);

            if (rule.hasTypeFilter() || rule.hasPartner()) {
                String typeTag2 = rangesSummary(rule);
                typeTag2 = trim(typeTag2, Math.max(20, w / 2));
                int tw = this.font.width(typeTag2);
                gg.drawString(this.font, typeTag2, x + w - 16 - tw, rowY + 4, COLOR_DIM, false);
            }
            boolean hoveredRow = mouseX >= x && mouseX <= x + w
                    && mouseY >= rowY && mouseY < rowY + RULE_ROW_H;
            if (hoveredRow) {
                // 编辑（···）与删除（×）两个热区
                gg.drawString(this.font, "···", x + w - 30, rowY + 4, COLOR_DIM, false);
                gg.drawString(this.font, "×", x + w - 11, rowY + 4, 0xFFFF6B6B, false);
            } else {
                gg.drawString(this.font, "×", x + w - 11, rowY + 4, 0xFF8A5555, false);
            }
        }
        gg.disableScissor();

        if (maxScroll > 0) {
            int barH = Math.max(10, (int) ((float) rows / all.size() * h));
            int barY = y + (int) ((float) ruleScroll / maxScroll * (h - barH));
            gg.fill(x + w - 2, barY, x + w, barY + barH, 0x60FFFFFF);
        }
    }

    private void drawFooter(GuiGraphics gg) {
        int x = listX();
        int y = footerY() - 12;
        long now = System.currentTimeMillis();
        String message = now < statusUntil ? status : "";
        int maxW = ruleW() + listW() - 240;
        if (!message.isEmpty()) {
            gg.drawString(this.font, trim(message, maxW), x, y, COLOR_OK, false);
        } else {
            String info = "隐藏配方 " + Cleaner.lastHiddenRecipes() + " · 物品 " + Cleaner.lastHiddenItems()
                    + " · " + Cleaner.lastApplyMs() + "ms";
            gg.drawString(this.font, trim(info, maxW), x, y, COLOR_FAINT, false);
        }
    }

    private void drawHoverTooltip(GuiGraphics gg, int mouseX, int mouseY) {
        if (!(mouseX >= listX() && mouseX <= listX() + listW()
                && mouseY >= listY() && mouseY <= listY() + listH())) {
            return;
        }
        int index = listScroll + (mouseY - listY()) / ROW_H;
        if (index < 0 || index >= filtered.size()) return;
        Entry entry = filtered.get(index);

        // 配方页：直接画这个配方的 JEI 布局（比一行文字直观得多）
        if (entry.tab() == Tab.RECIPE && drawRecipePreview(gg, entry, mouseX, mouseY)) {
            return;
        }

        List<Component> lines = new ArrayList<>();
        lines.add(Component.literal(entry.name().getString()));
        lines.add(Component.literal("§8" + entry.id()));
        if (entry.tab() == Tab.ITEM) {
            lines.add(Component.literal("§7点击：按属性台默认值添加规则"));
            lines.add(Component.literal("§7产物 " + (defaultOutput ? "隐藏" : "保留")
                    + " · 原料 " + (includeIngredient ? "隐藏" : "保留")
                    + " · 物品本体 " + (hideItemToo ? "隐藏" : "保留")
                    + " · " + (Rule.ACTION_REMOVE.equals(defaultAction) ? "删除" : "隐藏")
                    + " · 范围 " + (selectedType.isEmpty() ? "全部" : shortType(selectedType))));
        } else if (entry.tab() == Tab.RECIPE) {
            lines.add(Component.literal("§7点击：隐藏这一条配方"));
            lines.add(Component.literal("§8（JEI 预览不可用：可能没进过 JEI 界面或该配方未注册）"));
        }
        gg.renderComponentTooltip(this.font, lines, mouseX, mouseY);
    }

    /**
     * 悬浮配方条目时画 JEI 的配方布局（合成台 3×3、熔炉、切石机……都按 JEI 自己的
     * {@link IRecipeCategory} 渲染，所以和 JEI 里看到的一模一样）。
     *
     * @return true = 已经画了预览（调用方不要再画文字 tooltip）
     */
    private boolean drawRecipePreview(GuiGraphics gg, Entry entry, int mouseX, int mouseY) {
        IJeiRuntime runtime = Cleaner.RUNTIME;
        if (runtime == null || Minecraft.getInstance().level == null) return false;

        IRecipeLayoutDrawable<?> drawable = previewCache.get(entry.id());
        if (drawable == null) {
            drawable = buildPreview(runtime, entry.id());
            if (drawable == null) return false;
            if (previewCache.size() > 24) previewCache.clear();
            previewCache.put(entry.id(), drawable);
        }

        int pw;
        int ph;
        try {
            IRecipeCategory<?> category = drawable.getRecipeCategory();
            pw = category.getWidth();
            ph = category.getHeight();
            if (pw <= 0 || ph <= 0) return false;
        } catch (Throwable t) {
            previewCache.remove(entry.id());
            return false;
        }

        int pad = 5;
        int boxW = pw + pad * 2;
        int boxH = ph + pad * 2;
        int px = mouseX + 14;
        int py = mouseY + 12;
        if (px + boxW > this.width - 4) px = mouseX - boxW - 14;
        if (py + boxH > this.height - 4) py = this.height - boxH - 4;
        if (px < 4) px = 4;
        if (py < 4) py = 4;

        String title = entry.name().getString();
        int titleW = Math.min(this.font.width(title) + 2, Math.max(20, this.width - 8));
        int tx = Math.min(px + 2, Math.max(2, this.width - titleW - 4));
        int ty = py - 11;
        if (ty < 2) ty = py + boxH + 3;

        gg.fill(px, py, px + boxW, py + boxH, 0xF8101018);
        gg.renderOutline(px, py, boxW, boxH, COLOR_EDGE);

        try {
            drawable.setPosition(px + pad, py + pad);
            drawable.drawRecipe(gg, mouseX, mouseY);
            drawable.drawOverlays(gg, mouseX, mouseY);
        } catch (Throwable t) {
            // JEI 内部绘制失败：作废缓存并回退到文字提示
            previewCache.remove(entry.id());
            return false;
        }

        gg.drawString(this.font, trim(title, titleW), tx, ty, COLOR_TEXT, false);
        return true;
    }

    /** 按配方 id 构建 JEI 布局；拿不到（JEI 没注册 / 非原版配方）返回 null */
    private IRecipeLayoutDrawable<?> buildPreview(IJeiRuntime runtime, String entryId) {
        try {
            ResourceLocation id = ResourceLocation.tryParse(entryId);
            var level = Minecraft.getInstance().level;
            if (id == null || level == null) return null;

            Recipe<?> recipe = level.getRecipeManager().byKey(id).orElse(null);
            if (recipe == null) return null;

            ResourceLocation typeId = BuiltInRegistries.RECIPE_TYPE.getKey(recipe.getType());
            if (typeId == null) return null;

            IRecipeManager jeiRecipes = runtime.getRecipeManager();
            var typeOpt = jeiRecipes.getRecipeType(typeId);
            if (typeOpt.isEmpty()) return null;

            return createPreviewDrawable(jeiRecipes, typeOpt.get(), recipe);
        } catch (Throwable t) {
            return null;
        }
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static IRecipeLayoutDrawable<?> createPreviewDrawable(IRecipeManager jeiRecipes,
                                                                  RecipeType<?> type,
                                                                  Recipe<?> recipe) {
        IRecipeCategory category = jeiRecipes.getRecipeCategory(type);
        if (category == null) return null;
        Object drawable = jeiRecipes.createRecipeLayoutDrawable(category, recipe, EMPTY_FOCUS).orElse(null);
        return (IRecipeLayoutDrawable<?>) drawable;
    }

    // ---------------------------------------------------------------- 规则编辑弹窗

    private int editX() {
        return panelX() + (panelW() - EDIT_W) / 2;
    }

    private int editY() {
        return panelY() + (panelH() - EDIT_H) / 2;
    }

    private void openRuleEdit(int index) {
        if (index < 0 || index >= Rules.size()) return;
        if (this.getFocused() != null) this.setFocused(null); // 避免输入串进搜索框
        this.ruleEditIndex = index;
        this.ruleEditOpen = true;
    }

    private void closeRuleEdit() {
        this.ruleEditOpen = false;
    }

    /**
     * 编辑弹窗点击：改开关 / 模式立即 replaceOrAdd 写回
     * （同 type+target+recipeType 的规则会被覆盖，列表下标不变）。
     *
     * @return 点击落在弹窗内（已消费）= true；点弹窗外 = false（调用方关闭弹窗）
     */
    private boolean handleRuleEditClick(double mouseX, double mouseY) {
        int x = editX(), y = editY();
        if (mouseX < x || mouseX > x + EDIT_W || mouseY < y || mouseY > y + EDIT_H) {
            return false;
        }
        Rule r = Rules.get(ruleEditIndex);
        if (r == null) {
            closeRuleEdit();
            return true;
        }
        if (mouseY >= y + 38 && mouseY < y + 54) {
            if (mouseX >= x + 8 && mouseX < x + 64) {
                r.hideOutput = !r.hideOutput;
                applyEditRule(r);
            } else if (mouseX >= x + 88 && mouseX < x + 144) {
                r.hideIngredient = !r.hideIngredient;
                applyEditRule(r);
            }
        } else if (mouseY >= y + 58 && mouseY < y + 74) {
            if (mouseX >= x + 8 && mouseX < x + 64) {
                r.action = Rule.ACTION_HIDE;
                applyEditRule(r);
            } else if (mouseX >= x + 72 && mouseX < x + 128) {
                r.action = Rule.ACTION_REMOVE;
                applyEditRule(r);
            }
        } else if (mouseY >= y + 84 && mouseY < y + 102) {
            if (mouseX >= x + 8 && mouseX < x + 108) {
                r.enabled = !r.enabled;
                applyEditRule(r);
                setStatus((r.enabled ? "已启用：" : "已停用：") + r.target);
            } else if (mouseX >= x + 116 && mouseX < x + 172) {
                closeRuleEdit();
            }
        } else if (mouseY >= y + 114 && mouseY < y + 130) {
            int idx = ruleEditIndex;
            closeRuleEdit();
            openTypePickerFor(idx);
        }
        return true;
    }

    // ---------------------------------------------------------------- 设置弹窗

    // ------------------------------------------------------------ 毒瘤排行

    /** 是否已有「按原料隐藏该物品」的规则（排行里用来打亮颜色） */
    private static boolean hasIngredientRule(String id) {
        for (Rule r : Rules.all()) {
            if (!r.enabled || !r.type.equals(Rule.TYPE_ITEM) || !r.target.equals(id)) continue;
            if (r.hideIngredient) return true;
        }
        return false;
    }

    private int settingsW() {
        return Math.min(panelW() - 40, 230);
    }

    private int settingsH() {
        return 108;
    }

    private int optimizeW() {
        return Math.min(panelW() - 40, 230);
    }

    private int optimizeH() {
        return 190;
    }

    private int optimizeX() {
        return panelX() + (panelW() - optimizeW()) / 2;
    }

    private int optimizeY() {
        return panelY() + (panelH() - optimizeH()) / 2;
    }

    private int settingsX() {
        return panelX() + (panelW() - settingsW()) / 2;
    }

    private int settingsY() {
        return panelY() + (panelH() - settingsH()) / 2;
    }

    private void drawSettings(GuiGraphics gg, int mouseX, int mouseY) {
        // 提交前面已排队的文字（按钮文字走 text 批次，不 flush 会盖在遮罩上）
        gg.flush();
        gg.fill(0, 0, this.width, this.height, 0xF0121218);
        int x = settingsX(), y = settingsY(), w = settingsW(), h = settingsH();
        gg.fill(x, y, x + w, y + h, 0xF01C1C26);
        gg.renderOutline(x, y, w, h, COLOR_EDGE);
        gg.drawString(this.font, "§e设置", x + 8, y + 6, COLOR_TEXT, false);
        gg.fill(x + 8, y + 19, x + w - 8, y + 20, COLOR_EDGE);

        boolean hover = inRect(mouseX, mouseY, x + 8, y + 26, w - 16, 16);
        if (hover) gg.fill(x + 8, y + 26, x + w - 8, y + 42, COLOR_HOVER);
        gg.drawString(this.font, "搜索记忆", x + 12, y + 29, COLOR_TEXT, false);
        gg.drawString(this.font, ClientSettings.rememberSearch ? "●" : "○", x + w - 26, y + 29,
                ClientSettings.rememberSearch ? COLOR_OK : COLOR_OFF, false);
        gg.drawString(this.font, "§8关掉后每次打开都是空搜索框", x + 12, y + 50, COLOR_DIM, false);
        gg.drawString(this.font, "§8（性能相关选项在「优化设置」里）", x + 12, y + 62, COLOR_FAINT, false);

        drawToggleBtn(gg, x + 8, y + h - 26, 64, 18, "完成", false,
                inRect(mouseX, mouseY, x + 8, y + h - 26, 64, 18));
    }

    /** 优化设置：只放性能 / 同步相关开关，每项都带「不支持就灰掉 + 悬浮说明」 */
    private void drawOptimize(GuiGraphics gg, int mouseX, int mouseY) {
        gg.flush();
        gg.fill(0, 0, this.width, this.height, 0xF0121218);
        int x = optimizeX(), y = optimizeY(), w = optimizeW(), h = optimizeH();
        gg.fill(x, y, x + w, y + h, 0xF01C1C26);
        gg.renderOutline(x, y, w, h, COLOR_EDGE);
        gg.drawString(this.font, "§e优化设置", x + 8, y + 6, COLOR_TEXT, false);
        String sub = "只影响性能，不影响规则内容";
        gg.drawString(this.font, "§8" + sub, x + w - 8 - this.font.width(sub), y + 6, COLOR_FAINT, false);
        gg.fill(x + 8, y + 19, x + w - 8, y + 20, COLOR_EDGE);

        // ① 分页懒布局
        boolean lazyOk = LazyPages.supported();
        boolean lazyOn = lazyOk && ClientSettings.lazyPages;
        boolean lazyHover = inRect(mouseX, mouseY, x + 8, y + 26, w - 16, 16);
        if (lazyHover && lazyOk) gg.fill(x + 8, y + 26, x + w - 8, y + 42, COLOR_HOVER);
        gg.drawString(this.font, "分页懒布局", x + 12, y + 29, lazyOk ? COLOR_TEXT : COLOR_FAINT, false);
        gg.drawString(this.font, lazyOn ? "●" : "○", x + w - 26, y + 29,
                lazyOn ? COLOR_OK : COLOR_OFF, false);
        if (lazyOk) {
            gg.drawString(this.font, "§8>128 条的分类只建当前页", x + 12, y + 48, COLOR_DIM, false);
            gg.drawString(this.font, "§8仍可翻完全部，防按 U 炸内存", x + 12, y + 58, COLOR_FAINT, false);
        } else {
            gg.drawString(this.font, trim("不可用：" + LazyPages.reason(), w - 20),
                    x + 12, y + 48, 0xFFB06A6A, false);
            gg.drawString(this.font, "§8悬浮查看原因", x + 12, y + 58, COLOR_FAINT, false);
        }

        gg.fill(x + 8, y + 70, x + w - 8, y + 71, COLOR_EDGE);

        // ② 自动同步
        boolean autoHover = inRect(mouseX, mouseY, x + 8, y + 76, w - 16, 16);
        if (autoHover) gg.fill(x + 8, y + 76, x + w - 8, y + 92, COLOR_HOVER);
        gg.drawString(this.font, "自动同步", x + 12, y + 79, COLOR_TEXT, false);
        gg.drawString(this.font, ClientSettings.autoSync ? "●" : "○", x + w - 26, y + 79,
                ClientSettings.autoSync ? COLOR_OK : COLOR_OFF, false);
        gg.drawString(this.font, ClientSettings.autoSync
                        ? "§8改完约 0.4 秒自动重算" : "§8关着：要点「同步」才生效",
                x + 12, y + 98, COLOR_DIM, false);
        gg.drawString(this.font, "§8关掉可避免编辑时反复全量扫描", x + 12, y + 108, COLOR_FAINT, false);

        gg.fill(x + 8, y + h - 34, x + w - 8, y + h - 33, COLOR_EDGE);
        drawToggleBtn(gg, x + 8, y + h - 26, 64, 18, "完成", false,
                inRect(mouseX, mouseY, x + 8, y + h - 26, 64, 18));

        if (lazyHover) {
            List<Component> lines = new ArrayList<>();
            lines.add(Component.literal("§e分页懒布局"));
            lines.add(Component.literal("§7大分类按 U 时，JEI 会把整个列表的布局"));
            lines.add(Component.literal("§7一次性建完，条数一多就是卡死 + 内存耗尽。"));
            lines.add(Component.literal("§7开启后只建当前页，仍然能翻完全部。"));
            if (lazyOk) {
                lines.add(Component.literal("§a可用 §8· JEI " + LazyPages.jeiVersion()));
                lines.add(Component.literal("§8点击切换"));
            } else {
                lines.add(Component.literal("§c不可用 §8· JEI " + LazyPages.jeiVersion()));
                lines.add(Component.literal("§7原因：" + LazyPages.reason()));
            }
            gg.renderComponentTooltip(this.font, lines, mouseX, mouseY);
        } else if (autoHover) {
            List<Component> lines = new ArrayList<>();
            lines.add(Component.literal("§e自动同步"));
            lines.add(Component.literal("§7开着：改完规则约 0.4 秒自动重算一次。"));
            lines.add(Component.literal("§7关着：规则改动先记着，点顶部「同步」才生效。"));
            lines.add(Component.literal("§8关掉的好处：连续编辑时不会一顿一顿地全量扫描。"));
            gg.renderComponentTooltip(this.font, lines, mouseX, mouseY);
        }
    }

    private boolean handleOptimizeClick(double mouseX, double mouseY) {
        int x = optimizeX(), y = optimizeY(), w = optimizeW(), h = optimizeH();
        if (mouseX < x || mouseX > x + w || mouseY < y || mouseY > y + h) {
            optimizeOpen = false;
            return true;
        }
        if (mouseX >= x + 8 && mouseX <= x + w - 8 && mouseY >= y + 26 && mouseY < y + 42) {
            if (!LazyPages.supported()) {
                setStatus("分页懒布局不可用：" + LazyPages.reason());
                return true;
            }
            ClientSettings.lazyPages = !ClientSettings.lazyPages;
            ClientSettings.touch();
            setStatus(ClientSettings.lazyPages ? "分页懒布局：已开启" : "分页懒布局：已关闭");
            return true;
        }
        if (mouseX >= x + 8 && mouseX <= x + w - 8 && mouseY >= y + 76 && mouseY < y + 92) {
            ClientSettings.autoSync = !ClientSettings.autoSync;
            ClientSettings.touch();
            setStatus(ClientSettings.autoSync
                    ? "自动同步：已开启（改完自动重算）"
                    : "自动同步：已关闭（改完点「同步」才生效）");
            return true;
        }
        if (mouseX >= x + 8 && mouseX <= x + 72 && mouseY >= y + h - 26 && mouseY < y + h - 8) {
            optimizeOpen = false;
            return true;
        }
        return true;
    }

    private boolean handleSettingsClick(double mouseX, double mouseY) {
        int x = settingsX(), y = settingsY(), w = settingsW(), h = settingsH();
        if (mouseX < x || mouseX > x + w || mouseY < y || mouseY > y + h) {
            settingsOpen = false;
            return true;
        }
        if (mouseX >= x + 8 && mouseX <= x + w - 8 && mouseY >= y + 26 && mouseY < y + 42) {
            ClientSettings.rememberSearch = !ClientSettings.rememberSearch;
            ClientSettings.touch();
            return true;
        }
        if (mouseX >= x + 8 && mouseX <= x + 72 && mouseY >= y + h - 26 && mouseY < y + h - 8) {
            settingsOpen = false;
            return true;
        }
        return true;
    }

    private void applyEditRule(Rule r) {
        if (!ClientSettings.admin) r.action = Rule.ACTION_HIDE;
        if (Rules.isServerRule(ruleEditIndex)) {
            if (!(ClientSettings.admin && editServer)) return;
            Rules.updateServerAt(ruleEditIndex, r);
            Rules.pushServerRules();
        } else {
            Rules.updateAt(ruleEditIndex, r);
        }
    }

    private void drawRuleEdit(GuiGraphics gg, int mouseX, int mouseY) {
        // 提交前面已排队的文字（按钮文字走 text 批次，不 flush 会盖在遮罩上）
        gg.flush();
        gg.fill(0, 0, this.width, this.height, 0xF0121218);
        int x = editX(), y = editY();
        gg.fill(x, y, x + EDIT_W, y + EDIT_H, 0xF01C1C26);
        gg.renderOutline(x, y, EDIT_W, EDIT_H, COLOR_EDGE);

        Rule r = Rules.get(ruleEditIndex);
        if (r == null) {
            gg.drawString(this.font, "§8规则不存在", x + 8, y + 60, COLOR_DIM, false);
            return;
        }

        gg.drawString(this.font, "§e编辑规则", x + 8, y + 6, COLOR_TEXT, false);
        String title = r.target + ((r.hasTypeFilter() || r.hasPartner())
                ? " " + rangesSummary(r) : "");
        gg.drawString(this.font, trim(title, EDIT_W - 16), x + 8, y + 20,
                r.enabled ? COLOR_TEXT : COLOR_OFF, false);
        gg.fill(x + 8, y + 33, x + EDIT_W - 8, y + 34, COLOR_EDGE);

        boolean hoverOut = inRect(mouseX, mouseY, x + 8, y + 38, 56, 16);
        boolean hoverIng = inRect(mouseX, mouseY, x + 88, y + 38, 56, 16);
        if (hoverOut) gg.fill(x + 8, y + 38, x + 64, y + 54, COLOR_HOVER);
        if (hoverIng) gg.fill(x + 88, y + 38, x + 144, y + 54, COLOR_HOVER);
        gg.drawString(this.font, "产物", x + 12, y + 41, COLOR_TEXT, false);
        gg.drawString(this.font, r.hideOutput ? "●" : "○", x + 44, y + 41,
                r.hideOutput ? COLOR_OK : COLOR_OFF, false);
        gg.drawString(this.font, "原料", x + 92, y + 41, COLOR_TEXT, false);
        gg.drawString(this.font, r.hideIngredient ? "●" : "○", x + 124, y + 41,
                r.hideIngredient ? COLOR_OK : COLOR_OFF, false);

        boolean remove = Rule.ACTION_REMOVE.equals(r.action);
        drawToggleBtn(gg, x + 8, y + 58, 56, 16, "隐藏", !remove,
                inRect(mouseX, mouseY, x + 8, y + 58, 56, 16));
        drawToggleBtn(gg, x + 72, y + 58, 56, 16, "删除", remove,
                inRect(mouseX, mouseY, x + 72, y + 58, 56, 16));

        drawToggleBtn(gg, x + 8, y + 84, 100, 18, r.enabled ? "停用" : "启用", false,
                inRect(mouseX, mouseY, x + 8, y + 84, 100, 18));
        drawToggleBtn(gg, x + 116, y + 84, 56, 18, "完成", false,
                inRect(mouseX, mouseY, x + 116, y + 84, 56, 18));

        boolean hoverRange = inRect(mouseX, mouseY, x + 8, y + 114, EDIT_W - 16, 16);
        if (hoverRange) gg.fill(x + 8, y + 114, x + EDIT_W - 8, y + 130, COLOR_HOVER);
        String info = "范围 " + rangesSummary(r)
                + (r.hideItem ? "  隐形物品" : "");
        gg.drawString(this.font, trim(info, EDIT_W - 16), x + 8, y + 118, COLOR_DIM, false);
    }

    // ---------------------------------------------------------------- 配方类型选择（范围弹窗）

    private static List<String> recipeTypes() {
        if (recipeTypes != null) return recipeTypes;
        List<String> all = new ArrayList<>();
        for (ResourceLocation id : BuiltInRegistries.RECIPE_TYPE.keySet()) {
            all.add(id.toString());
        }
        List<String> priority = List.of(
                "minecraft:crafting",
                "minecraft:smelting",
                "minecraft:blasting",
                "minecraft:smoking",
                "minecraft:campfire_cooking",
                "minecraft:stonecutting",
                "minecraft:smithing");
        all.sort((a, b) -> {
            int ia = priority.indexOf(a);
            int ib = priority.indexOf(b);
            if (ia >= 0 || ib >= 0) {
                if (ia < 0) return 1;
                if (ib < 0) return -1;
                return Integer.compare(ia, ib);
            }
            return a.compareTo(b);
        });
        recipeTypes = all;
        return all;
    }

    private int pickerW() {
        return Math.min(panelW() - 40, 230);
    }

    /** 比原来高 20px：页签下方多了一行搜索框 */
    private int pickerH() {
        return Math.min(panelH() - 40, 220);
    }

    private int pickerX() {
        return panelX() + (panelW() - pickerW()) / 2;
    }

    private int pickerY() {
        return panelY() + (panelH() - pickerH()) / 2;
    }

    /** 把弹窗搜索框定位到弹窗内（打开时 / 窗口缩放后调用） */
    private void positionPickerSearch() {
        if (pickerSearch != null) {
            pickerSearch.setPosition(pickerX() + Math.max(0, (pickerW() - 210) / 2), pickerY() + 21);
        }
    }

    /** 关闭范围弹窗：搜索框挪出屏幕并释放输入焦点 */
    private void closeTypePicker() {
        typePickerOpen = false;
        if (pickerSearch != null) {
            pickerSearch.setFocused(false);
            pickerSearch.setPosition(0, -200);
        }
        this.setFocused(null);
    }

    private List<CatalystEntry> catalysts() {
        IJeiRuntime runtime = Cleaner.RUNTIME;
        if (runtime == null) return List.of();
        if (catalystCache != null && catalystCacheKey == runtime) return catalystCache;

        Map<Item, java.util.LinkedHashSet<String>> map = new java.util.LinkedHashMap<>();
        try {
            var manager = runtime.getRecipeManager();
            List<IRecipeCategory<?>> categories = manager.createRecipeCategoryLookup().get().toList();
            for (IRecipeCategory<?> category : categories) {
                var type = category.getRecipeType();
                try {
                    manager.createRecipeCatalystLookup(type).getItemStack().forEach(stack -> {
                        if (stack == null || stack.isEmpty()) return;
                        map.computeIfAbsent(stack.getItem(), k -> new java.util.LinkedHashSet<>())
                                .add(type.getUid().toString());
                    });
                } catch (Throwable ignored) {
                }
            }
        } catch (Throwable t) {
            return List.of();
        }

        List<CatalystEntry> list = new ArrayList<>();
        for (Map.Entry<Item, java.util.LinkedHashSet<String>> e : map.entrySet()) {
            ItemStack stack = new ItemStack(e.getKey());
            list.add(new CatalystEntry(stack, stack.getHoverName().getString(), String.join(",", e.getValue())));
        }
        list.sort(Comparator.comparing(CatalystEntry::name));
        catalystCache = list;
        catalystCacheKey = runtime;
        return list;
    }

    /** 范围弹窗的行：搜索框非空时按统一搜索的等级过滤并排序 */
    private List<Row> pickerRows() {
        List<Row> all = new ArrayList<>();
        all.add(new Row("全部类型", "", null, selectedType.isEmpty(), 0));
        if (pickerMode == 1) {
            for (CatalystEntry c : catalysts()) {
                all.add(new Row(c.name(), c.types(), c.stack(), c.types().equals(selectedType), 2));
            }
        } else {
            for (String type : recipeTypes()) {
                all.add(new Row(type, type, null, type.equals(selectedType), 1));
            }
        }
        String q = pickerSearchText == null ? "" : pickerSearchText.trim().toLowerCase(Locale.ROOT);
        if (q.isEmpty()) return all;
        List<Row> t1 = new ArrayList<>();
        List<Row> t2 = new ArrayList<>();
        List<Row> t3 = new ArrayList<>();
        for (Row r : all) {
            switch (rowTier(r, q)) {
                case 1 -> t1.add(r);
                case 2 -> t2.add(r);
                case 3 -> t3.add(r);
                default -> { }
            }
        }
        all.clear();
        all.addAll(t1);
        all.addAll(t2);
        all.addAll(t3);
        return all;
    }

    /** 弹窗行命中等级：1=拼音首字母，2=类型 id（英文），3=中文名称 */
    private static int rowTier(Row r, String q) {
        if (!isPureChinese(q) && PinyinIndex.initialsMatch(r.label(), q)) return 1;
        String id = (r.types() == null ? "" : r.types()).toLowerCase(Locale.ROOT);
        if (id.contains(q)) return 2;
        if (r.label().toLowerCase(Locale.ROOT).contains(q)) return 3;
        return -1;
    }

    private void drawPickerTab(GuiGraphics gg, int x, int y, int w, String label, boolean active,
                               int mouseX, int mouseY) {
        boolean hover = mouseX >= x && mouseX <= x + w && mouseY >= y && mouseY <= y + 14;
        int bg = active ? 0xFF3A5A8A : (hover ? 0x60FFFFFF : 0x30FFFFFF);
        gg.fill(x, y, x + w, y + 14, bg);
        gg.drawCenteredString(this.font, label, x + w / 2, y + 3, active ? 0xFFFFFFFF : COLOR_DIM);
    }

    private void drawTypePicker(GuiGraphics gg, int mouseX, int mouseY) {
        // 提交前面已排队的文字（按钮文字走 text 批次，不 flush 会盖在遮罩上）
        gg.flush();
        gg.fill(0, 0, this.width, this.height, 0xF0121218);

        int x = pickerX(), y = pickerY(), w = pickerW(), h = pickerH();
        gg.fill(x, y, x + w, y + h, 0xF01C1C26);
        gg.renderOutline(x, y, w, h, COLOR_EDGE);

        drawPickerTab(gg, x + 6, y + 5, 56, "按类型", pickerMode == 0, mouseX, mouseY);
        drawPickerTab(gg, x + 66, y + 5, 72, "按工作方块", pickerMode == 1, mouseX, mouseY);
        gg.drawString(this.font, "§8点外部关闭", x + w - 64, y + 6, COLOR_DIM, false);
        // 搜索框在 render() 里于本方法之后手动绘制

        List<Row> rows = pickerRows();
        int visible = Math.max(1, (h - 46) / 16);
        int maxScroll = Math.max(0, rows.size() - visible);
        if (typeScroll > maxScroll) typeScroll = maxScroll;
        if (typeScroll < 0) typeScroll = 0;

        if (pickerMode == 1 && rows.size() <= 1) {
            gg.drawString(this.font, "§8需要 JEI 就绪并进入世界", x + 8, y + 44, COLOR_DIM, false);
            return;
        }
        if (rows.isEmpty()) {
            gg.drawString(this.font, "§8没有匹配的类型", x + 8, y + 44, COLOR_DIM, false);
            return;
        }

        gg.enableScissor(x + 2, y + 39, x + w - 2, y + h - 4);
        for (int i = 0; i < visible; i++) {
            int index = typeScroll + i;
            if (index >= rows.size()) break;
            Row row = rows.get(index);
            int rowY = y + 39 + i * 16;
            boolean hovered = mouseX >= x + 2 && mouseX <= x + w - 2 && mouseY >= rowY && mouseY < rowY + 16;
            if (hovered) gg.fill(x + 2, rowY, x + w - 2, rowY + 16, COLOR_HOVER);
            int textX = x + 8;
            if (row.stack() != null && !row.stack().isEmpty()) {
                gg.renderItem(row.stack(), x + 4, rowY);
                textX = x + 24;
            }
            String label = (row.selected() ? "§e" : "§f") + trim(row.label(), x + w - textX - 8);
            gg.drawString(this.font, label, textX, rowY + 4, COLOR_TEXT, false);
        }
        gg.disableScissor();

        if (maxScroll > 0) {
            int barH = Math.max(10, (int) ((float) visible / rows.size() * (h - 50)));
            int barY = y + 39 + (int) ((float) typeScroll / maxScroll * (h - 50 - barH));
            gg.fill(x + w - 3, barY, x + w - 1, barY + barH, 0x80FFFFFF);
        }
    }

    private boolean handleTypePickerClick(double mouseX, double mouseY) {
        int x = pickerX(), y = pickerY(), w = pickerW(), h = pickerH();
        if (mouseX < x || mouseX > x + w || mouseY < y || mouseY > y + h) {
            closeTypePicker();
            return true;
        }
        if (mouseY >= y + 5 && mouseY <= y + 19) {
            if (mouseX >= x + 6 && mouseX <= x + 62) {
                pickerMode = 0;
                typeScroll = 0;
            } else if (mouseX >= x + 66 && mouseX <= x + 138) {
                pickerMode = 1;
                typeScroll = 0;
            }
            return true;
        }
        // 页签与搜索框区域：吸收点击（搜索框本身在 mouseClicked 里先处理）
        if (mouseY < y + 39) return true;

        List<Row> rows = pickerRows();
        int visible = Math.max(1, (h - 46) / 16);
        int index = typeScroll + (int) ((mouseY - (y + 39)) / 16);
        if (index >= 0 && index < rows.size() && index < typeScroll + visible) {
            Row row = rows.get(index);
            if (pickerForRule >= 0) {
                Rule target = Rules.get(pickerForRule);
                if (target != null && !Rules.isServerRule(pickerForRule)) {
                    Rule copy = target.copy();
                    copy.ensureRanges();
                    // 规则编辑弹窗里的「范围」= 第一组
                    copy.ranges.get(0).type = row.types();
                    copy.syncLegacy();
                    Rules.updateAt(pickerForRule, copy);
                    setStatus("范围：" + (row.kind() == 0 ? "全部类型" : row.label()));
                } else {
                    setStatus("服务端规则不能改范围");
                }
                pickerForRule = -1;
            } else {
                if (rangePickerIndex >= 0 && rangePickerIndex < defaultRanges.size()) {
                    defaultRanges.get(rangePickerIndex).type = row.types();
                }
                if (rangePickerIndex == 0) {
                    selectedType = row.types();
                    selectedLabel = row.kind() == 0 ? "" : row.label();
                    ClientSettings.defaultRecipeType = selectedType;
                    ClientSettings.defaultRecipeTypeLabel = selectedLabel;
                }
                ClientSettings.touch();
                setStatus("范围 " + (rangePickerIndex + 1) + "："
                        + (row.kind() == 0 ? "全部类型" : row.label()));
            }
            closeTypePicker();
        }
        return true;
    }

    private String trim(String text, int width) {
        if (text == null) return "";
        if (this.font.width(text) <= width) return text;
        return this.font.plainSubstrByWidth(text, Math.max(0, width - 6)) + "…";
    }

    // ---------------------------------------------------------------- 交互

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (optimizeOpen) {
            return handleOptimizeClick(mouseX, mouseY);
        }
        if (settingsOpen) {
            return handleSettingsClick(mouseX, mouseY);
        }

        // 规则编辑弹窗：弹窗内点击全部消费，点弹窗外关闭
        if (ruleEditOpen) {
            if (handleRuleEditClick(mouseX, mouseY)) return true;
            closeRuleEdit();
            return true;
        }

        if (partnerPickerOpen) {
            if (button == 0 && partnerSearch != null && partnerSearch.isMouseOver(mouseX, mouseY)) {
                this.setFocused(partnerSearch);
                return true;
            }
            return handlePartnerPickerClick(mouseX, mouseY);
        }

        if (typePickerOpen) {
            // 先给弹窗内的搜索框（点中它获得输入焦点），其余点击全部由弹窗吸收
            if (button == 0 && pickerSearch != null && pickerSearch.isMouseOver(mouseX, mouseY)) {
                this.setFocused(pickerSearch);
                return true;
            }
            return handleTypePickerClick(mouseX, mouseY);
        }

        if (rangeEditorOpen) {
            return handleRangeEditorClick(mouseX, mouseY);
        }

        if (super.mouseClicked(mouseX, mouseY, button)) return true;

        // 中间属性台：切换新规则默认值
        if (mouseX >= midX() && mouseX <= midX() + MID_W
                && mouseY >= listY() && mouseY <= listY() + listH()) {
            handlePropPanelClick(mouseX, mouseY);
            return true;
        }

        // 左栏滑条：点住可以直接拖
        if (mouseX >= listX() + listW() - 5 && mouseX <= listX() + listW()
                && mouseY >= listY() && mouseY <= listY() + listH()) {
            draggingListBar = true;
            scrollListFromMouse(mouseY);
            return true;
        }

        // 左栏候选条目
        if (mouseX >= listX() && mouseX <= listX() + listW()
                && mouseY >= listY() && mouseY <= listY() + listH()) {
            int index = listScroll + (int) ((mouseY - listY()) / ROW_H);
            if (index >= 0 && index < filtered.size()) {
                addFromEntry(filtered.get(index));
                return true;
            }
        }

        // 右栏滑条
        if (mouseX >= ruleX() + ruleW() - 5 && mouseX <= ruleX() + ruleW()
                && mouseY >= listY() && mouseY <= listY() + listH()) {
            draggingRuleBar = true;
            scrollRuleFromMouse(mouseY);
            return true;
        }

        // 右栏规则行
        if (mouseX >= ruleX() && mouseX <= ruleX() + ruleW()
                && mouseY >= listY() && mouseY <= listY() + listH()) {
            int index = ruleScroll + (int) ((mouseY - listY()) / RULE_ROW_H);
            if (index >= 0 && index < Rules.size()) {
                int right = ruleX() + ruleW();
                boolean onDelete = mouseX >= right - 14;
                boolean onEdit = mouseX >= right - 30 && mouseX < right - 14;

                if (Rules.isServerRule(index)) {
                    if (!(ClientSettings.admin && editServer)) {
                        setStatus("服务端规则由服主管理，本地不能改");
                        return true;
                    }
                    if (onDelete) {
                        Rule removed = Rules.get(index);
                        Rules.removeServerAt(index);
                        Rules.pushServerRules();
                        setStatus("已删除服务端规则：" + removed.target);
                    } else if (onEdit) {
                        openRuleEdit(index);
                    } else {
                        setStatus("点行尾「···」编辑服务端规则");
                    }
                    return true;
                }
                if (onDelete) {
                    Rule removed = Rules.get(index);
                    Rules.remove(index);
                    setStatus("已删除：" + removed.target);
                } else if (onEdit) {
                    openRuleEdit(index);
                } else {
                    Rules.toggleEnabled(index);
                    Rule toggled = Rules.get(index);
                    setStatus(toggled == null ? ""
                            : (toggled.enabled ? "已启用：" : "已停用：") + toggled.target);
                }
                return true;
            }
        }

        return false;
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
        if (ruleEditOpen) return true;
        if (partnerPickerOpen) {
            int visible = Math.max(1, (pickerH() - 44) / PARTNER_ROW_H);
            int maxScroll = Math.max(0, filteredPartnerRows().size() - visible);
            partnerScroll = Math.max(0, Math.min(maxScroll, partnerScroll - (int) Math.signum(delta)));
            return true;
        }
        if (rangeEditorOpen) {
            int viewH = rangeH() - 48;
            int maxScroll = Math.max(0, rangeContentHeight() - viewH);
            rangeEditorScroll = Math.max(0, Math.min(maxScroll,
                    rangeEditorScroll - (int) Math.signum(delta) * 12));
            return true;
        }
        if (typePickerOpen) {
            int visible = Math.max(1, (pickerH() - 46) / 16);
            int maxScroll = Math.max(0, pickerRows().size() - visible);
            typeScroll = Math.max(0, Math.min(maxScroll, typeScroll - (int) Math.signum(delta)));
            return true;
        }

        if (mouseX >= listX() && mouseX <= listX() + listW()
                && mouseY >= listY() && mouseY <= listY() + listH()) {
            int rows = visibleRows();
            int maxScroll = Math.max(0, filtered.size() - rows);
            listScroll = Math.max(0, Math.min(maxScroll, listScroll - (int) Math.signum(delta)));
            return true;
        }
        if (mouseX >= ruleX() && mouseX <= ruleX() + ruleW()
                && mouseY >= listY() && mouseY <= listY() + listH()) {
            int rows = visibleRuleRows();
            int maxScroll = Math.max(0, Rules.size() - rows);
            ruleScroll = Math.max(0, Math.min(maxScroll, ruleScroll - (int) Math.signum(delta)));
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, delta);
    }

    @Override
    public void onClose() {
        ClientSettings.lastSearch = ClientSettings.rememberSearch ? searchText : "";
        ClientSettings.lastTab = tab.name();
        ClientSettings.saveIfDirty();
        super.onClose();
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
