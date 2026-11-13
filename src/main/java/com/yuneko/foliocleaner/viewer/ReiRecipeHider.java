package com.yuneko.foliocleaner.viewer;

import com.yuneko.foliocleaner.HiddenRecipes;
import com.yuneko.foliocleaner.FolioCleaner;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.fml.ModList;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Optional;

/**
 * REI 的「配方级隐藏」适配器 —— **纯反射**，不加 REI 编译依赖。
 *
 * <p>REI 12 的接口（javap 实证）：
 * <pre>
 * me.shedaniel.rei.api.client.registry.display.DisplayRegistry
 *     static DisplayRegistry getInstance()
 *     void registerVisibilityPredicate(DisplayVisibilityPredicate)
 *     List&lt;DisplayVisibilityPredicate&gt; getVisibilityPredicates()
 *
 * me.shedaniel.rei.api.client.registry.display.visibility.DisplayVisibilityPredicate
 *     EventResult handleDisplay(DisplayCategory&lt;?&gt;, Display)   // 每次渲染都问一次 → 实时
 *     default double getPriority()                            // 默认 0
 *
 * me.shedaniel.rei.api.common.display.Display
 *     default Optional&lt;ResourceLocation&gt; getDisplayLocation()   // ← 配方 id 就从这里拿
 * </pre>
 * {@code EventResult} 属于 architectury（不在 REI 的 jar 里、也不在我们编译期依赖里），
 * 所以这里用 {@link Proxy} 实现接口，返回值靠反射调 {@code EventResult.pass()} /
 * {@code EventResult.interruptFalse()}（REI 自己的 {@code FilteredStacksVisibilityHandler}
 * 就是这两个返回值，字节码核对过）。
 *
 * <p>为什么不用编译依赖：architectury 的 jar 本地没有；而且反射失败只是「这一层不生效」，
 * 不该让整个模组构建/加载受影响。
 */
public final class ReiRecipeHider implements InvocationHandler {

    private static final String REI_MODID = "roughlyenoughitems";

    private static ReiRecipeHider handler;
    private static Object predicate;
    /** 只打一次错误日志，避免每次登录刷屏 */
    private static boolean loggedError;
    /** 连续几次"类找不到"才判定为版本不匹配（避免早期加载时机导致永久放弃） */
    private static int classMissCount;
    private static final int CLASS_MISS_LIMIT = 3;
    /** 谓词接口本身（反射时反复要，缓存一份） */
    private static Class<?> predicateType;

    private final Method handleDisplay;
    private final Method getDisplayLocation;
    private final Object passResult;
    private final Object hideResult;

    private ReiRecipeHider(Class<?> predicateClass, Class<?> displayClass, Class<?> eventResultClass)
            throws ReflectiveOperationException {
        this.handleDisplay = predicateClass.getMethod("handleDisplay",
                Class.forName("me.shedaniel.rei.api.client.registry.display.DisplayCategory"),
                displayClass);
        this.getDisplayLocation = displayClass.getMethod("getDisplayLocation");
        this.passResult = eventResultClass.getMethod("pass").invoke(null);
        this.hideResult = eventResultClass.getMethod("interruptFalse").invoke(null);
    }

    /**
     * 装一次（幂等）。REI 没装 / 还没初始化 / 反射失败都只是安静返回，不影响别的东西。
     *
     * <p>要重复调：REI 的 DisplayRegistry 在换世界 / /reload 后会重建谓词列表，
     * 那时得重新挂 —— 这里用「列表里有没有我们这个实例」判断，不靠一次性布尔。
     */
    public static void install() {
        if (ViewerStatus.reiFailed) return;
        try {
            if (!ModList.get().isLoaded(REI_MODID)) return;

            Class<?> registryClass = Class.forName("me.shedaniel.rei.api.client.registry.display.DisplayRegistry");
            Object registry = registryClass.getMethod("getInstance").invoke(null);
            if (registry == null) return;   // REI 还没初始化完，下次再来

            if (predicate == null) {
                predicateType = Class.forName(
                        "me.shedaniel.rei.api.client.registry.display.visibility.DisplayVisibilityPredicate");
                Class<?> displayClass = Class.forName("me.shedaniel.rei.api.common.display.Display");
                Class<?> eventResultClass = Class.forName("dev.architectury.event.EventResult");
                handler = new ReiRecipeHider(predicateType, displayClass, eventResultClass);
                predicate = Proxy.newProxyInstance(
                        predicateType.getClassLoader(), new Class<?>[]{predicateType}, handler);
            }

            Object existing = registryClass.getMethod("getVisibilityPredicates").invoke(registry);
            if (existing instanceof List<?> list) {
                for (Object o : list) {
                    if (o == predicate) return;    // 已经在里面了
                }
            }
            if (predicateType == null) return;
            registryClass.getMethod("registerVisibilityPredicate", predicateType)
                    .invoke(registry, predicate);
            if (!ViewerStatus.reiFilterInstalled) {
                FolioCleaner.LOGGER.info("[Folio Cleaner] 已挂上 REI 的配方可见性过滤器（配方级隐藏实时生效）");
            }
            ViewerStatus.reiFilterInstalled = true;
        } catch (ClassNotFoundException e) {
            // 可能是「真的没这个类（版本不匹配）」也可能是「这次加载得太早」
            // → 连续 3 次才判定失败，否则下次进世界/重载还会再试
            classMissCount++;
            if (classMissCount >= CLASS_MISS_LIMIT) {
                ViewerStatus.reiFailed = true;
                FolioCleaner.LOGGER.warn("[Folio Cleaner] REI 的类连续 {} 次找不到（版本不匹配？），"
                        + "配方级隐藏只在 JEI 生效：{}", CLASS_MISS_LIMIT, String.valueOf(e));
            }
        } catch (NoSuchMethodException | IllegalAccessException e) {
            // API 形状对不上（REI 版本差异）→ 也不再重试，但要说清楚
            if (!loggedError) {
                loggedError = true;
                FolioCleaner.LOGGER.warn("[Folio Cleaner] REI 的 API 形状不匹配，配方级隐藏只在 JEI 生效", e);
            }
            ViewerStatus.reiFailed = true;
        } catch (Throwable t) {
            // 其它（比如 REI 还没初始化完 / getInstance 返回异常）→ **不**一票否决，下次登录或重载再试
            if (!loggedError) {
                loggedError = true;
                FolioCleaner.LOGGER.info("[Folio Cleaner] REI 过滤器这次没挂上（下次再试）：{}", String.valueOf(t));
            }
        }
    }

    @Override
    public Object invoke(Object proxy, Method method, Object[] args) {
        String name = method.getName();
        switch (name) {
            case "handleDisplay":
                return hides(args) ? hideResult : passResult;
            case "getPriority":
                return 0.0d;
            case "compareTo":
                return 0;
            case "hashCode":
                return System.identityHashCode(proxy);
            case "equals":
                return args != null && args.length == 1 && proxy == args[0];
            case "toString":
                return "JEICleanerReiFilter";
            default:
                return null;
        }
    }

    /** display 的配方 id 命中规则就隐藏 */
    private boolean hides(Object[] args) {
        if (args == null || args.length < 2 || args[1] == null) return false;
        try {
            Object loc = getDisplayLocation.invoke(args[1]);
            if (loc instanceof Optional<?> opt && opt.isPresent() && opt.get() instanceof ResourceLocation id) {
                return HiddenRecipes.hides(id);
            }
        } catch (Throwable ignored) {
        }
        return false;
    }
}
