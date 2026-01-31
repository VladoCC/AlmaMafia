found = false

function action(list)
    if list[1]:getRole() == "Маньяк" then
        	text = "Подражатель нашел маньяка"
        	found = true
        else
          text = "Подражатель не нашел маньяка"
        end
        $INFO(text)
end

function team(table)
  if flip(table) then
  	return "maniac"
  end
  return "city"
end

function type(table)
  if flip(table) then
    	SET("maniac")
    end
end

function flip(table)
    alive = true
  for i=1,table.length do
    p = table[i]
    if p:getTeam() == "maniac" then
    	alive = p:isAlive()
    	break
    end
  end
	return not alive and found
end