package org.example.telegram

import com.github.kotlintelegrambot.Bot
import com.github.kotlintelegrambot.entities.ChatId
import org.example.*
import org.example.game.Town
import org.example.game.desc
import org.example.game.nightRoleDesc
import org.example.game.playerDayDesc

internal fun showAdMenu(chat: ChatId.Id, bot: Bot) {
    val chatId = chat.id
    val active = games.find().sortedBy { it.createdAt }.reversed()
    val recent = gameHistory.find().sortedBy { it.playedAt }.reversed()
    bot.sendmessage(
        chatId,
        if (active.isNotEmpty() || recent.isNotEmpty()) "Доступные игры:" else "Нет доступных игр"
    ).inlinekeyboard { msgId ->
        inlineKeyboard {
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
            button(deleteMsgCommand, msgId)
        }
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
        bot.sendmessage(
            chatId,
            "Настройки" +
                if (desc.isNotBlank()) "\n\nОписание:\n$desc" else ""
        ).msgId
    } else {
        messageId
    }
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

internal fun showLobbyMenu(
    chatId: Long,
    messageId: Long,
    game: Game,
    bot: Bot,
    forceUpdate: Boolean = false
): Long {
    val msgId = if (forceUpdate || messageId == -1L) {
        bot.sendmessage(chatId, "Меню ведущего:").msgId
    } else {
        messageId
    }
    bot.editMessageReplyMarkup(
        ChatId.fromId(chatId),
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
    val msgId = if (messageId == -1L) {
        bot.sendmessage(chatId, "Меню игрока:").msgId
    } else {
        messageId
    }
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
    chatId: Long,
    messageId: Long,
    bot: Bot,
    pageIndex: Int = 0
) {
    val adminsList = admins.find()
    showPaginatedMenu(
        chatId,
        messageId,
        bot,
        "Список администраторов",
        subList(adminsList, pageIndex),
        adminsList.size,
        {
            accounts.get(it.chatId)?.let { acc ->
                row {
                    button(blankCommand named acc.fullName())
                    button(removeAdminCommand, it.chatId, messageId, pageIndex)
                }
            }
        },
        {
            button(adminBackCommand, messageId)
        },
        adminSettingsCommand,
        pageIndex
    )
}

internal fun showGameStatusMenu(
    chatId: Long,
    messageId: Long,
    bot: Bot,
    pageIndex: Int = 0
) {
    val gamesList = games.find()
    showPaginatedMenu(
        chatId,
        messageId,
        bot,
        "Активные игры",
        subList(gamesList, pageIndex),
        gamesList.size,
        {
            button(blankCommand named it.name())
            button(terminateGameCommand, it.id, messageId)
        },
        {
            button(adminBackCommand, messageId)
        },
        gamesSettingsCommand,
        pageIndex
    )
}

fun <T: Any> subList(
    list: List<T>,
    pageIndex: Int,
    itemsPerPage: Int = defaultItemsPerPage
): List<T> {
    val firstElementIndex = pageIndex * itemsPerPage
    return list.subList(
        firstElementIndex,
        (firstElementIndex + itemsPerPage).coerceAtMost(list.size)
    )
}

internal fun <T: Any> showPaginatedMenu(
    chatId: Long,
    messageId: Long,
    bot: Bot,
    title: String,
    subList: List<T>, // Contains only the elements to be displayed on the CURRENT page (pageIndex)
    listSize: Int,
    actionForEach: KeyboardContext.(T) -> Unit,
    bottomContent: KeyboardContext.(Long) -> Unit,
    menuCommand: Command,
    pageIndex: Int,
    itemsPerPage: Int = defaultItemsPerPage
) {
    val msgId = if (messageId == -1L) {
        bot.sendmessage(chatId, title).msgId
    } else {
        messageId
    }
    bot.editMessageReplyMarkup(
        ChatId.fromId(chatId),
        msgId,
        replyMarkup = inlineKeyboard {
            button(blankCommand named title)
            if (listSize == 0) {
                button(blankCommand named "🤷 Здесь ничего нет")
            } else {
                val quotient = listSize / itemsPerPage
                val totalAvailablePages = if (listSize % itemsPerPage == 0) {
                    quotient
                } else {
                    quotient + 1
                }
                button(blankCommand named "Номер страницы: ${pageIndex + 1}")
                row {
                    if (pageIndex > 0) {
                        button(menuCommand named "⬅", msgId, pageIndex - 1)
                    }
                    if (pageIndex < totalAvailablePages - 1) {
                        button(menuCommand named "➡", msgId, pageIndex + 1)
                    }
                }
                subList.forEach {
                    actionForEach(it)
                }
                if (totalAvailablePages > 1) {
                    row {
                        button(menuCommand named "Первая", msgId, 0)
                        button(menuCommand named "Последняя", msgId, totalAvailablePages - 1)
                    }
                }
            }
            bottomContent(msgId)
        }
    )
}

internal fun showHostAdminSettingsMenu(
    chatId: Long,
    messageId: Long,
    bot: Bot,
    pageIndex: Int = 0
) {
    val hostSettingsList = hostSettings.find()
    showPaginatedMenu(
        chatId,
        messageId,
        bot,
        "Ведущие",
        subList(hostSettingsList, pageIndex),
        hostSettingsList.size,
        {
            button(chooseHostAdminCommand named (it.host?.fullName()?: ""), messageId, it.hostId)
        },
        {
            button(adminBackCommand, messageId)
        },
        hostAdminSettingsCommand,
        pageIndex
    )
}

internal fun showChosenSettingsMenu(chatId: Long, messageId: Long, bot: Bot, chosenId: Long) {
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

internal fun showChosenHostInfoSettings(chatId: Long, messageId: Long, bot: Bot, hostId: Long) {
    val msgId = if (messageId == -1L) {
        bot.sendmessage(chatId, "Настройки ведущего").msgId
    } else {
        messageId
    }
    bot.editMessageReplyMarkup(
        ChatId.fromId(chatId),
        msgId,
        replyMarkup = inlineKeyboard {
            hostInfos.get(hostId)?.let {
                row {
                    button(blankCommand named "🎮 Лимит игр")
                    if (it.gameLimit) {
                        button(gameLimitOnCommand named it.left.toString(), it.chatId, msgId)
                        button(gameLimitOffCommand, it.chatId, msgId)
                    } else {
                        button(gameLimitOnCommand, it.chatId, msgId)
                    }
                }
                row {
                    button(blankCommand named "⏰ Срок ведения")
                    if (it.timeLimit) {
                        button(timeLimitOnCommand named it.until.toString(), it.chatId, msgId)
                        button(timeLimitOffCommand, it.chatId, msgId)
                    } else {
                        button(timeLimitOnCommand, it.chatId, msgId)
                    }
                }
                row {
                    button(blankCommand named "👥 Передавать ведение")
                    button(shareCommand named if (it.canShare) "On" else "Off", it.chatId, msgId)
                }
                row {
                    button(blankCommand named "👇 Выбирать роли")
                    button(canReassignCommand named if (it.canReassign) "On" else "Off", it.chatId, msgId)
                }
                if (admins.get(it.chatId) == null) {
                    button(promoteHostCommand, it.chatId, msgId)
                } else {
                    button(blankCommand named "⚛️ Администратор")
                }
            }
            button(deleteMsgCommand named "Закрыть", msgId)
        }
    )
}

internal fun showKickMenu(
    game: Game,
    messageId: Long,
    bot: Bot,
    chatId: Long,
    pageIndex: Int = 0
) {
    val kicksList = kicks.find()
    showPaginatedMenu(
        chatId,
        messageId,
        bot,
        "Исключенные игроки",
        subList(kicksList, pageIndex),
        kicksList.size,
        {
            accounts.get(it.player)?.let { acc ->
                button(blankCommand named acc.fullName())
                button(unkickCommand, it.id, messageId)
            }
        },
        {
            button(hostBackCommand, messageId)
        },
        menuKickCommand,
        pageIndex
    )
}

internal fun showNightRoleMenu(
    town: Town,
    chatId: Long,
    bot: Bot,
    messageId: Long
) {
    val msgId = if (messageId == -1L) {
        bot.sendmessage(chatId, "Меню ночи:").msgId
    } else {
        messageId
    }
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
            bot.sendmessage(chatId, "Меню дня:").msgId
        } else {
            messageId
        }

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