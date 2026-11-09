package com.yuneko.foliocleaner.compat;

import com.yuneko.foliocleaner.FolioCleaner;
import net.minecraft.world.Container;

import java.io.File;
import java.lang.reflect.Constructor;
import java.lang.reflect.Modifier;
import java.net.JarURLConnection;
import java.net.URL;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/**
 * 适配器注册表 + 自动发现。
 *
 * <p>只要在 {@code com.yuneko.foliocleaner.compat} 包里写一个
 * {@code public class XxxGuard implements CraftGuard}（public 无参构造），
 * <b>不用改任何注册代码</b> —— 启动时会扫描本模组的 compat 包并自动实例化。
 *
 * <p>扫描同时支持两种形态：
 * <ul>
 *   <li>开发环境 / 文件夹 classpath → 遍历目录下的 .class</li>
 *   <li>打包后的 mod jar → 遍历 jar 条目</li>
 * </ul>
 *
 * <p>任何一步失败都只记 debug 日志，不影响游戏启动。
 */
public final class CraftGuards {

    /** 需要扫描的包（新增适配器就放在这里） */
    private static final String COMPAT_PACKAGE = "com.yuneko.foliocleaner.compat";

    private static final List<CraftGuard> GUARDS = new ArrayList<>();
    private static final Set<Class<?>> SEEN = new HashSet<>();

    static {
        // 内置兜底：即使扫描失败也能工作
        register(new CombinerCraftGuard());
        // 自动发现：往 compat 包里丢实现即可（本模组自己的）
        scanPackage(COMPAT_PACKAGE);
        // 外部导入：config/foliocleaner/adapters/*.jar（ServiceLoader 形态）
        try {
            AdapterLoader.loadAll(CraftGuards::register);
        } catch (Throwable t) {
            FolioCleaner.LOGGER.warn("[Folio Cleaner] 导入外部适配器失败（不影响内置）", t);
        }
    }

    private CraftGuards() {
    }

    /** 把「纯数据」适配器包成 CraftGuard（外部 jar 用这个接口，不需要依赖 MC 类） */
    public static void register(SimpleCraftGuard simple) {
        register(simple, null);
    }

    public static void register(SimpleCraftGuard simple, String source) {
        if (simple == null) return;
        register(new SimpleGuardBridge(simple, source));
    }

    /** 手动注册（去重，同一个类只注册一次） */
    public static void register(CraftGuard guard) {
        if (guard == null) return;
        if (!SEEN.add(guard.getClass())) return;
        GUARDS.add(guard);
    }

    public static List<CraftGuard> all() {
        return Collections.unmodifiableList(GUARDS);
    }

    /** 界面上展示一条适配器 */
    public record GuardInfo(String id, String name, String source, boolean enabled) {
    }

    /** 适配器 id：类名去掉 Guard 后缀再小写驼峰转下划线（CombinerCraftGuard → combiner_craft） */
    public static String idOf(CraftGuard guard) {
        if (guard == null) return "";
        if (guard instanceof SimpleGuardBridge bridge) return bridge.originId();
        String name = guard.getClass().getSimpleName();
        if (name.endsWith("Guard")) name = name.substring(0, name.length() - "Guard".length());
        return name.replaceAll("([a-z0-9])([A-Z])", "$1_$2").toLowerCase(java.util.Locale.ROOT);
    }

    /** 来源：内置（本模组 jar）还是外部适配器 mod */
    public static String sourceOf(CraftGuard guard) {
        if (guard instanceof SimpleGuardBridge bridge) return bridge.originSource();
        try {
            var src = guard.getClass().getProtectionDomain().getCodeSource();
            if (src == null) return "内置";
            String path = src.getLocation().getPath();
            String file = path.substring(path.lastIndexOf('/') + 1);
            if (file.isEmpty()) return "内置";
            return file.equalsIgnoreCase(ownJarName()) ? "内置" : file;
        } catch (Throwable t) {
            return "内置";
        }
    }

    private static String ownJarName() {
        try {
            var src = CraftGuards.class.getProtectionDomain().getCodeSource();
            if (src == null) return "";
            String path = src.getLocation().getPath();
            return path.substring(path.lastIndexOf('/') + 1);
        } catch (Throwable t) {
            return "";
        }
    }

    public static boolean isEnabled(CraftGuard guard) {
        return guard != null && !com.yuneko.foliocleaner.ClientSettings.disabledGuards.contains(idOf(guard));
    }

    public static void setEnabled(String id, boolean enabled) {
        if (id == null || id.isBlank()) return;
        var list = com.yuneko.foliocleaner.ClientSettings.disabledGuards;
        if (enabled) {
            list.remove(id);
        } else if (!list.contains(id)) {
            list.add(id);
        }
        com.yuneko.foliocleaner.ClientSettings.touch();
    }

    /** 已导入的外部 jar 名（界面提示用） */
    public static List<String> importedJars() {
        return AdapterLoader.loadedJars();
    }

    /** 界面用：列出全部适配器 + 开关状态 */
    public static List<GuardInfo> infos() {
        List<GuardInfo> out = new ArrayList<>(GUARDS.size());
        for (CraftGuard guard : GUARDS) {
            out.add(new GuardInfo(idOf(guard), safeName(guard), sourceOf(guard), isEnabled(guard)));
        }
        return out;
    }

    private static String safeName(CraftGuard guard) {
        try {
            String n = guard.name();
            if (n != null && !n.isBlank()) return n;
        } catch (Throwable ignored) {
        }
        return guard.getClass().getSimpleName();
    }

    /** 任意**已启用**的适配器认为该拦 → 拦（带界面上下文） */
    public static boolean isBlocked(Container inputs, Object menu) {
        if (inputs == null) return false;
        for (CraftGuard guard : GUARDS) {
            try {
                if (!isEnabled(guard)) continue;
                if (guard.blocks(inputs, menu)) return true;
            } catch (Throwable ignored) {
                // 单个适配器出错不影响其它
            }
        }
        return false;
    }

    /** 旧的单参版本：没有界面上下文 */
    public static boolean isBlocked(Container inputs) {
        return isBlocked(inputs, null);
    }

    /** 只询问指定适配器（Mixin 给自己的目标类型用，避免串味） */
    public static boolean isBlocked(Container inputs, CraftGuard guard) {
        if (inputs == null || guard == null) return false;
        try {
            return guard.blocks(inputs);
        } catch (Throwable t) {
            return false;
        }
    }

    // ------------------------------------------------------------ 自动发现

    private static void scanPackage(String pkg) {
        try {
            String path = pkg.replace('.', '/');
            ClassLoader loader = CraftGuards.class.getClassLoader();
            Enumeration<URL> resources = loader.getResources(path);
            while (resources.hasMoreElements()) {
                URL url = resources.nextElement();
                try {
                    if ("file".equals(url.getProtocol())) {
                        scanDirectory(new File(URLDecoder.decode(url.getPath(), StandardCharsets.UTF_8)), pkg);
                    } else if ("jar".equals(url.getProtocol())) {
                        scanJar(url, path);
                    }
                } catch (Throwable t) {
                    FolioCleaner.LOGGER.debug("[Folio Cleaner] 扫描适配器 {} 失败: {}", url, t.toString());
                }
            }
        } catch (Throwable t) {
            FolioCleaner.LOGGER.debug("[Folio Cleaner] 自动发现适配器失败（不影响内置适配器）: {}", t.toString());
        }
        FolioCleaner.LOGGER.info("[Folio Cleaner] 已加载 {} 个合成守卫适配器", GUARDS.size());
    }

    private static void scanDirectory(File dir, String pkg) {
        File[] files = dir.listFiles();
        if (files == null) return;
        for (File file : files) {
            String name = file.getName();
            if (file.isDirectory()) {
                scanDirectory(file, pkg + "." + name);
            } else if (name.endsWith(".class") && !name.contains("$")) {
                instantiate(pkg + "." + name.substring(0, name.length() - ".class".length()));
            }
        }
    }

    private static void scanJar(URL url, String path) throws Exception {
        JarURLConnection connection = (JarURLConnection) url.openConnection();
        try (JarFile jar = connection.getJarFile()) {
            String prefix = path.endsWith("/") ? path : path + "/";
            Enumeration<JarEntry> entries = jar.entries();
            while (entries.hasMoreElements()) {
                JarEntry entry = entries.nextElement();
                String name = entry.getName();
                if (entry.isDirectory() || !name.startsWith(prefix) || !name.endsWith(".class")) continue;
                String rest = name.substring(prefix.length());
                if (rest.contains("/") || rest.contains("$")) continue; // 只要本包下的顶层类
                instantiate(path.replace('/', '.') + "." + rest.substring(0, rest.length() - ".class".length()));
            }
        }
    }

    private static void instantiate(String className) {
        try {
            ClassLoader loader = CraftGuards.class.getClassLoader();
            Class<?> type = Class.forName(className, false, loader);
            if (!CraftGuard.class.isAssignableFrom(type)) return;
            if (type.isInterface() || Modifier.isAbstract(type.getModifiers())) return;
            if (type.getEnclosingClass() != null) return;

            Constructor<?> constructor = type.getDeclaredConstructor();
            constructor.setAccessible(true);
            Object instance = constructor.newInstance();
            if (instance instanceof CraftGuard guard) {
                register(guard);
                FolioCleaner.LOGGER.debug("[Folio Cleaner] 自动注册合成守卫: {}", guard.name());
            }
        } catch (Throwable t) {
            FolioCleaner.LOGGER.debug("[Folio Cleaner] 无法实例化适配器 {}: {}", className, t.toString());
        }
    }
}
