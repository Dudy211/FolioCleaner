# CHANGELOG_UI — JEI Cleaner GUI 大改造（TASK_UI.md）

MC 1.20.1 / Forge 47.4.23 / Java 17 / JEI 15.x
改动文件：`src/main/java/com/yuneko/jeicleaner/client/CleanerScreen.java`（重写）、`client/PinyinIndex.java`（新增）。
核心文件（Cleaner / Rule / Rules / Selector / RecipeRemover / ItemStats）未改动；无新依赖、无新 jar；未重命名任何 public 方法。

## 新增：client/PinyinIndex.java

- `PinyinIndex.java:15-277` — `HANZI` 常量：20892 个汉字的拼音首字母表（pypinyin 离线生成，按 Unicode 码位升序，可用二分查找）
- `PinyinIndex.java:279-541` — `INITIALS` 常量：对应的首字母串；已核对 `HANZI.length() == INITIALS.length() == 20892`（且与生成源逐字一致）
- `PinyinIndex.java:543-549` — static 断言：两串长度不一致直接抛异常
- `PinyinIndex.java:555-567` — `initialOf(char)`：汉字→拼音首字母（小写），非汉字返回 0（二分查找）
- `PinyinIndex.java:569-579` — `toInitials(String)`：整串转首字母串，非汉字原样保留并小写
- `PinyinIndex.java:581-591` — `initialsMatch(String, String)`：query 是否为首字母串的子序列（如 `jyxb` 命中「经验修补」）

## client/CleanerScreen.java

### 需求 1：中间「属性台」面板

- `CleanerScreen.java:55` — 新增常量 `MID_W = 96`（属性台固定宽）
- `CleanerScreen.java:84-86` — 新增字段 `defaultOutput = true`、`defaultAction = Rule.ACTION_HIDE`（`includeIngredient` 沿用，作为「原料」默认值）
- `CleanerScreen.java:155-157` — `listW()` 由 55% 改为 44%（下限 40，小窗口不崩）
- `CleanerScreen.java:168-170` — 新增 `midX()`：属性台左缘 = 左栏右侧 + 8px 间距
- `CleanerScreen.java:172-179` — `ruleX()` / `ruleW()` 改为从属性台推导（下限 30）
- `CleanerScreen.java:565-590` — `render()` 在左右列表之间调用 `drawPropPanel()`
- `CleanerScreen.java:662-694` — `drawPropPanel()`：属性台绘制（标题 / 产物 / 原料 / [隐藏][删除] / 底部提示；弹窗打开时显示正在编辑的规则供对照，此时不可点）
- `CleanerScreen.java:696-705` — `drawPropRow()`：产物/原料行，●=开 ○=关，悬停高亮
- `CleanerScreen.java:707-712` — `drawToggleBtn()`：共用小按钮（隐藏/删除/启用/停用/完成）
- `CleanerScreen.java:719-738` — `handlePropPanelClick()`：点击切换 `defaultOutput` / `includeIngredient` / `defaultAction`；切到 [删除] 时状态栏提示「删除仅在本机（单人有效）」
- `CleanerScreen.java:525-560` — `addFromEntry()` 改为按属性台默认值生成规则（`hideOutput` / `hideIngredient` / `action`）；`action=remove` 时状态栏追加「删除仅在本机，单人有效」
- `CleanerScreen.java:807-827` — 左栏 tooltip 文案改为「按属性台默认值添加规则」并列出当前默认属性
- `CleanerScreen.java:1196-1201` — `mouseClicked()`：属性台区域命中检测并路由到 `handlePropPanelClick()`
- 小窗口（320×180）下 `listH < 110` 时属性台底部两行提示自动隐藏，避免溢出

### 需求 2：右侧规则点击 → 属性编辑弹窗

- `CleanerScreen.java:57-58` — 新增常量 `EDIT_W = 220` / `EDIT_H = 140`
- `CleanerScreen.java:109-110` — 新增字段 `ruleEditOpen` / `ruleEditIndex`
- `CleanerScreen.java:831-837` — `editX()` / `editY()`：居中于面板（全部基于 panelX/panelY/panelW/panelH）
- `CleanerScreen.java:839-844` — `openRuleEdit()`：边界检查后打开弹窗，并清除搜索框焦点（避免输入串入）
- `CleanerScreen.java:846-848` — `closeRuleEdit()`
- `CleanerScreen.java:856-893` — `handleRuleEditClick()`：点产物/原料开关或 [隐藏]/[删除] 立即 `Rules.replaceOrAdd(改后规则)` 写回（同 type+target+recipeType 覆盖，下标不变）；点 [启用/停用] 同样写回；点 [完成] 关闭；返回 false 表示点了弹窗外（调用方关闭）
- `CleanerScreen.java:895-938` — `drawRuleEdit()`：220×140 弹窗（标题 / target+@类型 / 产物● 原料● / [隐藏][删除] / [启用/停用][完成] / 范围与隐形物品提示）
- `CleanerScreen.java:1186-1191` — `mouseClicked()`：规则编辑弹窗分支放在最前，优先级最高（弹窗打开时其他点击一律不处理）
- `CleanerScreen.java:1240-1241` — `mouseScrolled()`：弹窗打开时吞掉滚轮
- `CleanerScreen.java:1222-1237` — 规则行点击拆分：行尾 14px 内 = 删除（保留原行为）；其他位置 = 打开编辑弹窗

### 需求 3：「范围」弹窗支持搜索

- `CleanerScreen.java:975-978` — `pickerH()` 200 → 220（增高约 20px，容纳搜索框）
- `CleanerScreen.java:106-107` — 新增字段 `pickerSearch`（EditBox，210×14）/ `pickerSearchText`
- `CleanerScreen.java:235-243` — `init()` 创建范围搜索框（不进 renderables，关闭时放在屏外，打开时定位到弹窗内）
- `CleanerScreen.java:988-993` — `positionPickerSearch()`：定位到弹窗内（打开时 / 窗口缩放后调用）
- `CleanerScreen.java:995-1002` — `closeTypePicker()`：关闭弹窗时搜索框移出屏幕并释放焦点
- `CleanerScreen.java:1040-1071` — `pickerRows()`：搜索框非空时按命中等级过滤（等级 1/2/3 分组，组内保持原顺序），空时显示全部
- `CleanerScreen.java:1073-1081` — `rowTier()`：1=拼音首字母（纯中文查询无效）/ 2=类型 id（英文）/ 3=中文名称
- `CleanerScreen.java:1089-1139` — `drawTypePicker()`：列表起点 y+23 → y+39（给搜索框让位），可见行数 (h-46)/16，空结果提示「没有匹配的类型」；`按工作方块` 模式条目仍带物品图标
- `CleanerScreen.java:1141-1172` — `handleTypePickerClick()`：搜索框行（y+20..y+39）点击被吸收
- `CleanerScreen.java:583-587` — `render()`：弹窗背景绘制后手动渲染搜索框（保证层级正确）
- `CleanerScreen.java:1194-1199` — `mouseClicked()`：弹窗打开时点中搜索框则给它输入焦点

### 需求 4：统一搜索（所有搜索框）

- `CleanerScreen.java:440-473` — `rebuildFiltered()`：解析 `@modid` 前缀过滤；命中条目按等级 0(@modid) / 1(拼音) / 2(英文) / 3(中文) 分组，组内保持原顺序
- `CleanerScreen.java:475-491` — `entryTier()`：等级判定（0=@modid 精确/前缀；1=拼音首字母 `PinyinIndex.initialsMatch`，纯中文查询不走此档；2=物品 id / 英文名（id 冒号后最后一段）/ modid 包含；3=中文名包含）
- `CleanerScreen.java:493-497` — `modOf()`：id 的命名空间（modid）
- `CleanerScreen.java:499-503` — `englishName()`：id 冒号后最后一段（`minecraft:diamond_sword` → `diamond_sword`）
- `CleanerScreen.java:505-513` — `isPureChinese()`：查询是否全为汉字（纯中文查询时拼音档无效）
- `CleanerScreen.java:228` — 主搜索框占位文案改为「搜索名称 / 拼音首字母 / @modid …」

### 其他

- `CleanerScreen.java:606` — `drawCandidateList()` 中 `Minecraft.getInstance().level` 保留原有 null 检查；本次未新增任何无保护的 `level` 访问
- 所有 `list.get(index)` 前均有 `index >= 0 && index < size()` 边界检查；`filtered.size()==0` 时不做除法（唯一的除法 `CleanerScreen.java:650` 位于 `maxScroll > 0` 分支内，此时 `filtered.size() >= 2`）
- 全部 GUI 坐标基于 `panelW()/panelH()/panelX()/panelY()` 推导；320×180 逻辑分辨率下各栏宽 125/96/59、弹窗 220×140 与 230×132 均成立，无负数、无除零

---

# CHANGELOG_UI — 1.15.0（2026-11-06）配方预览 + 属性台分页 + 配色

改动文件：`client/CleanerScreen.java`。无新增类、无新依赖。

## 1. 配方列表悬浮显示 JEI 配方布局

- 新增 `drawRecipePreview(GuiGraphics, Entry, int, int)`：
  悬浮「配方」页条目时，按该配方的 **JEI 原生布局**绘制（合成台 3×3 / 熔炉 / 切石机 / 锻造台……），
  用的是 `IRecipeManager.createRecipeLayoutDrawable(category, recipe, focusGroup)` +
  `IRecipeLayoutDrawable.drawRecipe/drawOverlays`，所以外观与 JEI 里完全一致。
- 新增 `buildPreview(IJeiRuntime, String)`：按 `recipeManager.byKey(id)` 取配方 →
  `BuiltInRegistries.RECIPE_TYPE.getKey(recipe.getType())` 取类型 id →
  `IRecipeManager.getRecipeType(id)` 找 JEI 分类 → 建布局。
  任一环节失败返回 null，回退到原来的文字 tooltip（不会崩）。
- 新增 `createPreviewDrawable(...)`：raw 泛型调用 + `@SuppressWarnings`，编译期已核对签名。
- 新增 `EMPTY_FOCUS`：`IFocusGroup` 的空实现（不依赖 JEI 的 library 内部类）。
- 布局缓存 `previewCache`（最多 24 条，超出清空；切页签 / 重新加载条目时清空），
  避免每帧重建 layout。
- 预览框自动避让屏幕边缘：右侧放不下挪到鼠标左边，下方放不下上移；标题画在框外。

## 2. 属性台按当前页签变化

- 物品页：产物 / 原料 / 范围 / 隐藏·删除 + 教程（原样）
- 模组页：**去掉产物 / 原料**（对整模组无意义），只留 范围 / 隐藏·删除 + 灰字教程
  （白名单模式下提示「点击 = 只保留该模组」）
- 配方页：**整块只留灰字教程**（「配方规则按 id 精确隐藏一条 / 不受属性台影响」），
  底部顺带显示当前是「隐藏」还是「删除」
- 可点击区域改为 `List<PropHit>` 记录：`drawProp*()` 绘制时写入，`handlePropPanelClick()`
  按 action 派发。绘制与命中判定共用同一份坐标，不会再出现「画在这里点在那里」。
- 底部「原料 / 物品」按钮只在本页签显示（`updateTabButtons()` 里切 `visible`）。

## 3. 配色

根因：副标题是 `"§8" + sub`，`§8` 把 `drawString` 传入的颜色覆盖成了深灰（0x555555），
在斑马纹深色行上几乎看不见 —— 看起来和行底色糊在一起。

- `COLOR_DIM` 0xFF8A8A9A → **0xFFAEB8CC**（副标题，明显亮于行底）
- 新增 `COLOR_FAINT` 0xFF7C879C（更弱的说明文字）
- 新增 `COLOR_INFO` 0xFF8FC7FF（范围值等数据文字）
- `COLOR_TEXT` → 0xFFF0F3FA，`COLOR_ACCENT` → 0xFFFFC46B
- 面板 / 表头 / 边框改为偏冷的蓝灰（0xF0181C26 / 0xFF242C3E / 0xFF3E4A63）
- 悬停高亮改琥珀色半透明 `0x33FFC46B`（比纯白叠加更容易分辨）
- 去掉列表副标题、顶部计数、状态栏、规则编辑弹窗里的硬编码 `§7/§8/§a` 前缀，
  颜色统一由常量控制
- 斑马纹保留交错，但副标题已统一为亮灰，深浅行上都清晰

---

# CHANGELOG — 1.16.1（2026-11-06）第二个范围「搭配」+ 遮罩

## 1. 第二个范围：搭配（另一个输入）

原来只有「范围 = 配方类型」，粒度是「哪一类工作台」。新增「搭配」，粒度是「配方里的另一个材料」，
两者叠加就能做非常精确的匹配。

规则字段：`Rule.partner`（字符串，空 = 不限）
- 普通物品：`minecraft:diamond_sword`
- 物品标签：`#forge:swords` / `#irons_spellbooks:...`（标签里任一物品都算命中）

语义（只在 `hideIngredient = true` 时生效）：
1. 配方里必须有一格接受 `target`（原来的行为）
2. 且**另一格**必须接受 `partner` —— 不是同一格

举例：`target=irons_spellbooks:fire_upgrade_orb`、`recipeType=奥术铁砧`、`partner=#forge:swords`
→ 只隐藏「火焰升级法球 + 剑」的那些铁砧配方，法球配装备/法杖的不受影响。

实现位置：
- `Selector.matchesIngredient(recipe, item, partner)` + `Selector.ingredientAccepts(...)`
  （客户端 JEI 隐藏；标签检查用 `Ingredient.getItems()` 里任一栈 `is(tag)`，避免逐物品构造栈）
- `RecipeRemover.matchesIngredient(...)`（服务端真删，不引用 JEI 类型）
- `RuntimeFilter.containsPartner(...)`（非 `Recipe` 的 mod 自定义 JEI 配方，
  按 `inputA / inputB` 两边分别判，A 含 target 时看 B，反之亦然）
- `Rules.toJson/fromJson` 增加 `partner` 字段（旧配置没有该字段 → 默认空，兼容）
- `Rule.copy/sameAs/describe/hasPartner` 同步

## 2. GUI

- 属性台「物品」页新增一行 **搭配**：显示 `不限 / #标签短名 / 物品中文名`，
  点击打开新的**搭配选择弹窗**（`partnerPickerOpen`）
- 选择弹窗：顶部搜索框（占位「搜索物品 / 标签 …」），列表顺序
  **不限 → 物品标签（#id + 数量）→ 物品（图标 + 中文名）**，可滚动，当前值高亮
- 属性台布局相应下移：产物 20 / 原料 36 / 搭配 52 / 范围 72 / 隐藏·删除 94 / 教程 122
- 规则编辑弹窗底部信息行会显示 `+搭配名`
- `ClientSettings` 增加 `defaultPartner / defaultPartnerLabel`，重启后保持
- `hasRule()` 比较时带上 partner（同一物品 + 不同搭配算两条不同规则）

## 3. 蒙版下的按钮文字

原来遮罩是 `0xC8000000`（78%），弹窗打开时主界面的按钮文字透过遮罩仍然能读出来。

- 遮罩改为 `0xF0121218`（94% 不透明，带一点蓝调），白字只剩 6% 亮度，基本看不见
- 所有四个弹窗统一（设置 / 规则编辑 / 范围选择 / 搭配选择）

---

# CHANGELOG — 1.17.0（2026-11-06）多组范围 + 搭配的「与 / 或」

## 数据模型

```java
Rule.Range
  String type                       // 配方类型，空 = 不限
  List<List<String>> partnerGroups  // 外层「或」，内层「与」
```

`[ [A, B], [C] ]` 表示 `(A 且 B) 或 C`。

- `Rule.ranges: List<Range>`，至少一组（`ensureRanges()`）
- 旧字段 `recipeType` / `partner` 保留为「第一组」的镜像（旧指令 / 显示用），
  读 JSON 时若没有 `ranges` 则用旧字段构造一组；旧 `partners` 扁平列表会迁移成
  「每个各成一组」（保持原来的「或」语义）
- JSON 同时写 `partners`（扁平，兼容）与 `partnerGroups`（分组，新）

## 匹配语义

一条规则命中 = 任意一组 Range 命中；一组 Range 内 = 任意一个「与」组全满足。

- `hideIngredient`：target 占一格，某个「与」组里的每个 partner 都要能在**其它格子**找到
- `hideOutput`：只看产物，忽略搭配
- mod 规则：本模组的配方 + 该组的搭配条件
- 服务端 `RecipeRemover`、客户端 `Selector`、自定义 JEI 配方 `RuntimeFilter` 三处逻辑一致
  （RuntimeFilter 是 inputA/inputB 两侧交叉判断）

## GUI

- 属性台「范围」变成摘要行：`2 组 · 3 搭配 ▸`，点击打开**范围与搭配编辑器**
- 编辑器布局：

```
1. 奥术铁砧                          ×
   ┌──────────────────────────────┐
   │ • 剑                       × │
   │ • 装备                     × │
   │ + 添加（与）                 │
   └──────────────────────────────┘
   + 添加（或）
2. 全部类型                          ×
   ...
[ + 添加范围 ]              [ 完成 ]
```

- 点组头 → 改配方类型（复用原类型选择器）
- 框内「+ 添加（与）」→ 新搭配加入该 AND 组
- 框外「+ 添加（或）」→ 新搭配单独成一组
- 每组至少保留一组范围（删到 1 组时 × 变灰）
- 编辑器内容可滚动；选择器弹窗盖在编辑器之上，选完自动回到编辑器
- `ClientSettings.defaultRangesJson` 持久化整套模板（重启后保留多组）

## 显示

- 规则行右侧：单组显示 `类型 [A&B | C]`，多组显示 `N组`
- 规则编辑弹窗标题/信息行同样显示摘要

---

# CHANGELOG — 1.18.0（2026-11-06）规则行交互 + 自定义 JEI 配方隐藏

## 1. 规则行交互

- **点行本身 = 启用 / 停用**（原来直接打开编辑）
- 行尾两个热区（悬停时显示）：
  - `···` = 打开规则编辑弹窗
  - `×` = 删除
- 服务端规则仍只允许管理员（且编辑目标是服务端）操作；普通管理员点行只提示用 `···`

## 2. 自定义 JEI 配方隐藏（奥术铁砧等）

### 为什么之前藏不掉

这类配方（Iron's Spellbooks 的 `ArcaneAnvilJeiRecipe`）：
- 不是 `Recipe` 实现 → 不在原版 `RecipeManager` 里，`Selector` 遍历不到
- 由 mod 自己在 `JeiPlugin#registerRecipes` 里塞进 JEI → 只有 JEI 认识

旧方案靠 Mixin 过滤 `RecipeManagerInternal#getRecipesStream` + 反射 `getRecipeItems()`，
依赖具体 mod 的字段形状，且 JEI 缓存路径一变就静默失效。

### 新方案（JEI 官方 API，通用）

`Cleaner.applyCustomRecipes(...)`：

1. `createRecipeCategoryLookup()` 遍历**所有 JEI 分类**
2. 对每个分类 `createRecipeLookup(type).includeHidden().get()` 取全部配方（含已隐藏）
3. 跳过 `recipe instanceof Recipe`（原版配方已由 Selector 处理）
4. 判断是否命中：
   - 快路径：`RuntimeFilter.canInspect()` → 反射 `getRecipeItems()`（Iron's Spellbooks 走这条）
   - 通用路径：`createRecipeLayoutDrawable(category, recipe, EMPTY_FOCUS)` →
     `getRecipeSlotsView().getSlotViews()`，按 slot 的 `RecipeIngredientRole`
     把 `INPUT` / `OUTPUT` 的 `ItemStack` 分组，再用与 Selector 相同的
     范围 / 搭配（与·或）逻辑匹配
5. 命中的调 `IRecipeManager.hideRecipes(type, list)` 隐藏，记入 `HIDDEN_CUSTOM`
6. 规则变化时 `restore()` 先 `unhideRecipes` 再重算

日志会打印：`[JEI Cleaner] 自定义配方 <uid> 隐藏 N 条`。

### 已知边界

- `remove`（服务端真删）对这类配方无效 —— 它们本来就不在服务端配方表里，
  只能做客户端 `hide`
- 布局构建失败的配方会被跳过（不会崩），日志里没有该分类的隐藏记录
- CATALYST 角色的槽位不算输入/输出（避免铁砧模具之类误判）

---

# CHANGELOG — 1.19.0（2026-11-06）铁砧系自定义工作台「真正删除」

## 背景

Iron's Spellbooks 的奥术铁砧这类工作台**没有配方实体**：

- 配方逻辑写死在 `ArcaneAnvilMenu.m_6640_()`（`slotsChanged → createResult`）里
- 读 `f_39769_`（inputSlots）的 0/1 两个槽，按物品类型/标签判断能否合成
- 不在服务端 `RecipeManager`，`remove` 找不到可删的条目

所以「删配方数据」这条路不存在，只能**拦结果生成**。

## 实现

### `ServerCraftGuard`（服务端判断）

输入容器 + 规则 → 是否该拦：

- 只处理 `action=remove` + `type=item` 的规则
- 只匹配**范围不限类型**（`type` 为空）的组 —— 这类工作台没有原版 RecipeType，
  带类型限制的规则无法判断，直接跳过（避免误伤）
- target 占一个槽，某个「与」组的每个 partner 都要能在**其它槽**找到（与 Selector 同一套语义）

### `ItemCombinerMenuMixin`

```java
@Mixin(value = ItemCombinerMenu.class, remap = false)
@Inject(method = "m_6199_", at = @At("TAIL"), require = 0)
```

- `m_6199_` = `slotsChanged(Container)`，`ItemCombinerMenu` 在这里生成结果
- 末尾检查 `f_39768_`（resultSlots），命中规则就 `setItem(0, EMPTY)`
- `require = 0`：目标类结构变化时静默跳过，不让客户端崩

**覆盖范围**：所有继承 `ItemCombinerMenu` 的工作台 —— 原版铁砧、奥术铁砧，
以及其它模组用同一个基类的合成台。

## 注意

- 生效条件：`action=remove` + 物品规则 + **范围留空（不限类型）**
- 客户端也会执行同一个 Mixin（结果槽同步清空），但**权威判定在服务端**
  —— 多人游戏里客户端拦截挡不住作弊客户端
- 原版铁砧也会受影响（同一基类）。如果不希望误伤，规则里把 target/搭配配精确

## 关于现成模组

- **KubeJS / CraftTweaker**：只能移除 `RecipeManager` 里的配方
  （以及注册了 RecipeType 的自定义配方），对「容器内硬编码的合成」无效
- **GroovyScript**：能反射任意类，理论上可以，但需要为每个 mod 单独写适配
- 通用做法就是本模组这种：**拦容器基类的 result 生成**，按基类覆盖一批工作台

---

# 1.31.0 · 毒瘤排行

## 干什么

按「被多少条配方当作**原料**使用」排序的物品表，点一行直接生成一条
「按原料隐藏、不限工作台」的物品规则 —— 专治那些被上千条配方引用的万能材料
（对着它按 JEI 的 U 键会把上千个配方页一次性堆出来，直接卡死）。

## 改动点

| 文件 | 改动 |
|---|---|
| `client/CleanerScreen.java` | 新增毒瘤排行弹窗（`topW/topH/topX/topY/topListY/topListH`、`drawTop`、`handleTopClick`、`scrollTopFromMouse`、`addTopRule`、`hasIngredientRule`、`refreshTopRows`、`openTop`） |
| 同上 | 属性台（物品页）在「隐藏 / 删除」下面新增一行可点入口「毒瘤排行 ▸」（`propHits` action = `top`） |
| 同上 | `render()` / `mouseClicked()` / `mouseScrolled()` / `mouseDragged()` / `mouseReleased()` 各加一条 `topOpen` 分支；`draggingTopBar` 与其它三条滑条并列复位 |
| `ItemStats.java` | 同一条配方内**去重**：复用 `Item[] seen` 缓冲，`usage()` 现在是「被多少条配方用到」 |

## 为什么入口不放底栏

底栏在 320 宽的小窗口下已经挤爆（左侧 3 个按钮 198px、右侧 5 个按钮 261px，`panelW-26` 才 286）。
属性台是固定 96px 的竖栏，加一行只有 14px，不影响任何已有排版。

## 生成的规则长什么样

```json
{ "type": "item", "target": "mekanism:ingot_steel",
  "hideOutput": false, "hideIngredient": true, "hideItem": false,
  "action": "hide", "ranges": [ { "type": "" } ] }
```

- `hideOutput=false`：产物配方不动
- `hideIngredient=true`：所有「用到它」的配方都藏
- `hideItem=false`：物品本体保留（不然连它自己都搜不到）
- `ranges[0].type=""`：不限工作台

走 `Rules.add()` 而不是 `replaceOrAdd()` —— 后者按 `type + target + recipeType` 覆盖，
会把你原来给同一物品配过的「限某工作台」规则顺手抹掉。

## 排序缓存

`refreshTopRows()` 用 `identityHashCode(level) * 31 + ItemStats.totalRecipes()` 当签名。
`ItemStats.topUsage()` 每次要拷贝 + 排序几万个条目，**绝对不能每帧调**。

---

# 1.31.1 · 毒瘤排行改成页签

1.31.0 先做成了弹窗，但弹窗要自己维护一套「点击 / 滚动 / 滑条 / 命中」逻辑，
而且和左侧列表做同一件事。改成页签后这些全部免费复用。

## 改动

| 位置 | 改动 |
|---|---|
| `Tab` 枚举 | 新增 `USAGE("毒瘤")`，排在「配方」右边 |
| 标题栏 | 页签 `54+4` → `42+3`（4 格用 185px）；「设置」110 → 46 |
| 标题栏 | 移除「白名单」按钮 → 挪进模组页属性台（`drawPropMod` 里 `PropHit action="keep"`） |
| `loadUsage()` | 排行直接进 `entries`，左侧列表原样渲染 |
| `drawPropUsage()` | 毒瘤页专属属性台：隐藏/删除切换 + 「可见配方 N」 |
| `addFromEntry()` | 新增 `USAGE` 分支：`hideIngredient=true` / `hideOutput=false` / `hideItem=false`，范围不限 |
| `render()` | 每帧比 `ItemStats.signature()`，变了就重排（自动「每次修改后重新排行」） |
| `render()` | 手写的 Renderable 循环补 `visible` 判断（原来隐藏的按钮照样画） |
| `ItemStats` | 只统计可见配方（`RecipeRemover.matches(..., includeHide=true)`）；缓存键 = 世界身份 + `Rules.revision()` |
| `RecipeRemover` | `matches` 新增 `includeHide` 重载，3 参版本保持旧语义（服务端只认 remove） |

## 为什么「自动重排」放在 render 而不是点击回调

规则会被**四条路径**改：左侧点条目、右栏开关/删除、重载文件、管理员推服务端。
在每个入口都写一遍 `reloadUsage()` 迟早漏一个；比签名是最省心也最不会漏的写法，
而且签名没变时它只是一次 int 比较。

## 1.31.2 补丁

- 毒瘤页把底栏「原料 / 物品」两个开关收起（`itemTab = tab == Tab.ITEM`）。
  这两个开关只影响物品页的点击语义；毒瘤页的动作是固定的（只按原料下手），
  留着会让人以为「物品:隐」在这儿也生效。

## 1.31.3 补丁

标题栏按最小宽度重算：页签 `36+2`（4 格 158px）、「设置」34、「改:客户端」52。
按 `panelW` 四档验算（262 / 312 / 420 / 640）均不重叠 —— 262 是手机 GUI scale 4 时的实际宽度。

---

# 1.32.0 · 按页懒建布局（修「对着奥术铁砧按 U 直接 OOM」）

## 根因（字节码实证）

`mezz.jei.gui.recipes.RecipeGuiLogic#createRecipeLayoutsWithButtons`：

```java
List<T> recipes = focused.getRecipes();                       // 全部 1400 条
List<RecipeLayoutWithButtons<?>> layouts = recipes.stream()
        .map(r -> createRecipeLayoutDrawable(category, r, focuses, bg, 4))  // ★ 逐条建布局
        .map(recipeLayoutFactory::create)
        .toList();
return IRecipeLayoutList.create(stages, menu, player, layouts);
```

`LazySortedRecipeLayoutList` 名字里的 Lazy **只懒排序**，它拿到的列表里布局已经全建好了。
按 U 在奥术铁砧（该分类的催化剂）→ 催化剂展开整个分类 → 1400 个布局 → OOM。

## 修法

| 文件 | 作用 |
|---|---|
| `mixin/RecipeGuiLogicMixin.java` | `@Inject(method="createRecipeLayoutsWithButtons", at=@At("HEAD"), cancellable, require=0)`；条数 > 128 时返回懒列表 |
| `client/PagedRecipeLayoutList.java` | 实现 `IRecipeLayoutList`：`size()` 真实条数、`subList()` 才建布局、LRU 缓存 120 条、`tick()` 空实现 |

关键点：
- 分页/页码由 `cachedRecipeLayoutsWithButtons.size()` 驱动 ⇒ 我们的 `size()` 报真实条数，**页码不变，仍能翻完全部**
- 建布局参数与 JEI 原实现逐字节对齐（`createRecipeLayoutDrawable(..., 4)`）
- 整段包 try/catch，失败就 `warn` 并回退原逻辑 —— 宁可慢，不能崩

## 代价

超过 128 条的分类改用注册顺序（不再「可合成优先」）。原因：那个比较器要拿
`IRecipeLayoutDrawable.getRecipeSlotsView()` 逐条比对，等于把全量布局又逼出来一次。

## 顺带更正

`HANDOFF.md` 里原来那条「JEI 已经是懒布局，别碰分页」是**错的**，已改写并标注更正。

---

# 1.33.0 · 分页懒布局做成开关 + JEI 版本自检

## 开关

设置弹窗（标题栏「设置」）第二项：**分页懒布局** ●/○，默认开，存 `config/jeicleaner/client-settings.json`
的 `lazyPages` 字段。

## 自检（自动适配 JEI 版本）

`LazyPages.probe()` 在第一次用到时跑一遍，全字符串 + 反射，检查四块：

| # | 检查项 | 失败原因文案 |
|---|---|---|
| ① | `RecipeGuiLogic` 有 4 参 `createRecipeLayoutsWithButtons` | 找不到方法（可能改签名或重构） |
| ② | `IRecipeLayoutList.subList(int,int)` | 缺少方法 |
| ③ | `IRecipeManager` 有 5 参 `createRecipeLayoutDrawable` | 没有带 IScalableDrawable 的重载 |
| ④ | `Internal.getTextures()` | 缺少方法 |

任一失败 → 开关**自动灰掉**、点击只弹状态栏原因、悬浮显示完整原因 + 当前 JEI 版本号。

## 运行时行为

Mixin 第一句：`if (!LazyPages.enabled()) return;` —— 关掉或不支持时完全走 JEI 原逻辑，
连阈值判断都不做，等于这个功能不存在。

## 已知局限

自检是「按接口形状猜」，无法确认 Mixin 是否真的注入成功（`require = 0` 会静默跳过）。
判断真实生效看日志：`[JEI Cleaner] N 条配方改用按页懒建布局`。这条 debug 日志需要开 debug 级别。

---

# 1.35.0 · 毒瘤分类视角 + 优化设置 + 同步卡顿修复

## 1. 毒瘤页两个视角

属性台顶部 `[物品][分类]`：

| 视角 | 数据源 | 能看到什么 |
|---|---|---|
| 物品 | `ItemStats`（遍历 `mc.level.getRecipeManager()`） | 只有真正的 MC Recipe（数据包 / 原版） |
| **分类** | `CategoryStats`（`createRecipeCategoryLookup().includeHidden()` + `createRecipeLookup(type).includeHidden().count()`） | **模组自建的 JEI 配方也算** — 奥术铁砧 4000+ 条终于出现了 |

分类视角点一行 = 加 `TYPE_CATEGORY` 规则（`hideRecipeCategory`，整类隐藏），
**不走服务端删除**，右栏规则列表里随时能撤销。

## 2. 优化设置独立成弹窗

标题栏 `[物品][模组][配方][毒瘤] …… [优化设置][设置]`。
「分页懒布局」从「设置」挪到「优化设置」，并保留 JEI 版本自检 + 灰掉 + 悬浮原因。

## 3. 同步卡顿：找到两个真凶

### ① 物品索引被白重建两遍（几秒级，已修）

`applyNow()` 原来每次都：

```java
restore(runtime);                              // addIngredientsAtRuntime(全部隐藏物品)
...
removeIngredientsAtRuntime(全部要隐藏的物品);    // 又移除一遍
```

这两个调用都会触发 JEI **重建物品列表 + 搜索后缀树**。改一次规则就重建两遍 → 卡几秒。

修法：
- `restore()` 拆成 `restoreRecipes()`（配方/自定义/分类，纯 Set 操作，很便宜）+ 物品还原（只在 JEI 卸载时用）
- 物品改成**差集**：`toHide = wanted - HIDDEN_ITEMS`、`toShow = HIDDEN_ITEMS - wanted`，
  两个都空就一个 API 都不调

### ② 全量配方扫描（待测）

`for (Recipe<?> recipe : recipeManager.getRecipes()) selector.matches(...)`，O(配方数 × 槽位)。

## 4. 分段耗时日志（INFO，默认可见）

```
[JEI Cleaner] 同步耗时 812ms = 还原 3 + 选择器 1 + 扫描 640 + 隐藏 5 + 物品 160 + 分类/收尾 3
```

以后卡顿直接看这一行。若「扫描」占大头，下一步就把扫描也做成增量/按规则类型短路。

## 1.36.0 · 扫描去抖 + 统计延迟

- **规则变更去抖 8 tick（0.4 秒）**：`ClientEvents` 里 revision 变化不再立刻 `requestApply()`，
  改成 `requestApplyDelayed(8)`。GUI 里连点几条规则只会全量扫一次；手动点「同步」仍是下一 tick 立即生效。
- **`ItemStats` 按需构建**：`loadItems()` 只在「按用量排序」时才 `build()`，
  新增 `ItemStats.ready()`，没算过时 `U:` 不显示（不再把 0 当成真实数据）。
  `ItemStats.build()` 现在还要跑一遍规则匹配（只统计可见配方），省下它是实打实的。

## 1.37.0 · 手动同步（默认）

- **默认不再自动同步**：改完规则只写文件 + 顶部显示 `未同步·点同步`，点底部「同步」才真正应用。
  好处：连续编辑时不会每改一条就全量扫描一次。
- 「优化设置」新增 **自动同步** 开关（默认关）；打开后恢复 1.36 的行为（改完延后 8 tick 自动重算）。
- 进度条只属于自定义配方的分帧任务；手动同步时如果不需要处理自定义配方，就不会出现进度条。

## 1.38.0 · 修两个界面 bug

### ① 标题栏「优化设置」和「改:客户端」重叠

1.37.0 里两个按钮都按 `settingsX` 往左排，坐标撞在一起（`优化设置` 后来居上盖住了 `改:…`）。

现在从右往左固定顺序 `设置 / 改:客户端 / 优化设置`，并且按宽度降级：
1. 放不下 → 「优化设置」缩成「优化」
2. 还放不下 → 标题栏不显示「改:…」，改到「设置」弹窗里（管理员专属行，点一下切客户端/服务端）

### ② 加规则再删掉，仍显示「未同步」

`hasPendingChanges()` 原来比的是 `Rules.revision()`（单调递增计数器），
改完又改回来 revision 已经变了，所以永远显示未同步。

改成比**内容指纹**：新增 `Rule.contentHash()` + `Rules.contentHash()`（`bump()` 时重算），
`Cleaner.appliedFingerprint` 记录上次应用时的内容。内容一样就不再提示。

`revision` 保留 —— 它仍然用于「规则可能变了」的缓存失效（`ItemStats` / `RuntimeFilter`）。

## 1.38.1 · 指令回执补「要 apply」

默认手动同步后，`/jeicleaner add*/remove/toggle/clear` 改完也不会立刻生效。
所有回执统一追加 `（/jeicleaner apply 生效）`，避免"加了但没反应"的误会。

顺带把 MC百科正文的指令段从 `[code]` 换成两列表格（Tab 分隔），
并把 `list / remove / toggle` 和 `export / clear / reload / apply` 这两行拆开逐条写作用——
原来那两行在百科里渲染成空单元格。
