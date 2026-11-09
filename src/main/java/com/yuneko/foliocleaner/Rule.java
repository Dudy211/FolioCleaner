package com.yuneko.foliocleaner;

import java.util.ArrayList;
import java.util.List;

/**
 * 一条隐藏规则。
 *
 * <p>三种类型：
 * <ul>
 *   <li>{@code item}   —— target 是物品 id，例如 {@code minecraft:diamond_sword}</li>
 *   <li>{@code mod}    —— target 是 modid，例如 {@code botania}</li>
 *   <li>{@code recipe} —— target 是配方 id，例如 {@code minecraft:stick}</li>
 * </ul>
 *
 * <p>{@code item} 规则可以通过三个开关细化行为：隐藏产出它的配方、隐藏把它当原料的配方、
 * 以及把这个物品本身从 JEI 物品列表里摘掉。
 *
 * <p>{@code recipeType} 是可选的「工作台/配方类型」限制：
 * 例如 {@code minecraft:crafting} 表示只处理工作台配方，
 * 留空表示不限制（熔炉、切石机等等全都算）。
 */
public class Rule {

    public static final String TYPE_ITEM = "item";
    public static final String TYPE_MOD = "mod";
    public static final String TYPE_RECIPE = "recipe";
    /** 白名单：只保留这些模组的配方，其余全部隐藏 */
    /** 只在 JEI 里看不到，仍然可以合成 */
    public static final String ACTION_HIDE = "hide";
    /** 从服务端配方表移除：所有人都看不到、也合成不了 */
    public static final String ACTION_REMOVE = "remove";

    public static final String TYPE_KEEP = "keep";
    /** 整个配方类型（工作台）全部隐藏 */
    public static final String TYPE_CATEGORY = "category";

    /**
     * 一组范围：一个配方类型 + 该组下的若干「搭配」。
     *
     * <p>规则可以有多组，组与组之间是<b>或</b>：任意一组命中就生效。
     * 同一组里的多个搭配也是<b>或</b>：配方里另一个输入接受任意一个即可。
     */
    public static final class Range {
        /** 空 = 不限配方类型 */
        public String type = "";

        /**
         * 扁平搭配列表（旧格式 / 兼容用）。
         *
         * <p>真正的匹配以 {@link #partnerGroups} 为准：
         * 外层是「或」，内层是「与」。这里保留一份扁平镜像方便读写旧配置。
         */
        public List<String> partners = new ArrayList<>();

        /**
         * 分组的搭配条件：外层「或」，内层「与」。
         *
         * <pre>
         * [ [A, B], [C] ]  →  (A 且 B) 或 C
         * </pre>
         */
        public List<List<String>> partnerGroups = new ArrayList<>();

        public Range() {
        }

        public Range(String type) {
            this.type = type == null ? "" : type;
        }

        public boolean hasType() {
            return type != null && !type.isBlank();
        }

        /** 有效分组：新字段优先；否则把旧 partners 变成「每个各一组」（保持原来的或语义） */
        public List<List<String>> groups() {
            if (partnerGroups == null) partnerGroups = new ArrayList<>();
            if (partnerGroups.isEmpty() && partners != null && !partners.isEmpty()) {
                for (String partner : partners) {
                    List<String> group = new ArrayList<>();
                    group.add(partner);
                    partnerGroups.add(group);
                }
            }
            if (partnerGroups.isEmpty()) partnerGroups.add(new ArrayList<>());
            return partnerGroups;
        }

        /** 把分组扁平化回 partners（兼容旧读取方） */
        public void syncPartners() {
            partners = new ArrayList<>();
            for (List<String> group : groups()) {
                for (String partner : group) {
                    if (!partners.contains(partner)) partners.add(partner);
                }
            }
        }

        public int partnerCount() {
            int n = 0;
            for (List<String> group : groups()) n += group.size();
            return n;
        }

        public boolean hasPartners() {
            return partnerCount() > 0;
        }

        public Range copy() {
            Range copy = new Range(type);
            for (List<String> group : groups()) {
                copy.partnerGroups.add(new ArrayList<>(group));
            }
            copy.syncPartners();
            return copy;
        }

        public boolean sameAs(Range other) {
            if (other == null) return false;
            if (!(type == null ? "" : type).equals(other.type == null ? "" : other.type)) return false;
            List<List<String>> a = groups();
            List<List<String>> b = other.groups();
            if (a.size() != b.size()) return false;
            for (int i = 0; i < a.size(); i++) {
                if (!a.get(i).equals(b.get(i))) return false;
            }
            return true;
        }
    }

    /** item / mod / recipe */
    public String type = TYPE_ITEM;
    /** 物品 id / modid / 配方 id */
    public String target = "";

    /** item 规则：隐藏「产出这个物品」的配方 */
    public boolean hideOutput = true;
    /** item 规则：隐藏「把这个物品当原料」的配方 */
    public boolean hideIngredient = true;
    /** item 规则：把这个物品本身也从 JEI 的物品列表里移除 */
    public boolean hideItem = false;

    /** hide / remove */
    public String action = ACTION_HIDE;

    /** 可选的配方类型限制，空字符串 = 不限制 */
    public String recipeType = "";

    /**
     * 可选的「搭配」：配方的<b>另一个输入</b>必须包含它。
     *
     * <p>普通物品 id（{@code minecraft:diamond_sword}）或物品标签（{@code #forge:swords}）。
     * 空字符串 = 不限制。用来做更精确的匹配，例如：
     * 「只隐藏升级法球在奥术铁砧里搭配武器/装备的那些配方」。
     */
    public String partner = "";

    /**
     * 新模型：多组「类型 + 搭配」。
     *
     * <p>旧字段 {@link #recipeType} / {@link #partner} 仍然保留，作为第一组的镜像，
     * 供旧指令 / 规则行显示使用；运行时匹配以本字段为准（为空时回退到旧字段）。
     * 保证至少有一组。
     */
    public List<Range> ranges = new ArrayList<>();

    /** 临时停用（保留规则但不生效） */
    public boolean enabled = true;

    /**
     * 备注：纯给人看的。
     *
     * <p>**不参与**去重（{@link #sameAs}）、也不参与内容指纹（{@link #contentHash}）——
     * 加个备注不应该让「未同步」亮起来，也不应该被当成新规则。
     */
    public String note = "";

    public Rule() {
    }

    public Rule(String type, String target) {
        this.type = type;
        this.target = target;
    }

    public Rule copy() {
        Rule r = new Rule(type, target);
        r.hideOutput = hideOutput;
        r.hideIngredient = hideIngredient;
        r.hideItem = hideItem;
        r.action = action;
        r.recipeType = recipeType;
        r.partner = partner;
        r.ranges = new ArrayList<>();
        for (Range range : effectiveRanges()) r.ranges.add(range.copy());
        r.enabled = enabled;
        return r;
    }

    public boolean isValid() {
        return target != null && !target.isBlank()
                && (TYPE_ITEM.equals(type) || TYPE_MOD.equals(type) || TYPE_RECIPE.equals(type)
                || TYPE_KEEP.equals(type) || TYPE_CATEGORY.equals(type));
    }

    /**
     * 内容指纹（不含 revision）。
     *
     * <p>用来判断「改了又改回去」：这种时候 {@link Rules#revision()} 已经变了，
     * 但内容和上次应用的一模一样，不该再提示「未同步」。
     */
    public int contentHash() {
        int h = type == null ? 0 : type.hashCode();
        h = h * 31 + (target == null ? 0 : target.hashCode());
        h = h * 31 + (hideOutput ? 1 : 0);
        h = h * 31 + (hideIngredient ? 1 : 0);
        h = h * 31 + (hideItem ? 1 : 0);
        h = h * 31 + (action == null ? 0 : action.hashCode());
        h = h * 31 + (enabled ? 1 : 0);
        for (Range range : effectiveRanges()) {
            h = h * 31 + (range.type == null ? 0 : range.type.hashCode());
            for (java.util.List<String> group : range.groups()) {
                h = h * 31 + 1;
                for (String partner : group) {
                    h = h * 31 + (partner == null ? 0 : partner.hashCode());
                }
            }
        }
        return h;
    }

    public boolean sameAs(Rule other) {
        return other != null
                && type.equals(other.type)
                && target.equals(other.target)
                && hideOutput == other.hideOutput
                && hideIngredient == other.hideIngredient
                && hideItem == other.hideItem
                && action.equals(other.action)
                && recipeType.equals(other.recipeType)
                && partner.equals(other.partner)
                && sameRanges(other)
                && enabled == other.enabled;
    }

    private boolean sameRanges(Rule other) {
        List<Range> a = effectiveRanges();
        List<Range> b = other.effectiveRanges();
        if (a.size() != b.size()) return false;
        for (int i = 0; i < a.size(); i++) {
            if (!a.get(i).sameAs(b.get(i))) return false;
        }
        return true;
    }

    /** 是否限制了配方类型（任意一组） */
    public boolean hasTypeFilter() {
        for (Range range : effectiveRanges()) {
            if (range.hasType()) return true;
        }
        return false;
    }

    /** 是否限制了「搭配」输入（任意一组） */
    public boolean hasPartner() {
        for (Range range : effectiveRanges()) {
            if (range.hasPartners()) return true;
        }
        return false;
    }

    /** 保证至少一组（用户可删到只剩一组，不允许 0 组） */
    public void ensureRanges() {
        if (ranges == null) ranges = new ArrayList<>();
        if (ranges.isEmpty()) ranges.add(new Range());
    }

    /** 运行时用的范围列表：新字段优先，空则用旧字段构造一组 */
    public List<Range> effectiveRanges() {
        if (ranges != null && !ranges.isEmpty()) return ranges;
        List<Range> list = new ArrayList<>();
        Range legacy = new Range(recipeType == null ? "" : recipeType);
        if (partner != null && !partner.isBlank()) legacy.partners.add(partner);
        list.add(legacy);
        return list;
    }

    /** 把第一组同步回旧字段（供旧指令 / 显示使用） */
    public void syncLegacy() {
        ensureRanges();
        Range first = ranges.get(0);
        recipeType = first.type == null ? "" : first.type;
        List<List<String>> groups = first.groups();
        partner = (groups.isEmpty() || groups.get(0).isEmpty()) ? "" : groups.get(0).get(0);
    }

    public int rangeCount() {
        return effectiveRanges().size();
    }

    public int partnerCount() {
        int n = 0;
        for (Range range : effectiveRanges()) n += range.partnerCount();
        return n;
    }

    /** 给指令 / 日志用的单行描述 */
    public String describe() {
        StringBuilder sb = new StringBuilder();
        if (!enabled) sb.append("(停用) ");
        if (ACTION_REMOVE.equals(action)) sb.append("(删除) ");
        switch (type) {
            case TYPE_MOD:
                sb.append("[模组] ").append(target);
                break;
            case TYPE_RECIPE:
                sb.append("[配方] ").append(target);
                break;
            case TYPE_KEEP:
                sb.append("[仅保留] ").append(target);
                break;
            case TYPE_CATEGORY:
                sb.append("[整类] ").append(target);
                break;
            default:
                sb.append("[物品] ").append(target).append(" (");
                if (hideOutput) sb.append("产物");
                if (hideIngredient) sb.append(hideOutput ? "+原料" : "原料");
                if (!hideOutput && !hideIngredient) sb.append("仅物品");
                if (hideItem) sb.append(hideOutput || hideIngredient ? "，隐形物品" : "隐形物品");
                sb.append(')');
                break;
        }
        List<Range> ranges = effectiveRanges();
        if (ranges.size() > 1) {
            sb.append(" · ").append(ranges.size()).append("组范围");
            int n = partnerCount();
            if (n > 0) sb.append(" / ").append(n).append("个搭配");
        } else {
            Range only = ranges.get(0);
            if (only.hasType()) sb.append(" @").append(only.type);
            if (only.hasPartners()) sb.append(" +").append(String.join("/", only.partners));
        }
        if (note != null && !note.isBlank()) sb.append(" §8// ").append(note);
        return sb.toString();
    }
}
