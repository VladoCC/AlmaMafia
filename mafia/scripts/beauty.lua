-- Actions:
-- 1 - kill
-- 2 - heal
-- 3 - block

function action(list)
  return $SILENCE(list[1])
end

function passive(type)
  return $ALLOW()
end

function team(table)
  return "city"
end

function type(table)
  return "beauty"
end