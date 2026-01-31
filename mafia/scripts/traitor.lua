function type(table)
  if flip(table) then
    	$SET("mafia")
    end
end

function team(table)
  if flip(table) then
  	return "mafia"
  end
  return "city"
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