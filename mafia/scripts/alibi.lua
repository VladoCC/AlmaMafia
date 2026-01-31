selected = -1
actionId = -1

function action(list)
  selected = $STORE(list[1])
  actionId = $STORE($HEAL(list[1]))
end

function dusk()
    selected = -1
    actionId = -1
end

function passive(type)
  if $IS_KILL(type) then
        $KILL($STORED(selected))
        $CANCEL($STORED(actionId))
    end
end

function choice()
    $PLAYERS():ALIVE():EXCLUDE_ACTORS():COMMIT()
end