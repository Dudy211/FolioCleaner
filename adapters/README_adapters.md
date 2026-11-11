# adapters/ —— 主流模组合成守卫适配器（1.20.1 Forge）

任务书：`TASK_ADAPTERS_MAINSTREAM.md`。为「合成结果不在 RecipeManager 里」的常见工作台提供适配器：
当输入槽出现被规则判「删除」的物品时，清空结果槽（合成被拦）。

**本目录只放文件、不跑构建**。部署方法见文末。
⚠️ 目录扫描是**非递归**的：子目录不会自动生效，部署时必须平铺拷进根目录。

## 一览表

| 模组 | 文件 | 形态 | 菜单类 | 结果槽 | 置信度 | 怎么验证 |
|---|---|---|---|---|---|---|
| 暮色森林 拆解台 | `twilightforest/uncrafting_table/uncrafting_table.json` | json | `twilightforest.inventory.UncraftingMenu` | 1（输入 0） | 高（1.20.1 源码核实） | F7 面板核对类名+槽位编号；输入槽放一个被「删除」规则命中的物品，确认结果槽每次广播被清空 |
| 农夫乐事 烹饪锅 | `farmersdelight/cooking_pot/cooking_pot.json` | json | `vectorwing.farmersdelight.common.block.entity.container.CookingPotMenu` | 8（输入 0-5,7） | 高（源码核实） | 同上；**双保险**：配方在 RecipeManager，删配方即可覆盖 |
| 匠魂 工匠工作台 | `tinkers/crafting_station/crafting_station.json` | json | `slimeknights.tconstruct.tables.menu.CraftingStationContainerMenu` | 9（输入 0-8） | 高（源码 addSlot 顺序） | 同上；**双保险**（原版 CRAFTING 类型） |
| 匠魂 部件铸造机 | `tinkers/part_builder/part_builder.json` | json | `slimeknights.tconstruct.tables.menu.PartBuilderContainerMenu` | 0（输入 1,2） | 高（源码 addSlot 顺序） | 同上；**双保险**（`tinkers:part_builder`） |
| AE2 铭文机 | `ae2/inscriber/inscriber.json` | json | `appeng.menu.implementations.InscriberMenu` | 3（输入 0,1,2） | 高（源码 addSlot 顺序） | 同上；**双保险**（`appeng:inscriber`） |
| 血魔法 炼金桌 | `bloodmagic/alchemy_table/alchemy_table.json` | json | `wayoftime.bloodmagic.common.container.tile.ContainerAlchemyTable` | 7（输入 0-6） | 高（源码 addSlot 顺序） | 同上；**双保险**（`bloodmagic:alchemytable`） |
| 血魔法 灵魂熔炉 | `bloodmagic/soul_forge/soul_forge.json` | json | `wayoftime.bloodmagic.common.container.tile.ContainerSoulForge` | 5（输入 0-4） | 高（源码 addSlot 顺序） | 同上；**双保险**（`bloodmagic:tartaricforge`） |

**「双保险」是什么意思**：这些工作台的结果本来就走已注册的 RecipeManager 配方类型，
普通「删除」规则删掉配方就已经不产出了；适配器只是额外一层（防止模组自己塞结果槽）。
所以它们**不需要**也能正常工作，装了也不会误伤。

### 刻意没写的

| 模组 | 工作台 | 为什么 |
|---|---|---|
| 匠魂 | Tinker Station | 结果槽索引 = `1 + 输入槽数`，而输入槽数藏在父类 `RetexturedTableBlockEntity` 里没查到 → **不敢写**（索引错了会去清输入槽，销毁物品）。而且它走 `tinkers:tinker_station` 配方类型，删配方即可 |

## 部署

`_deploy/` 是**扁平化**的一份（非递归扫描只认根目录），整个目录的内容拷进
`config/foliocleaner/adapters/` 即可；或直接用发布的 `foliocleaner-adapters-*.zip`。

> 发布包**只含上表这 7 个 json**。`_test/`（演示）和 `_unverified/`（未验证/不支持的占位）
> **都不分发** —— 尤其是多结构那几处，模板里的槽位是猜的，丢进去会清错槽位。


## 调研过、但不需要适配器（走 RecipeManager，删配方即可）

| 模组 | 工作台 | 菜单类 | 原因（源码证据） |
|---|---|---|---|
| 匠魂 | Part Builder 部件铸造机 | `slimeknights.tconstruct.tables.menu.PartBuilderContainerMenu` | 结果由 `level.getRecipeManager().byType(TinkerRecipeTypes.PART_BUILDER)` 查出（`tinkers:part_builder` 已注册配方类型），删配方即覆盖。槽位：0=结果, 1=模板, 2=材料 |
| 匠魂 | Tinker Station 匠魂熔炉台 | `slimeknights.tconstruct.tables.menu.TinkerStationContainerMenu` | `manager.getRecipeFor(TinkerRecipeTypes.TINKER_STATION, …)`（`tinkers:tinker_station` 已注册，含冶炼/换件/修复/损毁）；槽位：0=工具槽, 1-4=输入, 5=结果 |
| 匠魂 | Crafting Station 匠魂工作台 | `slimeknights.tconstruct.tables.menu.CraftingStationContainerMenu` | 原版 `RecipeType.CRAFTING`（3x3 网格槽 0-8，结果槽 9）；删配方即覆盖 |

（依据：TinkersConstruct@1.20.1 分支 `library/recipe/TinkerRecipeTypes.java` 与 `tables/block/entity/table/*BlockEntity.java`）

| 模组 | 工作台 | 菜单类 | 原因（源码证据） |
|---|---|---|---|
| AE2 | Inscriber 铭文机 | `appeng.menu.implementations.InscriberMenu` | `InscriberRecipes` 走 `level.getRecipeManager().byType(InscriberRecipe.TYPE)`（`appeng:inscriber` 已注册）；删配方即覆盖。槽位：0=上书板, 1=下书板, 2=材料, 3=产出 |
| AE2 | Charger 充能器 | 无 ContainerMenu（1.20.1 源码树中无 Charger 菜单类） | 充能也是**已注册配方类型**：`ChargerRecipes` 走 `level.getRecipeManager().byType(ChargerRecipe.TYPE)`（`appeng:charger` 已注册）；删配方即覆盖，无适配器问题 |
| 血魔法 | 炼金桌 Alchemy Table | `wayoftime.bloodmagic.common.container.tile.ContainerAlchemyTable` | 模组配方注册表只是 RecipeManager 的封装：`getAlchemyTable` → `world.getRecipeManager().getAllRecipesFor(BloodMagicRecipeType.ALCHEMYTABLE)`（`bloodmagic:alchemytable` 已注册）；删配方即覆盖。槽位：0-5=材料(六边), 6=魔力水晶, 7=产出 |
| 血魔法 | 灵魂熔炉 Soul Forge（1.20.1 即旧「地狱火熔炉 Hellfire Forge」） | `wayoftime.bloodmagic.common.container.tile.ContainerSoulForge` | `getTartaricForge` → `world.getRecipeManager().getAllRecipesFor(BloodMagicRecipeType.TARTARICFORGE)`（`bloodmagic:tartaricforge` 已注册）；删配方即覆盖。槽位：0-3=材料, 4=灵魂瓶, 5=产出 |

（依据：WayOfTime/BloodMagic@1.20.1 分支 `impl/BloodMagicRecipeRegistrar.java`、`common/tile/TileAlchemyTable.java`、`common/tile/TileSoulForge.java`）

## 做不了（需要新注入点）

原则：没有 `ContainerMenu` 的机器/结构，`broadcastChanges` 注入点覆盖不到，
需要专门的 Mixin 注入点（BlockEntity 层）才能拦。

| 模组 | 机器 | 依据（源码） | 现状 |
|---|---|---|---|
| 植物魔法 | 大地聚合板 Terrestrial Agglomeration Plate | 结构型合成（板+环绕花瓣触发）；1.20.x 源码树中无任何 Menu/Container/Screen 类，只有 `TerrestrialAgglomerationPlateBlockEntity` | 无 ContainerMenu，做不了守卫适配器；但配方是已注册类型（有 datagen provider `TerrestrialAgglomerationProvider`），删配方仍可用 |
| 植物魔法 | 花瓣药铺 Petal Apothecary | 1.20.x 源码树中无 Menu/Container/Screen 类（只有 `PetalApothecaryBlockEntity`）；取配方走 `level.getRecipeManager().getRecipeFor(BotaniaRecipeTypes.PETAL_TYPE)`（`botania:petal_apothecary` 已注册） | 无 ContainerMenu，做不了守卫适配器；删配方可覆盖配方表 |
| 造物 Create | 机械合成器 Mechanical Crafter | `content/kinetics/crafter` 包无任何 Menu/Container 类（只有 `MechanicalCrafterBlockEntity` 等）；配方 `MechanicalCraftingRecipe extends ShapedRecipe`，经 `AllRecipeTypes` 注册 | 无 ContainerMenu，做不了守卫适配器；删配方可覆盖。占位模板见 `_unverified/create_mechanical_crafter.lua`（**不随发布包分发**） |

## 部署方法

★ 目录扫描是**非递归**的：必须把文件**平铺**拷进适配器根目录，子目录不生效。

```bash
cp adapters/twilightforest/uncrafting_table/*.json <整合包>/config/foliocleaner/adapters/
# 后续每加一个适配器同样平铺拷贝：
# cp adapters/<modid>/<feature>/*.{json,lua} <整合包>/config/foliocleaner/adapters/
```

- json / lua：服务端执行 `/folioserver adapters reload` 热加载（或客户端界面点「同步加载」）
- jar（SimpleCraftGuard）：需重启游戏（类加载器无法卸载）
- **`_unverified/` 不分发**：里面是未验证/当前框架覆盖不到的占位（多结构祭坛、Create 机械合成器、
  通用机械模板）。它们的 `MENU_HINT` / `result` 都是猜的，**丢进 config 可能清掉输入槽销毁物品**。
  真要启用必须先按文件头注释用 F7 核对菜单类名与槽位。
- **多结构（无容器界面的祭坛/合成器）当前不支持**：`broadcastChanges` 注入点打不到，
  需要 BlockEntity / 结构事件级别的新注入点，见 HANDOFF「已知未修」第 1 条。
