package com.yuneko.foliocleaner;

import mezz.jei.api.IModPlugin;
import mezz.jei.api.JeiPlugin;
import mezz.jei.api.runtime.IJeiRuntime;
import net.minecraft.resources.ResourceLocation;

/**
 * JEI 插件入口。只用来拿 {@link IJeiRuntime} 和感知重载。
 */
@JeiPlugin
public class FolioCleanerPlugin implements IModPlugin {

    public static final ResourceLocation UID = new ResourceLocation(FolioCleaner.MODID, "main");

    @Override
    public ResourceLocation getPluginUid() {
        return UID;
    }

    @Override
    public void onRuntimeAvailable(IJeiRuntime runtime) {
        Cleaner.RUNTIME = runtime;
        // JEI 刚重建完配方索引，稍等几 tick 让世界里的 RecipeManager 也稳定
        Cleaner.requestApplyDelayed(2);
    }

    @Override
    public void onRuntimeUnavailable() {
        Cleaner.onRuntimeGone();
    }
}
