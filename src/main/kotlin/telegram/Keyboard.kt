package org.example.telegram

import org.example.blankCommand

internal fun <T : Any> numpadKeyboard(
    title: String,
    numCommand: Command,
    acceptCommand: Command?,
    cancelCommand: Command,
    target: T,
    value: Int,
    messageId: Long
) =
    inlineKeyboard {
        row { button(blankCommand named title) }
        fun KeyboardContext.RowContext.digitButton(it: Int) = button(
            numCommand named it.toString(),
            target,
            if (value == 0) it else value * 10 + it,
            messageId
        )

        val text = if (value == 0) "Не указано" else value.toString()
        row {
            button(blankCommand)
            button(blankCommand named text)
            button(
                numCommand named "⌫",
                target,
                if (value.toString().length > 1) value.toString().dropLast(1) else "0",
                messageId
            )
        }
        (1..9).chunked(3).forEach {
            row {
                it.forEach {
                    digitButton(it)
                }
            }
        }
        row {
            button(blankCommand)
            digitButton(0)
            button(blankCommand)
        }
        row {
            button(cancelCommand, messageId)
            if (value != 0 && acceptCommand != null) {
                button(acceptCommand, target, value, messageId)
            }
        }
    }
