cop = false

function action(list)
  if list[1]:getRole() == "Комиссар" then
    	text = "Стукач переметнулся"
    	cop = true
    else
      text = "Стукач не нашел комиссара"
    end
    $INFO(text)
end

function team(table)
  if cop then
  	return "city"
  end
  return "mafia"
end

function type(table)
  if cop then
    	$SET("snitch_civil")
        $SET("mafia")
    else
    $SET("snitch")
    $SET("mafia")
    end
end