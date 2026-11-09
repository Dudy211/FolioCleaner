package com.yuneko.foliocleaner;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimplePreparableReloadListener;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraftforge.event.AddReloadListenerEvent;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * 服务端事件：加载完配方后执行「删除」规则，玩家登录时下发规则。
 *
 * <p>本类运行在双端，不能引用任何 JEI 类型。
 */
@Mod.EventBusSubscriber(modid = FolioCleaner.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class ServerEvents {

    private static MinecraftServer server;

    private ServerEvents() {
    }

    /**
     * 服务端指令（控制台 / OP 都能用）：{@code /jeicleanerserver unify status|gen|rollback|wipe}。
     *
     * <p>用处：专用服务器的数据包只能在服务端生成，以前必须让一个开着客户端的管理员来点按钮；
     * 现在服主直接在控制台敲就行（而且生成完会自动 /reload）。
     */
    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        var root = event.getDispatcher().register(Commands.literal("folioserver")
                .requires(source -> source.hasPermission(2))
                .then(Commands.literal("unify")
                        .executes(ctx -> unify(ctx, ServerUnify.STATUS))
                        .then(Commands.literal("status").executes(ctx -> unify(ctx, ServerUnify.STATUS)))
                        .then(Commands.literal("gen").executes(ctx -> unify(ctx, ServerUnify.GEN)))
                        .then(Commands.literal("rollback").executes(ctx -> unify(ctx, ServerUnify.ROLLBACK)))
                        .then(Commands.literal("wipe").executes(ctx -> unify(ctx, ServerUnify.WIPE)))));

    }

    /**
     * 1.x 生成过的数据包在 {@code datapacks/jeicleaner}，2.0 只认 {@code datapacks/foliocleaner}
     * —— 旧目录不会自动清（那是用户存档里的东西），但它会继续生效，必须让管理员知道。
     */
    private static void warnLegacyPack(MinecraftServer server) {
        try {
            java.nio.file.Path legacy = server.getWorldPath(
                            net.minecraft.world.level.storage.LevelResource.ROOT)
                    .resolve("datapacks/jeicleaner");
            if (java.nio.file.Files.isDirectory(legacy)) {
                FolioCleaner.LOGGER.warn(
                        "[Folio Cleaner] 发现 1.x 遗留的数据包目录 {}：2.0 不再管理它，"
                                + "它会继续生效；建议删掉该目录后执行 /reload", legacy);
            }
        } catch (Throwable ignored) {
        }
    }

    private static int unify(com.mojang.brigadier.context.CommandContext<CommandSourceStack> ctx, String op) {
        CommandSourceStack source = ctx.getSource();
        String msg;
        try {
            String overrides = DupOverrides.toJson(DupOverrides.load());
            msg = ServerUnify.run(source.getServer(), op, overrides, true, true);
        } catch (Throwable t) {
            msg = "§c失败：" + t.getClass().getSimpleName()
                    + (t.getMessage() == null ? "" : " " + t.getMessage());
        }
        for (String line : msg.split("\n")) {
            source.sendSuccess(() -> Component.literal("[Folio Cleaner] " + line), false);
        }
        return 1;
    }

    @SubscribeEvent
    public static void onServerStarted(ServerStartedEvent event) {
        server = event.getServer();
        Rules.loadServerRules();
        ServerSettings.load();
        RecipeRemover.apply(server);
        // 统一（改配方 / 运行时）：服务端也要应用，否则专用服上这两个模式等于没开
        UnifyRuntime.applyOnServer(server, ClientSettings.unifyMode);
        FolioCleaner.LOGGER.info("[Folio Cleaner] 服务端已就绪，规则 {} 条", Rules.size());
        warnLegacyPack(server);
    }

    @SubscribeEvent
    public static void onServerStopping(ServerStoppingEvent event) {
        RecipeRemover.restore(server);
        server = null;
    }

    /** /reload 会重新加载配方，这里在重载链最后再应用一次删除规则 */
    @SubscribeEvent
    public static void onAddReloadListener(AddReloadListenerEvent event) {
        event.addListener(new SimplePreparableReloadListener<Void>() {
            @Override
            protected Void prepare(ResourceManager resourceManager, ProfilerFiller profiler) {
                return null;
            }

            @Override
            protected void apply(Void unused, ResourceManager resourceManager, ProfilerFiller profiler) {
                Rules.loadServerRules();
                if (server != null) {
                    RecipeRemover.apply(server);
                    UnifyRuntime.applyOnServer(server, ClientSettings.unifyMode);
                }
            }
        });
    }

    @SubscribeEvent
    public static void onPlayerLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            CleanerNetwork.sendTo(player);
            // 先下发规则，再要一次适配器指纹（不一致按 server-settings.json 处理）
            CleanerNetwork.requestAdapterCheck(player);
        }
    }
}
