package org.example.telegram

import com.github.kotlintelegrambot.Bot
import com.github.kotlintelegrambot.entities.ChatId
import org.example.*
import org.example.game.Town
import org.example.game.desc
import org.example.game.nightRoleDesc
import org.example.game.playerDayDesc

internal fun showAdMenu(chat: ChatId.Id, bot: Bot) {
    val active = games.find().sortedBy { it.createdAt }.reversed()
    val recent = gameHistory.find().sortedBy { it.playedAt }.reversed()
    val res = bot.sendMessage(
        chat,
        if (active.isNotEmpty() || recent.isNotEmpty()) "Доступные игры:" else "Нет доступных игр"
    )
    if (res.isSuccess) {
        val msgId = res.get().messageId
        bot.editMessageReplyMarkup(
            chat,
            msgId,
            replyMarkup = inlineKeyboard {
                if (active.isNotEmpty()) {
                    button(blankCommand named "Активные")
                }

                active.forEach {
                    button(sendAdCommand named it.name(), it.id, msgId)
                }
                if (recent.isNotEmpty()) {
                    button(blankCommand named "Недавние")
                }
                recent.forEach {
                    button(sendAdHistoryCommand named it.name(), it.id, msgId)
                }
                button(deleteMsgCommand, res.get().messageId)
            }
        )
    }
}

internal fun showSettingsMenu(it: HostSettings, chatId: Long, messageId: Long, gameMessageId: Long, bot: Bot, desc: String = "") {
    val text = "Настройки" +
            if (desc.isNotBlank()) "\n\nОписание:\n$desc" else ""
    val msgId = if (messageId == -1L) {
        val res = bot.sendMessage(
            ChatId.fromId(chatId),
            text,
            replyMarkup = inlineKeyboard { button(blankCommand named "Загрузка...") }
        )
        if (res.isSuccess) {
            res.get().messageId
        } else {
            messageId
        }
    } else {
        messageId
    }

    bot.editMessageText(
        ChatId.fromId(chatId),
        msgId,
        text = text,
        replyMarkup = inlineKeyboard {
            /*fun setting(command: Command, state: Boolean) {
                row {
                    button(command, messageId, gameMessageId)
                    button(command named (if (state) "✅" else "❌"), msgId, gameMessageId)
                }
            }
            setting(fallModeCommand, it.fallMode)
            setting(detailedViewCommand, it.detailedView)
            setting(doubleColumnNightCommand, it.doubleColumnNight)
            setting(confirmNightSelectionCommand, it.confirmNightSelection)
            setting(timerSettingCommand, it.timer)
            setting(hidePlayersSettingCommand, it.hideDayPlayers)*/
            HostOptions.entries.forEach { entry ->
                row {
                    button(settingDescCommand named entry.shortName, msgId, gameMessageId, entry.name)
                    button(hostSettingCommand named (if (entry.current(it)) "✅" else "❌"), msgId, gameMessageId, entry.name)
                }
            }
            button(deleteMsgCommand named "Закрыть", msgId)
        }
    )
}

internal fun showLobbyMenu(
    chatId: Long,
    messageId: Long,
    game: Game,
    bot: Bot,
    forceUpdate: Boolean = false
): Long {
    val id = ChatId.fromId(chatId)
    var msgId = messageId
    if (forceUpdate || msgId == -1L) {
        val res = bot.sendMessage(
            id,
            text = "Меню ведущего:"
        )
        if (res.isSuccess) {
            msgId = res.get().messageId
            accounts.update(chatId) {
                menuMessageId = msgId
            }
        }
    }
    bot.editMessageReplyMarkup(
        id,
        msgId,
        replyMarkup = lobby(msgId, game)
    )
    return msgId
}

internal fun showPlayerMenu(
    chatId: Long,
    messageId: Long,
    bot: Bot,
    connectionId: ConnectionId,
    value: Int = 0
): Long {
    val chat = ChatId.fromId(chatId)
    val msgId = if (messageId == -1L) {
        bot.sendMessage(
            chat,
            "Меню игрока:"
        ).get().messageId
    } else {
        messageId
    }
    bot.editMessageReplyMarkup(
        chat,
        msgId,
        replyMarkup = numpadKeyboard(
            "Номер игрока",
            playerNumCommand,
            playerConfirmCommand,
            mainMenuCommand,
            connectionId,
            value,
            msgId
        )
    )
    accounts.update(chatId) {
        menuMessageId = msgId
    }
    return msgId
}

internal fun showRevealMenu(game: Game, bot: Bot, chatId: Long, messageId: Long) {
    bot.editMessageReplyMarkup(
        ChatId.fromId(chatId),
        messageId,
        replyMarkup = inlineKeyboard {
            button(blankCommand named "Статус игроков")
            val cons = pairings.find { gameId == game.id }.sortedBy { it.connection?.pos ?: -1 }
            val notified = cons.count { it.connection?.notified ?: false }
            reordered(cons).chunked(2).forEach { list ->
                val leftCon = list[0].connection
                val rightCon = if (list.size < 2) null else list[1].connection
                row {
                    fun conRow(connection: Connection?) =
                        if (connection != null) {
                            button(blankCommand named "${connection.pos}. ${connection.name()}")
                            val textLeft = if (connection.notified) "🫡" else "🌚"
                            if (connection.bot) {
                                button(markBotCommand named textLeft, connection.id, messageId)
                            } else {
                                button(blankCommand named textLeft)
                            }
                        } else {
                            button(blankCommand)
                            button(blankCommand)
                        }

                    conRow(leftCon)
                    conRow(rightCon)
                }
                if (!getHideRolesMode(game, chatId)) {
                    row {
                        val leftName = list[0].role?.displayName
                        button(if (leftName != null) blankCommand named leftName else blankCommand)
                        val rightName = if (list.size < 2) null else list[1].role?.displayName
                        button(if (rightName != null) blankCommand named rightName else blankCommand)
                    }
                }
            }

            button(blankCommand named "Ознакомлены: $notified / ${cons.size}")
            button(proceedCommand, messageId)
        }
    )
}

internal fun showAdminListMenu(chatId: Long, messageId: Long, bot: Bot) {
    bot.editMessageReplyMarkup(
        ChatId.fromId(chatId),
        messageId,
        replyMarkup = inlineKeyboard {
            button(blankCommand named "Список администраторов")
            admins.find().forEach {
                accounts.get(it.chatId)?.let { acc ->
                    row {
                        button(blankCommand named acc.fullName())
                        button(removeAdminCommand, it.chatId, messageId)
                    }
                }
            }
            button(adminBackCommand, messageId)
        }
    )
}

internal fun showGameStatusMenu(chatId: Long, messageId: Long, bot: Bot) {
    bot.editMessageReplyMarkup(
        ChatId.fromId(chatId),
        messageId,
        replyMarkup = inlineKeyboard {
            button(blankCommand named "Активные игры")
            games.find().forEach {
                button(blankCommand named it.name())
                button(terminateGameCommand, it.id, messageId)
            }
            button(adminBackCommand, messageId)
        }
    )
}

internal fun showHostAdminSettingsMenu(chatId: Long, messageId: Long, bot: Bot) {
    bot.editMessageReplyMarkup(
        ChatId.fromId(chatId),
        messageId,
        replyMarkup = inlineKeyboard {
            button(blankCommand named "Ведущие")
            hostSettings.find().forEach {
                button(chooseHostAdminCommand named (it.host?.fullName()?: ""), messageId, it.hostId)
            }
            button(adminBackCommand, messageId)
        }
    )
}

internal fun showChosenSettingsMenu(chatId: Long, messageId: Long, bot: Bot, chosenId: Long) {
    hostSettings.get(chosenId)?.let { settings ->
        bot.editMessageReplyMarkup(
            ChatId.fromId(chatId),
            messageId,
            replyMarkup = inlineKeyboard {
                button(blankCommand named "Настройки ${accounts.get(chosenId)?.fullName()?: ""}")
                HostOptions.entries.forEach { entry ->
                    row {
                        button(changeHostAdminSettingCommand named entry.shortName, messageId, chosenId, entry.name)
                        button(changeHostAdminSettingCommand named (if (entry.current(settings)) "✅" else "❌"), messageId, chosenId, entry.name)
                    }
                }
                button(adminBackCommand, messageId)
            }
        )
        return
    }
}

internal fun showKickMenu(game: Game, messageId: Long, bot: Bot, chatId: Long) {
    bot.editMessageReplyMarkup(
        ChatId.fromId(chatId),
        messageId,
        replyMarkup = inlineKeyboard {
            button(blankCommand named "Исключенные игроки")
            kicks.find { gameId == game.id }.forEach {
                accounts.get(it.player)?.let { acc ->
                    button(blankCommand named acc.fullName())
                    button(unkickCommand, it.id, messageId)
                }
            }
            button(hostBackCommand, messageId)
        }
    )
}

internal fun showNightRoleMenu(
    town: Town,
    chatId: Long,
    bot: Bot,
    messageId: Long
) {
    val chat = ChatId.fromId(chatId)
    val msgId = if (messageId == -1L) {
        bot.sendMessage(
            chat,
            "Меню ночи:"
        ).get().messageId
    } else {
        messageId
    }
    val wake = if (town.night.size > town.index) town.night[town.index] else null
    if (wake == null) {
        bot.editMessageText(
            chat,
            msgId,
            text = "Ночь завершена",
            replyMarkup = inlineKeyboard {
                button(dayCommand, msgId)
            }
        )
        return
    }
    val text = nightRoleDesc(wake)
    bot.editMessageText(
        chat,
        msgId,
        text = text,
        replyMarkup = inlineKeyboard {
            if (wake.players.none { it.alive }) {
                row {
                    if (town.actions.isNotEmpty()) {
                        button(cancelActionCommand, msgId)
                    }
                    button(skipRoleCommand, msgId)
                }
            } else {
                val players = town.players.filter { it.alive }.sortedBy { it.pos }
                val actor = wake.actor()
                val settings = accounts.get(chatId)?.settings
                fun KeyboardContext.RowContext.selectButton(it: Person) {
                    button(
                        selectCommand named ((if (it.pos in town.selections) "✅ " else "") + desc(it)),
                        it.pos,
                        msgId,
                        actor?.roleData?.id ?: ""
                    )
                }
                if (settings == null || settings.doubleColumnNight) {
                    reordered(players).chunked(2).forEach { list ->
                        row {
                            list.forEach {
                                selectButton(it)
                            }
                            if (list.size == 1) {
                                button(blankCommand)
                            }
                        }
                    }
                } else {
                    players.forEach {
                        row {
                            selectButton(it)
                        }
                    }
                }
                row {
                    if (town.actions.isNotEmpty()) {
                        button(cancelActionCommand, msgId)
                    }
                    if (town.selections.isEmpty()) {
                        button(skipRoleCommand, msgId)
                    } else if (settings?.confirmNightSelection == true && town.selections.size == wake.type.choice) {
                        button(
                            executeActionCommand,
                            msgId,
                            actor?.roleData?.id ?: ""
                        )
                    }
                }
            }
        }
    )
}

internal fun showDayMenu(
    town: Town,
    chatId: Long,
    messageId: Long,
    bot: Bot,
    game: Game
) {
    withAccount(chatId) { acc ->
        val settings = game.host?.settings
        val view = settings?.dayView ?: DayView.ALL
        val fallMode = settings?.fallMode ?: false

        val msgId = if (acc.menuMessageId == -1L) {
            val res = bot.sendMessage(
                ChatId.fromId(chatId),
                "Меню дня:",
            )
            if (res.isSuccess) {
                val msg = res.get().messageId
                accounts.update(chatId) {
                    menuMessageId = msg
                }
                msg
            } else {
                -1L
            }
        } else {
            acc.menuMessageId
        }

        val keyboard = inlineKeyboard {
            if (settings?.hideDayPlayers == true) {
                button(
                    hidePlayersCommand named (if (settings.playersHidden) "👓 Показать игроков" else hidePlayersCommand.name),
                    msgId
                )
            }
            val hideRolesMode = shouldHideRoles(game, chatId)
            if (settings?.playersHidden != true) {
                row { button(filterCommand named "Фильтр: ${view.desc}", msgId) }
                for (player in town.players.sortedBy { it.pos }) {
                    if (view.filter(player)) {
                        row {
                            button(
                                (if (settings?.detailedView == true) blankCommand else dayDetailsCommand) named desc(
                                    player,
                                    hideRolesMode = hideRolesMode
                                ),
                                player.pos,
                                msgId
                            )
                        }
                        if (settings?.detailedView == true) {
                            row {
                                playerDayDesc(player, msgId, fallMode)
                            }
                        }
                    }
                }
            }
            button(settingsCommand, msgId)
            if (settings?.timer == true) {
                button(timerCommand)
            }
            if (hideRolesMode) {
                button(startRevealingRolesCommand, msgId)
            } else {
                button(nightCommand, msgId)
            }
        }
        bot.editMessageReplyMarkup(
            ChatId.fromId(chatId),
            msgId,
            replyMarkup = keyboard
        )
    }
}


internal fun showAliveMenu(
    game: Game,
    con: Connection,
    bot: Bot,
    messageId: Long,
    roleId: RoleId
) {
    val desc = if (game.state == GameState.Reveal) {
        val cons = game.connectionList
        val count = cons.size
        "Вживых: $count / $count\n\n" +
                "Игроки:\n" + cons.sortedBy { it.pos }.joinToString("\n") {
            "№" + it.pos + " " + it.name()
        }
    } else if (game.state == GameState.Game) {
        val town = towns[game.id]
        if (town == null) {
            ""
        } else {
            val all = town.players
            val alive = all.filter { it.alive }.sortedBy { it.pos }
            "Вживых: ${alive.size} / ${all.size}\n\n" +
                    "Игроки:\n" + alive.joinToString("\n") {
                "№" + it.pos + " " + it.name
            }
        }
    } else {
        ""
    }
    val chat = ChatId.fromId(con.playerId)
    bot.editMessageText(
        chat,
        messageId,
        text = desc,
        replyMarkup = inlineKeyboard {
            button(revealRoleCommand, roleId, messageId)
            button(gameInfoCommand, roleId, messageId)
        }
    )
    messageLinks.updateMany({
        this.messageId == messageId
                && chatId == con.playerId
                && gameId == game.id
    }) {
        type = LinkType.ALIVE
    }
}