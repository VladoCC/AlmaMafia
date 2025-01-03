-- Actions:
-- 1 - kill
-- 2 - heal
-- 3 - block

function action(list)
  return $INFO(list[1]:getRole())
end

function passive(type)
  return $ALLOW()
end

function team(table)
  return "city"
end

function type(table)
  return "hacker"
end