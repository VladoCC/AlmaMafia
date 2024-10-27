-- Actions:
-- 1 - kill
-- 2 - heal
-- 3 - block

function action(list)
  if list[1]:getTeam() == list[2]:getTeam() then
  	text = "Игроки в одной команде"
  else
    text = "Игроки в разных командах"
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
  return "journalist"
end