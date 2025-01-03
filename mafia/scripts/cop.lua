-- Actions:
-- 1 - kill
-- 2 - heal
-- 3 - block

function action(list)
  if list[1]:getTeam() == "city" or list[1]:getRole() == "don" or list[1]:getRole() == "maniac" then
  	text = "Светлая роль"
  else
    text = "Темная роль"
  end
  return $INFO(text)
end

function passive(type)
  return $ALLOW()
end

function team(table)
  return "city"
end

function type(table)
  return "cop"
end