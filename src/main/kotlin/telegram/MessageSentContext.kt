package org.example.telegram

import com.github.kotlintelegrambot.Bot
import com.github.kotlintelegrambot.entities.ChatId
import com.github.kotlintelegrambot.entities.KeyboardReplyMarkup
import com.github.kotlintelegrambot.entities.ReplyMarkup

data class MessageSentContext(
    private val bot: Bot,
    private val chatId: Long,
    val msgId: Long
)  {

    fun callback(action: (Long) -> Unit) {
        if (msgId > 0) {
            action.invoke(msgId)
        }
    }

    fun inlinekeyboard(definition: KeyboardContext.(Long) -> Unit): MessageMarkedUpContext {
        return replyMarkup { inlineKeyboard { definition(it) } }
    }

//    fun <T: Any> numpadkeyboard(
//        title: String,
//        numCommand: Command,
//        acceptCommand: Command?,
//        cancelCommand: Command,
//        target: T,
//        value: Int
//    ): MessageMarkedUpContext {
//        return replyMarkup { numpadKeyboard(title, numCommand, acceptCommand, cancelCommand, target, value, it) }
//    }

//    fun footerkeyboard(definition: FooterContext.() -> Unit): MessageMarkedUpContext {
//        return replyMarkup { footerKeyboard { definition() } }
//    }

    fun replyMarkup(replyMarkup: (Long) -> ReplyMarkup): MessageMarkedUpContext {
        callback {
            bot.editMessageReplyMarkup(
                ChatId.fromId(chatId),
                it,
                replyMarkup = replyMarkup.invoke(msgId)
            )
        }
        return MessageMarkedUpContext(msgId)
    }

}
