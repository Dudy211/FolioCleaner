package com.yuneko.jeicleaner;

import com.mojang.logging.LogUtils;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.loading.FMLEnvironment;
import org.slf4j.Logger;

/**
 * JEI Cleaner —— JEI 配方显示管理器。
 *
 * <p>纯客户端模组：所有逻辑都只在客户端执行，专用服务器上加载时什么也不做。
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
