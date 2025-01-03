-- Actions:
-- 1 - kill
-- 2 - heal
-- 3 - block

maniac = false
found = false

function action(list)
    if maniac then
        return $KILL(list[1])
    end
        if list[1]:getRole() == "Маньяк" then
        	text = "Подражатель нашел маньяка"
        	found = true
        else
          text = "Подражатель не нашел маньяка"
        end
        return $INFO(text)
end

function passive(type)
  return $ALLOW()
end

function team(table)
  if flip(table) then
  	return "maniac"
  end
  return "city"
end

function type(table)
  if flip(table) then
    	return "maniac"
    end
    return "copycat"
end

function flip(table)
  for i=1,table.length do
    p = table[i]
    if p:getTeam() == "maniac" and not p:isAlive() and found then
    	return true
    end
  end
	return false
end