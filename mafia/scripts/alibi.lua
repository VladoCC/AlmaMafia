-- Actions:
-- 1 - kill
-- 2 - heal
-- 3 - block

selected = -1
actionId = -1

function action(list)
  selected = $STORE(list[1])
  res = $HEAL(list[1])
  actionId = $STORE(res)
  return res
end

function passive(type)
  if $IS_KILL(type) then
        kill = $KILL($STORED(selected))
        cancel = $CANCEL($STORED(actionId))
        return $TWO(kill, cancel)
    end
  if $IS_HEAL(type) then
      return $HEAL($STORED(selected))
  end
    return $ALLOW()
end

function team(table)
  return "city"
end

function type(table)
  return "alibi"
end