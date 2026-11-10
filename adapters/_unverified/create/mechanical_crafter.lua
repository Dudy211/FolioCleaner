-- Create（造物）机械合成器 Mechanical Crafter —— 未验证模板
-- 菜单类: ?（1.20.1 源码树 content/kinetics/crafter 包内无任何 Menu/Container 类；
--          若 F7 面板在打开它时不显示菜单类名，说明它根本没有 ContainerMenu，
--          本文件用不上，请删除并保留 README「做不了」条目）
-- 置信度: 低（纯模板，MENU_HINT 与 result 均为占位，未实测）
-- 与 RecipeManager 的关系: 机械合成器走注册配方类型（MechanicalCraftingRecipe extends
--   ShapedRecipe，经 AllRecipeTypes 注册），删配方即可覆盖——本模板只是双保险占位。
-- 为什么走模板而非 json: 槽位布局未确认，json 只能固定槽位，lua 可留待 F7 实测后再定。
--
-- 启用步骤（务必做完再启用）：
--   1. 进游戏打开机械合成器，按 F7 调出调试面板
--   2. 把 MENU_HINT 改成面板显示的菜单类简名（留空=对所有界面生效，危险，勿留空）
--   3. 把 result 改成面板显示的结果槽编号（机械合成器产出常直接进相邻存储，可能没有独立结果槽；
--      没有则本模板无意义，删除即可）
--   4. 平铺拷进 config/foliocleaner/adapters/，服务端 /folioserver adapters reload

name = "Create·机械合成器（未验证模板）"

MENU_HINT = ""        -- ← F7 面板看到的菜单类简名；留空 = 对所有界面生效（危险），启用前必须填
result = {0}          -- ← F7 面板看到的结果槽编号（可多个，如 {0, 1}）

function blocks(ctx)
  if MENU_HINT == "" then
    return false
  end
  if not string.find(ctx:menu(), MENU_HINT, 1, true) then
    return false
  end
  local ok, hit = pcall(function()
    local targets = ctx:removeTargets()
    if #targets == 0 then
      return false
    end
    for i = 0, ctx:size() - 1 do
      local id = ctx:slot(i)
      if id then
        for _, t in ipairs(targets) do
          if id == t then
            return true
          end
        end
      end
    end
    return false
  end)
  return ok and hit
end
