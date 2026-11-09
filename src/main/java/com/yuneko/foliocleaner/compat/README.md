# compat/ —— 自定义工作台适配器

有些工作台的合成逻辑**不在**服务端 `RecipeManager` 里，而是写死在 Menu / BlockEntity 中：

- 原版铁砧（`ItemCombinerMenu`）
- Iron's Spellbooks 的奥术铁砧（`ArcaneAnvilMenu extends ItemCombinerMenu`）
- 部分科技模组的机器（配方表自建 / 缓存）

这类工作台没法用「删配方」处理，只能用 **CraftGuard** 拦截合成结果。

---

## 现有适配器

| 类 | 覆盖 | 说明 |
|---|---|---|
| `CombinerCraftGuard` | `ItemCombinerMenu` 家族 | 原版铁砧、奥术铁砧，以及所有继承同一基类的合成台 |

---

## 新增一个适配器

### 1. 如果目标工作台是 `ItemCombinerMenu` 的子类

不用写新类，直接复用 `CombinerCraftGuard`（它按输入容器判断，不关心具体 Menu）。

### 2. 其它类型（自定义 Menu / BlockEntity）

新建 `XxxCraftGuard implements CraftGuard`：

```java
public final class XxxCraftGuard implements CraftGuard {

    @Override
    public String name() {
        return "xxx";
    }

    @Override
    public boolean blocks(Container inputs) {
        // 按需要读 inputs.getItem(i)，对照 Rules.serverRules() 里 action=remove 的规则
        // 返回 true = 这次合成无效
        return false;
    }
}
```

**不用注册**：`CraftGuards` 启动时会扫描 `com.yuneko.foliocleaner.compat` 包，
自动实例化所有 `CraftGuard` 实现（要求：`public` 类、`public` 无参构造）。

丢进这个目录 → 重新编译 → 自动生效。日志会打印：

```
[Folio Cleaner] 已加载 2 个合成守卫适配器
[Folio Cleaner] 自动注册合成守卫: xxx
```

（`CombinerCraftGuard` 仍保留在静态块里作为兜底；同类只会注册一次。）

### 3. 需要新的注入点？

`CombinerCraftGuard` 靠 `mixin/ItemCombinerMenuMixin` 注入
`ItemCombinerMenu.m_6199_`（`slotsChanged`）的 TAIL，检查 `resultSlots`。

如果目标不是 `ItemCombinerMenu`：

1. 新建 `mixin/XxxMenuMixin`，在生成结果 / 取出结果的方法末尾调用
   `CraftGuards.isBlocked(container, new XxxCraftGuard())`（或按类型过滤）；
2. 在 `src/main/resources/foliocleaner.mixins.json` 的 `mixins` 数组里登记；
3. 注入必须带 `require = 0`，目标结构变化时静默跳过，不能因此崩客户端。

---

## 约定

- **只读**：`blocks()` 不要修改容器内容，清空结果由调用方负责
- **不崩**：任何异常都要吞掉并返回 `false`，守卫失败不能影响正常游戏
- **范围限制**：这类工作台没有原版 RecipeType，只处理「范围不限类型」的规则；
  带类型限制的组直接跳过，避免误伤
