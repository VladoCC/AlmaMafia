package org.example.lua

import com.google.devtools.ksp.processing.Dependencies
import org.example.Person
import org.example.game.Town

class LuaInterface(val actors: List<Int>, val selection: List<Person>, val town: Town, val dependency: Action? = null) {
    fun NONE() = NoneAction
    fun INFO(result: String) = Return(InfoAction(result, actors, selection, dependency))
    fun KILL() = KILL(selection)
    fun KILL(select: Person?) = KILL(listOf(select))
    fun KILL(select: List<Person?>) = Return(KillAction(actors, select.filterNotNull(), dependency))
    fun HEAL() = HEAL(selection)
    fun HEAL(select: Person?) = HEAL(listOf(select))
    fun HEAL(select: List<Person?>) = Return(HealAction(actors, select.filterNotNull(), dependency))
    fun BLOCK() = BLOCK(selection)
    fun BLOCK(select: Person?) = BLOCK(listOf(select))
    fun BLOCK(select: List<Person?>) = Return(BlockAction(actors, select.filterNotNull(), dependency))
    fun SILENCE() = SILENCE(selection)
    fun SILENCE(select: Person?) = SILENCE(listOf(select))
    fun SILENCE(select: List<Person?>) = Return(SilenceAction(actors, select.filterNotNull(), dependency))

    fun ALLOW() = Return(NoneAction)
    fun CANCEL(blocked: Action) = Return(CancelAction(blocked, actors, blocked.actors.map { town.playerMap[it] }.filterNotNull(), dependency))
    fun CANCEL(blocked: Return) = Return(
        blocked.actions.map {
            CancelAction(it, actors, it.actors.map { index -> town.playerMap[index] }.filterNotNull(), dependency)
        }
    )

    fun STORE(value: Any) = town.store(value)
    fun STORED(key: Int) = town.get(key)

    fun IS_INFO(action: Action) = action is InfoAction
    fun IS_KILL(action: Action) = action is KillAction
    fun IS_HEAL(action: Action) = action is HealAction
    fun IS_BLOCK(action: Action) = action is BlockAction
    fun IS_SILENCE(action: Action) = action is SilenceAction

    fun GET_ACTORS() = actors.map { town.playerMap[it] }
    fun TWO(ret1: Return, ret2: Return) = Return(ret1.actions + ret2.actions)
    fun THREE(ret1: Return, ret2: Return, ret3: Return) = TWO(TWO(ret1, ret2), ret3)
}

data class Return(val actions: List<Action>) {
    constructor(action: Action) : this(listOf(action))
}