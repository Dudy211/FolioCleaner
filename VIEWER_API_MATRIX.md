# REI / EMI 隐藏 API 对照表（1.20.1 · 实测 javap）

> 数据来源：本地拆包 `javap -p`
> - REI：`RoughlyEnoughItems-12.1.785-forge.jar`
> - EMI：`emi-1.1.24+1.20.1+forge.jar`（包名是 `dev.emi.emi`）
>
> atom 跑了一轮（26 分钟、32 次工具调用），因为 provider_error 中断没写文件；
> 下面这份是**我自己逐个 javap 复核过**的结论。

## 总表

| 能力 | JEI（已实现，作对照） | REI 12.1.785 | EMI 1.1.24 |
|---|---|---|---|
| 隐藏单个物品 | `IIngredientManager#removeIngredientsAtRuntime(IIngredientType, Collection)` | ✅ `EntryRegistry#removeEntry(EntryStack<?>)` / `removeEntryIf(Predicate)` | ✅ 直接写 `EmiHidden.disabledStacks`（`Set<EmiIngredient>`，public static） |
| 隐藏单条配方 | `IRecipeManager#hideRecipes(RecipeType, Collection)`（**实时**） | ✅ `DisplayRegistry#registerVisibilityPredicate(DisplayVisibilityPredicate)`，predicate 每次渲染评估 → **实时** | ✅ `EmiRegistry#removeRecipes(Predicate<EmiRecipe>)` + `removeRecipes(ResourceLocation)` —— **仅插件初始化期**生效 |
| 隐藏整个分类 | `hideRecipeCategory(RecipeType)` | ✅ `CategoryVisibilityPredicate` | ⚠️ 通过 filter 或数据文件 |
| 是否需要重启/重载 | 否，实时 | 否，实时（`removeEntry` 当场生效） | 否，实时（写 set 即可，`EmiHidden.reload()` 刷新） |
| 持久化 | 无（我们自己的 json） | 配置里的 FilteringRule | ✅ `EmiHidden#save() → JsonArray` / `load(JsonArray)` |

## 关键类（含出处）

### REI

```
me/shedaniel/rei/api/client/registry/entry/EntryRegistry.class
  boolean removeEntry(EntryStack<?>)
  boolean removeEntryIf(Predicate<? extends EntryStack<?>>)
  boolean removeEntryExactHashIf(LongPredicate)
  boolean removeEntryFuzzyHashIf(LongPredicate)
  void addEntries(Collection<? extends EntryStack<?>>)

me/shedaniel/rei/api/client/registry/display/visibility/DisplayVisibilityPredicate.class
  EventResult handleDisplay(DisplayCategory<?>, Display)
  double getPriority()          ← 多条规则按优先级排序

me/shedaniel/rei/api/client/registry/category/visibility/CategoryVisibilityPredicate.class
me/shedaniel/rei/api/client/entry/filtering/FilteringRule.class  ← 配置驱动的过滤
```

另外 REI 支持 `c:hidden_from_recipe_viewers` 这个 tag（数据包级隐藏，跨 viewer 通用）。

### EMI

```
dev/emi/emi/runtime/EmiHidden.class
  public static Set<EmiIngredient> disabledStacks
  public static List<IndexStackData$Filter> disabledFilters
  public static Map<String, Boolean> disabledFilterLookup
  public static Set<EmiIngredient> pluginDisabledStacks
  public static List<Predicate<EmiStack>> pluginDisabledFilters
  public static Set<EmiIngredient> hiddenStacks
  static void reload()
  static JsonArray save() / void load(JsonArray)
  static boolean isHidden(EmiIngredient)
```

`dev/emi/emi/api/EmiApi` 里**没有** disable/hide 方法 —— 只能写上面这些字段（它们是 public static）。

EMI 另有 datapack 形式：`RecipeDefaultLoader`（`disabled` / `added` / `resolutions` / `tags` 字段）。

## 结论

1. **三家都能实时隐藏物品**，不需要重启。入口各不相同，但都在公开 API 里，不需要 mixin。
2. **配方级隐藏**：JEI ✅ 实时 / REI ✅ 实时 / **EMI ✅ 但只在插件初始化期**
   （`EmiRegistry#removeRecipes` 是给 `EmiPlugin` 用的注册期 API；运行期 `EmiApi` 只有 getter）。

   ★ 更正（2026-11-09）：本文件早先写成「EMI 没有配方级 API」是**错的** —— 当时只查了
   `EmiApi` / `EmiHidden`，漏了 `dev.emi.emi.api.EmiRegistry#removeRecipes(Predicate<EmiRecipe>)`。
3. ~~所以「一套规则 → 三家生效」的短板是 EMI 的配方隐藏~~ → **已更正**：短板只剩「EMI 的配方隐藏是初始化期一次性的」，
   功能上做得到（`EmiRegistry#removeRecipes`），只是改完规则要重开游戏。
4. 共同的坑：三个 API 都在**客户端**，且都要在 JEI/REI/EMI 初始化完成后调用（我们现在的 `ClientEvents` 时机够用）。
5. `c:hidden_from_recipe_viewers` 这个 tag 三家都认 —— **如果能用 tag 表达隐藏，就天然跨 viewer**，
   比逐个调 API 更省事（但只能覆盖"整个物品"，不能覆盖"某条配方"）。

## 建议

- 物品级隐藏：优先走 `c:hidden_from_recipe_viewers` tag（一次写，三家生效）
- 配方级隐藏：JEI / REI 各接一个适配器（都支持实时）；EMI 用 `EmiPlugin` 初始化时 remove，
  规则改动后提示「需要重开游戏 / 让 EMI 重载」
- 全部走「能力探测 + 灰掉 + 悬浮原因」那套（已有 `LazyPages` 的雏形）

---

# 实现状态（2026-11-09 · 2.0.0-test8 已落地）

| 层 | 实现 | 生效时机 |
|---|---|---|
| 物品级（三家通吃） | 数据包 `data/c/tags/items/hidden_from_recipe_viewers.json`（`DatapackGen.writeHiddenTag`） | 生成数据包 + `/reload` |
| 配方级 · JEI | 原有 `Selector` + `hideRecipes`（能按原料/产物/范围/分类匹配） | 实时 |
| 配方级 · REI | `viewer/ReiRecipeHider`：反射拿 `DisplayRegistry.getInstance()`，用 `Proxy` 实现 `DisplayVisibilityPredicate`，返回 `EventResult.pass()` / `interruptFalse()` | **实时**（每次渲染都会问） |
| 配方级 · EMI | `viewer/EmiRecipeHiderPlugin`（`@EmiEntrypoint` + `EmiRegistry#removeRecipes`），编译期依赖 `compile_only_depend/emi-*.jar` | **只在一开始**，改规则要重开游戏 |

两个实现细节值得记住：

1. **REI 只能走反射**：`DisplayVisibilityPredicate#handleDisplay` 返回 `dev.architectury.event.EventResult`，
   而 architectury 的 jar 本地没有、REI 自己也没打包它。所以用 `java.lang.reflect.Proxy` 实现接口 +
   反射调 `EventResult.pass()` / `interruptFalse()`（REI 自己的 `FilteredStacksVisibilityHandler`
   用的就是这两个返回值，字节码核对过）。副作用：拿不到编译期检查，全靠 `install()` 里的 try/catch 兜底。
2. **EMI 必须编译期接入**：`removeRecipes` 只给 `EmiPlugin#register(EmiRegistry)` 用，运行期 `EmiApi`
   全是 getter。`@EmiEntrypoint` 是 **CLASS retention**（字节码里是 `RuntimeInvisibleAnnotations`），
   EMI 用 ASM 扫得到；没装 EMI 时这个类永远不会被加载。

**匹配口径**（`HiddenRecipes#hides`）：只有 `recipe`（id 全等）、`mod`（命名空间全等）、
`keep`（白名单外全藏，和 `Selector` 同语义）三类会作用到 REI/EMI。
`item` / `category` 规则**故意不猜** —— 它们要看槽位或 RecipeType，靠配方 id 反推会误伤。
