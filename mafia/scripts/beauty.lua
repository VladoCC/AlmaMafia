function action(list)
  $SILENCE(list[1])
end

function choice()
    $PLAYERS():ALIVE():EXCLUDE_ACTORS():COMMIT()
end