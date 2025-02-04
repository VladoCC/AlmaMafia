package org.example.game

import com.github.kotlintelegrambot.Bot
import com.github.kotlintelegrambot.entities.ChatId
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
                actions.add(KillAction(emptyList(), listOf(it), null))
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

    fun processActions() {
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
    }

    fun endNight() {
        try {
            players.forEach { it.protected = false }

            val blocks = actions.filterIsInstance<BlockAction>()
            for (block in blocks) {
                val select = block.selection
                actions.removeIf { res ->
                    select.map { it.pos }.toSet().intersect(res.actors.toSet()).isNotEmpty()
                }
            }
            val cancels = actions.filterIsInstance<CancelAction>().map { it }
            for (cancel in cancels) {
                actions.remove(cancel.canceled)
            }
            val heals = actions.filterIsInstance<HealAction>().map { it }
            for (heal in heals) {
                val select = heal.selection.filterNotNull()
                actions.removeIf {
                    select.intersect(it.selection.toSet()).isNotEmpty() && it is KillAction
                }
            }
            val kills = actions.filterIsInstance<KillAction>()
            for (it in kills) {
                it.selection.filterNotNull().forEach {
                    playerMap[it.pos]?.alive = false
                }
            }
            val mutes = actions.filterIsInstance<SilenceAction>()
            for (it in mutes) {
                it.selection.filterNotNull().forEach {
                    playerMap[it.pos]?.protected = true
                }
            }
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
        val fullLog = fullLog(this)
        endNight()
        if (fullLog.isNotBlank()) {
            bot.sendMessage(
                ChatId.fromId(chatId),
                "Все события:\n${fullLog}"
            )
        }

        bot.sendMessage(
            ChatId.fromId(chatId),
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

        bot.sendMessage(
            ChatId.fromId(chatId),
            "День ${towns[game.id]?.day}\n" +
                    "Вживых:\n" + teamSet.joinToString("\n") {
                it + ": " + mapAlive.getOrDefault(it, 0) + " / " + mapAll.getOrDefault(it, 0)
            }
        )

        // todo replace -1L with messageId
        showDayMenu(this, chatId, -1L, bot, game)
    }
}