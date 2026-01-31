package org.example.lua

import org.example.Person
import org.luaj.vm2.LuaValue
import org.luaj.vm2.lib.jse.CoerceJavaToLua

class ActionInterface(
    private val script: Script,
    private val actors: List<Person>,
    private val selection: List<Person>,
    private val actions: MutableList<Action>,
    private val dependencies: Set<Action> = emptySet()
) : MemoryStore by script {
    fun INFO(result: String) = InfoAction(result, actors, selection, dependencies).also { actions.add(it) }

    fun KILL(select: Person?) = KILL(listOf(select))
    fun KILL(select: List<Person?>) = KillAction(actors, select.filterNotNull(), dependencies).also { actions.add(it) }

    fun HEAL(select: Person?) = HEAL(listOf(select))
    fun HEAL(select: List<Person?>) = HealAction(actors, select.filterNotNull(), dependencies).also { actions.add(it) }

    fun BLOCK(select: Person?) = BLOCK(listOf(select))
    fun BLOCK(select: List<Person?>) =
        BlockAction(actors, select.filterNotNull(), dependencies).also { actions.add(it) }

    fun SILENCE(select: Person?) = SILENCE(listOf(select))
    fun SILENCE(select: List<Person?>) =
        SilenceAction(actors, select.filterNotNull(), dependencies).also { actions.add(it) }

    fun CANCEL(blocked: Action) =
        CancelAction(
            blocked,
            actors,
            blocked.actors,
            dependencies
        ).also { actions.add(it) }

    fun STORE(value: Any) = store(value)
    fun STORED(key: Int) = stored(key)

    fun IS_INFO(action: Action) = action is InfoAction
    fun IS_KILL(action: Action) = action is KillAction
    fun IS_HEAL(action: Action) = action is HealAction
    fun IS_BLOCK(action: Action) = action is BlockAction
    fun IS_SILENCE(action: Action) = action is SilenceAction

    fun GET_ACTORS() = actors
}

class ChoiceInterface(
    private val script: Script,
    private val players: List<Person>,
    private val actors: List<Person>,
    private val choices: MutableList<Choice>
) : MemoryStore by script {
    fun TEXT(text: String, id: Int) = SimpleChoice(id, text).also { choices.add(it) }
    fun PERSON(person: Person) = PersonChoice(person).also { choices.add(it) }

    fun PLAYERS() = PlayerDescriptor(players, this)

    fun STORE(value: Any) = store(value)
    fun STORED(key: Int) = stored(key)

    class PlayerDescriptor(private val players: List<Person>, private val scope: ChoiceInterface) {
        fun ALIVE() = PlayerDescriptor(players.filter { it.alive }, scope)
        fun EXCLUDE(person: Person) = PlayerDescriptor(players.filter { it.pos != person.pos }, scope)
        fun EXCLUDE_ACTORS() = PlayerDescriptor(players.filter { it !in scope.actors }, scope)
        fun FILTER(predicate: LuaValue) = PlayerDescriptor(
            if (predicate.isfunction()) {
                players.filter { predicate.call(CoerceJavaToLua.coerce(it)).toboolean() }
            } else {
                players
            },
            scope
        )
        fun COMMIT() {
            players.forEach { scope.PERSON(it) }
        }
    }
}

class StatusInterface {
    fun PLAY() = PlayState
    fun WON(team: String) = WonState(team)
    fun TIE(vararg teams: String) = TieState(teams.toList())
}

class TypeInterface(private val types: MutableList<String>) {
    fun SET(type: String) {
        if (type !in types) {
            types.add(type)
        }
    }
}

interface MemoryStore {
    fun store(value: Any): Int
    fun stored(key: Int): Any?
}