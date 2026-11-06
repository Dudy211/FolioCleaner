package com.yuneko.jeicleaner.compat;

import com.yuneko.jeicleaner.JeiCleaner;
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
 * <p>只要在 {@code com.yuneko.jeicleaner.compat} 包里写一个
 * {@code public class XxxGuard implements CraftGuard}（public 无参构造），
 * <b>不用改任何注册代码</b> —— 启动时会扫描本模组的 compat 包并自动实例化。
 *
 * <p>扫描同时支持：开发环境（目录 classpath）与打包后的 mod jar。
 * 任何一步失败都只记 debug 日志，不影响游戏启动。
 */
public final class CraftGuards {

    /** 需要扫描的包（新增适配器就放在这里） */
    private static final String COMPAT_PACKAGE = "com.yuneko.jeicleaner.compat";

    private static final List<CraftGuard> GUARDS = new ArrayList<>();
    private static final Set<Class<?>> SEEN = new HashSet<>();

    static {
        // 内置兜底：即使扫描失败也能工作
        register(new CombinerCraftGuard());
        // 自动发现：往 compat 包里丢实现即可
        scanPackage(COMPAT_PACKAGE);
    }

    private CraftGuards() {
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

    /** 任意适配器认为该拦 → 拦 */
    public static boolean isBlocked(Container inputs) {
        if (inputs == null) return false;
        for (CraftGuard guard : GUARDS) {
            try {
                if (guard.blocks(inputs)) return true;
            } catch (Throwable ignored) {
                // 单个适配器出错不影响其它
            }
        }
        return false;
    }

    /** 只询问指定适配器（Mixin 给自己的目标类型用） */
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
                    JeiCleaner.LOGGER.debug("[JEI Cleaner] 扫描适配器 {} 失败: {}", url, t.toString());
                }
            }
        } catch (Throwable t) {
            JeiCleaner.LOGGER.debug("[JEI Cleaner] 自动发现适配器失败（不影响内置适配器）: {}", t.toString());
        }
        JeiCleaner.LOGGER.info("[JEI Cleaner] 已加载 {} 个合成守卫适配器", GUARDS.size());
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
                JeiCleaner.LOGGER.debug("[JEI Cleaner] 自动注册合成守卫: {}", guard.name());
            }
        } catch (Throwable t) {
            JeiCleaner.LOGGER.debug("[JEI Cleaner] 无法实例化适配器 {}: {}", className, t.toString());
        }
    }
}
