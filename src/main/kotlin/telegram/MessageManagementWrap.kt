package org.example.telegram

import com.github.kotlintelegrambot.Bot
import com.github.kotlintelegrambot.entities.ChatId
import com.github.kotlintelegrambot.entities.InlineKeyboardMarkup
import com.github.kotlintelegrambot.entities.ParseMode
import com.github.kotlintelegrambot.entities.ReplyMarkup

fun Bot.sendMsg(
    chatId: Long,
    text: String,
    replyMarkup: ReplyMarkup? = null
): MessageKeyboardContext {
    val res = this.sendMessage(
        ChatId.fromId(chatId),
        text,
        parseMode = ParseMode.HTML,
        replyMarkup = replyMarkup
    )
    return MessageKeyboardContext(
        this,
        chatId,
        if (res.isSuccess) res.get().messageId else null
    )
}

sealed class MessageCallbackContext(
    val msgId: Long?
) {
    fun <T> then(action: (Long) -> T): T? {
        msgId?.let {
            return action.invoke(msgId)
        }
        return null
    }
}

class MessageKeyboardContext(
    private val bot: Bot,
    private val chatId: Long,
    msgId: Long?
) : MessageCallbackContext(msgId) {

    private fun replyMarkup(replyMarkup: (Long) -> InlineKeyboardMarkup): MessageCallbackContext {
        then { msgId ->
            bot.editMessageReplyMarkup(
                ChatId.fromId(chatId),
                msgId,
                replyMarkup = replyMarkup.invoke(msgId)
            )
        }
        return this
    }

    fun inlineKeyboard(definition: KeyboardContext.(Long) -> Unit): MessageCallbackContext {
        return replyMarkup { org.example.telegram.inlineKeyboard { definition(it) } }
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

class MessageUpdateContext(
    private val bot: Bot,
    private val chatId: Long,
    msgId: Long?
): MessageCallbackContext(msgId) {

    fun updateKeyboard(replyMarkup: (Long) -> InlineKeyboardMarkup): MessageCallbackContext {
        then { msgId ->
            bot.editMessageReplyMarkup(
                ChatId.fromId(chatId),
                msgId,
                replyMarkup = replyMarkup.invoke(msgId)
            )
        }
        return this
    }

}