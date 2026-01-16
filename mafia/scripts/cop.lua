-- Actions:
-- 1 - kill
-- 2 - heal
-- 3 - block

don = false
maniac = false

function action(list)
  if list[1]:getTeam() == "city" or (list[1]:getRole() == "don" and don == false) or (list[1]:getRole() == "maniac" and maniac == false) then
  	text = "Светлая роль"
  else
    text = "Темная роль"
  end
  if list[1]:getRole() == "don" then
    don = true
  end
  if list[1]:getRole() == "maniac" then
    maniac = true
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