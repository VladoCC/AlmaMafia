-- Actions:
-- 1 - kill
-- 2 - heal
-- 3 - block

function action(list)
  return $KILL(list[1])
end

function passive(type)
  return $ALLOW()
end

function team(table)
  return "yakuza"
end

function type(table)
  return "yakuza"
end