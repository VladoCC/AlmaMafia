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
  if flip(table) then
  	return "mafia"
  end
  return "city"
end

function type(table)
  if flip(table) then
    	return "mafia"
    end
    return "none"
end

function flip(table)
  for i=1,table.length do
    p = table[i]
    if p:getTeam() == "mafia" and not p:isAlive() then
    	return true
    end
  end
	return false
end