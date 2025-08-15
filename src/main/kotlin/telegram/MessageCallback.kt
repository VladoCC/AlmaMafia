package org.example.telegram

import com.github.kotlintelegrambot.Bot
import com.github.kotlintelegrambot.entities.ChatId
import com.github.kotlintelegrambot.entities.InlineKeyboardMarkup

sealed class MessageCallbackContext(
    val msgId: Long
) {
    fun then(action: (Long) -> Unit) {
        if (msgId > 0) {
            action.invoke(msgId)
        }
    }
}

class MessageKeyboardContext(
    private val bot: Bot,
    private val chatId: Long,
    msgId: Long
) : MessageCallbackContext(msgId) {

    fun inlineKeyboard(definition: KeyboardContext.(Long) -> Unit): MessageCallbackContext {
        return replyMarkup { org.example.telegram.inlineKeyboard { definition(it) } }
    }

    private fun replyMarkup(replyMarkup: (Long) -> InlineKeyboardMarkup): MessageCallbackContext {
        then {
            bot.editMessageReplyMarkup(
                ChatId.fromId(chatId),
                it,
                replyMarkup = replyMarkup.invoke(msgId)
            )
        }
        return this
    }

    fun <T: Any> numpadKeyboard(
        title: String,
        numCommand: Command,
        acceptCommand: Command?,
        cancelCommand: Command,
        target: T,
        value: Int
    ): MessageCallbackContext {
        return replyMarkup { numpadKeyboard(title, numCommand, acceptCommand, cancelCommand, target, value, it) }
    }

}