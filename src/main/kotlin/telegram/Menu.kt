package org.example.telegram

import com.github.kotlintelegrambot.Bot
import com.github.kotlintelegrambot.entities.ChatId
import com.github.kotlintelegrambot.entities.ParseMode
import com.github.kotlintelegrambot.entities.ReplyMarkup
import org.example.*
import org.example.db.Collection
import org.example.game.Town
import org.example.game.desc
import org.example.game.nightRoleDesc
import org.example.game.playerDayDesc

const val defaultItemsPerPage: Int = 10

fun getActiveGames(): List<Game> {
    return games.find().sortedBy { it.createdAt }.reversed()
}

fun getRecentGames(): List<GameSummary> {
    return gameHistory.find().sortedBy { it.playedAt }.reversed()
}

internal fun showAdMenu(chat: ChatId.Id, bot: Bot) {
    val active = getActiveGames()
    val recent = getRecentGames()
    inlineKeyboardLambdaSendMessage(
        chat.id,
        bot,
        if (active.isNotEmpty() || recent.isNotEmpty()) "Реклама" else "Нет доступных игр",
        { newMessageId ->
            if (active.isNotEmpty()) {
                button(listActiveGamesCommand, newMessageId, 0)
            }

            if (recent.isNotEmpty()) {
                button(listRecentGamesCommand, newMessageId, 0)
            }
            recent.forEach {

            }
            button(deleteMsgCommand, newMessageId)
        }
    )
}

internal fun <T: Any> selectGameForAdvertisement(
    chatId: Long,
    messageId: Long,
    bot: Bot,
    pageIndex: Int,
    menuCommand: Command,
    list: List<T>,
    actionForEach: KeyboardContext.(T) -> Unit,
) {
    showPaginatedMenu(
        chatId,
        messageId,
        bot,
        "Доступные игры",
        list,
        { actionForEach(it) },
        { button(deleteMsgCommand, it) },
        menuCommand,
        pageIndex
    )
}

internal fun showActiveGamesMenu(
    chatId: Long,
    messageId: Long,
    bot: Bot,
    pageIndex: Int = 0
) {
    selectGameForAdvertisement(
        chatId, messageId, bot, pageIndex,
        listActiveGamesCommand,
        getActiveGames(),
        { button(sendAdCommand named it.name(), it.id, messageId) }
    )
}

internal fun showRecentGamesMenu(chatId: Long, messageId: Long, bot: Bot, pageIndex: Int = 0) {
    selectGameForAdvertisement(
        chatId, messageId, bot, pageIndex,
        listRecentGamesCommand,
        getRecentGames(),
        { button(sendAdHistoryCommand named it.name(), it.id, messageId) }
    )
}

internal fun showSettingsMenu(it: HostSettings, chatId: Long, messageId: Long, gameMessageId: Long, bot: Bot, desc: String = "") {
    inlineKeyboardLambda(
        chatId,
        messageId,
        bot,
        "Настройки" +
                if (desc.isNotBlank()) "\n\nОписание:\n$desc" else "",
        { msgId ->
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
    return inlineKeyboardLambda(chatId, messageId, bot, "Меню ведущего:", { newMessageId ->
            val players = connections.find { gameId == game.id }
            val playerList = players.sortedWith(compareBy({ it.pos }, { it.createdAt }))
            val ordered = reordered(playerList)
            ordered.chunked(2).forEach {
                val first = it[0]
                row {
                    button(detailsCommand named first.name(), first.id, newMessageId)
                    button(
                        if (first.pos == Int.MAX_VALUE || first.pos < 1)
                            positionCommand
                        else positionCommand named first.pos.toString(),
                        first.id,
                        0,
                        newMessageId
                    )
                    if (it.size > 1) {
                        val second = it[1]
                        button(detailsCommand named second.name(), second.id, newMessageId)
                        button(
                            if (second.pos == Int.MAX_VALUE || first.pos < 1)
                                positionCommand
                            else positionCommand named second.pos.toString(),
                            second.id,
                            0,
                            newMessageId
                        )
                    } else {
                        button(blankCommand)
                        button(blankCommand)
                    }
                }
            }
            row {
                button(command("Игроков: ${players.size}", "default"))
            }
            row { button(dummyCommand, newMessageId) }
            row { button(menuKickCommand, newMessageId, 0) }
            if (game.creator?.hostInfo?.canShare == true) {
                button(changeHostCommand, newMessageId)
            }
            button(menuRolesCommand, newMessageId)
        },
        { equalsNegativeOne: Boolean ->
            equalsNegativeOne || forceUpdate
        },
        { newMessageId ->
            accounts.update(chatId) {
                menuMessageId = newMessageId
            }
        }
    )
}

internal fun showPlayerMenu(
    chatId: Long,
    messageId: Long,
    bot: Bot,
    connectionId: ConnectionId,
    value: Int = 0
): Long {
    return numpadKeyboardLambda(
        chatId,
        messageId,
        bot,
        "Меню игрока:",
        "Номер игрока",
        playerNumCommand,
        playerConfirmCommand,
        mainMenuCommand,
        connectionId,
        value
    )
}

internal fun showRevealMenu(game: Game, bot: Bot, chatId: Long, messageId: Long) {
    inlineKeyboardLambda(
        chatId, messageId, bot,
        { newMessageId ->
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
                                button(markBotCommand named textLeft, connection.id, newMessageId)
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
            button(proceedCommand, newMessageId)
        }
    )
}

internal fun showAdminListMenu(
    chatId: Long,
    messageId: Long,
    bot: Bot,
    pageIndex: Int = 0
) {
    showPaginatedAdminSubmenu(
        chatId,
        messageId,
        bot,
        "Список администраторов",
        admins,
        {
            accounts.get(it.chatId)?.let { acc ->
                row {
                    button(blankCommand named acc.fullName())
                    button(removeAdminCommand, it.chatId, messageId, pageIndex)
                }
            }
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
    showPaginatedAdminSubmenu(
        chatId,
        messageId,
        bot,
        "Активные игры",
        games,
        {
            button(blankCommand named it.name())
            button(terminateGameCommand, it.id, messageId)
        },
        gamesSettingsCommand,
        pageIndex
    )
}

internal fun <T: Any> showPaginatedMenu(
    chatId: Long,
    messageId: Long,
    bot: Bot,
    title: String,
    list: List<T>,
    actionForEach: KeyboardContext.(T) -> Unit,
    bottomContent: KeyboardContext.(Long) -> Unit,
    menuCommand: Command,
    desiredPageIndex: Int,
    itemsPerPage: Int = defaultItemsPerPage
) {
    inlineKeyboardLambda(
        chatId,
        messageId,
        bot,
        title,
        { newMessageId ->
            button(blankCommand named title)
            val listSize = list.size
            if (listSize > 0) {
                val quotient = listSize / itemsPerPage
                val totalAvailablePages = if (listSize % itemsPerPage == 0) {
                    quotient
                } else {
                    quotient + 1
                }
                val pageIndex = if (desiredPageIndex >= totalAvailablePages || desiredPageIndex < 0) {
                    0
                } else {
                    desiredPageIndex
                }
                button(blankCommand named "Номер страницы: ${pageIndex + 1}")
                val firstElementIndex = pageIndex * itemsPerPage
                row {
                    if (pageIndex > 0) {
                        button(menuCommand named "⬅", newMessageId, pageIndex - 1)
                    }
                    if (pageIndex < totalAvailablePages - 1) {
                        button(menuCommand named "➡", newMessageId, pageIndex + 1)
                    }
                }
                for (i in firstElementIndex until firstElementIndex + itemsPerPage) {
                    if (i < listSize) {
                        actionForEach(list.get(i))
                    }
                }
                if (totalAvailablePages > 1) {
                    row {
                        button(menuCommand named "Первая", newMessageId, 0)
                        button(menuCommand named "Последняя", newMessageId, totalAvailablePages - 1)
                    }
                }
            } else {
                button(blankCommand named "🤷 Пусто")
            }
            bottomContent(newMessageId)
        }
    )
}

internal fun <K: Any, T: Any> showPaginatedMenu(
    chatId: Long,
    messageId: Long,
    bot: Bot,
    title: String,
    list: Collection<K, T>,
    actionForEach: KeyboardContext.(T) -> Unit,
    bottomContent: KeyboardContext.(Long) -> Unit,
    menuCommand: Command,
    desiredPageIndex: Int,
    itemsPerPage: Int = defaultItemsPerPage
) {
    showPaginatedMenu(
        chatId, messageId, bot, title,
        list.find(),
        actionForEach, bottomContent, menuCommand, desiredPageIndex, itemsPerPage
    )
}

internal fun <K: Any, T: Any> showPaginatedAdminSubmenu(
    chatId: Long,
    messageId: Long,
    bot: Bot,
    title: String,
    list: Collection<K, T>,
    actionForEach: KeyboardContext.(T) -> Unit,
    menuCommand: Command,
    pageIndex: Int,
    itemsPerPage: Int = defaultItemsPerPage
) {
    showPaginatedMenu(
        chatId,
        messageId,
        bot,
        title,
        list,
        actionForEach,
        {
            button(adminBackCommand, messageId)
        },
        menuCommand,
        pageIndex,
        itemsPerPage
    )
}

internal fun showListHostOptionsMenu(
    chatId: Long,
    messageId: Long,
    bot: Bot,
    pageIndex: Int = 0
) {
    showPaginatedAdminSubmenu(
        chatId,
        messageId,
        bot,
        "Ведущие",
        hostSettings,
        {
            button(chooseHostOptionsCommand named (it.host?.fullName()?: ""), -1L, it.hostId)
        },
        listHostOptionsCommand,
        pageIndex
    )
}

fun getNewMessageId(
    chatId: Long,
    messageId: Long,
    bot: Bot,
    text: String?,
    shouldAttemptToMakeNewMessageId: (Boolean) -> Boolean,
    resSuccess: (Long) -> Unit,
    parseMode: ParseMode? = null
): Long {
    return if (shouldAttemptToMakeNewMessageId(messageId == -1L)) {
        val res = bot.sendMessage(
            ChatId.fromId(chatId),
            text.toString(),
            replyMarkup = inlineKeyboard { button(blankCommand named "Загрузка...") },
            parseMode = parseMode
        )
        if (res.isSuccess) {
            val newMessageId = res.get().messageId
            resSuccess(newMessageId)
            newMessageId
        } else {
            messageId
        }
    } else {
        messageId
    }
}

fun generalKeyboardLambda(
    chatId: Long,
    messageId: Long,
    bot: Bot,
    text: String?,
    replyMarkup: (Long) -> ReplyMarkup,
    shouldAttemptToMakeNewMessageId: (Boolean) -> Boolean,
    resSuccess: (Long) -> Unit,
    parseMode: ParseMode? = null
): Long {
    val newMessageId = getNewMessageId(
        chatId,
        messageId,
        bot,
        text,
        shouldAttemptToMakeNewMessageId,
        resSuccess,
        parseMode
    )
    bot.editMessageReplyMarkup(
        ChatId.fromId(chatId),
        newMessageId,
        replyMarkup = replyMarkup(newMessageId)
    )
    return newMessageId;
}

fun inlineKeyboardLambda(
    chatId: Long,
    messageId: Long,
    bot: Bot,
    text: String?,
    definition: KeyboardContext.(Long) -> Unit,
    shouldAttemptToMakeNewMessageId: (Boolean) -> Boolean,
    resSuccess: (Long) -> Unit,
    parseMode: ParseMode? = null
): Long {
    return generalKeyboardLambda(chatId, messageId, bot, text,
        { newMessageId -> inlineKeyboard { definition(newMessageId) } },
        shouldAttemptToMakeNewMessageId, resSuccess, parseMode
    )
}

fun inlineKeyboardLambda(
    chatId: Long,
    messageId: Long,
    bot: Bot,
    text: String?,
    definition: KeyboardContext.(Long) -> Unit,
    parseMode: ParseMode? = null
): Long {
    return inlineKeyboardLambda(chatId, messageId, bot, text, definition,
        { it }, {  },
        parseMode
    )
}

fun inlineKeyboardLambdaSendMessage(
    chatId: Long,
    bot: Bot,
    text: String,
    definition: KeyboardContext.(Long) -> Unit,
    resSuccess: (Long) -> Unit,
    parseMode: ParseMode? = null
): Long {
    return inlineKeyboardLambda(
        chatId, -1L, bot, text, definition, { it }, resSuccess, parseMode
    )
}

fun inlineKeyboardLambdaSendMessage(
    chatId: Long,
    bot: Bot,
    text: String,
    definition: KeyboardContext.(Long) -> Unit,
    parseMode: ParseMode? = null
): Long {
    return inlineKeyboardLambda(chatId, -1L, bot, text, definition, parseMode)
}

fun inlineKeyboardLambda(
    chatId: Long,
    messageId: Long,
    bot: Bot,
    definition: KeyboardContext.(Long) -> Unit,
    parseMode: ParseMode? = null
): Long {
    return inlineKeyboardLambda(chatId, messageId, bot, null, definition, parseMode)
}

fun emptyKeyboardLambda(
    chatId: Long,
    messageId: Long,
    bot: Bot,
    text: String
): Long {
    return inlineKeyboardLambda(chatId, messageId, bot, text, {})
}

fun<T: Any> numpadKeyboardLambda(
    chatId: Long,
    messageId: Long,
    bot: Bot,
    text: String?,
    title: String,
    numCommand: Command,
    acceptCommand: Command?,
    cancelCommand: Command,
    target: T,
    value: Int,
    shouldAttemptToMakeNewMessageId: (Boolean) -> Boolean,
    resSuccess: (Long) -> Unit,
    parseMode: ParseMode? = null
): Long {
    return generalKeyboardLambda(chatId, messageId, bot, text,
        { newMessageId -> numpadKeyboard(
            title, numCommand, acceptCommand, cancelCommand, target, value, newMessageId
        )
        },
        shouldAttemptToMakeNewMessageId, resSuccess, parseMode
    )
}

fun<T: Any> numpadKeyboardLambda(
    chatId: Long,
    messageId: Long,
    bot: Bot,
    text: String?,
    title: String,
    numCommand: Command,
    acceptCommand: Command?,
    cancelCommand: Command,
    target: T,
    value: Int,
    parseMode: ParseMode? = null
): Long {
    return numpadKeyboardLambda(
        chatId, messageId, bot, text, title, numCommand, acceptCommand, cancelCommand, target, value,
        { it }, {  },
        parseMode
    )
}

fun<T: Any> numpadKeyboardLambda(
    chatId: Long,
    messageId: Long,
    bot: Bot,
    title: String,
    numCommand: Command,
    acceptCommand: Command?,
    cancelCommand: Command,
    target: T,
    value: Int,
    parseMode: ParseMode? = null
): Long {
    return numpadKeyboardLambda(
        chatId, messageId, bot, null, title, numCommand, acceptCommand, cancelCommand, target, value, parseMode
    )
}

internal fun showChosenHostOptionsMenu(chatId: Long, messageId: Long, bot: Bot, chosenId: Long) {
    inlineKeyboardLambda(chatId, messageId, bot, "Настройки ведущего", { newMessageId ->
        hostSettings.get(chosenId)?.let { settings ->
            button(blankCommand named "Настройки ${accounts.get(chosenId)?.fullName()?: ""}")
            HostOptions.entries.forEach { entry ->
                row {
                    button(changeHostOptionsCommand named entry.shortName, newMessageId, chosenId, entry.name)
                    button(changeHostOptionsCommand named (if (entry.current(settings)) "✅" else "❌"), newMessageId, chosenId, entry.name)
                }
            }
            button(deleteMsgCommand named "Закрыть", newMessageId)
        }
    })
}

internal fun showChosenHostSettingsMenu(chatId: Long, messageId: Long, bot: Bot, hostId: Long) {
    inlineKeyboardLambda(
        chatId,
        messageId,
        bot,
        "Настройки ведущего",
        { newMessageId ->
            hostInfos.get(hostId)?.let {
                row {
                    button(blankCommand named "🎮 Лимит игр")
                    if (it.gameLimit) {
                        button(gameLimitOnCommand named it.left.toString(), it.chatId, newMessageId)
                        button(gameLimitOffCommand, it.chatId, newMessageId)
                    } else {
                        button(gameLimitOnCommand, it.chatId, newMessageId)
                    }
                }
                row {
                    button(blankCommand named "⏰ Срок ведения")
                    if (it.timeLimit) {
                        button(timeLimitOnCommand named it.until.toString(), it.chatId, newMessageId)
                        button(timeLimitOffCommand, it.chatId, newMessageId)
                    } else {
                        button(timeLimitOnCommand, it.chatId, newMessageId)
                    }
                }
                row {
                    button(blankCommand named "👥 Передавать ведение")
                    button(shareCommand named if (it.canShare) "On" else "Off", it.chatId, newMessageId)
                }
                row {
                    button(blankCommand named "👇 Выбирать роли")
                    button(canReassignCommand named if (it.canReassign) "On" else "Off", it.chatId, newMessageId)
                }
                if (admins.get(it.chatId) == null) {
                    button(promoteHostCommand, it.chatId, newMessageId)
                } else {
                    button(blankCommand named "⚛️ Администратор")
                }
            }
            button(deleteMsgCommand named "Закрыть", newMessageId)
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
    showPaginatedMenu(
        chatId,
        messageId,
        bot,
        "Исключенные игроки",
        kicks,
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
    val wake = if (town.night.size > town.index) town.night[town.index] else null
    inlineKeyboardLambda(
        chatId,
        messageId,
        bot,
        if (wake == null) "Ночь завершена" else nightRoleDesc(wake),
        { newMessageId ->
            if (wake == null) {
                button(dayCommand, newMessageId)
            } else {
                if (wake.players.none { it.alive }) {
                    row {
                        if (town.actions.isNotEmpty()) {
                            button(cancelActionCommand, newMessageId)
                        }
                        button(skipRoleCommand, newMessageId)
                    }
                } else {
                    val players = town.players.filter { it.alive }.sortedBy { it.pos }
                    val actor = wake.actor()
                    val settings = accounts.get(chatId)?.settings
                    fun KeyboardContext.RowContext.selectButton(it: Person) {
                        button(
                            selectCommand named ((if (it.pos in town.selections) "✅ " else "") + desc(it)),
                            it.pos,
                            newMessageId,
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
                            button(cancelActionCommand, newMessageId)
                        }
                        if (town.selections.isEmpty()) {
                            button(skipRoleCommand, newMessageId)
                        } else if (settings?.confirmNightSelection == true && town.selections.size == wake.type.choice) {
                            button(
                                executeActionCommand,
                                newMessageId,
                                actor?.roleData?.id ?: ""
                            )
                        }
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

        inlineKeyboardLambda(
            chatId,
            messageId,
            bot,
            "Меню дня:",
            { newMessageId ->
                if (settings?.hideDayPlayers == true) {
                    button(
                        hidePlayersCommand named (if (settings.playersHidden) "👓 Показать игроков" else hidePlayersCommand.name),
                        newMessageId
                    )
                }
                val hideRolesMode = getHideRolesMode(game)
                if (settings?.playersHidden != true) {
                    row { button(filterCommand named "Фильтр: ${view.desc}", newMessageId) }
                    for (player in town.players.sortedBy { it.pos }) {
                        if (view.filter(player)) {
                            row {
                                button(
                                    (if (settings?.detailedView == true) blankCommand else dayDetailsCommand) named desc(
                                        player,
                                        hideRolesMode = hideRolesMode
                                    ),
                                    player.pos,
                                    newMessageId
                                )
                            }
                            if (settings?.detailedView == true) {
                                row {
                                    playerDayDesc(player, newMessageId, fallMode)
                                }
                            }
                        }
                    }
                }
                button(settingsCommand, newMessageId)
                if (settings?.timer == true) {
                    button(timerCommand)
                }
                if (!hideRolesMode) {
                    button(nightCommand, newMessageId)
                }
            },
            { it },
            { newMessageId ->
                accounts.update(chatId) {
                    menuMessageId = newMessageId
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
    inlineKeyboardLambda(
        con.playerId,
        messageId,
        bot,
        desc,
        {
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