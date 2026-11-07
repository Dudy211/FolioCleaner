package com.yuneko.jeicleaner.compat;

import com.yuneko.jeicleaner.Rule;
import com.yuneko.jeicleaner.Rules;
import net.minecraft.world.Container;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import java.util.List;

/**
 * 铁砧系工作台的适配器。
 *
 * <p>覆盖所有 {@code ItemCombinerMenu} 的子类：
 * 原版铁砧、Iron's Spellbooks 奥术铁砧，以及其它模组用同一基类的合成台。
 *
 * <p>这类工作台的合成逻辑写死在 {@code slotsChanged → createResult} 里，
 * 读输入容器的前两格，所以只要判断「这两格里是不是同时出现了规则里的物品/搭配」，
 * 就能决定要不要清空结果。
 *
 * <p>只处理 {@code action=remove} + {@code type=item} + <b>范围不限类型</b> 的规则：
 * 这类工作台没有原版 RecipeType，带类型限制的规则无法判断，直接跳过（避免误伤）。
 */
public final class CombinerCraftGuard implements CraftGuard {

    @Override
    public String name() {
        return "combiner";
    }

    @Override
    public boolean blocks(Container inputs) {
        if (inputs == null) return false;
        List<Rule> rules = Rules.serverRules();
        if (rules.isEmpty()) return false;

        int size = inputs.getContainerSize();
        for (Rule rule : rules) {
            if (rule == null || !rule.enabled) continue;
            if (!Rule.ACTION_REMOVE.equals(rule.action)) continue;
            if (!Rule.TYPE_ITEM.equals(rule.type)) continue;

            Item target = ItemUtil.itemOf(rule.target);
            if (target == null) continue;

            for (Rule.Range range : rule.effectiveRanges()) {
                if (range.hasType()) continue; // 这类工作台没有 RecipeType

                int targetSlot = -1;
                for (int i = 0; i < size; i++) {
                    if (inputs.getItem(i).getItem() == target) {
                        targetSlot = i;
                        break;
                    }
                }
                if (targetSlot < 0) continue;

                for (List<String> group : range.groups()) {
                    if (group.isEmpty()) return true;
                    boolean all = true;
                    for (String partner : group) {
                        boolean found = false;
                        for (int i = 0; i < size; i++) {
                            if (i == targetSlot) continue;
                            if (ItemUtil.accepts(inputs.getItem(i), partner)) {
                                found = true;
                                break;
                            }
                        }
                        if (!found) {
                            all = false;
                            break;
                        }
                    }
                    if (all) return true;
                }
            }
        }
        return false;
    }
}
