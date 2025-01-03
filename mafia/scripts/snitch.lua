-- Actions:
-- 1 - kill
-- 2 - heal
-- 3 - block

cop = false

function action(list)
  if list[1]:getRole() == "Комиссар" then
    	text = "Стукач переметнулся"
    	cop = true
    else
      text = "Стукач не нашел комиссара"
    end
    return $INFO(text)
end

function passive(type)
  return $ALLOW()
end

function team(table)
  if cop then
  	return "city"
  end
  return "mafia"
end

function type(table)
  if cop then
    	return "mafia"
    end
    return "mafia,snitch"
end