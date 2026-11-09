package com.yuneko.foliocleaner.compat;

import net.minecraft.world.Container;

/**
 * 「没有配方实体」的自定义工作台适配器。
 *
 * <p>有些工作台（铁砧、奥术铁砧、部分 mod 机器）的合成逻辑写死在 Menu / BlockEntity 里，
 * 配方不在服务端 {@code RecipeManager}，因此没法用普通的「删配方」处理。
 * 这类工作台需要单独写一个 CraftGuard：判断当前输入是否命中
 * {@code action=remove} 的规则，命中就阻止这次合成。
 *
 * <h2>怎么加一个新的适配器</h2>
 * <ol>
 *   <li>在 {@code compat/} 目录新建一个类，实现本接口；</li>
 *   <li>在 {@link CraftGuards} 的静态块里 {@code register(new YourGuard())}；</li>
 *   <li>如果目标工作台不是 {@code ItemCombinerMenu} 的子类，需要再加一个 Mixin
 *       （参考 {@code mixin/ItemCombinerMenuMixin}），在生成结果 / 取出结果的位置
 *       调用 {@link CraftGuards#isBlocked(Container, CraftGuard)}。</li>
 * </ol>
 *
 * <p><b>只读约定</b>：实现里不要改容器内容，只返回 true/false；清空结果由调用方负责。
 */
public interface CraftGuard {

    /** 适配器名字（日志 / 调试用） */
    String name();

    /**
     * 当前输入是否应该被拦截。
     *
     * @param inputs 工作台的输入容器（可能是 2 格铁砧，也可能是多格机器）
     * @return true = 这次合成无效
     */
    boolean blocks(Container inputs);

    /**
     * 带界面上下文的版本：同一个容器大小可能被多种工作台共用（原版铁砧 vs 奥术铁砧都是 2 格），
     * 只靠容器分辨不出来，所以把菜单实例也传进来。默认退回 {@link #blocks(Container)}。
     */
    default boolean blocks(Container inputs, Object menu) {
        return blocks(inputs);
    }
}
