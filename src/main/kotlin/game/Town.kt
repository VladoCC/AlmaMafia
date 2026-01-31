package org.example.game

import com.github.kotlintelegrambot.Bot
import com.github.kotlintelegrambot.entities.ChatId
import com.github.kotlintelegrambot.entities.ParseMode
import org.example.*
import org.example.lua.*
import org.example.telegram.showDayMenu

data class Town(
    val gameId: GameId,
    val players: List<Person>,
    val types: Map<String, Type>,
    var mode: Mode = Mode.OPEN,
    var day: Int = 1
) {
    private val log = logger<Town>()

    val playerMap = players.associateBy { it.pos }
    val actions = mutableListOf<Action>()
    val night = mutableListOf<Wake>()
    var index = 0

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

    fun rollback() {
        if (index == 0) {
            return
        }
        index--
        night[index].let { wake ->
            val actors = wake.players.map { it.pos }.toSet()
            actions.removeIf { res -> res.actors.any { it.pos in actors } }
        }
    }

    fun startDusk() {
        scripts[gameId]?.forEach {
            it.value.dusk()
        }
    }

    fun startDawn() {
        scripts[gameId]?.forEach {
            it.value.dawn()
        }
        updateTeams()
    }

    fun prepNight() {
        actions.clear()
        selections.clear()
        val map = mutableMapOf<Type, MutableList<Person>>()
        for (person in players) {
            val script = scripts[gameId]?.get(person.types.firstOrNull())
            person.types = script?.type(players).let {
                if (it == null || it.isEmpty()) {
                    person.types
                } else {
                    it
                }
            }
            person.types.forEach {
                if (it in types && types[it]?.passive == false) {
                    map.getOrPut(types[it]!!) { mutableListOf() }.add(person)
                }
            }
        }
        night.clear()
        index = 0
        for (type in map.keys.sortedBy { it.order }) {
            if (mode == Mode.CLOSED || (map[type]?.filter { it.alive }?.size ?: 0) > 0) {
                night.add(
                    Wake(
                        night.size,
                        type,
                        map[type]?.sortedBy { it.roleData.priority }?.reversed() ?: emptyList()
                    ).also {
                        it.choices = playerSelectionForWake(it)
                    }
                )
            }
        }
    }

    fun updateTeams() {
        players.forEach {
            val script = scripts[gameId]?.get(it.types.firstOrNull())
            val team = script?.team(players) ?: it.team
            it.team = team
        }
    }

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
            val select = action.selection.toSet()
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
                    val free = blocker !in blocked && blocker.dependencies.intersect(blocked).isEmpty()
                    val skipped = blocker.skippedBy != null || blocker.dependencies.any { it.skippedBy != null }
                    if (free || skipped) {
                        processed.add(blocker)

                        dependants[blocker]?.forEach { action ->
                            dependencies[action]?.remove(blocker)
                            if (dependencies[action]?.isEmpty() == true) {
                                dependencies.remove(action)
                                blocked.remove(action)
                            }
                        }

                        if (skipped) {
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
                    if (it.skippedBy == null) {
                        dep.skippedBy = it
                    }
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
                    if (current.skippedBy == null) {
                        dep.skippedBy = current
                    }
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
        val hideRoles = game.host?.settings?.hideRolesMode ?: false
        val fullLog = fullLog(this, hideRoles)
        endNight()
        val chat = ChatId.fromId(chatId)
        if (fullLog.isNotBlank()) {
            bot.sendMessage(
                chat,
                "Все события:\n${fullLog}",
                parseMode = ParseMode.HTML
            )
        }

        bot.sendMessage(
            chat,
            "Результат ночи:\n" + shortLog(this, !hideRoles).ifBlank { "Не произошло никаких событий" }
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

        val teamNames = teamNames.find { gameId == game.id }.associate { it.team to it.name }

        fun teamName(team: String): String {
            return teamNames.getOrDefault(team, team)
        }
        val teamCounts = teamSet.joinToString("\n") {
            teamNames.getOrDefault(it, it) +
                    ": " + mapAlive.getOrDefault(it, 0) +
                    " / " + mapAll.getOrDefault(it, 0)
        }
        val hide = game.host?.settings?.hideRolesMode ?: false
        bot.sendMessage(
            chat,
            "День ${towns[game.id]?.day}\n" +
                    "Вживых:\n" +
                    (if (hide) "<span class=\"tg-spoiler\">" else "") +
                    teamCounts +
                    (if (hide) "</span>" else ""),
            parseMode = ParseMode.HTML
        )

        if (scripts[gameId]?.containsKey(statusScriptName) == true) {
            try {
                val script = scripts[gameId]?.get(statusScriptName)
                val status = script?.status(players)
                if (status != null) {
                    if (status is WonState) {
                        bot.sendMessage(
                            chat,
                            "<b>‼️ Игра окончена!\n" +
                                    "🏆 Победила команда: ${teamName(status.team)}</b>",
                            parseMode = ParseMode.HTML
                        )
                    } else if (status is TieState) {
                        bot.sendMessage(
                            chat,
                            "<b>‼️ Игра окончена!\n" +
                                    "🤝 Ничья между командами:\n${status.teams.joinToString("\n") { "- " + teamName(it) }}</b>",
                            parseMode = ParseMode.HTML
                        )
                    }
                }
            } catch (e: Exception) {
                log.error("Unable to call status script for town: $this", e)
            }
        }
        showDayMenu(this, chatId, -1L, bot, game)
    }

    internal fun playerSelectionForWake(wake: Wake): List<Choice> {
        val players = players.sortedBy { it.pos }
        val script = scripts[gameId]?.get(wake.type.name)
        val default = { players.filter { it.alive }.map { PersonChoice(it) } }
        if (script != null) {
            return script.choice(
                players,
                wake.players
            ).ifEmpty {
                default()
            }
        } else {
            log.error("Script not found for game ${gameId} role ${wake.type.name}")
            return default()
        }
    }
}