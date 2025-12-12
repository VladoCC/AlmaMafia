function status(table)
    local all = 0
    local counts = {}
    local teams = {}
    local doctor = false
    local captain = false
    local alibi = false
    local invincible = false

    for i=1,table.length do
        p = table[i]
        t = p:getTeam()
        if p:isAlive() then
            c = counts[t]
            all = all + 1
            if c == nil then
                teams[#teams + 1] = t
                c = 0
            end
        	counts[t] = c + 1
        end

        if p:getRole() == "doctor" and p:isAlive() then
            doctor = true
        end
        if p:getRole() == "captain" and p:isAlive() then
            captain = true
        end
        if p:getRole() == "alibi" and p:isAlive() then
            alibi = true
        end
        if p:getRole() == "invincible" and p:isAlive() then
            invincible = true
        end
    end
    if #teams < 2 then
        -- print("Teams less than 2")
        return $WON(teams[1] or "city")
    end
    if #teams == 2 then
        city = counts["city"]
        if city == nil then
            min = all
            max = 0
            maxTeam = nil
            for i=1,#teams do
                t = teams[i]
                c = counts[t]
                if c < min then
                    min = c
                end
                if c > max then
                    max = c
                    maxTeam = t
                end
            end

            if min == max then
                -- print("Two teams tie " .. teams[1] .. " and " .. teams[2])
                return $TIE(teams)
            else
                -- print("Non-city team wins: " .. maxTeam)
                return $WON(maxTeam)
            end
        end
        if city * 2 > all then
            -- print("City majority")
            return $PLAY()
        end
        if city * 2 == all then
            if doctor or captain or alibi or invincible then
                -- print("City tie with special roles")
                return $PLAY()
            end
        end
        for i=1,#teams do
            t = teams[i]
            if t ~= "city" then
                -- print("Non-city team wins: " .. t)
                return $WON(t)
            end
        end
    end
    -- print("No win conditions met")
    return $PLAY()
end