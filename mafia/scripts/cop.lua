-- Actions:
-- 1 - kill
-- 2 - heal
-- 3 - block

function action(list)
  if list[1]:getTeam() == "city" or list[1]:getRole() == "Дон" or list[1]:getRole() == "Маньяк" then
  	text = "Светлая роль"
  else
    text = "Темная роль"
  end
  return CONST:INFO(text)
end

function passive(type)
  return CONST:ALLOW()
end

function team(table)
  return "city"
end

function type(table)
  return "cop"
end