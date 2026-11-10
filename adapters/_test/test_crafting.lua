-- 临时测试用：原版工作台（CraftingMenu）
-- result = {0} —— 工作台 0 号槽就是结果槽
-- 逻辑：输入里只要出现「删除」规则的目标物品 → 拦
name = "测试·工作台(lua)"
result = {0}

function blocks(ctx)
  if not string.find(ctx:menu(), "CraftingMenu", 1, true) then
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
