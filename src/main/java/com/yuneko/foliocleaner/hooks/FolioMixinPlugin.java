package com.yuneko.foliocleaner.hooks;

import com.yuneko.foliocleaner.FolioCleaner;
import net.minecraftforge.fml.loading.LoadingModList;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 「结构 hook」mixin 配置的门控插件。
 *
 * <h2>为什么需要它</h2>
 * {@code foliocleaner.hooks.mixins.json} 里的 mixin 目标是**别的模组**的类
 * （例如 Ars Nouveau 的 {@code AbstractRitual}）。如果玩家没装那个模组，
 * 直接让 Mixin 去解析目标会报错/刷日志。这里在**应用前**按 modId 判断，没装就整条跳过。
 *
 * <h2>为什么用 LoadingModList 而不是 ModList</h2>
 * mixin 应用发生在 mod **构造之前**，那时 {@code ModList} 还没建好，
 * 但 {@link LoadingModList} 已经有扫描结果了 —— 这是这个阶段唯一可靠的判断依据。
 */
public final class FolioMixinPlugin implements IMixinConfigPlugin {

    /** mixin 类名 → 它依赖的 modId；不在表里的默认应用 */
    private static final Map<String, String> REQUIRES = Map.of(
            "com.yuneko.foliocleaner.hooks.mixin.ArsRitualMixin", "ars_nouveau"
    );

    @Override
    public void onLoad(String mixinPackage) {
        // 什么都不做（这里不能碰 ModList）
    }

    @Override
    public String getRefMapperConfig() {
        return null;
    }

    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        String modId = REQUIRES.get(mixinClassName);
        if (modId == null) return true;
        try {
            boolean present = LoadingModList.get().getModFileById(modId) != null;
            if (!present) {
                FolioCleaner.LOGGER.debug("[Folio Cleaner] 跳过结构 hook {}：未安装 {}", mixinClassName, modId);
            }
            return present;
        } catch (Throwable t) {
            // 判断不了就**不应用**：宁可少一个 hook，也不能让启动有风险
            return false;
        }
    }

    @Override
    public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {
    }

    @Override
    public List<String> getMixins() {
        return null;
    }

    @Override
    public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
    }

    @Override
    public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
    }
}
