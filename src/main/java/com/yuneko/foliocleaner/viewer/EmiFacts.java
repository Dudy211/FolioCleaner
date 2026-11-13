package com.yuneko.foliocleaner.viewer;

import dev.emi.emi.api.recipe.EmiRecipe;
import dev.emi.emi.api.recipe.EmiRecipeCategory;
import dev.emi.emi.api.stack.EmiIngredient;
import dev.emi.emi.api.stack.EmiStack;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 把 EMI 的 {@link EmiRecipe} 翻译成与 viewer 无关的 {@link RuleMatcher.Facts}。
 *
 * <p>为什么抽出来：初始化期的 {@code EmiRecipeHiderPlugin} 和运行期的
 * {@code EmiBackend} 需要**完全一致**的判断口径 —— 两处各写一份迟早漂移。
 */
public final class EmiFacts {

    private EmiFacts() {
    }

    public static RuleMatcher.Facts of(EmiRecipe recipe) {
        if (recipe == null) return null;

        ResourceLocation id = null;
        try {
            id = recipe.getId();
        } catch (Throwable ignored) {
        }

        String categoryUid = null;
        try {
            EmiRecipeCategory category = recipe.getCategory();
            if (category != null && category.getId() != null) categoryUid = category.getId().toString();
        } catch (Throwable ignored) {
        }

        // 只放真实存在的标识：分类 id（EMI 的 category id 就是配方类型的注册名）
        Set<String> typeIds = new LinkedHashSet<>();
        if (categoryUid != null) typeIds.add(categoryUid);

        List<Set<String>> inputs = new ArrayList<>();
        try {
            List<EmiIngredient> in = recipe.getInputs();
            if (in != null) {
                for (EmiIngredient ingredient : in) inputs.add(ids(ingredient));
            }
        } catch (Throwable ignored) {
        }

        Set<String> outputs = new HashSet<>();
        try {
            List<EmiStack> out = recipe.getOutputs();
            if (out != null) {
                for (EmiStack stack : out) {
                    String item = idOf(stack);
                    if (item != null) outputs.add(item);
                }
            }
        } catch (Throwable ignored) {
        }

        String mod = id == null ? "" : id.getNamespace();
        return new RuleMatcher.Facts(id == null ? null : id.toString(), categoryUid, mod,
                typeIds, inputs, outputs);
    }

    private static Set<String> ids(EmiIngredient ingredient) {
        Set<String> out = new LinkedHashSet<>();
        if (ingredient == null) return out;
        try {
            for (EmiStack stack : ingredient.getEmiStacks()) {
                String item = idOf(stack);
                if (item != null) out.add(item);
            }
        } catch (Throwable ignored) {
        }
        return out;
    }

    /** EmiStack → 物品 id；不是物品（流体等）返回 null */
    public static String idOf(EmiStack stack) {
        if (stack == null) return null;
        try {
            ItemStack item = stack.getItemStack();
            if (item == null || item.isEmpty()) return null;
            ResourceLocation id = BuiltInRegistries.ITEM.getKey(item.getItem());
            return id == null ? null : id.toString();
        } catch (Throwable t) {
            return null;
        }
    }

    /** EmiIngredient 里第一个物品栈（拿来当分类图标 / 催化剂） */
    public static ItemStack firstItem(EmiIngredient ingredient) {
        if (ingredient == null) return ItemStack.EMPTY;
        try {
            for (EmiStack stack : ingredient.getEmiStacks()) {
                ItemStack item = stack.getItemStack();
                if (item != null && !item.isEmpty()) return item;
            }
        } catch (Throwable ignored) {
        }
        return ItemStack.EMPTY;
    }
}
