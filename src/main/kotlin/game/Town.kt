package org.example.game

import com.github.kotlintelegrambot.Bot
import org.example.*
import org.example.lua.*
import org.example.telegram.showDayMenu

data class Town(
    val gameId: GameId,
    val players: List<Person>,
    val order: List<String>,
    val types: Map<String, Type>,
    var mode: Mode = Mode.OPEN,
    var day: Int = 1
) {
    private val log = logger<Town>()

    val playerMap = players.associateBy { it.pos }
    val actions = mutableListOf<Action>()
    val night = mutableListOf<Wake>()
    var index = 0
    private val storage: MutableMap<Int, Any> = mutableMapOf()
    private var storageIndex = 1

    val selections = mutableSetOf<Int>()

    fun setAlive(pos: Int, alive: Boolean) {
        players.firstOrNull { it.pos == pos }?.let {
            it.alive = alive
            if (alive) {
                actions.removeIf { res -> res is KillAction && it in res.selection }
            } else {
                actions.add(KillAction(emptyList(), listOf(it), emptySet()))
            }
        }
    }

    fun changeProtected(pos: Int) {
        playerMap[pos]?.protected = !(playerMap[pos]?.protected ?: true)
    }

    fun store(value: Any): Int {
        storage[storageIndex] = value
        return storageIndex++
    }

    fun get(key: Int) = storage[key]

    fun rollback() {
        if (index == 0) {
            return
        }
        index--
        night[index].let { wake ->
            val actors = wake.players.map { it.pos }.toSet()
            actions.removeIf { res -> res.actors.any { it in actors } }
        }
    }

    fun prepNight() {
        actions.clear()
        selections.clear()
        val map = mutableMapOf<String, MutableList<Person>>()
        for (person in players) {
            if (person.roleData.scripted) {
                val script = scripts[gameId]?.get(person.roleData.name)
                script?.type(players) ?: "none"
            } else {
                "none"
            }.split(",").forEach {
                map.getOrPut(it.trim()) { mutableListOf() }.add(person)
            }
        }
        night.clear()
        index = 0
        for (type in order) {
            if ((map[type]?.size ?: 0) > 0 && (mode == Mode.CLOSED || (map[type]?.filter { it.alive }?.size
                    ?: 0) > 0)
            ) {
                night.add(Wake(types[type]!!, map[type]?.sortedBy { it.roleData.priority }?.reversed() ?: emptyList()))
            }
        }
    }

    fun updateTeams() {
        players.forEach {
            val script = scripts[gameId]?.get(it.roleData.name)
            val team = script?.team(players) ?: it.team
            it.team = team
        }
    }

    /*fun processActions() {
        val blockedActors = mutableSetOf<Int>()
        var actionCount = actions.size
        val grouped = actions.groupBy { it::class }.mapValues { it.value.toMutableList() }.toMutableMap()
        val dependants = mutableMapOf<Action, MutableList<Action>>()
        fun isActive(action: Action): Boolean {
            return !action.blocked && blockedActors.intersect(action.actors.toSet()).isEmpty()
        }
        fun cancel(action: Action) {
            action.blocked = true
            dependants[action]?.forEach {
                cancel(it)
            }
        }
        fun processReturn(ret: Return, dependency: Action) {
            ret.actions.forEach { action ->
                // if cancel action then block target actions and their dependants
                // otherwise add action to queue, mark its dependency
                // since all actons in a single return are from the same lua call and thus have the same dependency,
                // if this dependency is blocked, terminate processing immediately
                if (dependency.blocked) {
                    return
                }
                if (action is CancelAction) {
                    cancel(action.canceled)
                } else {
                    grouped.getOrDefault(action::class, mutableListOf()).add(action)
                    actionCount++
                    dependants.getOrDefault(dependency, mutableListOf()).add(dependency)
                }
            }
        }
        fun processPassives(action: Action) {
            action.selection.forEach { person ->
                scripts[gameId]?.get(person.roleData.name)
                    ?.passive(
                        action,
                        LuaInterface(listOf(person.pos), action.actors.mapNotNull { playerMap[it] }, this, action)
                    ) { ret ->
                        processReturn(ret, action)
                    }
            }
        }

        while (actionCount > 0) {
            actionCount--

            val block = grouped[BlockAction::class]?.firstOrNull()
            if (block != null) {
                grouped[BlockAction::class]?.remove(block)
                if (!isActive(block)) {
                    continue
                }

                processPassives(block)
                if (block.blocked) {
                    continue
                }
                val select = block.selection.map { it.pos }.toSet()
                select.forEach {
                    blockedActors.add(it)
                }
                actions.filter { select.intersect(it.actors.toSet()).isNotEmpty() }.forEach { it.blocked = true }
                continue
            }

            val heal = grouped[HealAction::class]?.firstOrNull()
            if (heal != null) {
                grouped[HealAction::class]?.remove(heal)
                if (!isActive(heal)) {
                    continue
                }

                processPassives(heal)
                if (heal.blocked) {
                    continue
                }
                val select = heal.selection.map { it.pos }.toSet()
                
                continue
            }
        }
    }*/

    fun processActions() {
        val grouped = actions.groupBy { it::class }.mapValues { it.value.toMutableList() }
        var blockers = mutableSetOf<Action>()
        val blocked = mutableSetOf<Action>()
        val dependencies = mutableMapOf<Action, MutableSet<Action>>()
        val dependants = mutableMapOf<Action, MutableSet<Action>>()
        grouped[CancelAction::class]?.forEach {
            if (it !is CancelAction) {
                return@forEach
            }
            blockers.add(it)
            blocked.add(it.canceled)
            dependencies.getOrPut(it.canceled) { mutableSetOf() }.add(it)
            dependants[it] = mutableSetOf(it.canceled)
        }

        grouped[BlockAction::class]?.forEach { action ->
            blockers.add(action)
            val select = action.selection.map { it.pos }.toSet()
            dependants[action] = mutableSetOf()
            actions.filter { it.actors.toSet().intersect(select).isNotEmpty() }.forEach {
                blocked.add(it)
                dependencies.getOrPut(it) { mutableSetOf() }.add(action)
                dependants[action]!!.add(it)
            }
        }

        grouped[HealAction::class]?.forEach { action ->
            blockers.add(action)
            val select = action.selection.map { it.pos }.toSet()
            dependants[action] = mutableSetOf()
            actions.filter { it is KillAction && it.selection.all { person -> person.pos in select } }.forEach {
                blocked.add(it)
                dependencies.getOrPut(it) { mutableSetOf() }.add(action)
                dependants[action]!!.add(it)
            }
        }
        blockers = blockers.sortedBy { it.createdAt }.toMutableSet()

        // processing actions that do not depend on probable blocked actions
        fun handleFreeBlocks() {
            val processed = mutableSetOf<Action>()
            while (true) {
                processed.clear()

                blockers.forEach { blocker ->
                    if (blocker !in blocked && blocker.dependencies.intersect(blocked).isEmpty()) {
                        processed.add(blocker)

                        dependants[blocker]?.forEach { action ->
                            dependencies[action]?.remove(blocker)
                            if (dependencies[action]?.size == 0) {
                                dependencies.remove(action)
                                blocked.remove(action)
                            }
                        }

                        if (blocker.skippedBy != null || blocker.dependencies.any { it.skippedBy != null }) {
                            dependants.remove(blocker)
                            return@forEach
                        }

                        dependants[blocker]?.forEach { action ->
                            action.skippedBy = blocker
                            dependencies.remove(action)
                            blocked.remove(action)
                        }
                        dependants.remove(blocker)
                    }
                }

                if (processed.isEmpty()) {
                    break
                } else {
                    blockers.removeAll(processed)
                }
            }
        }
        handleFreeBlocks()

        /*if (blockers.isNotEmpty()) {
            // strategy 1: if cancel only depends on block, cancel has priority
            var success = false
            blockers.filter { it is CancelAction && it.canceled is BlockAction }.forEach outer@{ action ->
                val blockedBy: BlockAction = (action as CancelAction).canceled as BlockAction
                val list = dependencies[action]
                if ((list?.size?: 0) != 1) {
                    return@outer
                }
                if (list?.first() != blockedBy) {
                    return@outer
                }
                action.dependencies.forEach { dep ->
                    dependencies[dep]?.forEach {
                        if (it !is BlockAction) {
                            return@outer
                        }
                        if (blockedBy != it) {
                            return@outer
                        }
                        blockedBy = it
                    }
                }

                success = true
                dependants[blockedBy]?.forEach { dep ->
                    dependencies[dep]?.remove(blockedBy)
                    if (dependencies[dep]?.size == 0) {
                        dependencies.remove(dep)
                        blocked.remove(dep)
                    }
                }
                dependants.remove(blockedBy)
            }

            if (success) {
                handleFreeBlocks()
            }
        }*/

        if (blockers.isNotEmpty()) {
            // strategy 1: block has priority over any other action
            val processed = blockers.filter { it is BlockAction }.map {
                dependants[it]?.forEach { dep ->
                    dep.skippedBy = it
                    dependencies.remove(dep)
                    blocked.remove(dep)
                }
                dependants.remove(it)
                it
            }
            blockers.removeAll(processed.toSet())

            if (processed.isNotEmpty()) {
                handleFreeBlocks()
            }
        }

        if (blockers.isNotEmpty()) {
            // strategy 2: cancel has second priority
            val processed = blockers.filter { it is CancelAction }.map {
                dependants[it]?.forEach { dep ->
                    dep.skippedBy = it
                    dependencies.remove(dep)
                    blocked.remove(dep)
                }
                dependants.remove(it)
                it
            }
            blockers.removeAll(processed.toSet())

            if (processed.isNotEmpty()) {
                handleFreeBlocks()
            }
        }

        if (blockers.isNotEmpty()) {
            // strategy 3: process everything else in creation order
            while (blockers.isNotEmpty()) {
                val current = blockers.first()
                dependants[current]?.forEach { dep ->
                    dep.skippedBy = current
                    dependencies.remove(dep)
                    blocked.remove(dep)
                }
                dependants.remove(current)
                blockers.remove(current)
            }
        }

        grouped[KillAction::class]?.filter { it.skippedBy == null }?.forEach { action ->
            action.selection.forEach {
                playerMap[it.pos]?.alive = false
            }
        }

        grouped[SilenceAction::class]?.filter { it.skippedBy == null }?.forEach { action ->
            action.selection.forEach {
                playerMap[it.pos]?.protected = true
            }
        }
    }

    fun endNight() {
        try {
            players.forEach { it.protected = false }

            processActions()
        } catch (e: Exception) {
            log.error("Unable to end night, town: $this", e)
        }
    }

    fun startDay(
        chatId: Long,
        bot: Bot,
        game: Game
    ) {
        day++
        endNight()
        val fullLog = fullLog(this)
        if (fullLog.isNotBlank()) {
            bot.sendmessage(
                chatId,
                "Все события:\n${fullLog}"
            )
        }

        bot.sendmessage(
            chatId,
            "Результат ночи:\n" + shortLog(this).ifBlank { "Не произошло никаких событий" }
        )
        actions.clear()

        val mapAll = mutableMapOf<String, Int>()
        val mapAlive = mutableMapOf<String, Int>()
        val teamSet = mutableSetOf("all")
        for (player in players) {
            teamSet.add(player.team)

            mapAll.getOrPut("all") { 0 }.let {
                mapAll["all"] = it + 1
            }
            mapAll.getOrPut(player.team) { 0 }.let {
                mapAll[player.team] = it + 1
            }

            if (player.alive) {
                mapAlive.getOrPut("all") { 0 }.let {
                    mapAlive["all"] = it + 1
                }
                mapAlive.getOrPut(player.team) { 0 }.let {
                    mapAlive[player.team] = it + 1
                }
            }
        }
        
        bot.sendmessage(
            chatId,
            "День ${towns[game.id]?.day}\n" +
                    "Вживых:\n" + teamSet.joinToString("\n") {
                it + ": " + mapAlive.getOrDefault(it, 0) + " / " + mapAll.getOrDefault(it, 0)
            }
        )

        // todo replace -1L with messageId
        showDayMenu(this, chatId, -1L, bot, game)
    }
}