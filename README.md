# Folio Cleaner · 洁册灵

> MC 1.20.1 · Forge 47.x · 双端 · **2.0.0 是一次全面重写，不兼容 1.x**
>
> 配方浏览器支持 **JEI / REI / EMI**（EMI 的配方过滤只在初始化期生效）

按物品 / 模组 / 配方 ID / 分类**隐藏**或**真正删除**配方；把多个模组重复添加的同一种物品**归并成一份**；
统计谁被最多配方当原料；还给「配方不在 `RecipeManager` 里」的特殊工作台（铁砧、奥术铁砧…）提供适配器机制。

---

## ⚠️ 2.0.0 与 1.x 不兼容

| 项 | 1.x | 2.0.0 |
|---|---|---|
| mod id | `jeicleaner` | **`foliocleaner`** |
| 配置目录 | `config/jeicleaner/` | **`config/foliocleaner/`** |
| 数据包目录 | `world/datapacks/jeicleaner/` | **`world/datapacks/foliocleaner/`** |
| 客户端指令 | `/jeicleaner`、`/jc` | **`/foliocleaner`**（短别名 `/folio`） |
| 服务端指令 | `/jeicleanerserver` | **`/folioserver`** |

升级时：旧配置**不会**自动迁移（2.0 是全新版本）。如果世界里还有旧的数据包目录，
服务端启动会打一条 WARN 提醒你删掉它 —— 那是存档里的东西，模组不会替你删。

---

## 三种「统一」实现（把重复物品归并成一份）

| 模式 | 做法 | 运行时开销 | 适用 |
|---|---|---|---|
| **改配方**（默认） | 反射替换 `Ingredient` 的物品缓存数组 = 原物品 ∪ 同组成员 | **0**（之后就是原版数组循环） | 单人 / 集成服 / 专用服（服务端也应用） |
| **运行时** | `Ingredient#test` 查表放宽匹配，显示不变 | 一次 identity 查表 | 同上，更保守 |
| **数据包** | 生成 datapack（tag 合并 + 配方 `item→tag` 改写） | 0 | 想要可回滚 / 手动检查 |

> 「改配方 / 运行时」要求**服务端与客户端都装**，并且两边的适配器必须一致 —— 见下。

## 适配器（给特殊工作台）

`config/foliocleaner/adapters/` 一个目录，按扩展名自动分类：

| 类型 | 写法 | 生效 |
|---|---|---|
| `*.json` | 声明式：菜单类名 + 结果槽 / 输入槽 | 热加载 |
| `*.lua` | 脚本式：`function blocks(ctx)`，沙箱 + 20ms 预算 | 热加载 |
| `*.jar` | 代码式：`ServiceLoader` 找 `CraftGuard` 实现 | **需要重启游戏** |

```json
{
  "id": "arcane_anvil",
  "name": "奥术铁砧",
  "comment": "悬浮提示里显示这句",
  "scope": { "menu": "irons_spellbooks.ArcaneAnvilMenu" },
  "probe": { "result": [2], "inputs": [0, 1] },
  "action": "clearResult"
}
```

```lua
name   = "奥术铁砧"
result = {2}

function blocks(ctx)
  return ctx:slot(0) == "minecraft:iron_ingot"
     and ctx:slot(1) == "minecraft:diamond"
end
```

### 调试面板

游戏内按 **F7**（可在「选项 → 控制 → 按键绑定 → 洁册灵」改）：

- 任何容器界面上叠加**槽位编号**（结果槽绿色）
- 可拖动的悬浮窗：菜单类名 / 槽位表 / 鼠标指向的槽位
- `[复制骨架]` 直接把当前界面的 json 适配器骨架塞进剪贴板
- `[演练]` 开启后适配器**只打日志不清结果**，验证完再关

### 客户端 / 服务端一致性

- 登录时服务端下发规则，并比对**适配器指纹**（`kind|id|内容哈希` 的 sha1 前 16 位）
- 指纹不一致 → 按 `config/foliocleaner/server-settings.json` 处理：

```json
{ "adapterCheck": "kick" }
```

`kick`（默认）直接断开并列出**缺少 / 多出 / 内容不同**；`warn` 只提示；`off` 不检查。
开关状态（enabled）由**服务端**下发，普通玩家改不了。

---

## 指令

```
/foliocleaner                 打开图形界面
/foliocleaner debug [on|off|dump|list|dryrun]
/foliocleaner viewers         看 JEI / REI / EMI 三家的适配状态
/foliocleaner unify [status|gen|rollback|wipe]
/folioserver unify ...        服务端（控制台 / OP）生成或撤销数据包
```

## 构建

```bash
# 需要 JDK 17；compile_only_depend/ 里要有 JEI 和 EMI 的 jar（不随仓库发布）
./gradlew build
```

## 许可

MIT
