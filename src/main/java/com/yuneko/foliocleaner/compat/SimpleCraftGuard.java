package com.yuneko.foliocleaner.compat;

import java.util.List;

/**
 * **不引用 Minecraft 类型**的适配器接口 —— 给「从文件夹导入 jar」的人用。
 *
 * <p>为什么要有它：{@link CraftGuard#blocks} 的参数是 {@code Container}（MC 类型），
 * 外部 jar 要调用 MC 方法就必须用 ForgeGradle + reobf，门槛很高。
 * 这个接口只吃**纯数据**，所以：
 * <pre>
 * javac -cp foliocleaner-forge-1.20.1-2.0.0.jar MyGuard.java      // 够了
 * jar cf my-adapter.jar MyGuard.class META-INF/services/com.yuneko.foliocleaner.compat.SimpleCraftGuard
 * </pre>
 *
 * <p>内部会自动把它包成 {@link CraftGuard}，行为与内置适配器完全一样（同样受开关控制）。
 */
public interface SimpleCraftGuard {

    /** 日志 / 界面里显示的名字 */
    String name();

    /**
     * @param slotIds   输入容器的物品 id（空格是空字符串），下标 = 槽位序号
     * @param menuClass 当前界面菜单的类名（可能为空，比如不是容器界面时）
     * @param removeTargets 当前生效的「删除」规则涉及的物品 id（只读，找不到就空表）
     * @return true = 这次合成无效
     */
    boolean blocks(List<String> slotIds, String menuClass, List<String> removeTargets);
}
