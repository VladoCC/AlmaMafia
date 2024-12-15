-- Actions:
-- 1 - kill
-- 2 - heal
-- 3 - block

selected = -1
actionId = -1

function action(list)
  selected = CONST:STORE(list[1])
  res = CONST:HEAL(list[1])
  actionId = CONST:STORE(res)
  return res
end

function passive(type)
  if CONST:IS_KILL(type) then
        kill = CONST:KILL(CONST:STORED(selected))
        cancel = CONST:CANCEL(CONST:STORED(actionId))
        return CONST:TWO(kill, cancel)
    end
  if CONST:IS_HEAL(type) then
      return CONST:HEAL(CONST:STORED(selected))
  end
    return CONST:ALLOW()
end

function team(table)
  return "city"
end

function type(table)
  return "alibi"
end