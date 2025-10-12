package org.example.telegram

import com.github.kotlintelegrambot.Bot
import com.github.kotlintelegrambot.entities.ChatId
import org.example.*
import org.example.game.Town
import org.example.game.desc
import org.example.game.nightRoleDesc
import org.example.game.playerDayDesc

internal fun showAdMenu(bot: Bot, chat: ChatId.Id) {
    val chatId = chat.id
    val active = games.find().sortedBy { it.createdAt }.reversed()
    val recent = gameHistory.find().sortedBy { it.playedAt }.reversed()
    bot.sendMsg(
        chatId,
        if (active.isNotEmpty() || recent.isNotEmpty()) "Доступные игры:" else "Нет доступных игр"
    ).inlineKeyboard { msgId ->
        if (active.isNotEmpty()) {
            button(blankCommand named "Активные")
        }
        active.forEach {
            button(sendAdCommand named it.name(), it.id, msgId)
        }
        if (recent.isNotEmpty()) {
            button(blankCommand named "Недавние")
        }
        recent.subList(0, defaultPageSize.coerceAtMost(recent.size)).forEach {
            button(sendAdHistoryCommand named it.name(), it.id, msgId)
        }
        button(deleteMsgCommand, msgId)
    }
}

internal fun showSettingsMenu(
    hostSettings: HostSettings,
    chatId: Long,
    messageId: Long,
    gameMessageId: Long,
    bot: Bot,
    desc: String = ""
) {
    val msgId = if (messageId == -1L) {
        bot.sendMsg(
            chatId,
            "Настройки" +
                if (desc.isNotBlank()) "\n\nОписание:\n$desc" else ""
        ).msgId
    } else {
        messageId
    }
    if (msgId != null) {
        bot.editMessageReplyMarkup(
            ChatId.fromId(chatId),
            msgId,
            replyMarkup = inlineKeyboard {
                HostOptions.entries.forEach { entry ->
                    row {
                        button(settingDescCommand named entry.shortName, msgId, gameMessageId, entry.name)
                        button(
                            hostSettingCommand named (if (entry.current(hostSettings)) "✅" else "❌"),
                            msgId,
                            gameMessageId,
                            entry.name
                        )
                    }
                }
                button(deleteMsgCommand named "Закрыть", msgId)
            }
        )
    }
}

internal fun showLobbyMenu(
    chatId: Long,
    messageId: Long,
    game: Game,
    bot: Bot,
    forceUpdate: Boolean = false
): Long? {
    val msgId = if (forceUpdate || messageId == -1L) {
        bot.sendMsg(chatId, "Меню ведущего:").msgId
    } else {
        messageId
    }
    if (msgId != null) {
        bot.editMessageReplyMarkup(
            ChatId.fromId(chatId),
            msgId,
            replyMarkup = lobby(msgId, game)
        )
    }
    return msgId
}

internal fun showPlayerMenu(
    chatId: Long,
    messageId: Long,
    bot: Bot,
    connectionId: ConnectionId,
    value: Int = 0
): Long? {
    val msgId = if (messageId == -1L) {
        bot.sendMsg(chatId, "Меню игрока:").msgId
    } else {
        messageId
    }
    if (msgId != null) {
        bot.editMessageReplyMarkup(
            ChatId.fromId(chatId),
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
                if (!getHideRolesMode(game)) {
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

internal fun showAdminListMenu(
    bot: Bot,
    chatId: Long,
    messageId: Long,
    itemsOffset: Int
) {
    showPaginatedMenu(
        chatId,
        messageId,
        bot,
        "Список администраторов",
        admins.find(),
        { _, account ->
            accounts.get(account.chatId)?.let { acc ->
                row {
                    button(blankCommand named acc.fullName())
                    button(removeAdminCommand, acc.chatId, messageId, itemsOffset)
                }
            }
        },
        adminBackCommand,
        adminSettingsCommand,
        itemsOffset
    )
}

internal fun showGameStatusMenu(
    bot: Bot,
    chatId: Long,
    messageId: Long,
    itemsOffset: Int
) {
    showPaginatedMenu(
        chatId,
        messageId,
        bot,
        "Активные игры",
        games.find(),
        { _, game ->
            button(blankCommand named game.name())
            button(terminateGameCommand, game.id, messageId)
        },
        adminBackCommand,
        gamesSettingsCommand,
        itemsOffset
    )
}

internal fun <T: Any> showPaginatedMenu(
    chatId: Long,
    messageId: Long,
    bot: Bot,
    title: String,
    list: List<T>,
    actionForEach: KeyboardContext.(Int, T) -> Unit,
    bottomButtonCommand: Command,
    menuCommand: Command,
    itemsOffset: Int,
    pageSize: Int = defaultPageSize
) {
    val markup = inlineKeyboard {
        button(blankCommand named title)
        val listSize = list.size
        if (listSize == 0) {
            button(blankCommand named "Этот список пуст...")
        } else {
            val pageIndex = itemsOffset / pageSize
            val totalAvailablePages = listSize / pageSize +
                    if (listSize % pageSize == 0) 0
                    else 1
            button(blankCommand named "Номер страницы: ${pageIndex + 1}")
            val topItemIndex = itemsOffset - itemsOffset % pageSize
            row {
                if (pageIndex > 0) {
                    button(menuCommand named "⬅", messageId, topItemIndex - pageSize)
                }
                if (pageIndex < totalAvailablePages - 1) {
                    button(menuCommand named "➡", messageId, topItemIndex + pageSize)
                }
            }
            for (i in topItemIndex until topItemIndex + pageSize) {
                if (i >= list.size) {
                    break
                }
                actionForEach(i, list[i])
            }
            if (totalAvailablePages > 1) {
                row {
                    button(menuCommand named "⏪ Первая", messageId, 0)
                    button(menuCommand named "⏩ Последняя", messageId, (totalAvailablePages - 1) * pageSize)
                }
            }
        }
        button(bottomButtonCommand, messageId)
    }
    bot.editMessageReplyMarkup(
        ChatId.fromId(chatId),
        messageId,
        replyMarkup = markup
    )
}

internal fun showHostAdminSettingsMenu(
    bot: Bot,
    chatId: Long,
    messageId: Long,
    itemsOffset: Int
) {
    showPaginatedMenu(
        chatId,
        messageId,
        bot,
        "Ведущие",
        hostSettings.find(),
        { _, hostSettings ->
            button(
                chooseHostAdminCommand named (hostSettings.host?.fullName()?: ""),
                messageId,
                hostSettings.hostId
            )
        },
        adminBackCommand,
        hostAdminSettingsCommand,
        itemsOffset
    )
}

internal fun showChosenSettingsMenu(bot: Bot, chatId: Long, messageId: Long, chosenId: Long) {
    hostSettings.get(chosenId)?.let { settings ->
        bot.editMessageReplyMarkup(
            ChatId.fromId(chatId),
            messageId,
            replyMarkup = inlineKeyboard {
                button(blankCommand named "Настройки ${accounts.get(chosenId)?.fullName() ?: ""}")
                HostOptions.entries.forEach { entry ->
                    row {
                        button(changeHostAdminSettingCommand named entry.shortName, messageId, chosenId, entry.name)
                        button(
                            changeHostAdminSettingCommand named (if (entry.current(settings)) "✅" else "❌"),
                            messageId,
                            chosenId,
                            entry.name
                        )
                    }
                }
                button(adminBackCommand, messageId)
            }
        )
        return
    }
}

internal fun showChosenHostSettings(bot: Bot, chatId: Long, messageId: Long, hostId: Long) {
    bot.editMessageReplyMarkup(
        ChatId.fromId(chatId),
        messageId,
        replyMarkup = inlineKeyboard {
            hostInfos.get(hostId)?.let {
                button(blankCommand named "Настройки ведущего")
                row {
                    button(blankCommand named "🎮 Лимит игр")
                    if (it.gameLimit) {
                        button(gameLimitOnCommand named it.left.toString(), it.chatId, messageId)
                        button(gameLimitOffCommand, it.chatId, messageId)
                    } else {
                        button(gameLimitOnCommand, it.chatId, messageId)
                    }
                }
                row {
                    button(blankCommand named "⏰ Срок ведения")
                    if (it.timeLimit) {
                        button(timeLimitOnCommand named it.until.toString(), it.chatId, messageId)
                        button(timeLimitOffCommand, it.chatId, messageId)
                    } else {
                        button(timeLimitOnCommand, it.chatId, messageId)
                    }
                }
                row {
                    button(blankCommand named "👥 Передавать ведение")
                    button(shareCommand named if (it.canShare) "On" else "Off", it.chatId, messageId)
                }
                row {
                    button(blankCommand named "👇 Выбирать роли")
                    button(canReassignCommand named if (it.canReassign) "On" else "Off", it.chatId, messageId)
                }
                if (admins.get(it.chatId) == null) {
                    button(promoteHostCommand, it.chatId, messageId)
                } else {
                    button(blankCommand named "⚛️ Администратор")
                }
            }
            button(hostSettingsCommand named "Назад", messageId, 0, false)
        }
    )
}

internal fun showKickMenu(
    game: Game,
    messageId: Long,
    bot: Bot,
    chatId: Long,
    itemsOffset: Int = 0
) {
    showPaginatedMenu(
        chatId,
        messageId,
        bot,
        "Исключенные игроки",
        kicks.find(),
        { _, kick ->
            accounts.get(kick.player)?.let { acc ->
                button(blankCommand named acc.fullName())
                button(unkickCommand, kick.id, messageId)
            }
        },
        hostBackCommand,
        menuKickCommand,
        itemsOffset
    )
}

internal fun showNightRoleMenu(
    town: Town,
    chatId: Long,
    bot: Bot,
    messageId: Long
) {
    val msgId = if (messageId == -1L) {
        bot.sendMsg(chatId, "Меню ночи:").msgId
    } else {
        messageId
    }
    if (msgId != null) {
        val wake = if (town.night.size > town.index) town.night[town.index] else null
        if (wake == null) {
            bot.editMessageText(
                ChatId.fromId(chatId),
                msgId,
                text = "Ночь завершена",
                replyMarkup = inlineKeyboard { button(dayCommand, msgId) }
            )
            return
        }
        val text = nightRoleDesc(wake)
        bot.editMessageText(
            ChatId.fromId(chatId),
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
            bot.sendMsg(chatId, "Меню дня:").msgId
        } else {
            acc.menuMessageId
        }
        msgId?.let {
            accounts.update(chatId) {
                menuMessageId = msgId
            }
            bot.editMessageReplyMarkup(
                ChatId.fromId(chatId),
                msgId,
                replyMarkup = inlineKeyboard {
                    if (settings?.hideDayPlayers == true) {
                        button(
                            hidePlayersCommand named (if (settings.playersHidden) "👓 Показать игроков" else hidePlayersCommand.name),
                            msgId
                        )
                    }
                    val hideRolesMode = getHideRolesMode(game)
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
                    if (!hideRolesMode) {
                        button(nightCommand, msgId)
                    }
                }
            )
        }
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
    bot.editMessageText(
        ChatId.fromId(con.playerId),
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