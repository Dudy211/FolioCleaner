package com.yuneko.foliocleaner.compat;

import com.yuneko.foliocleaner.FolioCleaner;
import net.minecraftforge.fml.loading.FMLPaths;

import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.ServiceLoader;
import java.util.function.Consumer;

/**
 * **从文件夹导入可执行适配器代码**（丢 jar 进 {@code config/foliocleaner/adapters/} 即可）。
 *
 * <h2>怎么加载的</h2>
 * 每个 jar 用一个 {@link URLClassLoader}（parent = 本模组的 ClassLoader，所以能看见
 * Minecraft / Forge / 本模组的类）打开，然后：
 * <ol>
 *   <li>优先用 {@link ServiceLoader}：jar 里放
 *       {@code META-INF/services/com.yuneko.foliocleaner.compat.CraftGuard}，
 *       内容是实现类的全限定名 —— 这是标准做法，不会误加载无关类；</li>
 *   <li>没有 services 文件就跳过（并在日志里提示），**不**做"遍历所有 class 硬试"：
 *       那样会把 jar 里乱七八糟的类也初始化一遍，风险大于收益。</li>
 * </ol>
 *
 * <h2>能导入什么、不能导入什么（重要）</h2>
 * <table>
 *   <tr><th>类型</th><th>能不能</th><th>原因</th></tr>
 *   <tr>
 *     <td>「判据」型适配器（实现 {@link CraftGuard}，只看输入容器）</td>
 *     <td>✅ 可以</td>
 *     <td>调用点是本模组已有的 {@code ItemCombinerMenuMixin}，不需要自己注入</td>
 *   </tr>
 *   <tr>
 *     <td>需要**自己 Mixin 注入**的适配器（新的工作台类型）</td>
 *     <td>❌ 不行</td>
 *     <td>Mixin 在启动期由 MixinBootstrap 按配置应用，运行期加进来的 jar 赶不上；
 *         这种请做成独立 Forge mod（依赖本模组，自己带 mixin 配置）</td>
 *   </tr>
 * </table>
 *
 * <h2>写一个可导入的适配器</h2>
 * <pre>
 * // 编译：把 foliocleaner 当 compileOnly 依赖（ForgeGradle，编译后 reobf 到 SRG）
 * public final class MyGuard implements CraftGuard {
 *     public String name() { return "我的工作台"; }
 *     public boolean blocks(Container inputs) { ... }
 * }
 * // META-INF/services/com.yuneko.foliocleaner.compat.CraftGuard
 * //   内容一行： com.example.MyGuard
 * </pre>
 *
 * <p>⚠️ 安全提醒：这里执行的是**本地 jar 里的任意代码**，和装 mod 同级。
 * 只放自己信任的 jar。
 */
public final class AdapterLoader {

    private static final List<URLClassLoader> LOADERS = new ArrayList<>();
    /** 记录每个 jar 装载了多少个适配器（界面/日志用） */
    public static final List<String> LOADED = new ArrayList<>();
    /** 已经加载过的 jar 文件名（同一个 jar 不重复加载，避免重复注册） */
    private static final java.util.Set<String> DONE = new java.util.HashSet<>();
    /** jar 名 → "size:mtime"（同名但内容变了 = 需要重启） */
    private static final java.util.Map<String, String> STAMP = new java.util.HashMap<>();

    private AdapterLoader() {
    }

    /** {@code config/foliocleaner/adapters/} */
    public static Path dir() {
        return FMLPaths.CONFIGDIR.get().resolve("foliocleaner").resolve("adapters");
    }

    /**
     * 装载目录下所有 jar。
     *
     * @param sink 每个适配器实例交给谁（通常是 {@code CraftGuards::register}）
     * @return 装载到的适配器个数
     */
    public static int loadAll(Consumer<CraftGuard> sink) {
        Path dir = dir();
        if (!Files.isDirectory(dir)) {
            try {
                Files.createDirectories(dir);
            } catch (Throwable t) {
                FolioCleaner.LOGGER.debug("[Folio Cleaner] 创建适配器目录失败：{}", dir, t);
            }
            return 0;
        }
        int total = 0;
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir, "*.jar")) {
            for (Path jar : ds) {
                String name = jar.getFileName().toString();
                String stamp = "";
                try {
                    stamp = Files.size(jar) + ":" + Files.getLastModifiedTime(jar).toMillis();
                } catch (Throwable ignored) {
                }
                if (!DONE.add(name)) {
                    // 同名 jar 被替换了 → 类已经加载过，只能提示重启
                    if (!stamp.isEmpty() && !stamp.equals(STAMP.get(name))) {
                        STAMP.put(name, stamp);
                        AdapterDir.noteJarChange();
                        FolioCleaner.LOGGER.info("[Folio Cleaner] 适配器 jar 已变更，需要重启：{}", name);
                    }
                    continue;
                }
                STAMP.put(name, stamp);
                total += loadJar(jar, sink);
            }
        } catch (Throwable t) {
            FolioCleaner.LOGGER.warn("[Folio Cleaner] 扫描适配器目录失败：{}", dir, t);
        }
        if (total > 0) {
            FolioCleaner.LOGGER.info("[Folio Cleaner] 从 {} 导入了 {} 个适配器：{}",
                    dir, total, LOADED);
        }
        return total;
    }

    private static int loadJar(Path jar, Consumer<CraftGuard> sink) {
        int n = 0;
        URLClassLoader loader = null;
        try {
            loader = new URLClassLoader(new URL[]{jar.toUri().toURL()},
                    AdapterLoader.class.getClassLoader());
            boolean any = false;
            for (CraftGuard guard : ServiceLoader.load(CraftGuard.class, loader)) {
                any = true;
                try {
                    sink.accept(guard);
                    n++;
                } catch (Throwable t) {
                    FolioCleaner.LOGGER.warn("[Folio Cleaner] 注册适配器失败：{}", guard.getClass().getName(), t);
                }
            }
            {
                // 纯数据接口（不需要 MC 类，外部 jar 首选）
                for (SimpleCraftGuard simple : ServiceLoader.load(SimpleCraftGuard.class, loader)) {
                    try {
                        CraftGuards.register(simple, jar.getFileName().toString());
                        n++;
                    } catch (Throwable t) {
                        FolioCleaner.LOGGER.warn("[Folio Cleaner] 注册纯数据适配器失败", t);
                    }
                }
            }
            if (n > 0) {
                LOADERS.add(loader);      // 有人被注册了才留着这个 loader
                LOADED.add(jar.getFileName().toString() + " (" + n + ")");
            } else if (!any) {
                FolioCleaner.LOGGER.warn(
                        "[Folio Cleaner] 适配器 jar {} 里没有 META-INF/services/{}（已跳过）",
                        jar.getFileName(), CraftGuard.class.getName());
            }
        } catch (Throwable t) {
            FolioCleaner.LOGGER.warn("[Folio Cleaner] 加载适配器 jar 失败：{}", jar, t);
        } finally {
            if (n == 0 && loader != null) {
                try {
                    loader.close();
                } catch (Throwable ignored) {
                }
            }
        }
        return n;
    }

    /** 已导入的 jar（界面显示用） */
    public static List<String> loadedJars() {
        return List.copyOf(LOADED);
    }
}
