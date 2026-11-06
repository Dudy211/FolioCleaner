package com.yuneko.jeicleaner;

import com.mojang.logging.LogUtils;
import net.minecraftforge.fml.common.Mod;
import org.slf4j.Logger;

/**
 * JEI Cleaner —— JEI 配方显示 / 合成表管理器。
 *
 * <p>双端模组：
 * <ul>
 *   <li>客户端：把规则作用到 JEI（隐藏配方 / 物品）</li>
 *   <li>服务端：按 remove 规则真删 RecipeManager 条目，并拦截没有配方实体的自定义工作台</li>
 * </ul>
 */
@Mod(JeiCleaner.MODID)
public class JeiCleaner {

    public static final String MODID = "jeicleaner";
    public static final Logger LOGGER = LogUtils.getLogger();

    public JeiCleaner() {
        // 双端都加载规则：服务端用它做「删除」，客户端用它做「隐藏」
        Rules.loadServerRules();
        Rules.loadClientRules();
        CleanerNetwork.register();
        if (net.minecraftforge.fml.loading.FMLEnvironment.dist == net.minecraftforge.api.distmarker.Dist.CLIENT) {
            ClientSettings.load();
        }
        LOGGER.info("[JEI Cleaner] 规则已加载：服务端 {} 条 / 本地 {} 条",
                Rules.serverRules().size(), Rules.clientRules().size());
    }
}
