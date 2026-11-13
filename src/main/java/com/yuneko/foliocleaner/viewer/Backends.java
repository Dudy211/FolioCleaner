package com.yuneko.foliocleaner.viewer;

import com.yuneko.foliocleaner.FolioCleaner;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 后端注册表：探测装了哪些配方浏览器、挑一个「主后端」给界面用、把 tick / 应用
 * 广播给所有后端。
 *
 * <p><b>为什么用反射 new 而不是直接 {@code new JeiBackend()}：</b>
 * {@code JeiBackend} 的方法签名里全是 {@code mezz.jei.*} 类型。直接 new 会让 JVM
 * 在验证这个类时去加载那些类 —— 没装 JEI 就抛 {@code NoClassDefFoundError}，
 * 而它发生在 {@code Backends} 自己的静态初始化链路里，很不好兜。
 * 反射之后，失败被锁在 {@code Class.forName / newInstance} 这一次调用里，
 * 一个 {@code catch (Throwable)} 就能干净地降级成「这个后端不可用」。
 *
 * <p>优先级 = JEI → REI → EMI：能力从强到弱（JEI 有完整布局 API），
 * 而且大多数玩家装的是 JEI。装了多个时，规则会同时作用到所有后端
 * （各后端自己实现 {@code apply}），界面只用主后端。
 */
public final class Backends {

    /** 优先级顺序（能力从强到弱） */
    private static final String[] BACKEND_IDS = {"jei", "rei", "emi"};
    /** 对应的模组 id —— REI 的模组 id 是 {@code roughlyenoughitems}，不是 {@code rei} */
    private static final String[] MOD_IDS = {Viewers.JEI, Viewers.REI, Viewers.EMI};

    private static final String[] CLASSES = {
            "com.yuneko.foliocleaner.viewer.JeiBackend",
            "com.yuneko.foliocleaner.viewer.ReiBackend",
            "com.yuneko.foliocleaner.viewer.EmiBackend",
    };

    private static List<RecipeViewerBackend> all = List.of();
    private static RecipeViewerBackend primary;
    private static boolean inited;

    private Backends() {
    }

    /** 幂等；第一次调用时探测。失败的后端会被记进日志但不影响别的。 */
    private static volatile boolean initedVolatile;

    public static void init() {
        if (initedVolatile) return;      // 渲染线程每帧都会问，别每次都抢锁
        initSlow();
    }

    private static synchronized void initSlow() {
        if (inited) {
            initedVolatile = true;
            return;
        }
        inited = true;
        try {

        List<RecipeViewerBackend> list = new ArrayList<>(3);
        for (int i = 0; i < CLASSES.length; i++) {
            String id = BACKEND_IDS[i];
            if (!Viewers.loadedRaw(MOD_IDS[i])) continue;
            try {
                Class<?> c = Class.forName(CLASSES[i]);
                Object o = c.getDeclaredConstructor().newInstance();
                if (o instanceof RecipeViewerBackend backend) {
                    list.add(backend);
                    FolioCleaner.LOGGER.info("[Folio Cleaner] 配方浏览器后端已加载：{}（{}{}）",
                            backend.label(), backend.ready() ? "就绪" : "等待初始化",
                            backend.caps().apply() ? "" : " · 只读");
                }
            } catch (Throwable t) {
                FolioCleaner.LOGGER.warn("[Folio Cleaner] 后端 {} 不可用：{}", id, t.toString());
            }
        }
        all = Collections.unmodifiableList(list);

        // 主后端：优先「已就绪」的；都还没就绪就用第一个（界面仍能编辑规则，只是预览为空）
        RecipeViewerBackend ready = null;
        for (RecipeViewerBackend b : list) {
            if (b.ready()) {
                ready = b;
                break;
            }
        }
        primary = ready != null ? ready : (list.isEmpty() ? null : list.get(0));
        } finally {
            initedVolatile = true;   // 就算中途抛了也别让渲染线程每帧抢锁
        }
    }

    /** 有没有任何一个（无论是否就绪） */
    public static boolean any() {
        init();
        return !all.isEmpty();
    }

    /** 主后端（界面用），可能为 null */
    public static RecipeViewerBackend primary() {
        init();
        return primary;
    }

    /** 换主后端（比如用户在设置里手动指定） */
    public static void setPrimary(RecipeViewerBackend backend) {
        init();
        if (backend != null && all.contains(backend)) primary = backend;
    }

    public static List<RecipeViewerBackend> all() {
        init();
        return all;
    }

    /** 界面能不能开：主后端支持枚举（JEI / REI / EMI 都支持） */
    public static boolean browseAvailable() {
        RecipeViewerBackend b = primary();
        return b != null && b.caps().browse();
    }

    /** 能不能画预览 */
    public static boolean previewAvailable() {
        RecipeViewerBackend b = primary();
        return b != null && b.ready() && b.caps().preview();
    }

    // ------------------------------------------------------------ 广播

    public static void tickAll() {
        for (RecipeViewerBackend b : all()) {
            try {
                b.tick();
            } catch (Throwable ignored) {
            }
        }
    }

    public static void requestApplyDelayed(int ticks) {
        for (RecipeViewerBackend b : all()) {
            try {
                b.requestApplyDelayed(ticks);
            } catch (Throwable ignored) {
            }
        }
    }

    public static void requestApply() {
        for (RecipeViewerBackend b : all()) {
            try {
                b.requestApply();
            } catch (Throwable ignored) {
            }
        }
    }

    public static void invalidateAll() {
        for (RecipeViewerBackend b : all()) {
            try {
                b.invalidate();
            } catch (Throwable ignored) {
            }
        }
    }

    /** 有任一后端在同步 */
    public static boolean isSyncing() {
        for (RecipeViewerBackend b : all()) {
            try {
                if (b.isSyncing()) return true;
            } catch (Throwable ignored) {
            }
        }
        return false;
    }

    /** 最大进度（没有同步时 -1） */
    public static float progress() {
        float p = -1f;
        for (RecipeViewerBackend b : all()) {
            try {
                p = Math.max(p, b.progress());
            } catch (Throwable ignored) {
            }
        }
        return p;
    }
}
