selected = -1

function action(list)
  selected = $STORE(list[1])
  $HEAL(list[1])
end

function choice()
    if selected ~= -1 then
        $PLAYERS():ALIVE():EXCLUDE($STORED(selected)):COMMIT()
    end
end