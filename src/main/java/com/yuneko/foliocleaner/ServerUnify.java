package com.yuneko.foliocleaner;

import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.List;

/**
 * 「统一数据包」的**服务端实现** —— 单人客户端、集成服务器、专用服务器共用这一份。
 *
 * <p>不引用任何 {@code net.minecraft.client} 类，所以专用服务器能直接加载它
 * （客户端的那层包装见 {@code client/UnifyActions}）。
 *
 * <p>数据包只写在自己这个存档的 {@code datapacks/foliocleaner} 里，
 * 专用服务器上就是 {@code <服务器根>/world/datapacks/foliocleaner} —— 位置天然正确。
 */
public final class ServerUnify {

    public static final String STATUS = "status";
    public static final String GEN = "gen";
    public static final String ROLLBACK = "rollback";
    public static final String WIPE = "wipe";

    private static final DateTimeFormatter TIME =
            DateTimeFormatter.ofPattern("MM-dd HH:mm").withZone(ZoneId.systemDefault());

    private ServerUnify() {
    }

    /** 数据包根目录 */
    public static Path root(MinecraftServer server) {
        return server.getWorldPath(LevelResource.ROOT).resolve("datapacks/foliocleaner");
    }

    /**
     * 服务端扫一遍重复组。
     *
     * @param overridesJson 客户端带来的覆盖层 JSON（专用服务器上没有玩家本地的
     *                      {@code dup-overrides.json}，只能让客户端把它的发过来）
     */
    public static List<DuplicateGroups.Group> groups(MinecraftServer server, String overridesJson) {
        return DuplicateGroups.scan(server.overworld(), DupOverrides.parse(overridesJson));
    }

    /**
     * 执行一个操作，返回**可直接展示的一句话**（含 § 颜色码）。
     *
     * @param op           status / gen / rollback / wipe
     * @param overridesJson 重复组覆盖层 JSON（可为 null）
     * @param crossTag     交叉物品是否用双 tag 包装
     * @param autoReload   生成 / 撤销后是否自动 reload（专用服务器建议开，不然要手动 /reload）
     */
    public static String run(MinecraftServer server, String op, String overridesJson,
                             boolean crossTag, boolean autoReload) {
        Path root = root(server);
        return switch (op) {
            case GEN -> generate(server, root, overridesJson, crossTag, autoReload);
            case ROLLBACK -> rollback(server, root, autoReload);
            case WIPE -> wipe(server, root, autoReload);
            default -> status(root);
        };
    }

    // ---------------------------------------------------------------- 各操作

    /** 清单里除了「跨 viewer 隐藏 tag」之外，还有没有统一生成的东西 */
    private static boolean hasGeneratedContent(UnifyManifest.Data m) {
        if (m == null || m.entries.isEmpty()) return false;
        for (UnifyManifest.Entry e : m.entries) {
            if ("tag".equals(e.kind) && e.path.contains("hidden_from_recipe_viewers")) continue;
            return true;
        }
        return false;
    }

    public static String status(Path root) {
        UnifyManifest.Data m = UnifyManifest.load(root);
        int tags = 0, recipes = 0, cross = 0, other = 0;
        for (UnifyManifest.Entry e : m.entries) {
            switch (e.kind) {
                case "tag" -> tags++;
                case "recipe" -> recipes++;
                case "cross" -> cross++;
                default -> other++;
            }
        }
        String when = m.updatedAt <= 0 ? "从未生成" : TIME.format(Instant.ofEpochMilli(m.updatedAt));
        int onDisk = DatapackGen.listGenerated(root).size();
        String base = "§7清单 §f" + m.entries.size() + " §7条";
        if (!m.entries.isEmpty()) {
            base += "（tag " + tags + " / 配方 " + recipes + (cross > 0 ? " / 交叉 " + cross : "")
                    + (other > 0 ? " / 其它 " + other : "") + "）";
        }
        base += "\n§7磁盘 tag 文件 §f" + onDisk + " §7个 §8· 生成于 " + when;
        if (m.entries.isEmpty()) {
            base += "\n§e没有清单：旧版本生成的数据包撤销不了，用「全删」整个删掉";
        }
        return base;
    }

    public static String generate(MinecraftServer server, Path root, String overridesJson,
                                  boolean crossTag, boolean autoReload) {
        List<DuplicateGroups.Group> groups;
        try {
            groups = groups(server, overridesJson);
        } catch (Throwable t) {
            return "§c重新扫描重复组失败：" + t.getClass().getSimpleName();
        }
        List<String> hidden = Rules.hiddenItemIds();
        if (groups.isEmpty() && hidden.isEmpty()) {
            return "没有重复组、也没有隐藏物品 —— 没什么可统一的";
        }
        // 一个组都没扫到、但数据包里还有上次生成的 tag / 配方副本：
        // 更可能是扫描失败（{@code scan()} 内部吞异常返回空列表），照常同步会把那些文件全清掉
        // → 先拦住，让人自己决定要不要清。
        UnifyManifest.Data old = UnifyManifest.load(root);
        if (groups.isEmpty() && hasGeneratedContent(old)) {
            return "§e这次没扫到任何重复组，但数据包里还有上次生成的内容 —— 疑似扫描失败，先不动"
                    + "\n§7确认要清空请点「撤销」或「全删」";
        }
        DatapackGen.Sync sync = DatapackGen.sync(
                root, server.getResourceManager(), groups, crossTag, hidden);

        // 冲突检测：别的数据包也写了同一个配方文件 → 谁在上层谁生效，先说清楚
        List<PackConflicts.Conflict> conflicts = PackConflicts.find(server, sync.recipes().all(), 20);
        for (PackConflicts.Conflict c : conflicts) {
            FolioCleaner.LOGGER.warn("[Folio Cleaner] 配方冲突：{} 也被数据包 {} 提供（我们的在更上层）",
                    c.path(), c.packId());
        }

        if (autoReload) {
            server.reloadResources(server.getPackRepository().getSelectedIds());
        }
        String msg = "已生成：写 " + sync.written() + " 个文件 / 清理陈旧 " + sync.pruned()
                + " 个 / 清单 " + sync.entries() + " 条"
                + (hidden.isEmpty() ? "" : " §8（含 " + hidden.size() + " 个跨 viewer 隐藏物品）")
                + (sync.keptModified().isEmpty() ? ""
                : " §e（保留 " + sync.keptModified().size() + " 个被改过的）")
                + (autoReload ? "§7，已 /reload" : "§7，记得手动 /reload");
        if (!conflicts.isEmpty()) {
            msg += "\n§e⚠ " + conflicts.size() + " 个配方也被别的数据包提供（明细见日志）";
        }
        return msg;
    }

    public static String rollback(MinecraftServer server, Path root, boolean autoReload) {
        UnifyManifest.Data m = UnifyManifest.load(root);
        if (m.entries.isEmpty()) {
            return "§e清单是空的（旧版本生成的数据包没记过账）→ 要全删请点「全删」";
        }
        DatapackGen.Rollback rb = DatapackGen.rollback(root);
        if (autoReload) {
            server.reloadResources(server.getPackRepository().getSelectedIds());
        }
        return "已撤销 " + rb.deleted() + " 个文件"
                + (rb.keptModified().isEmpty() ? ""
                : " §e（保留 " + rb.keptModified().size() + " 个被手工改过的）")
                + (autoReload ? "§7，已 /reload" : "§7，记得手动 /reload");
    }

    public static String wipe(MinecraftServer server, Path root, boolean autoReload) {
        if (!Files.exists(root)) return "数据包目录本来就不存在（已经是干净的）";
        int n = 0;
        try (var walk = Files.walk(root)) {
            for (Path path : walk.sorted(Comparator.reverseOrder()).toList()) {
                if (Files.deleteIfExists(path)) n++;
            }
        } catch (Throwable t) {
            return "§c删除失败：" + t.getClass().getSimpleName();
        }
        if (autoReload) {
            server.reloadResources(server.getPackRepository().getSelectedIds());
        }
        return "已整个删除 datapacks/foliocleaner（" + n + " 个条目）"
                + "\n§7注意：统一方式仍是「数据包」，下次同步会重新生成";
    }
}
