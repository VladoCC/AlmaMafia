-- Actions:
-- 1 - kill
-- 2 - heal
-- 3 - block

function action(list)
  return CONST:KILL(list[1])
end

function passive(type)
  return CONST:ALLOW()
end

function team(table)
  return "yakuza"
end

function type(table)
  return "yakuza"
end