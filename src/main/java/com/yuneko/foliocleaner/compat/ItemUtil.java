package com.yuneko.foliocleaner.compat;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/** 适配器共用的小工具（物品 id / #标签 解析）。 */
public final class ItemUtil {

    private ItemUtil() {
    }

    public static Item itemOf(String id) {
        if (id == null || id.isBlank()) return null;
        ResourceLocation rl = ResourceLocation.tryParse(id);
        if (rl == null) return null;
        Item item = BuiltInRegistries.ITEM.get(rl);
        return item == Items.AIR ? null : item;
    }

    /** 这个栈是否符合 partner（物品 id 或 #标签） */
    public static boolean accepts(ItemStack stack, String partner) {
        if (stack == null || stack.isEmpty() || partner == null || partner.isBlank()) return false;
        try {
            if (partner.startsWith("#")) {
                ResourceLocation id = ResourceLocation.tryParse(partner.substring(1));
                if (id == null) return false;
                return stack.is(TagKey.create(Registries.ITEM, id));
            }
            Item item = itemOf(partner);
            return item != null && stack.getItem() == item;
        } catch (Throwable t) {
            return false;
        }
    }
}
