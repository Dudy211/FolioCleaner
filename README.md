# JEI Cleaner

> MC 1.20.1 · Forge 47.x · 双端（客户端隐藏 + 服务端真删）· 依赖 JEI 15.x

按物品 / 模组 / 配方隐藏或**真正删除** JEI 与合成表里的配方，游戏内图形界面操作。
为整合包与服务器设计：服务端是唯一真源，规则登录时统一下发。

---

## 两种模式

| 模式 | 谁执行 | 效果 |
|---|---|---|
| **隐藏**（hide） | 客户端 JEI | JEI 里看不到；玩家记得配方仍然能合成 |
| **删除**（remove） | 服务端 | 配方从 `RecipeManager` 移除 → 看不到、摆不出、机器不生产 |

「删除」分三层覆盖：

| 层 | 原版配方 | 铁砧类（奥术铁砧） | 科技包机器 |
|---|---|---|---|
| JEI 看不到 | ✅ | ✅ | ✅ |
| 摆不出产物 | ✅ | ✅ 拦结果槽 | ✅ 若走 RecipeManager |
| 机器不生产 | ✅ | — | ✅ 同上 |

---

## 规则模型

一条规则可以有多组「范围」，组之间是**或**；每组下面可以有多条「搭配」，
框内是**与**、框外是**或**：

```
(奥术铁砧 + 剑 + 法球) 或 (锻造台 + 护甲)
```

```json
{
  "type": "item",
  "target": "irons_spellbooks:fire_upgrade_orb",
  "action": "remove",
  "ranges": [
    {
      "type": "irons_spellbooks:arcane_anvil",
      "partnerGroups": [["#forge:swords", "irons_spellbooks:fire_upgrade_orb"]]
    }
  ]
}
```

- `type`：`item` / `mod` / `recipe` / `keep`（白名单） / `category`（整类隐藏）
- `action`：`hide` / `remove`
- `ranges`：至少一组；`type` 空 = 不限配方类型
- `partnerGroups`：外层或、内层与

旧字段 `recipeType` / `partner` 仍可读（当作第一组）。

---

## 图形界面

```
/jeicleaner      或   /jc
```

- 顶部页签：**物品 / 模组 / 配方**
- 中间「属性台」按页签变化：
  - 物品：产物 / 原料 / 范围（多组编辑器）/ 隐藏·删除
  - 模组：范围 / 隐藏·删除
  - 配方：灰字教程
- 右栏规则：**点行 = 启用/停用**，`···` = 编辑，`×` = 删除
- 配方页悬浮条目会直接渲染**该配方的 JEI 布局**（合成台 3×3、熔炉、切石机……）

---

## 安装

1. Forge 1.20.1（47.x）
2. JEI 15.x
3. 把 jar 放进 `mods/`（整合包建议客户端 + 服务端都放）

JEI 是可选依赖：没装 JEI 时不会崩，只是没有隐藏效果（服务端删除仍然可用）。

---

## 指令

```
/jeicleaner                                 打开 GUI
/jeicleaner add item <物品id> [配方类型]      隐藏/删除该物品的配方
/jeicleaner add itemonly <物品id>            只从 JEI 物品列表移除
/jeicleaner add mod <modid> [配方类型]        整个模组
/jeicleaner add keep <modid>                 白名单：只保留这个模组
/jeicleaner add recipe <配方id>               单条配方
/jeicleaner add type <配方类型>               整个分类
/jeicleaner list / remove <n> / toggle <n>
/jeicleaner export / clear / reload / apply
/jeicleaner verify                          隐藏自检
/jeicleaner diag <物品id>                    诊断某物品的配方命中情况
```

---

## 配置文件

```
config/jeicleaner/
├── server.json           服务端权威规则（服主配置，登录时下发给所有玩家）
├── client.json           玩家本地规则（只影响自己）
└── client-settings.json  GUI 偏好（属性台模板、搜索记忆等）
```

旧版 `config/jeicleaner.json` 会自动迁移到 `server.json`。

服务端删除生效时日志会打印：

```
[JEI Cleaner] 服务端已删除 128 条配方（规则 3 条）：ars_nouveau 18 / mekanism 42 / minecraft 68
```

---

## 兼容：自定义工作台

有些工作台的合成逻辑写在 Menu / BlockEntity 里，没有配方实体（例如
Iron's Spellbooks 的奥术铁砧）。这类工作台通过 `compat/` 下的**适配器**拦截：

```
src/main/java/com/yuneko/jeicleaner/compat/
├── CraftGuard.java          接口
├── CraftGuards.java         注册表
├── CombinerCraftGuard.java  内置：ItemCombinerMenu 家族（铁砧类）
├── ItemUtil.java            物品 / 标签匹配
└── README.md                怎么新增适配器
```

新增一种工作台：写一个 `CraftGuard` 实现 → 在 `CraftGuards` 静态块注册 →
（如果不是 `ItemCombinerMenu` 子类）再加一个 Mixin 调用它。

---

## 构建

```bash
JAVA_HOME=<jdk17> GRADLE_USER_HOME=<gradle 缓存> \
  ./gradlew build --offline
```

需要的本地依赖（不随仓库提交）：

| 目录 | 内容 |
|---|---|
| `compile_only_depend/` | JEI 15.20.0.106 的 jar |
| `mixin-libs/` | `mixin-processor.jar`（Mixin 0.8.5 annotation processor） |

产物：`build/libs/jeicleaner-forge-1.20.1-<version>.jar`

---

## 目录

```
src/main/java/com/yuneko/jeicleaner/
├── Rule.java / Rules.java        规则模型与持久化
├── Selector.java                 客户端 JEI 隐藏（原版配方）
├── Cleaner.java                  应用规则到 JEI（含自定义配方）
├── RecipeRemover.java            服务端真删 RecipeManager 条目
├── RuntimeFilter.java            非 Recipe 的 JEI 配方判断
├── ServerCraftGuard.java         compat 的兼容入口
├── CleanerNetwork.java           规则同步
├── ServerEvents.java             服务端生命周期
├── compat/                       自定义工作台适配器
├── client/                       GUI（CleanerScreen / PinyinIndex / 指令）
└── mixin/                        RecipeManagerInternal / ItemCombinerMenu
```

---

## 许可

MIT
