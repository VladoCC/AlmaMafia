-- Actions:
-- 1 - kill
-- 2 - heal
-- 3 - block

function action(list)
  return $NONE()
end

function passive(action)
  if $IS_KILL(action) then
  	return $CANCEL(action)
  end
  return $ALLOW()
end

function team(table)
  return "city"
end

function type(table)
  return "invincible"
end