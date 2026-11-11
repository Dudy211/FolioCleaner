package com.yuneko.foliocleaner.compat;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.yuneko.foliocleaner.ClientSettings;
import com.yuneko.foliocleaner.FolioCleaner;
import net.minecraftforge.fml.loading.FMLPaths;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * 把「结构 hook 适配器」（带 mixin 的 jar）镜像到 {@code mods/}，让 FML 下次启动把它当 mod 加载。
 *
 * <h2>为什么必须镜像</h2>
 * mixin 只能在**启动阶段、且 jar 是一个 mod（在 mods/ 里）**时加载：
 * {@code TransformingClassLoader} 是模块化的（没有 addURL），而 JVM 不允许对已加载的类新增方法，
 * 所以"运行期把 config/adapters 里的 jar 变成 mixin"做不到。
 *
 * <h2>为什么退出时**不删**镜像</h2>
 * 复制的时机在 mod 发现**之后**，所以本次启动不生效、下次启动才生效。
 * 如果退出时删掉，下次启动又是"刚复制→不生效"，永远差一次启动。
 * 因此镜像长期驻留，只在**源变更/消失**时的下一次启动做同步。
 *
 * <h2>异常退出怎么兜底</h2>
 * 复制一律「先写 {@code .tmp} 再原子 move」→ 断电只会留下 .tmp，启动时统一清理；
 * 清单 {@code hook-mirror.json} 记录已镜像文件及其 sha256，也原子写。
 */
public final class HookMirror {

    /** 镜像文件名前缀：只有带这个前缀、且清单里记过的文件才会被我们删除 */
    public static final String PREFIX = "foliocleaner-hook-";

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private HookMirror() {
    }

    public static Path modsDir() {
        return FMLPaths.MODSDIR.get();
    }

    public static Path manifestFile() {
        return FMLPaths.CONFIGDIR.get().resolve("foliocleaner").resolve("hook-mirror.json");
    }

    /** 一次同步的结果（给界面/日志用） */
    public record Result(int copied, int removed, int unchanged, boolean changed, String note) {
        public boolean pendingRestart() {
            return changed;
        }
    }

    /**
     * 适配器目录里的 hook jar（内含 {@code *.mixins.json}）。
     *
     * <p>判定标准：zip 里存在任意以 {@code .mixins.json} 结尾的条目。
     * 普通 jar 适配器（ServiceLoader 那种）没有这个文件，不会被误镜像。
     */
    public static List<Path> scanHooks() {
        List<Path> out = new ArrayList<>();
        Path dir = AdapterDir.folder();
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir, "*.jar")) {
            for (Path f : ds) {
                if (isHookJar(f)) out.add(f);
            }
        } catch (Throwable t) {
            FolioCleaner.LOGGER.debug("[Folio Cleaner] 扫描 hook 适配器失败：{}", t.toString());
        }
        return out;
    }

    public static boolean isHookJar(Path jar) {
        if (jar == null || !Files.isRegularFile(jar)) return false;
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            var en = zip.entries();
            while (en.hasMoreElements()) {
                ZipEntry e = en.nextElement();
                if (e.getName().endsWith(".mixins.json")) return true;
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    /**
     * 同步一次（幂等；启动时调）。
     *
     * <p>开关关掉且**没有** hook 适配器时才会真的跳过 —— 有 hook 适配器时强制开启
     * （否则它们永远不会生效，用户会以为是 bug）。
     */
    public static Result sync() {
        List<Path> hooks = scanHooks();
        boolean forced = !hooks.isEmpty();
        if (!ClientSettings.mirrorHookAdapters && !forced) {
            return new Result(0, 0, 0, false, "已关闭镜像（未发现 hook 适配器）");
        }

        Path mods = modsDir();
        Path manifestPath = manifestFile();
        Map<String, String> manifest = readManifest(manifestPath);

        int copied = 0, removed = 0, unchanged = 0;
        StringBuilder note = new StringBuilder();

        // 0) 清掉上次异常退出留下的半截文件
        cleanupTmp(mods);

        try {
            Files.createDirectories(mods);
        } catch (Throwable t) {
            return new Result(0, 0, 0, false, "mods/ 不可写：" + t.getClass().getSimpleName());
        }

        Map<String, String> next = new LinkedHashMap<>();
        for (Path src : hooks) {
            String targetName = PREFIX + src.getFileName();
            Path target = mods.resolve(targetName);
            String srcHash;
            try {
                srcHash = sha256(src);
            } catch (Throwable t) {
                note.append("读取失败:").append(src.getFileName()).append(' ');
                continue;
            }
            String oldHash = manifest.get(targetName);
            boolean needCopy = !Files.isRegularFile(target) || !srcHash.equals(oldHash);
            if (!needCopy) {
                // 清单对得上，还要看磁盘上那份有没有被动过
                try {
                    needCopy = !srcHash.equals(sha256(target));
                } catch (Throwable t) {
                    needCopy = true;
                }
            }
            if (needCopy) {
                if (copyAtomic(src, target)) {
                    copied++;
                    note.append("已更新:").append(src.getFileName()).append(' ');
                } else {
                    note.append("复制失败:").append(src.getFileName()).append(' ');
                    // ★保留旧记录：否则下次同步会以为"这个镜像不是我们的"，永久留着一个过期文件
                    if (oldHash != null) next.put(targetName, oldHash);
                    continue;
                }
            } else {
                unchanged++;
            }
            next.put(targetName, srcHash);
        }

        // 源已消失的镜像 → 删掉（只删清单里记过、且是我们前缀的）
        for (String oldName : new ArrayList<>(manifest.keySet())) {
            if (next.containsKey(oldName)) continue;
            if (!oldName.startsWith(PREFIX)) continue;
            try {
                Path old = mods.resolve(oldName);
                if (Files.deleteIfExists(old)) {
                    removed++;
                    note.append("已移除:").append(oldName).append(' ');
                }
            } catch (Throwable t) {
                note.append("删除失败:").append(oldName).append(' ');
            }
        }

        writeManifest(manifestPath, next);

        boolean changed = copied > 0 || removed > 0;
        if (changed) {
            FolioCleaner.LOGGER.info("[Folio Cleaner] hook 适配器已同步到 mods/：复制 {} / 移除 {} / 未变 {}（{}）",
                    copied, removed, unchanged, note.toString().trim());
        }
        return new Result(copied, removed, unchanged, changed, note.toString().trim());
    }

    /** 启动时静默同步（异常不影响启动） */
    public static void syncQuietly() {
        try {
            sync();
        } catch (Throwable t) {
            FolioCleaner.LOGGER.warn("[Folio Cleaner] hook 适配器同步失败（忽略，不影响游戏）", t);
        }
    }

    /** 界面用：已镜像几个 */
    public static int mirroredCount() {
        return readManifest(manifestFile()).size();
    }

    /** 界面用：已镜像的文件名集合（便宜，读清单） */
    public static java.util.Set<String> mirroredNames() {
        return readManifest(manifestFile()).keySet();
    }

    /** 界面用：有几个 hook 适配器（**缓存**，因为每帧都查会反复开 zip） */
    private static int cachedCount = -1;

    public static int hookCount() {
        if (cachedCount < 0) cachedCount = scanHooks().size();
        return cachedCount;
    }

    /** 适配器目录变化后让缓存失效 */
    public static void invalidateCache() {
        cachedCount = -1;
    }

    // ---------------------------------------------------------------- 内部

    private static boolean copyAtomic(Path src, Path target) {
        Path tmp = target.resolveSibling(target.getFileName() + ".tmp");
        try {
            Files.copy(src, tmp, StandardCopyOption.REPLACE_EXISTING);
            try {
                Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (Throwable atomicFailed) {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
            }
            return true;
        } catch (Throwable t) {
            try {
                Files.deleteIfExists(tmp);
            } catch (Throwable ignored) {
            }
            FolioCleaner.LOGGER.warn("[Folio Cleaner] 复制 hook 适配器失败：{} → {}", src, target, t);
            return false;
        }
    }

    private static void cleanupTmp(Path mods) {
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(mods, PREFIX + "*.tmp")) {
            for (Path p : ds) {
                try {
                    Files.deleteIfExists(p);
                    FolioCleaner.LOGGER.info("[Folio Cleaner] 清理上次异常退出残留：{}", p.getFileName());
                } catch (Throwable ignored) {
                }
            }
        } catch (Throwable ignored) {
        }
    }

    private static Map<String, String> readManifest(Path path) {
        Map<String, String> out = new LinkedHashMap<>();
        try {
            if (!Files.isRegularFile(path)) return out;
            var root = JsonParser.parseString(Files.readString(path, StandardCharsets.UTF_8));
            if (!root.isJsonObject()) return out;
            JsonObject obj = root.getAsJsonObject();
            if (obj.has("files") && obj.get("files").isJsonObject()) {
                for (var e : obj.getAsJsonObject("files").entrySet()) {
                    if (e.getValue().isJsonPrimitive()) out.put(e.getKey(), e.getValue().getAsString());
                }
            }
        } catch (Throwable t) {
            FolioCleaner.LOGGER.debug("[Folio Cleaner] 读取 hook-mirror.json 失败：{}", t.toString());
        }
        return out;
    }

    private static void writeManifest(Path path, Map<String, String> files) {
        try {
            Files.createDirectories(path.getParent());
            JsonObject obj = new JsonObject();
            JsonObject f = new JsonObject();
            files.forEach(f::addProperty);
            obj.add("files", f);
            Path tmp = path.resolveSibling(path.getFileName() + ".tmp");
            Files.writeString(tmp, GSON.toJson(obj), StandardCharsets.UTF_8);
            try {
                Files.move(tmp, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (Throwable atomicFailed) {
                Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (Throwable t) {
            FolioCleaner.LOGGER.warn("[Folio Cleaner] 写 hook-mirror.json 失败", t);
        }
    }

    private static String sha256(Path path) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        try (InputStream in = Files.newInputStream(path)) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) md.update(buf, 0, n);
        }
        StringBuilder sb = new StringBuilder(64);
        for (byte b : md.digest()) sb.append(String.format("%02x", b));
        return sb.toString();
    }
}
