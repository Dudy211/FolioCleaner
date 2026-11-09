package com.yuneko.foliocleaner.mixin;

import com.yuneko.foliocleaner.compat.DeclarativeGuards;
import net.minecraft.world.inventory.AbstractContainerMenu;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 声明式适配器的通用触发点：{@code AbstractContainerMenu#broadcastChanges} 的 TAIL。
 *
 * <p>为什么选它：几乎所有容器界面每 tick 都会同步一次槽位，覆盖范围比铁砧系的
 * {@code slotsChanged} 大得多，而且「结果槽已经有东西了才处理」这个前提能过滤掉绝大多数空转。
 *
 * <p>没有声明任何 json 适配器时，{@link DeclarativeGuards#check} 第一行就返回，
 * 成本是一次静态字段读取 + 一次虚调用。
 */
@Mixin(AbstractContainerMenu.class)
public abstract class ContainerMenuMixin {

    @Inject(method = "broadcastChanges", at = @At("TAIL"), require = 0)
    private void folio$declarativeGuard(CallbackInfo ci) {
        try {
            DeclarativeGuards.check((AbstractContainerMenu) (Object) this);
        } catch (Throwable ignored) {
        }
    }
}
