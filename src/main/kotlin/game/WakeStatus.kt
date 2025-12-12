package org.example.game

sealed interface WakeStatus {
    companion object {
        fun none() = NoneStatus
        fun action() = ActionStatus
        fun skipped() = SkippedStatus
        fun woke(result: String) = WokeStatus(result)
    }

    fun desc(): String
    fun result(): String
}

object NoneStatus: WakeStatus {
    override fun desc(): String = "💤 Не просыпались"
    override fun result(): String = ""
}

object ActionStatus: WakeStatus {
    override fun desc(): String = "⏳ Ожидание"
    override fun result(): String = ""
}

object SkippedStatus: WakeStatus {
    override fun desc(): String = "🙈 Пропущено"
    override fun result(): String = "Действие пропущено."
}

class WokeStatus(private val result: String): WakeStatus {
    override fun desc(): String = "✅ Выполнено"
    override fun result(): String = "Действие выполнено.\n\nРезультат:\n$result"
}