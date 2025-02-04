package org.example.lua

sealed class Event(
    val pos: Int,
    val actors: List<Int>
) {
    abstract fun desc(): String
    abstract fun symbol(): String
}

class KillEvent(
    pos: Int,
    actors: List<Int>
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
    actors: List<Int>
) : Event(pos, actors) {
    override fun desc(): String {
        return "не принимает участие в обсуждении"
    }

    override fun symbol(): String {
        return "💋"
    }
}