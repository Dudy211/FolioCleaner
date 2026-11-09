package com.yuneko.foliocleaner.client;

import com.yuneko.foliocleaner.DuplicateGroups;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 「重复组」编辑弹窗（自绘，不注册任何 widget —— 和项目里其它弹窗一个套路）。
 *
 * <p>上层（CleanerScreen）职责：
 * <ul>
 *   <li>弹窗打开时跳过主界面整块绘制，转而调 {@link #draw}（遮罩由本类自己画）</li>
 *   <li>把点击 / 滚轮转发给 {@link #click} / {@link #scroll}（打开时都要返回 true 吸收事件）</li>
 *   <li>关闭后若 {@link #wasSaved()} 为真，读 {@link #result()} 存盘</li>
 * </ul>
 *
 * <p>面板内容（260×220，居中于传入的 panel 矩形）：
 * <pre>
 * ┌ 重复组 ────────────────────────┐
 * │ c:ingots/steel        [×]      │   key + 关闭
 * │ ─────────────────────────────  │
 * │ 成员（点一行 = 统一到）          │
 * │   §7#c:ingots/steel            │   组原有 tag（只读展示）
 * │ [■] ● modid:item_a             │   左方块 = 参与/排除；● = 统一到（代表）
 * │ [■] ○ modid:item_b             │   取消勾选 = 进 excluded
 * │   标签：                        │
 * │   #c:ingots/steel        [×]   │   已有 tag，可删（见文件末尾假设）
 * │  [+ 添加标签]  [+ 添加物品]      │   本版占位
 * │            [取消]  [保存]       │
 * └────────────────────────────────┘
 * </pre>
 */
public final class DupPanel {

    private static final int PANEL_W = 260;
    private static final int PANEL_H = 220;
    /** 列表区行高 */
    private static final int ROW_H = 12;
    /** 列表区起点（相对面板顶）与可见行数（11 × 12 = 132，到 y+180；下方 y+186 是分隔线） */
    private static final int LIST_TOP = 48;
    private static final int VISIBLE = 11;

    private static final int COLOR_EDGE = 0xFF3E4A63;
    private static final int COLOR_TEXT = 0xFFF0F3FA;
    private static final int COLOR_DIM = 0xFFAEB8CC;
    private static final int COLOR_FAINT = 0xFF7C879C;
    private static final int COLOR_ACCENT = 0xFFFFC46B;
    private static final int COLOR_HOVER = 0x33FFC46B;
    private static final int COLOR_OK = 0xFF7CD992;

    /** 列表行类型 */
    private enum Kind { TAG_RO, MEMBER, TAG_LABEL, TAG_EDIT, BUTTONS }

    /** 列表行：kind + 文本（TAG 行是 tag id，MEMBER 行是物品 id） */
    private record Row(Kind kind, String text) {
    }

    private final Font font;

    /** 正在编辑的组（null = 从没打开过） */
    private DuplicateGroups.Group group;
    private boolean open;
    private boolean saved;

    /** 组原有 tag（只读展示，不随编辑变化） */
    private List<String> baseTags = List.of();
    /** 成员全集（allMembers 的 id） */
    private List<String> memberIds = List.of();
    /** 当前被排除的成员 id（未勾选） */
    private Set<String> excluded = new LinkedHashSet<>();
    /** 当前「统一到」的代表物品 id（null = 未选） */
    private String canonicalId;
    /** 可编辑的 tag 列表（初始 = 组 tag，可删） */
    private List<String> tags = new ArrayList<>();
    /** 用户新增的 tag（本版占位，恒空） */
    private List<String> extraTags = new ArrayList<>();
    /** 列表滚动偏移（行） */
    private int scroll;
    /** 底部提示（[+ 添加标签] / [+ 添加物品] 点了之后显示） */
    private String statusMsg = "";

    /** 手动追加的物品 id（对应 Override.extraItems） */
    private final List<String> extraItems = new ArrayList<>();
    /** 手动删掉的 tag id（对应 Override.removedTags） */
    private final Set<String> removedTags = new LinkedHashSet<>();
    /** 内联输入：0 = 加 tag，1 = 加物品 */
    private boolean editing;
    private int editKind;
    private String editText = "";

    /** 上一次 draw 时面板的实际位置（click 没有坐标参数，只能缓存） */
    private int drawX, drawY;

    public DupPanel(Font font) {
        this.font = font;
    }

    /** 打开某一组；group 为 null 时直接 return（保持原状态）。 */
    public void open(DuplicateGroups.Group group) {
        open(group, null);
    }

    /**
     * 打开某一组，并把**已保存的覆盖层**带进来。
     *
     * <p>★ 必须传 {@code saved}：{@code group} 是 {@code DuplicateGroups.scan(overrides)} 的结果，
     * 只反映「覆盖之后」的成员 / tag / 代表物品，拿不到「用户手工加过哪些 tag / 物品、删过哪些 tag」
     * 这三个原始字段。以前 open() 把它们全重置成空 → 重开面板再点保存，
     * extraTags / extraItems / removedTags 就被静默抹掉了。
     */
    public void open(DuplicateGroups.Group group, DuplicateGroups.Override saved) {
        if (group == null) return;
        this.group = group;
        DuplicateGroups.Override ov = saved != null ? saved : DuplicateGroups.Override.EMPTY;

        this.extraTags = new ArrayList<>(nullSafe(ov.extraTags()));
        this.extraItems.clear();
        this.extraItems.addAll(nullSafe(ov.extraItems()));
        this.removedTags.clear();
        this.removedTags.addAll(nullSafe(ov.removedTags()));

        // group.tags 已经过 scan() 应用：包含 extraTags、不含 removedTags
        this.tags = group.tags != null ? new ArrayList<>(group.tags) : new ArrayList<>();
        // 只读区只放「本来就有」的 tag，否则每个 tag 会显示两遍
        this.baseTags = new ArrayList<>(this.tags);
        this.baseTags.removeAll(this.extraTags);

        // 成员全集优先用 allMembers：这样已被排除的成员还能在面板里重新勾回
        List<Item> all = group.allMembers != null && !group.allMembers.isEmpty()
                ? group.allMembers
                : (group.items != null ? group.items : List.of());
        this.memberIds = new ArrayList<>(all.size());
        for (Item item : all) {
            this.memberIds.add(itemId(item));
        }
        // 初始 excluded 从 items（= allMembers - excluded）反推
        Set<String> inItems = new LinkedHashSet<>();
        if (group.items != null) {
            for (Item item : group.items) inItems.add(itemId(item));
        }
        this.excluded = new LinkedHashSet<>();
        for (String id : this.memberIds) {
            if (!inItems.contains(id)) this.excluded.add(id);
        }
        // 覆盖层里 excluded 指向「当前分组里不存在」的物品（模组更新 / 分组变化）→ 丢掉，
        // 否则会留下用户在界面上看不见、也取消不掉的隐藏项
        this.excluded.retainAll(this.memberIds);

        this.canonicalId = group.canonical != null ? itemId(group.canonical) : null;
        // 代表物品被排除掉了 → 视为未选，避免保存出「canonical 在 excluded 里」的矛盾状态
        if (this.canonicalId != null && !this.memberIds.contains(this.canonicalId)) {
            this.canonicalId = null;
        }
        this.scroll = 0;
        this.statusMsg = "";
        this.saved = false;
        this.open = true;
    }

    private static List<String> nullSafe(List<String> list) {
        return list == null ? List.of() : list;
    }

    public boolean isOpen() {
        return open;
    }

    public void close() {
        open = false;
    }

    /** 是否以 [保存] 关闭（上层据此决定要不要存盘；取消 / 点外部关闭都是 false）。 */
    public boolean wasSaved() {
        return saved;
    }

    /** 当前编辑结果，供上层存盘。[保存] 关闭后状态不清空，所以随时可读。 */
    public DuplicateGroups.Override result() {
        if (group == null) return DuplicateGroups.Override.EMPTY;
        return new DuplicateGroups.Override(
                new ArrayList<>(extraTags),
                new ArrayList<>(extraItems),
                new ArrayList<>(excluded),
                canonicalId,
                new ArrayList<>(removedTags));
    }

    // ---------------------------------------------------------------- 绘制

    /** 画遮罩 + 面板。弹窗打开时上层会把主界面整块跳过，所以遮罩在这里自己画。 */
    public void draw(GuiGraphics gg, int mouseX, int mouseY,
                     int panelX, int panelY, int panelW, int panelH) {
        if (!open || font == null) return;
        gg.flush();
        // 全屏遮罩（1.20.1：窗口尺寸在 com.mojang.blaze3d.platform.Window 上）
        Minecraft mc = Minecraft.getInstance();
        gg.fill(0, 0, mc.getWindow().getGuiScaledWidth(), mc.getWindow().getGuiScaledHeight(), 0xF0121218);

        // 居中于传入的 panel 矩形
        int x = panelX + Math.max(0, (panelW - PANEL_W) / 2);
        int y = panelY + Math.max(0, (panelH - PANEL_H) / 2);
        drawX = x;
        drawY = y;
        int w = PANEL_W, h = PANEL_H;
        gg.fill(x, y, x + w, y + h, 0xF01C1C26);
        gg.renderOutline(x, y, w, h, COLOR_EDGE);

        // 标题 + key + 关闭
        gg.drawString(font, "§e重复组", x + 8, y + 6, COLOR_TEXT, false);
        String key = group.key != null ? group.key : "?";
        gg.drawString(font, "§7" + trim(key, w - 36), x + 8, y + 18, COLOR_DIM, false);
        boolean closeHover = inRect(mouseX, mouseY, x + w - 20, y + 14, 14, 12);
        gg.fill(x + w - 20, y + 14, x + w - 6, y + 26, closeHover ? 0x60FFFFFF : 0x30FFFFFF);
        gg.drawString(font, "×", x + w - 14, y + 16, closeHover ? COLOR_TEXT : COLOR_FAINT, false);

        gg.fill(x + 8, y + 32, x + w - 8, y + 33, COLOR_EDGE);
        gg.drawString(font, "§8成员（点一行 = 统一到）", x + 8, y + 36, COLOR_FAINT, false);

        // 列表：只读 tag 行 + 成员行 + 「标签：」+ 可编辑 tag 行 + 占位按钮行
        List<Row> rows = rows();
        int maxScroll = Math.max(0, rows.size() - VISIBLE);
        if (scroll > maxScroll) scroll = maxScroll;
        int listTop = y + LIST_TOP;
        int hoverIndex = -1;
        if (mouseY >= listTop && mouseY < listTop + VISIBLE * ROW_H) {
            hoverIndex = scroll + (int) ((mouseY - listTop) / ROW_H);
        }
        for (int i = 0; i < rows.size(); i++) {
            int ry = listTop + (i - scroll) * ROW_H;
            if (ry < listTop || ry >= listTop + VISIBLE * ROW_H) continue;
            Row row = rows.get(i);
            if (i == hoverIndex) {
                gg.fill(x + 8, ry, x + w - 8, ry + ROW_H, COLOR_HOVER);
            }
            switch (row.kind()) {
                case TAG_RO ->
                        gg.drawString(font, "§7#" + trim(row.text(), w - 24), x + 12, ry + 2, COLOR_FAINT, false);
                case MEMBER -> drawMemberRow(gg, x, ry, row.text());
                case TAG_LABEL ->
                        gg.drawString(font, "§e标签：", x + 12, ry + 2, COLOR_ACCENT, false);
                case TAG_EDIT -> {
                    gg.drawString(font, "§7#" + trim(row.text(), w - 44), x + 12, ry + 2, COLOR_DIM, false);
                    int bx = x + w - 24;
                    boolean delHover = hoverIndex == i && mouseX >= bx && mouseX <= bx + 14;
                    gg.fill(bx, ry, bx + 14, ry + ROW_H, delHover ? 0x60FFFFFF : 0x30FFFFFF);
                    gg.drawString(font, "×", bx + 4, ry + 2, delHover ? COLOR_TEXT : COLOR_FAINT, false);
                }
                case BUTTONS -> {
                    drawGhostBtn(gg, x + 10, ry + 1, 114, 10, "+ 添加标签", mouseX, mouseY);
                    drawGhostBtn(gg, x + 136, ry + 1, 114, 10, "+ 添加物品", mouseX, mouseY);
                }
            }
        }

        // 底部：提示（左）+ 取消 / 保存（右）
        gg.fill(x + 8, y + h - 34, x + w - 8, y + h - 33, COLOR_EDGE);
        if (editing) {
            String line = "> " + editText + ((System.currentTimeMillis() / 500) % 2 == 0 ? "\u258F" : "");
            gg.fill(x + 8, y + h - 24, x + w - 8, y + h - 12, 0x50000000);
            gg.drawString(font, "§a" + trim(line, 150), x + 10, y + h - 21, COLOR_TEXT, false);
        } else if (!statusMsg.isEmpty()) {
            gg.drawString(font, "§8" + trim(statusMsg, 120), x + 8, y + h - 21, COLOR_FAINT, false);
        }
        drawToggleBtn(gg, x + 134, y + h - 28, 56, 16, "取消",
                inRect(mouseX, mouseY, x + 134, y + h - 28, 56, 16));
        drawToggleBtn(gg, x + 196, y + h - 28, 56, 16, "保存",
                inRect(mouseX, mouseY, x + 196, y + h - 28, 56, 16));
    }

    /** 成员行：[复选框] [●/○ 统一到] 物品 id */
    private void drawMemberRow(GuiGraphics gg, int x, int ry, String id) {
        boolean checked = !excluded.contains(id);
        boolean canonical = id.equals(canonicalId);
        int cx = x + 10;
        gg.fill(cx, ry + 2, cx + 8, ry + 9, checked ? 0xFF3A5A8A : 0xFF232A38);
        gg.renderOutline(cx, ry + 2, 8, 7, COLOR_EDGE);
        gg.drawString(font, canonical ? "●" : "○", x + 24, ry + 2,
                canonical ? COLOR_OK : COLOR_FAINT, false);
        gg.drawString(font, trim(id, PANEL_W - 56), x + 36, ry + 2,
                checked ? COLOR_TEXT : COLOR_FAINT, false);
    }

    /** 小按钮：选中 = 蓝底白字，未选中 = 灰底（可悬停高亮）—— 抄 CleanerScreen.drawToggleBtn */
    private void drawToggleBtn(GuiGraphics gg, int x, int y, int w, int h, String label, boolean hovered) {
        gg.fill(x, y, x + w, y + h, hovered ? 0x60FFFFFF : 0x30FFFFFF);
        gg.drawCenteredString(font, label, x + w / 2, y + 3, hovered ? COLOR_TEXT : COLOR_DIM);
    }

    /** 占位按钮（本版点了只出提示） */
    private void drawGhostBtn(GuiGraphics gg, int bx, int by, int bw, int bh, String label,
                              int mouseX, int mouseY) {
        boolean hover = inRect(mouseX, mouseY, bx, by, bw, bh);
        gg.fill(bx, by, bx + bw, by + bh, hover ? 0x60FFFFFF : 0x30FFFFFF);
        gg.drawCenteredString(font, label, bx + bw / 2, by + 1, COLOR_DIM);
    }

    // ---------------------------------------------------------------- 交互

    public boolean click(double mouseX, double mouseY) {
        if (!open) return false;
        int x = drawX, y = drawY;
        int w = PANEL_W, h = PANEL_H;
        // 点面板外 = 关闭（和 CleanerScreen 其它弹窗一致）
        if (mouseX < x || mouseX >= x + w || mouseY < y || mouseY >= y + h) {
            close();
            return true;
        }
        // 关闭按钮
        if (inRect(mouseX, mouseY, x + w - 20, y + 14, 14, 12)) {
            close();
            return true;
        }
        // 取消 / 保存
        if (inRect(mouseX, mouseY, x + 134, y + h - 28, 56, 16)) {
            close();
            return true;
        }
        if (inRect(mouseX, mouseY, x + 196, y + h - 28, 56, 16)) {
            saved = true;
            close();
            return true;
        }
        // 列表区
        int listTop = y + LIST_TOP;
        if (mouseY >= listTop && mouseY < listTop + VISIBLE * ROW_H) {
            List<Row> rows = rows();
            int index = scroll + (int) ((mouseY - listTop) / ROW_H);
            if (index >= 0 && index < rows.size()) {
                int ry = listTop + (index - scroll) * ROW_H;
                handleRowClick(rows.get(index), mouseX, x, ry);
            }
            return true;
        }
        return true;   // 面板内其它区域：吸收
    }

    public boolean scroll(double delta) {
        if (!open) return false;
        int maxScroll = Math.max(0, rows().size() - VISIBLE);
        scroll = Math.max(0, Math.min(maxScroll, scroll - (int) Math.signum(delta)));
        return true;
    }

    // ---------------------------------------------------------------- 内联输入

    public boolean isEditing() {
        return open && editing;
    }

    private void beginEdit(int kind) {
        editing = true;
        editKind = kind;
        editText = "";
        statusMsg = kind == 0 ? "输入 tag，如 c:ingots/steel（回车确认）" : "输入物品 id（回车确认）";
    }

    /** 由上层把键盘事件转进来；返回 true = 已消费 */
    public boolean charTyped(char c) {
        if (!isEditing()) return false;
        if (editText.length() < 80 && (Character.isLetterOrDigit(c)
                || c == '_' || c == ':' || c == '#' || c == '.' || c == '-' || c == '/')) {
            editText += c;
        }
        return true;
    }

    public boolean keyPressed(int keyCode) {
        if (!isEditing()) return false;
        if (keyCode == 259) {                       // backspace
            if (!editText.isEmpty()) editText = editText.substring(0, editText.length() - 1);
            return true;
        }
        if (keyCode == 257 || keyCode == 335) {     // enter / numpad enter
            commitEdit();
            return true;
        }
        if (keyCode == 256) {                       // esc
            editing = false;
            editText = "";
            statusMsg = "";
            return true;
        }
        return true;
    }

    private void commitEdit() {
        String v = editText.trim();
        if (!v.isEmpty()) {
            if (editKind == 0) {
                if (!extraTags.contains(v)) extraTags.add(v);
                removedTags.remove(v);
                if (!tags.contains(v)) tags.add(v);
                statusMsg = "已加标签 " + v;
            } else {
                if (!extraItems.contains(v)) extraItems.add(v);
                excluded.remove(v);
                if (!memberIds.contains(v)) memberIds.add(v);   // 立刻显示在成员列表里
                statusMsg = "已加物品 " + v;
            }
        }
        editing = false;
        editText = "";
    }

    private void handleRowClick(Row row, double mouseX, int x, int ry) {
        switch (row.kind()) {
            case MEMBER -> {
                String id = row.text();
                int cx = x + 10;
                if (mouseX >= cx && mouseX <= cx + 10) {
                    // 点左侧方框 = 参与 / 排除
                    if (excluded.contains(id)) excluded.remove(id);
                    else excluded.add(id);
                } else {
                    // 点成员行 = 设为「统一到」
                    canonicalId = id;
                }
            }
            case TAG_EDIT -> {
                int bx = x + PANEL_W - 24;
                if (mouseX >= bx && mouseX <= bx + 14) {
                    tags.remove(row.text());
                    removedTags.add(row.text());
                    extraTags.remove(row.text());   // 之前手工加的 tag 也要一起撤销，否则重扫又回来了
                }
            }
            case BUTTONS -> beginEdit(mouseX < x + 130 ? 0 : 1);
            default -> { /* TAG_RO / TAG_LABEL：只读 */ }
        }
    }

    // ---------------------------------------------------------------- 内部

    /** 列表内容（每次调用现算，组规模小，开销可忽略） */
    private List<Row> rows() {
        List<Row> rows = new ArrayList<>();
        for (String tag : baseTags) rows.add(new Row(Kind.TAG_RO, tag));
        for (String id : memberIds) rows.add(new Row(Kind.MEMBER, id));
        rows.add(new Row(Kind.TAG_LABEL, ""));
        for (String tag : tags) rows.add(new Row(Kind.TAG_EDIT, tag));
        rows.add(new Row(Kind.BUTTONS, ""));
        return rows;
    }

    private static String itemId(Item item) {
        try {
            ResourceLocation rl = BuiltInRegistries.ITEM.getKey(item);
            return rl == null ? "?" : rl.toString();
        } catch (Throwable t) {
            return "?";
        }
    }

    private static boolean inRect(double mx, double my, int x, int y, int w, int h) {
        return mx >= x && mx < x + w && my >= y && my < y + h;
    }

    private String trim(String text, int width) {
        if (text == null) return "";
        if (font.width(text) <= width) return text;
        return font.plainSubstrByWidth(text, Math.max(0, width - 6)) + "…";
    }

    // ----------------------------------------------------------------
    // 假设与未完成项
    // ----------------------------------------------------------------
    // 1. 删 tag 走 Override.removedTags（scan() 里会把它们从组 tag 里去掉），已持久化。
    //    重开面板时会再把覆盖层喂回 open()，所以删除 / 追加 / 排除三种编辑都不会丢。
    // 2. [+ 添加标签] / [+ 添加物品] 是内联输入，写进 extraTags / extraItems 并随 result() 落盘；
    //    新增 tag 的成员要等重扫（保存后自动重扫）才会出现在成员列表里。
    // 3. result() 的 canonical 恒为「当前选中项」：用户没动过的组，保存后写回的
    //    canonical 与扫描时的自动推荐相同（幂等，无副作用）。
    // 4. click 没有坐标参数，面板位置缓存自上一次 draw（drawX/drawY）；
    //    上层必须保证 draw 先于 click 至少一次（正常渲染循环天然满足）。
    // 5. 被排除的成员点行仍可设为 canonical（再勾选即一致）；本版不再额外约束。
    // 6. 复选框用方块绘制（默认字体无 ☑/☐ 字形），radio 沿用项目已验证的 ●/○。
}
