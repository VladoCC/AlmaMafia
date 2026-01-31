package org.example.lua

import org.example.Person

sealed class Event(
    val pos: Int,
    val actors: List<Person>
) {
    abstract fun desc(): String
    abstract fun symbol(): String
}

class KillEvent(
    pos: Int,
    actors: List<Person>
) : Event(pos, actors) {
    override fun desc(): String {
        return "убит"
    }

    override fun symbol(): String {
        return "💀"
    }
}

class SilenceEvent(
    pos: Int,
    actors: List<Person>
) : Event(pos, actors) {
    override fun desc(): String {
        return "не принимает участие в обсуждении"
    }

    override fun symbol(): String {
        return "💋"
    }
}