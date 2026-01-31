function passive(action)
  if $IS_KILL(action) then
  	$CANCEL(action)
  end
end
