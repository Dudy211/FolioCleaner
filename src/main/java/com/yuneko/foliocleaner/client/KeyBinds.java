package com.yuneko.foliocleaner.client;

import com.mojang.blaze3d.platform.InputConstants;
import com.yuneko.foliocleaner.FolioCleaner;
import net.minecraft.client.KeyMapping;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.settings.KeyConflictContext;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.lwjgl.glfw.GLFW;

/**
 * 按键绑定：默认 <b>F7</b> 开关调试面板。
 *
 * <p>为什么不放在 {@code ClientEvents}：按键注册走的是 **MOD 事件总线**
 * （{@code RegisterKeyMappingsEvent}），和 ClientEvents 那条 FORGE 总线不是一回事。
 *
 * <p>玩家可以在「选项 → 控制 → 按键绑定 → Folio Cleaner」里改成别的键 ——
 * 打开机器界面也能按（KeyMapping 的轮询在 clientTick 里，不依赖界面）。
 */
@Mod.EventBusSubscriber(modid = FolioCleaner.MODID, value = Dist.CLIENT,
        bus = Mod.EventBusSubscriber.Bus.MOD)
public final class KeyBinds {

    public static final KeyMapping DEBUG = new KeyMapping(
            "key.foliocleaner.debug",
            KeyConflictContext.IN_GAME,
            InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_F7,
            "key.categories.foliocleaner");

    private KeyBinds() {
    }

    @SubscribeEvent
    public static void register(net.minecraftforge.client.event.RegisterKeyMappingsEvent event) {
        event.register(DEBUG);
    }
}
