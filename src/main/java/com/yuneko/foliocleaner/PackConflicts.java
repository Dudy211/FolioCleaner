package com.yuneko.foliocleaner;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.packs.PackResources;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.repository.Pack;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;

/**
 * 冲突检测：我们写进数据包的**配方副本**，会不会和别的数据包抢同一个文件。
 *
 * <p>为什么需要：统一功能会把自己改写过的整份配方 JSON 放进
 * {@code datapacks/jeicleaner/data/<ns>/recipes/...}，这会**整份覆盖**同名资源。
 * 如果用户还装了别的数据包（或另一个模组自带的数据包）修改了同一条配方，
 * 两个包谁在上面谁生效 —— 我们的包通常在顶层，于是别人的改动被静默吃掉。
 *
 * <p>这里只**报告**，不改名、不绕开：改文件名会让配方 id 变化（引用它的地方全废），
 * 是不是要保留我们的版本，只能人来决定。
 *
 * <p>对 tag 文件不做检测 —— {@code replace:false} 是合并语义，多个包往同一个 tag
 * 里加东西本来就是设计允许的。
 */
public final class PackConflicts {

    /** 一条冲突：某个包也提供了同一个路径 */
    public record Conflict(String packId, String path) {
    }

    /**
     * 原版 / Forge 自带的那几个包不算冲突（模组配方本来就在 {@code mod_resources} 里，
     * 我们覆盖它们正是「统一」要做的事）。
     */
    private static final Set<String> BUILT_IN = Set.of(
            "vanilla", "mod_resources", "forge", "programmer_art", "high_contrast");

    private PackConflicts() {
    }

    /**
     * 在服务端已选中的资源包里找同名文件。
     *
     * @param paths 我们写的数据包相对路径（{@code data/<ns>/recipes/x.json}）
     * @param limit 最多返回多少条（防刷屏）
     */
    public static List<Conflict> find(MinecraftServer server, Collection<String> paths, int limit) {
        List<Conflict> out = new ArrayList<>();
        if (server == null || paths == null || paths.isEmpty()) return out;
        try {
            for (Pack pack : server.getPackRepository().getSelectedPacks()) {
                if (out.size() >= limit) break;
                String id;
                try {
                    id = pack.getId();
                } catch (Throwable t) {
                    continue;
                }
                // ★真机实测：Forge 给每个模组起的资源包 id 是 "mod:forge" / "mod:jei"…
                //   （不是老版本那个单一的 "mod_resources"）→ 一律按「模组的资源」跳过，
                //   否则每条被改写的原版配方都会被误报成"和别的数据包冲突"。
                if (id == null || BUILT_IN.contains(id) || id.contains(FolioCleaner.MODID)
                        || id.startsWith("mod:") || id.equals("mod_resources")) {
                    continue;
                }

                PackResources res = null;
                try {
                    res = pack.open();
                    for (String path : paths) {
                        if (out.size() >= limit) break;
                        ResourceLocation rl = toLocation(path);
                        if (rl == null) continue;
                        if (res.getResource(PackType.SERVER_DATA, rl) != null) {
                            out.add(new Conflict(id, path));
                        }
                    }
                } catch (Throwable ignored) {
                    // 某个包打不开（文件损坏 / 权限）→ 跳过它，不影响别的检测
                } finally {
                    if (res != null) {
                        try {
                            res.close();
                        } catch (Throwable ignored) {
                        }
                    }
                }
            }
        } catch (Throwable t) {
            FolioCleaner.LOGGER.debug("[Folio Cleaner] 冲突检测失败", t);
        }
        return out;
    }

    /** {@code data/ns/recipes/x.json} → {@code ns:recipes/x.json} */
    private static ResourceLocation toLocation(String path) {
        if (path == null) return null;
        String p = path.startsWith("data/") ? path.substring("data/".length()) : path;
        int slash = p.indexOf('/');
        if (slash <= 0 || slash == p.length() - 1) return null;
        String ns = p.substring(0, slash);
        String rest = p.substring(slash + 1);
        return ResourceLocation.tryBuild(ns, rest);
    }
}
