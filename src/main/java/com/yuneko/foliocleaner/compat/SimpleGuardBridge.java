package com.yuneko.foliocleaner.compat;

import net.minecraft.world.Container;

import java.util.ArrayList;
import java.util.List;

/**
 * 把「纯数据」适配器（{@link SimpleCraftGuard}）桥接成 {@link CraftGuard}。
 *
 * <p>★ 必须是一个**有名字的类**：之前用匿名内部类包，`getSimpleName()` 返回空串，
 * 结果界面上显示 `id=code:`、来源显示「内置」—— 因为匿名类和我们的 jar 同源。
 * 这里显式记住 delegate 和它来自哪个 jar。
 */
final class SimpleGuardBridge implements CraftGuard {

    private final SimpleCraftGuard delegate;
    private final String id;
    private final String source;

    SimpleGuardBridge(SimpleCraftGuard delegate, String source) {
        this.delegate = delegate;
        this.source = source == null || source.isBlank() ? "jar" : source;
        String simple = delegate.getClass().getSimpleName();
        if (simple.isEmpty()) simple = delegate.getClass().getName();
        if (simple.endsWith("Guard")) simple = simple.substring(0, simple.length() - "Guard".length());
        this.id = simple.replaceAll("([a-z0-9])([A-Z])", "$1_$2").toLowerCase(java.util.Locale.ROOT);
    }

    String originId() {
        return id;
    }

    String originSource() {
        return source;
    }

    @Override
    public String name() {
        try {
            String n = delegate.name();
            if (n != null && !n.isBlank()) return n;
        } catch (Throwable ignored) {
        }
        return delegate.getClass().getSimpleName();
    }

    @Override
    public boolean blocks(Container inputs) {
        return blocks(inputs, null);
    }

    @Override
    public boolean blocks(Container inputs, Object menu) {
        try {
            if (inputs == null) return false;
            List<String> ids = new ArrayList<>(inputs.getContainerSize());
            for (int i = 0; i < inputs.getContainerSize(); i++) {
                var stack = inputs.getItem(i);
                ids.add(stack == null || stack.isEmpty() ? ""
                        : net.minecraft.core.registries.BuiltInRegistries.ITEM
                        .getKey(stack.getItem()).toString());
            }
            List<String> targets = new ArrayList<>();
            for (com.yuneko.foliocleaner.Rule rule : com.yuneko.foliocleaner.Rules.serverRules()) {
                if (rule == null || !rule.enabled) continue;
                if (!com.yuneko.foliocleaner.Rule.ACTION_REMOVE.equals(rule.action)) continue;
                if (!com.yuneko.foliocleaner.Rule.TYPE_ITEM.equals(rule.type)) continue;
                if (rule.target != null) targets.add(rule.target);
            }
            return delegate.blocks(ids, menu == null ? "" : menu.getClass().getName(), targets);
        } catch (Throwable t) {
            return false;
        }
    }
}
