package com.yuneko.foliocleaner.mixin;

import com.yuneko.foliocleaner.ServerCraftGuard;
import net.minecraft.world.Container;
import net.minecraft.world.inventory.ItemCombinerMenu;
import net.minecraft.world.inventory.ResultContainer;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 给「铁砧系」自定义工作台（原版铁砧、Iron's Spellbooks 奥术铁砧等）做真正的删除。
 *
 * <p>这些工作台都继承 {@link ItemCombinerMenu}，合成结果在
 * {@code slotsChanged(Container)} 里生成到 {@code resultSlots}。
 * 在方法末尾检查一次结果槽：输入组合命中 {@code remove} 规则就清空，
 * 玩家那边看到的就是「没有可合成的结果」。
 *
 * <p>{@code require = 0}：一旦目标类结构变了（换 MC 版本），静默跳过，绝不因此崩客户端。
 */
@Mixin(value = ItemCombinerMenu.class, remap = false)
public abstract class ItemCombinerMenuMixin {

    /** resultSlots（ResultContainer） */
    @Shadow
    @Final
    protected ResultContainer f_39768_;

    /** inputSlots（Container） */
    @Shadow
    @Final
    protected Container f_39769_;

    @Inject(method = "m_6199_", at = @At("TAIL"), require = 0)
    private void folio$guardCombinerResult(Container container, CallbackInfo ci) {
        try {
            if (f_39768_ == null || f_39769_ == null) return;
            ItemStack result = f_39768_.getItem(0);
            if (result.isEmpty()) return;
            if (ServerCraftGuard.isBlocked(f_39769_, (Object) this)) {
                f_39768_.setItem(0, ItemStack.EMPTY);
            }
        } catch (Throwable ignored) {
            // 守卫出问题时保持原行为
        }
    }
}
