package com.yuneko.jeicleaner;

import java.util.ArrayList;
import java.util.List;

/**
 * 一条隐藏/删除规则。
 *
 * <p>类型：item / mod / recipe / keep（白名单）/ category（整个配方类型）。
 *
 * <p>范围模型：一条规则可以有多组 {@link Range}，组之间是「或」；
 * 每组下面可以有多条搭配，{@link Range#partnerGroups} 外层「或」、内层「与」。
 */
public class Rule {

    public static final String TYPE_ITEM = "item";
    public static final String TYPE_MOD = "mod";
    public static final String TYPE_RECIPE = "recipe";
    /** 白名单：只保留这些模组的配方 */
    public static final String TYPE_KEEP = "keep";
    /** 整个配方类型 */
    public static final String TYPE_CATEGORY = "category";

    /** 只在 JEI 里看不到，仍然可以合成 */
    public static final String ACTION_HIDE = "hide";
    /** 从服务端配方表移除（或拦结果）：看不到、合不成 */
    public static final String ACTION_REMOVE = "remove";

    /**
     * 一组范围：一个配方类型 + 若干「与/或」搭配。
     */
    public static final class Range {
        /** 空 = 不限配方类型 */
        public String type = "";

        /** 扁平搭配列表（旧格式 / 兼容用） */
        public List<String> partners = new ArrayList<>();

        /** 分组搭配：外层「或」，内层「与」：[ [A,B], [C] ] → (A且B) 或 C */
        public List<List<String>> partnerGroups = new ArrayList<>();

        public Range() {
        }

        public Range(String type) {
            this.type = type == null ? "" : type;
        }

        public boolean hasType() {
            return type != null && !type.isBlank();
        }

        /** 有效分组：新字段优先；否则把旧 partners 变成「每个各一组」 */
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

    public String type = TYPE_ITEM;
    public String target = "";

    /** item：隐藏「产出这个物品」的配方 */
    public boolean hideOutput = true;
    /** item：隐藏「把这个物品当原料」的配方 */
    public boolean hideIngredient = true;
    /** item：把这个物品本身也从 JEI 物品列表里移除 */
    public boolean hideItem = false;

    /** hide / remove */
    public String action = ACTION_HIDE;

    /** 旧字段：第一组的类型镜像（兼容） */
    public String recipeType = "";
    /** 旧字段：第一组的第一个搭配镜像（兼容） */
    public String partner = "";

    /** 多组范围（至少一组） */
    public List<Range> ranges = new ArrayList<>();

    public boolean enabled = true;

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

    public boolean hasTypeFilter() {
        for (Range range : effectiveRanges()) {
            if (range.hasType()) return true;
        }
        return false;
    }

    public boolean hasPartner() {
        for (Range range : effectiveRanges()) {
            if (range.hasPartners()) return true;
        }
        return false;
    }

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
            if (only.hasPartners()) sb.append(" +" ).append(String.join("/", only.partners));
        }
        return sb.toString();
    }
}
