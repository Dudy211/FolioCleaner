package com.yuneko.foliocleaner.viewer;

/**
 * 别家配方浏览器（REI / EMI）的适配状态 —— 纯字段，不引用任何第三方类，
 * 所以指令 / 界面可以安全地读它（不会因为没装 REI/EMI 而炸）。
 */
public final class ViewerStatus {

    /** REI 的 {@code DisplayVisibilityPredicate} 是否已经挂上 */
    public static volatile boolean reiFilterInstalled;
    /** REI 适配尝试失败（版本不匹配 / 反射失败） */
    public static volatile boolean reiFailed;
    /** EMI 插件的 {@code register(EmiRegistry)} 跑过了 */
    public static volatile boolean emiPluginRan;
    /** EMI 插件注册时移除掉了多少条配方（初始化期一次性） */
    public static volatile int emiRemoved;

    private ViewerStatus() {
    }
}
