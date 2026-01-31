function action(list)
  if list[1]:getTeam() == list[2]:getTeam() then
  	text = "Игроки в одной команде"
  else
    text = "Игроки в разных командах"
  end
  $INFO(text)
end

function choice()
    $PLAYERS():ALIVE():EXCLUDE_ACTORS():COMMIT()
end