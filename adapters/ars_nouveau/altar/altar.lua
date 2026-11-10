-- 新生魔艺（Ars Nouveau）/ 巫法祭坛 —— Lua 适配器模板
--
-- 怎么用（3 步）：
--   1. 进游戏，打开那个祭坛界面，按 F7 调出调试面板
--   2. 面板上会显示「菜单类名」和每条槽位的编号 → 把下面的 MENU_HINT 和 result 改成实际值
--   3. 把本文件丢进 config/foliocleaner/adapters/，在服务端执行 /folioserver adapters reload
--
-- 说明：本脚本只做**判断**，清空结果槽由引擎负责（result 里列的就是要清的槽位）。

name = "新生魔艺·祭坛"

-- ← 改成 F7 面板里看到的「结果槽」编号，可以有多个，例如 {2} 或 {0, 1}
result = {2}

-- ← 菜单类名里的一段（区分大小写）。用 F7 面板看实际类名后填。
--    留空则对所有界面生效（不建议）。
MENU_HINT = "ArsNouveau"

function blocks(ctx)
  if MENU_HINT ~= "" and not string.find(ctx:menu(), MENU_HINT, 1, true) then
    return false
  end

  -- 「删除」规则涉及哪些物品？
  local targets = ctx:removeTargets()
  if #targets == 0 then
    return false
  end

  -- 输入槽里只要有任意一个目标物品 → 拦这次合成
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
end
