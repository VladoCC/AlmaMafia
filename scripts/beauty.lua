-- Actions:
-- 1 - kill
-- 2 - heal
-- 3 - block

function action(list)
  return CONST:SILENCE(list[1])
end

function passive(type)
  return CONST:ALLOW()
end

function team(table)
  return "city"
end

function type(table)
  return "beauty"
end