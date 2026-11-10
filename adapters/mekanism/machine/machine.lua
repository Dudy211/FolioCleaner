-- 通用机械（Mekanism）机器 —— Lua 适配器模板
--
-- 用法同 ars_nouveau/altar/altar.lua：
--   F7 看「菜单类名」和槽位编号 → 改下面的 MENU_HINT / result → 丢进适配器目录 → /folioserver adapters reload
--
-- 注意：Mekanism 的机器大多是**方块实体逻辑**，不一定有容器界面。
--   只有在「打开界面 + 结果槽」存在的情况下，这个适配器才有意义；
--   如果 F7 面板打不开（说明不是容器界面），那就得走 jar 适配器（需要 mixin）。

name = "通用机械·机器"

result = {2}                 -- ← 改成 F7 看到的结果槽编号

MENU_HINT = "Mekanism"

function blocks(ctx)
  if MENU_HINT ~= "" and not string.find(ctx:menu(), MENU_HINT, 1, true) then
    return false
  end

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
end
