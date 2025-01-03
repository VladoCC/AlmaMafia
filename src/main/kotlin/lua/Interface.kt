package org.example.lua

import org.example.Person
import org.example.game.Town

sealed class Action(
    val pos: Int,
    val actors: List<Int>
) {
    // todo remove or use
    var blocked = false
    abstract fun desc(): String
    abstract fun symbol(): String
}

class KillAction(
    pos: Int,
    actors: List<Int>
) : Action(pos, actors) {
    override fun desc(): String {
        return "убит"
    }

    override fun symbol(): String {
        return "💀"
    }
}

class SilenceAction(
    pos: Int,
    actors: List<Int>
) : Action(pos, actors) {
    override fun desc(): String {
        return "не принимает участие в обсуждении"
    }

    override fun symbol(): String {
        return "💋"
    }
}

class LuaInterface(val actors: List<Int>, val selection: List<Person?>, val town: Town) {
    fun NONE() = NoneResult
    fun INFO(result: String) = Return(InfoResult(result, actors, selection))
    fun KILL() = KILL(selection)
    fun KILL(select: Person?) = KILL(listOf(select))
    fun KILL(select: List<Person?>) = Return(KillResult(actors, select))
    fun HEAL() = HEAL(selection)
    fun HEAL(select: Person?) = HEAL(listOf(select))
    fun HEAL(select: List<Person?>) = Return(HealResult(actors, select))
    fun BLOCK() = BLOCK(selection)
    fun BLOCK(select: Person?) = BLOCK(listOf(select))
    fun BLOCK(select: List<Person?>) = Return(BlockResult(actors, select))
    fun SILENCE() = SILENCE(selection)
    fun SILENCE(select: Person?) = SILENCE(listOf(select))
    fun SILENCE(select: List<Person?>) = Return(SilenceResult(actors, select))

    fun ALLOW() = Return(NoneResult)
    fun CANCEL(blocked: Result) = Return(CancelResult(blocked, actors, blocked.actors.map { town.playerMap[it] }))
    fun CANCEL(blocked: Return) = Return(
        blocked.results.map {
            CancelResult(it, actors, it.actors.map { index -> town.playerMap[index] })
        }
    )

    fun STORE(value: Any) = town.store(value)
    fun STORED(key: Int) = town.get(key)

    fun IS_INFO(result: Result) = result is InfoResult
    fun IS_KILL(result: Result) = result is KillResult
    fun IS_HEAL(result: Result) = result is HealResult
    fun IS_BLOCK(result: Result) = result is BlockResult
    fun IS_SILENCE(result: Result) = result is SilenceResult

    fun GET_ACTORS() = actors.map { town.playerMap[it] }
    fun TWO(ret1: Return, ret2: Return) = Return(ret1.results + ret2.results)
    fun THREE(ret1: Return, ret2: Return, ret3: Return) = TWO(TWO(ret1, ret2), ret3)
}

data class Return(val results: List<Result>) {
    constructor(result: Result) : this(listOf(result))
}

sealed class Result(val actors: List<Int>, val selection: List<Person?>) {
    var blocked = false
    abstract fun desc(): String
    abstract fun actions(): List<Action>
}

class InfoResult(val text: String, actors: List<Int>, selection: List<Person?>) : Result(actors, selection) {
    override fun desc(): String {
        return "Проверить"
    }

    override fun actions(): List<Action> {
        return emptyList()
    }
}

sealed class TargetedResult(actors: List<Int>, selection: List<Person?>) : Result(actors, selection)
class KillResult(actors: List<Int>, selection: List<Person?>) : TargetedResult(actors, selection) {
    override fun desc(): String {
        return "Убить"
    }

    override fun actions(): List<Action> {
        return selection.filterNotNull().map { KillAction(it.pos, actors) }
    }
}

class HealResult(actors: List<Int>, selection: List<Person?>) : TargetedResult(actors, selection) {
    override fun desc(): String {
        return "Вылечить"
    }

    override fun actions(): List<Action> {
        return emptyList()
    }
}

class BlockResult(actors: List<Int>, selection: List<Person?>) : TargetedResult(actors, selection) {
    override fun desc(): String {
        return "Заблокировать роль"
    }

    override fun actions(): List<Action> {
        return emptyList()
    }
}

class SilenceResult(actors: List<Int>, selection: List<Person?>) : TargetedResult(actors, selection) {
    override fun desc(): String {
        return "Заблокировать обсуждение"
    }

    override fun actions(): List<Action> {
        return selection.filterNotNull().map { SilenceAction(it.pos, actors) }
    }
}

class CancelResult(val canceled: Result, actors: List<Int>, selection: List<Person?>) :
    TargetedResult(actors, selection) {
    override fun desc(): String {
        return "Отменить действие: ${canceled.desc()}"
    }

    override fun actions(): List<Action> {
        return emptyList()
    }
}

data object NoneResult : Result(emptyList(), emptyList()) {
    override fun desc(): String {
        return "Действие не указано"
    }

    override fun actions(): List<Action> {
        return emptyList()
    }
}