-- Actions:
-- 1 - kill
-- 2 - heal
-- 3 - block

function action(list)
  return CONST:NONE()
end

function passive(action)
  if CONST:IS_KILL(action) then
  	return CONST:CANCEL(action)
  end
  return CONST:ALLOW()
end

function team(table)
  return "city"
end

function type(table)
  return "invincible"
end