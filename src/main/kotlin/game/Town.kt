package org.example.game

import org.example.*
import org.example.lua.*
import org.example.lua.Script

data class Town(
    val host: Long,
    val players: List<Person>,
    val order: List<String>,
    val types: Map<String, Type>,
    val scripts: Map<String, Script>,
    var mode: Mode = Mode.OPEN,
    var day: Int = 1
) {
    private val log = logger<Town>()

    val playerMap = players.associateBy { it.pos }
    val actions = mutableListOf<Result>()
    val night = mutableListOf<Wake>()
    var index = 0
    private val storage: MutableMap<Int, Any> = mutableMapOf()
    private var storageIndex = 1

    val selections = mutableSetOf<Int>()

    fun setAlive(pos: Int, alive: Boolean) {
        players.firstOrNull { it.pos == pos }?.let {
            it.alive = alive
            if (alive) {
                actions.removeIf { res -> res is KillResult && it in res.selection }
            } else {
                actions.add(KillResult(emptyList(), listOf(it)))
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
                val script = scripts[person.roleData.name]
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
            val script = scripts[it.roleData.name]
            val team = script?.team(players) ?: it.team
            it.team = team
        }
    }

    fun endNight() {
        try {
            players.forEach { it.protected = false }
            /*val blockedActors = mutableSetOf<Int>()
            var index = 0
            while (index < actions.size) {
                val it = actions[index]
                if (it.actors.toSet().intersect(blockedActors).isNotEmpty()) {
                    it.blocked = true
                    index++
                    continue
                }
                when (it) {
                    is BlockResult -> {
                        blockedActors.addAll(it.selection.filterNotNull().map { it.pos })
                        index++
                        continue
                    }
                    is CancelResult -> TODO()
                    is HealResult -> TODO()
                    is KillResult -> TODO()
                    is SilenceResult -> {
                        it.selection.filterNotNull().forEach {
                            playerMap[it.pos]?.protected = true
                        }
                    }
                    else -> {}
                }
                index++
            }*/

            val blocks = actions.filterIsInstance<BlockResult>()
            for (block in blocks) {
                val select = block.selection.filterNotNull()
                actions.removeIf { res ->
                    select.map { it.pos }.toSet().intersect(res.actors.toSet()).isNotEmpty()
                }
            }
            val cancels = actions.filterIsInstance<CancelResult>().map { it }
            for (cancel in cancels) {
                actions.remove(cancel.canceled)
            }
            val heals = actions.filterIsInstance<HealResult>().map { it }
            for (heal in heals) {
                val select = heal.selection.filterNotNull()
                actions.removeIf {
                    select.intersect(it.selection.toSet()).isNotEmpty() && it is KillResult
                }
            }
            val kills = actions.filterIsInstance<KillResult>()
            for (it in kills) {
                it.selection.filterNotNull().forEach {
                    playerMap[it.pos]?.alive = false
                }
            }
            val mutes = actions.filterIsInstance<SilenceResult>()
            for (it in mutes) {
                it.selection.filterNotNull().forEach {
                    playerMap[it.pos]?.protected = true
                }
            }
        } catch (e: Exception) {
            log.error("Unable to end night, town: $this", e)
        }
    }
}