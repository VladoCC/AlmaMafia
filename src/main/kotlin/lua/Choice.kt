package org.example.lua

import org.example.Person

interface Choice {
    val id: Int
    fun text(view: ChoiceView): String
}

class SimpleChoice(override val id: Int, private val text: String) : Choice {
    override fun text(view: ChoiceView) = text
}

class PersonChoice(val person: Person) : Choice {
    override val id: Int = person.pos
    override fun text(view: ChoiceView) = person.desc(icons = false, roles = view == ChoiceView.HOST)
}

enum class ChoiceView {
    HOST, PLAYER
}
