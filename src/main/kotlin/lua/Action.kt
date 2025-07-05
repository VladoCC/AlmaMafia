package org.example.lua

import org.example.Person

sealed class Action(val actors: List<Int>, val selection: List<Person>, val dependencies: Set<Action>) {
    var skippedBy: Action? = null
    val createdAt: Long = System.currentTimeMillis()
    val master: Boolean = false
    abstract fun desc(): String
    abstract fun events(): List<Event>
}

class InfoAction(val text: String, actors: List<Int>, targets: List<Person>, dependencies: Set<Action>)
    : Action(actors, targets, dependencies) {
    override fun desc(): String {
        return "Проверить"
    }

    override fun events(): List<Event> {
        return emptyList()
    }
}

sealed class TargetedAction(actors: List<Int>, targets: List<Person>, dependencies: Set<Action>)
    : Action(actors, targets, dependencies)

class KillAction(actors: List<Int>, targets: List<Person>, dependencies: Set<Action>)
    : TargetedAction(actors, targets, dependencies) {
    override fun desc(): String {
        return "Убить"
    }

    override fun events(): List<Event> {
        return selection.filterNotNull().map { KillEvent(it.pos, actors) }
    }
}

class HealAction(actors: List<Int>, targets: List<Person>, dependencies: Set<Action>)
    : TargetedAction(actors, targets, dependencies) {
    override fun desc(): String {
        return "Вылечить"
    }

    override fun events(): List<Event> {
        return emptyList()
    }
}

class BlockAction(actors: List<Int>, targets: List<Person>, dependencies: Set<Action>)
    : TargetedAction(actors, targets, dependencies) {
    override fun desc(): String {
        return "Заблокировать роль"
    }

    override fun events(): List<Event> {
        return emptyList()
    }
}

class SilenceAction(actors: List<Int>, targets: List<Person>, dependencies: Set<Action>)
    : TargetedAction(actors, targets, dependencies) {
    override fun desc(): String {
        return "Заблокировать обсуждение"
    }

    override fun events(): List<Event> {
        return selection.filterNotNull().map { SilenceEvent(it.pos, actors) }
    }
}

class CancelAction(val canceled: Action, actors: List<Int>, targets: List<Person>, dependencies: Set<Action>)
    : TargetedAction(actors, targets, dependencies) {
    override fun desc(): String {
        return "Отменить действие: ${canceled.desc()}"
    }

    override fun events(): List<Event> {
        return emptyList()
    }
}

data object NoneAction : Action(emptyList(), emptyList(), emptySet()) {
    override fun desc(): String {
        return "Действие не указано"
    }

    override fun events(): List<Event> {
        return emptyList()
    }
}