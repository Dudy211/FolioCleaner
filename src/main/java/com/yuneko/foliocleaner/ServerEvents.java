package com.yuneko.foliocleaner;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.core.registries.BuiltInRegistries;
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
                        .then(Commands.literal("wipe").executes(ctx -> unify(ctx, ServerUnify.WIPE))))
                .then(Commands.literal("adapters")
                        .executes(ctx -> adapters(ctx.getSource(), false))
                        .then(Commands.literal("list").executes(ctx -> adapters(ctx.getSource(), false)))
                        .then(Commands.literal("reload").executes(ctx -> adapters(ctx.getSource(), true))))
                .then(Commands.literal("adaptercheck")
                        .executes(ctx -> adapterCheck(ctx.getSource(), null, null))
                        .then(Commands.argument("fp", com.mojang.brigadier.arguments.StringArgumentType.word())
                                .executes(ctx -> adapterCheck(ctx.getSource(),
                                        com.mojang.brigadier.arguments.StringArgumentType.getString(ctx, "fp"), ""))
                                .then(Commands.argument("ids", com.mojang.brigadier.arguments.StringArgumentType.greedyString())
                                        .executes(ctx -> adapterCheck(ctx.getSource(),
                                                com.mojang.brigadier.arguments.StringArgumentType.getString(ctx, "fp"),
                                                com.mojang.brigadier.arguments.StringArgumentType.getString(ctx, "ids"))))))
                .then(Commands.literal("groups")
                        .executes(ctx -> groups(ctx.getSource(), false))
                        .then(Commands.literal("all").executes(ctx -> groups(ctx.getSource(), true))))
                .then(Commands.literal("mode")
                        .executes(ctx -> mode(ctx.getSource(), null))
                        .then(Commands.argument("m", com.mojang.brigadier.arguments.StringArgumentType.word())
                                .executes(ctx -> mode(ctx.getSource(),
                                        com.mojang.brigadier.arguments.StringArgumentType.getString(ctx, "m"))))));

    }

    /**
     * 查看 / 修改「统一实现方式」（服务端权威的值）。
     *
     * <pre>
     * /folioserver mode                  看当前值
     * /folioserver mode rewrite|match|datapack
     * </pre>
     *
     * <p>专服没有界面，这条就是唯一的改法（等价于管理员在「改:服务端」模式里点那三个按钮）。
     */
    private static int mode(CommandSourceStack source, String m) {
        if (m == null) {
            source.sendSuccess(() -> Component.literal("[Folio Cleaner] 统一实现方式："
                    + ServerSettings.unifyMode + "（客户端这一项是灰的，由服务端决定）"), false);
            return 1;
        }
        if (!ServerSettings.validMode(m)) {
            source.sendSuccess(() -> Component.literal(
                    "[Folio Cleaner] §c不认识：" + m + "（可选 datapack / rewrite / match）"), false);
            return 0;
        }
        ServerSettings.unifyMode = m;
        ServerSettings.save();
        if (server != null) {
            UnifyRuntime.applyOnServer(server, m);
            CleanerNetwork.broadcast(server);
        }
        boolean datapack = "datapack".equals(m);
        source.sendSuccess(() -> Component.literal("[Folio Cleaner] 统一实现方式已改为 " + m
                + "，已重新应用并广播"
                + (datapack ? "（数据包模式还要 /folioserver unify gen 生成，或等下次改规则自动生成）" : "")), false);
        return 1;
    }

    /**
     * 服务端看到的重复组（登录时就是这份会随规则一起下发给客户端）。
     *
     * <pre>
     * /folioserver groups       组数 + 指纹 + 前 8 组
     * /folioserver groups all   全部列出（上限 80 行）
     * </pre>
     *
     * <p>用途：客户端「统一」结果和服务端不一致时，第一件事就是对比这两边的组清单/指纹。
     * 另外 {@code rewrite} / {@code match} 模式下客户端**改用服务端这份**，所以这也是
     * 「玩家那边实际会拿到的数据」。
     */
    private static int groups(CommandSourceStack source, boolean all) {
        if (server == null) {
            source.sendSuccess(() -> Component.literal("[Folio Cleaner] 服务端还没起来"), false);
            return 0;
        }
        try {
            String text = GroupSync.fromLevel(server.overworld(), DupOverrides.load());
            java.util.Map<String, java.util.List<net.minecraft.world.item.Item>> map = GroupSync.groups(text);
            source.sendSuccess(() -> Component.literal("[Folio Cleaner] 重复组 " + map.size()
                    + " 个 · 指纹 " + GroupSync.fingerprint(text)
                    + " · 编码 " + text.length() + " 字符"), false);
            if (map.isEmpty()) {
                source.sendSuccess(() -> Component.literal(
                        "[Folio Cleaner] 没有重复组（纯净服 / 装的东西不重复时是正常的）"), false);
                return 1;
            }
            int limit = all ? 80 : 8;
            int n = 0;
            for (var e : map.entrySet()) {
                if (n++ >= limit) {
                    source.sendSuccess(() -> Component.literal("[Folio Cleaner] §8……还有 "
                            + (map.size() - limit) + " 组，用 /folioserver groups all 看全部"), false);
                    break;
                }
                StringBuilder sb = new StringBuilder();
                sb.append("  §e").append(e.getKey()).append(" §8(").append(e.getValue().size()).append(") ");
                for (var item : e.getValue()) {
                    sb.append("§7").append(BuiltInRegistries.ITEM.getKey(item)).append(' ');
                }
                source.sendSuccess(() -> Component.literal(sb.toString()), false);
            }
            return 1;
        } catch (Throwable t) {
            source.sendSuccess(() -> Component.literal("[Folio Cleaner] §c读取重复组失败："
                    + t.getClass().getSimpleName()), false);
            return 0;
        }
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

    /**
     * 服务端适配器管理（专服没有界面，只能靠指令）。
     *
     * <pre>
     * /folioserver adapters          列出当前适配器（类型 / id / 名字 / 来源 / 开关）
     * /folioserver adapters reload   重新扫描 config/foliocleaner/adapters/（json+lua 热加载；新 jar 也会被认出来）
     * </pre>
     */
    private static int adapters(CommandSourceStack source, boolean reload) {
        // 列之前总是重扫一次：避免"文件已经放好了但列表还是旧的"
        reload = true;
        if (reload) {
            int n = com.yuneko.foliocleaner.compat.AdapterDir.reload();
            source.sendSuccess(() -> Component.literal("[Folio Cleaner] 已重新扫描适配器：" + n + " 个"), false);
        }
        var list = com.yuneko.foliocleaner.compat.AdapterDir.all();
        if (list.isEmpty()) {
            source.sendSuccess(() -> Component.literal(
                    "[Folio Cleaner] 还没有适配器 —— 把 json / lua / jar 丢进 config/foliocleaner/adapters/"), false);
            return 1;
        }
        source.sendSuccess(() -> Component.literal("[Folio Cleaner] 适配器 " + list.size() + " 个："), false);
        for (var e : list) {
            source.sendSuccess(() -> Component.literal("  " + (e.enabled() ? "§a● " : "§8○ ")
                    + "[" + e.kind() + "] " + e.name() + " §8(" + e.id() + " · " + e.file() + ")"), false);
        }
        source.sendSuccess(() -> Component.literal("[Folio Cleaner] 指纹 "
                + com.yuneko.foliocleaner.compat.AdapterDir.fingerprint()), false);
        source.sendSuccess(() -> Component.literal("[Folio Cleaner] id=哈希 "
                + com.yuneko.foliocleaner.compat.AdapterDir.hashList()), false);
        return 1;
    }

    /**
     * 不用真客户端也能验证「指纹/差异清单」这条逻辑。
     *
     * <pre>
     * /folioserver adaptercheck                       打印本服指纹 + 适配器清单
     * /folioserver adaptercheck &lt;fp&gt; [clientIds]      模拟一个客户端来比对
     * </pre>
     */
    private static int adapterCheck(CommandSourceStack source, String fp, String ids) {
        String serverFp = com.yuneko.foliocleaner.compat.AdapterDir.fingerprint();
        int count = com.yuneko.foliocleaner.compat.AdapterDir.all().size();
        source.sendSuccess(() -> Component.literal("[Folio Cleaner] 本服指纹 " + serverFp
                + " · 适配器 " + count + " 个（检查模式 " + ServerSettings.adapterCheck + "）"), false);
        source.sendSuccess(() -> Component.literal("[Folio Cleaner] id 清单："
                + com.yuneko.foliocleaner.compat.AdapterDir.idList()), false);
        if (fp == null) {
            source.sendSuccess(() -> Component.literal(
                    "[Folio Cleaner] 想模拟客户端：/folioserver adaptercheck <fp> [id[=hash8],id...]"), false);
            return 1;
        }
        String diff = CleanerNetwork.diffAdapters(fp, ids);
        if (diff == null) {
            source.sendSuccess(() -> Component.literal("[Folio Cleaner] §a指纹一致 —— 真实客户端会被放行"), false);
        } else {
            source.sendSuccess(() -> Component.literal(
                    "[Folio Cleaner] §c指纹不一致 —— 真实客户端会被断开（adapterCheck="
                            + ServerSettings.adapterCheck + "）："), false);
            for (String line : diff.split("\n")) {
                if (line.isBlank()) continue;
                source.sendSuccess(() -> Component.literal("  " + line), false);
            }
        }
        return 1;
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
        UnifyRuntime.applyOnServer(server, ServerSettings.unifyMode);
        FolioCleaner.LOGGER.info("[Folio Cleaner] 服务端已就绪，规则 {} 条，统一方式 {}",
                Rules.size(), ServerSettings.unifyMode);
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
                // /reload 也重读服务端设置（改了 adapterCheck 不用重启）
                ServerSettings.load();
                // ★/reload 也要重新扫适配器（否则新丢进去的 json/lua/jar 认不出来）
                try {
                    com.yuneko.foliocleaner.compat.AdapterDir.reload();
                } catch (Throwable ignored) {
                }
                if (server != null) {
                    RecipeRemover.apply(server);
                    UnifyRuntime.applyOnServer(server, ServerSettings.unifyMode);
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
