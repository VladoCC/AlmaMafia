package org.example.telegram

import com.github.kotlintelegrambot.Bot
import com.github.kotlintelegrambot.entities.ChatId
import com.github.kotlintelegrambot.entities.ParseMode
import kotlinx.serialization.json.Json
import org.bson.types.ObjectId
import org.example.*
import org.example.game.DistributionContext
import org.example.game.DistributionData
import org.example.game.RoleDistribution
import org.example.game.Town
import org.example.game.WakeStatus
import org.example.game.WokeStatus
import org.example.game.accuracy
import org.example.game.confidence
import org.example.game.deviation
import org.example.game.executeNightAction
import org.example.game.getRoleDesc
import org.example.game.historyStrength
import org.example.game.nightRoleDesc
import org.example.game.playerDayDesc
import org.example.game.unfairness
import org.example.game.weight
import org.example.lua.Choice
import org.example.lua.ChoiceView
import org.example.lua.PlayState
import org.example.lua.TieState
import org.example.lua.WonState
import java.io.File

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

internal fun showSettingsMenu(
    settings: HostSettings,
    chatId: Long,
    messageId: Long,
    gameMessageId: Long,
    bot: Bot,
    desc: String = ""
) {
    val text = "⚙️ Опции" +
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
            HostOptions.entries.forEach { entry ->
                row {
                    button(settingDescCommand named entry.shortName, msgId, gameMessageId, entry.name)
                    button(
                        hostSettingCommand named (if (entry.current(settings)) "✅" else "❌"),
                        msgId,
                        gameMessageId,
                        entry.name
                    )
                }
            }

            if (HostOptions.AutoNight.current(settings)) {
                val setting = settings.autoNight
                row {
                    button(autoSingLimDescCommand, msgId, gameMessageId)
                    button(
                        autoSingLimSelCommand named (setting?.actionSingleLimit?.toSeconds()?.pretty() ?: "Ошибка"),
                        msgId,
                        gameMessageId
                    )
                }
                row {
                    button(autoTeamLimDescCommand, msgId, gameMessageId)
                    button(
                        autoTeamLimSelCommand named (setting?.actionTeamLimit?.toSeconds()?.pretty() ?: "Ошибка"),
                        msgId,
                        gameMessageId
                    )
                }
            }
            if (checks.get(CheckOption.SHOW_TOWN)) {
                button(shareGameCommand, msgId)
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
    val allowed = canHost(game.creatorId)
    if (!allowed) {
        createHostRequest(chatId)
    }
    val id = ChatId.fromId(chatId)
    var msgId = messageId
    if (forceUpdate || msgId == -1L) {
        val res = bot.sendMessage(
            id,
            text = if (allowed) "Меню ведущего:" else "Возможность перезапуска игры недоступна для создателя этого лобби."
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
        replyMarkup = if (allowed) {
            lobby(msgId, game)
        } else {
            inlineKeyboard {
                button(stopLobbyCommand, accounts.get(chatId)?.menuMessageId ?: -1L)
            }
        }
    )
    return msgId
}

fun showRolesMenu(
    chatId: Long,
    messageId: Long,
    bot: Bot,
    game: Game
) {
    val players = connections.find { gameId == game.id }
    pairings.find { gameId == game.id }
    val gameSetups = setups.find { gameId == game.id }
    val keyboard = inlineKeyboard {
        val script = game.script!!
        button(blankCommand named "🎭 Роли: ${script.displayName()}")
        gameSetups.sortedBy { it.index }.chunked(2).forEach {
            val left = it[0]
            val right = if (it.size > 1) it[1] else null
            row {
                button(roleCommand named left.role!!.displayName, left.roleId, messageId)
                if (right != null) {
                    button(roleCommand named right.role!!.displayName, right.roleId, messageId)
                } else {
                    button(blankCommand)
                }
            }
            row {
                button(decrCommand, left.id, messageId)
                button(blankCommand named left.count.toString())
                button(incrCommand, left.id, messageId)
                if (right != null) {
                    button(decrCommand, right.id, messageId)
                    button(blankCommand named right.count.toString())
                    button(incrCommand, right.id, messageId)
                } else {
                    button(blankCommand)
                    button(blankCommand)
                    button(blankCommand)
                }
            }
        }
        row {
            button(command("Игроков: ${players.size}", "default"))
        }
        row {
            button(blankCommand named "♦️️: ${gameSetups.filter { it.role?.defaultTeam == "city" }.sumOf { it.count }}")
            button(blankCommand named "Выбрано: ${gameSetups.sumOf { it.count }}")
            button(blankCommand named "♣️: ${gameSetups.filter { it.role?.defaultTeam != "city" }.sumOf { it.count }}")
        }
        val scriptCount = (game.host?.scripts?.size ?: 0) + (game.creator?.scripts?.size ?: 0)
        if (scriptCount > 1) {
            button(changeScriptCommand, game.id, messageId)
        }
        button(resetRolesCommand, game.id, messageId)
        row {
            button(menuLobbyCommand, messageId)
            button(previewCommand, game.id, messageId)
        }
    }
    bot.editMessageReplyMarkup(
        ChatId.fromId(chatId),
        messageId,
        replyMarkup = keyboard
    )
}


internal fun showPreviewMenu(
    bot: Bot,
    chatId: Long,
    messageId: Long,
    game: Game
) {
    val players = connections.find { gameId == game.id }
    val pairs = pairings.find { gameId == game.id }.associateBy { it.connectionId }
    val keyboard = inlineKeyboard {
        val hideRolesMode = isHideRolesMode(game)
        players.sortedBy { it.pos }.forEach {
            val pair = pairs[it.id]
            row {
                button(
                    if (it.pos == Int.MAX_VALUE) positionCommand
                    else (positionCommand named it.pos.toString()),
                    it.id,
                    0,
                    messageId
                )
                button(detailsCommand named it.name(), it.id, messageId)
                val roleName = pair?.roleId?.let { id ->
                    if (hideRolesMode) {
                        "👌 Роль выдана"
                    } else {
                        roles.get(id)?.displayName
                    }
                } ?: "❗ Роль не выдана"
                if (game.host?.hostInfo?.canReassign == true) {
                    button(reassignRoleCommand named roleName, messageId, it.id)
                } else {
                    button(blankCommand named roleName)
                }
            }
        }
        row {
            button(command("Игроков: ${players.size}", "default"))
        }
        row {
            button(blankCommand named "Распределено ролей: ${pairs.size}")
        }
        if (game.script?.roleDistribution == RoleDistribution.WEIGHTED
            && game.host?.hostInfo?.showDistribution == true
        ) {
            button(menuWeightCommand, messageId)
        }
        button(
            toggleHideRolesCommand named
                    if (hideRolesMode) "👓 Показывать роли" else "🕶️ Скрывать роли",
            messageId
        )
        button(previewCommand named "🔄 Перераздать", chatId, messageId)
        row {
            button(menuRolesCommand named "◀️ Меню ролей", messageId)
            button(gameCommand, game.id, messageId)
        }
    }
    bot.editMessageReplyMarkup(
        ChatId.fromId(chatId),
        messageId,
        replyMarkup = keyboard
    )
}

internal fun showWeightMenu(
    bot: Bot,
    chatId: Long,
    messageId: Long,
    game: Game
) {
    bot.editMessageReplyMarkup(
        ChatId.fromId(chatId),
        messageId,
        replyMarkup = inlineKeyboard {
            try {
                val context = DistributionContext(game)
                game.connectionList
                    .filter { !it.bot }
                    .map { it to DistributionData(it, game, context) }
                    .sortedBy { it.second.unfairness() }
                    .forEach { (con, data) ->
                        row {
                            button(
                                menuDistributionCommand
                                        named (if (con.pos < Int.MAX_VALUE) "${con.pos}. " else "") + con.name(),
                                messageId,
                                con.id
                            )
                            button(blankCommand named "%.2f".format(data.unfairness()))
                        }
                    }
                button(menuPreviewCommand named "◀️ Назад", messageId)
            } catch (e: Exception) {
                log.error("Failed to show weight menu", e)
            }
        }
    )
}

internal fun showDistributionMenu(
    bot: Bot,
    chatId: Long,
    messageId: Long,
    game: Game,
    connectionId: ConnectionId
) {
    connections.get(connectionId)?.let { con ->
        bot.editMessageReplyMarkup(
            ChatId.fromId(chatId),
            messageId,
            replyMarkup = inlineKeyboard {
                button(blankCommand named "Распределение ${con.name()}")
                val context = DistributionContext(game)
                val data = DistributionData(con, game, context)
                val names = teamNames.find { gameId == game.id }.associate { it.team to it.name }
                val weights = context.teams.keys.associateWith { data.weight(it) }
                val weightSum = weights.values.sum()
                context.teams.keys.forEach {
                    try {
                        row {
                            button(blankCommand named names.getOrDefault(it, it))
                            val teamHistory = data.history(it)
                            val size = data.historySize
                            button(blankCommand named "%.2f".format(weights[it]!! / weightSum * 100.0) + "%")
                            button(
                                blankCommand named
                                        "${data.history(it)} / ${data.historySize} ("
                                        + "%.1f".format(teamHistory / size.toDouble() * 100.0)
                                        + "%)"
                            )
                            button(
                                blankCommand named
                                        "${context.teams[it]!!.size} / ${context.roles.size} ("
                                        + "%.1f".format(context.teams[it]!!.size.toDouble() / context.roles.size * 100.0)
                                        + "%)"
                            )
                        }
                        row {
                            button(blankCommand named "%.2f".format(data.deviation(it)))
                            button(blankCommand named "%.2f".format(data.historyStrength()))
                            button(blankCommand named "%.2f".format(data.confidence(it)))
                            button(blankCommand named "%.2f".format(data.accuracy(it)))
                        }
                    } catch (e: Exception) {
                        log.error("Failed to show distribution menu", e)
                    }
                }
                button(blankCommand named "🧠 История")
                teamHistories.find { scriptId == game.scriptId && playerId == con.playerId }
                    .sortedByDescending { it.date }
                    .forEach { history ->
                        row {
                            button(blankCommand named history.team.let { names.getOrDefault(it, it) })
                            button(blankCommand named history.date.toString())
                        }
                    }
                button(menuWeightCommand named "◀️ Назад", messageId)
            }
        )
        return@let
    }
}

internal fun showPlayerLobbyMenu(
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
            "Меню игрока:",
            disableNotification = true
        ).get().messageId
    } else {
        messageId
    }
    bot.editMessageReplyMarkup(
        chat,
        msgId,
        replyMarkup = numpadKeyboard(
            "Номер игрока:",
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

fun showPlayerGameMenu(
    connection: Connection,
    chat: ChatId,
    msgId: Long,
    roleId: RoleId,
    state: LinkType,
    game: Game,
    bot: Bot
) {
    val text = when (state) {
        LinkType.NONE -> "📋 Роли выданы"
        LinkType.ROLE -> {
            roles.get(roleId)?.let { role ->
                val desc = getRoleDesc(role)
                connections.update(connection.id) {
                    notified = true
                }
                pendings.save(Pending(ObjectId(), game.hostId, game.id))
                desc
            } ?: "Роль не найдена"
        }

        LinkType.INFO -> getGameInfo(game, connection)
        LinkType.ALIVE -> getAlivePlayerDesc(game)
        LinkType.REVEAL -> "🏘️ Меню города"
    }
    messageLinks.updateMany({
        messageId == msgId
                && chatId == connection.playerId
                && gameId == game.id
    }) {
        type = state
    }
    bot.editMessageText(
        chat,
        msgId,
        text = text,
        parseMode = ParseMode.HTML,
        replyMarkup = inlineKeyboard {
            if (state == LinkType.REVEAL) {
                connection.game?.let { game ->
                    towns[game.id]?.let { town ->
                        for (player in town.players.sortedBy { it.pos }) {
                            row {
                                button(blankCommand named player.desc(roles = true))
                            }
                        }
                    }
                }
                button(
                    playerMenuCommand named "◀️ Меню игрока",
                    roleId,
                    msgId,
                    LinkType.ALIVE
                )
            } else {
                LinkType.entries.forEach { menuState ->
                    if (menuState != state && menuState.showInMenu(connection)) {
                        button(
                            playerMenuCommand named menuState.desc,
                            roleId,
                            msgId,
                            menuState
                        )
                    }
                }
            }
        }
    )
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
                if (!isHideRolesMode(game)) {
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
                button(chooseHostAdminCommand named (it.host?.fullName() ?: ""), messageId, it.hostId)
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

internal fun showNightActionMenu(
    town: Town,
    wake: Wake,
    bot: Bot,
    chatId: Long,
    messageId: Long
) {
    val text = executeNightAction(town, wake, true)
    wake.status = WakeStatus.woke(text)
    bot.editMessageText(
        ChatId.fromId(chatId),
        messageId,
        text = text,
        replyMarkup = inlineKeyboard {
            row {
                button(cancelActionCommand, messageId)
                if (town.index >= town.night.size) {
                    button(dayCommand, messageId)
                } else {
                    button(nextRoleCommand, messageId)
                }
            }
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
    nightHostMessages.save(NightHostMessage(chatId, msgId, town.gameId))
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
    wake.status = WakeStatus.action()
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
                val actor = wake.actor()
                val settings = accounts.get(chatId)?.settings
                fun KeyboardContext.RowContext.selectButton(it: Choice) {
                    button(
                        selectCommand named ((if (it.id in wake.selections) "✅ " else "") + it.text(ChoiceView.HOST)),
                        it.id,
                        msgId,
                        actor?.roleData?.id ?: ""
                    )
                }

                if (settings == null || settings.doubleColumnNight) {
                    doubleColumnView(wake.choices)
                        .default { button(blankCommand) }
                        .build { selectButton(it) }
                } else {
                    wake.choices.forEach {
                        row {
                            selectButton(it)
                        }
                    }
                }

                row {
                    if (town.actions.isNotEmpty()) {
                        button(cancelActionCommand, msgId)
                    }
                    if (wake.selections.isEmpty()) {
                        button(skipRoleCommand, msgId)
                    } else if (settings?.confirmNightSelection == true && wake.filled()) {
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

internal fun showAutoNightHostMenu(
    town: Town,
    chatId: Long,
    bot: Bot,
    messageId: Long
) {
    val chat = ChatId.fromId(chatId)
    val msgId = if (messageId == -1L) {
        bot.sendMessage(
            chat,
            "🤖 Меню авто-ночи:"
        ).get().messageId
    } else {
        messageId
    }
    nightHostMessages.save(NightHostMessage(chatId, msgId, town.gameId))
    bot.editMessageReplyMarkup(
        chat,
        msgId,
        replyMarkup = inlineKeyboard {
            button(blankCommand named "Статус ролей")
            town.night.forEach { wake ->
                row {
                    button(blankCommand named wake.type.displayName)
                    button(blankCommand named wake.status.desc())
                }
            }
            button(autoNightUpdCommand, msgId)
            button(dayCommand, msgId)
        }
    )
}

internal fun showAutoNightPrepMenu(
    actorId: AutoNightActorId,
    wake: Wake?,
    role: Role,
    chatId: Long,
    bot: Bot
): Long {
    val res = bot.sendMessage(
        ChatId.fromId(chatId),
        "Ведущий начал авто-ночь. Нажмите кнопку ниже, когда  ведущий разбудит вашу роль.\n" +
                "Напоминание: ваша роль - <span class=\"tg-spoiler\">${
                    (role.displayName + " ").padEnd(
                        roleNameLen,
                        '_'
                    )
                }</span>",
        parseMode = ParseMode.HTML
    )
    if (res.isSuccess) {
        val msgId = res.get().messageId
        bot.editMessageReplyMarkup(
            ChatId.fromId(chatId),
            msgId,
            replyMarkup = inlineKeyboard {
                button(autoNightPlayCommand, msgId, actorId, wake?.id ?: -1)
            }
        )
        return msgId
    }
    return -1L
}

internal fun showAutoNightPlayerMenu(
    wake: Wake,
    town: Town,
    link: ActorActionLink,
    chatId: Long,
    messageId: Long,
    bot: Bot
) {
    val actorLinks = link.action?.actorLinks
    val leader = actorLinks?.firstOrNull { it.leader }?.actor?.connection?.pos
    val text = if (wake.status == WakeStatus.action()) {
        nightRoleDesc(wake) +
                if ((actorLinks?.size ?: 0) > 1)
                    "\n\n" +
                            actorLinks?.joinToString("\n") { actorLink ->
                                actorLink.actor?.let { actor ->
                                    town.playerMap[actor.connection?.pos]?.let { person ->
                                        val selections = actorLink.selections
                                        if (selections.isNotEmpty()) {
                                            "Игрок " + person.pos + ". " + person.name + " выбрал:\n" +
                                                    selections.sortedBy { it.selection }
                                                        .mapNotNull { sel ->
                                                            town.playerMap[sel.selection]
                                                                ?.let { "  -  " + it.pos.toString() + " - " + it.name }
                                                        }.joinToString("\n")
                                        } else {
                                            ""
                                        }
                                    }
                                } ?: ""
                            }.let { if (it?.isNotBlank() == true) it + "\n\n" else it } +
                            "<b>" + (
                            if (link.leader)
                                "Вы принимаете решение"
                            else "Решение принимает: " +
                                    (leader?.let { "$it - ${town.playerMap[it]?.name}" } ?: "Игрок не указан")
                            ) +
                            "</b>"
                else ""
    } else {
        wake.status.result()
    }
    bot.editMessageText(
        ChatId.fromId(chatId),
        messageId,
        text = text,
        parseMode = ParseMode.HTML,
        replyMarkup =
            if (wake.status == WakeStatus.action())
                inlineKeyboard {
                    val current = link.selections.map { it.selection }.toSet()
                    val amounts =
                        actorLinks?.map { it.selections.map { sel -> sel.selection } }?.flatten()?.groupingBy { it }
                            ?.eachCount()

                    fun KeyboardContext.RowContext.selectButton(it: Choice) {
                        button(
                            selectTargetCommand named (
                                    (if (it.id in current) "✅ " else "") +
                                            (if ((actorLinks?.size ?: 0) > 1) amounts?.get(it.id)?.pretty()
                                                ?: "" else "") +
                                            it.text(ChoiceView.PLAYER)),
                            messageId,
                            link.id,
                            it.id
                        )
                    }

                    doubleColumnView(wake.choices).default { button(blankCommand) }
                        .build { choice ->
                            selectButton(choice)
                        }

                    if ((actorLinks?.size ?: 0) > 1) {
                        leader?.let {
                            town.playerMap[it]?.let { person ->
                                button(
                                    blankCommand
                                            named (
                                            if (link.leader)
                                                "🫡 Вы принимаете решение"
                                            else
                                                "➡️ Лидер: №${person.pos} - ${person.name}"
                                            )
                                )
                            }
                        }
                    }

                    row {
                        if (link.leader) {
                            button(autoNightSkipCommand, messageId, link.id)
                            if (wake.type.choice == current.size) {
                                button(autoNightDoneCommand, messageId, link.id)
                            } else if (current.isNotEmpty()) {
                                button(blankCommand named if (wake.type.choice < current.size) "🔻 Слишком много" else "🔺 Слишком мало")
                            }
                        } else {
                            button(forceLeadCommand, messageId, link.id)
                        }
                    }
                }
            else if (wake.status is WokeStatus)
                inlineKeyboard {
                    link.actor?.let { actor ->
                        val next = actor.actionLinks.mapNotNull { it.action }.sortedBy { it.wakeId }
                            .firstOrNull { it.wakeId > wake.id }
                        if (next == null) {
                            button(deleteMsgCommand, messageId)
                        } else {
                            // todo not tested yet, need to check later
                            button(autoNightPlayCommand named "Следующая роль ▶️", messageId, actor.id, next.id)
                        }
                    }
                }
            else inlineKeyboard { }
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
            val hideRolesMode = isHideRolesMode(game)
            if (settings?.playersHidden != true) {
                row { button(filterCommand named "Фильтр: ${view.desc}", msgId) }
                for (player in town.players.sortedBy { it.pos }) {
                    if (view.filter(player)) {
                        row {
                            button(
                                (
                                        if (settings?.detailedView == true)
                                            blankCommand
                                        else dayDetailsCommand
                                        ) named
                                        player.desc(
                                            roles = !hideRolesMode
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

            row {
                button(nightCommand, msgId)
                if (settings?.autoNight?.enabled == true) {
                    button(autoNightCommand, msgId)
                }
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
    val desc = getAlivePlayerDesc(game)
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

internal fun showEndGameMenu(
    chatId: Long,
    messageId: Long,
    game: Game,
    bot: Bot,
    includeStatus: Boolean = false
) {
    val town = towns[game.id]
    val teams = town?.players?.map { it.team }?.toSet() ?: emptySet()
    val map = teamNames.find { gameId == game.id }.filter { it.team in teams }.associateBy { it.team }
    if (includeStatus && town != null) {
        val state = scripts[game.id]?.get(statusScriptName)?.status(town.players) ?: PlayState
        winSelections.deleteMany { gameId == game.id }
        val winners = when (state) {
            is WonState -> setOf(state.team)
            is TieState -> state.teams.toSet()
            else -> emptySet()
        }
        winners.forEach { team ->
            winSelections.save(WinSelection(ObjectId(), game.id, team))
        }
    }
    val selected = winSelections.find { gameId == game.id }.map { it.team }.toSet()
    bot.editMessageReplyMarkup(
        ChatId.fromId(chatId),
        messageId,
        replyMarkup = inlineKeyboard {
            doubleColumnView(map.entries.toList()).default { button(blankCommand) }
                .build { entry ->
                    val team = entry.key
                    val name = entry.value.name
                    button(
                        selectWinnerCommand named (
                                (if (team in selected) "✅ " else "") + name),
                        team,
                        messageId
                    )
                }
            row {
                button(deleteMsgCommand named "◀️ Отмена", messageId)
                button(acceptRehostCommand named restartGameCommand.name, messageId)
                if (teams.isNotEmpty()) {
                    button(acceptEndCommand, messageId)
                }
            }
        }
    )
}

fun showAdminMenu(
    chatId: Long,
    messageId: Long,
    bot: Bot
) {
    bot.editMessageReplyMarkup(
        ChatId.fromId(chatId),
        messageId,
        replyMarkup = inlineKeyboard {
            CheckOption.entries.forEach {
                row {
                    button(blankCommand named it.display)
                    button(
                        updateCheckCommand named (if (checks.get(it)) "✅" else "❌"),
                        it.key,
                        messageId
                    )
                }
            }
            button(hostRequestCommand, messageId)
            button(hostSettingsCommand, messageId)
            button(adminSettingsCommand, messageId)
            button(gamesSettingsCommand, messageId)
            button(hostAdminSettingsCommand, messageId)
            button(advertCommand)
            button(deleteMsgCommand, messageId)
        }
    )
}

fun showStatMenu(
    chatId: Long,
    messageId: Long,
    bot: Bot
) {
    try {
        val stats = scriptStats.find { playerId == chatId }
        if (stats.size == 1) {
            showScriptStatMenu(
                chatId,
                messageId,
                stats[0].scriptId,
                bot
            )
            return
        }

        bot.editMessageText(
            ChatId.fromId(chatId),
            messageId,
            text = "Статистика игрока:" + if (stats.isEmpty()) "\n\nНе удалось найти статистику для данного игрока" else "",
            replyMarkup = inlineKeyboard {
                stats.forEach {
                    it.script?.let { script ->
                        button(scriptStatCommand named script.name, messageId, it.id)
                    }
                }
                button(deleteMsgCommand, messageId)
            }
        )
    } catch (e: Exception) {
        log.error("Unable to show stat menu", e)
    }
}

fun showScriptStatMenu(
    chatId: Long,
    messageId: Long,
    scriptId: ScriptStatId,
    bot: Bot
) {
    try {
        scriptStats.get(scriptId)?.let { stat ->
            stat.script?.let { script ->
                val gameSet = Json.decodeFromString<GameSet>(
                    File("${script.path}/${script.jsonPath}").readText()
                )
                val teamNames = gameSet.teamDisplayNames
                val roleNames = gameSet.roles.associate { it.name to it.displayName }
                bot.editMessageText(
                    ChatId.fromId(chatId),
                    messageId,
                    text = "Статистика игрока:\n\n" +
                            "Тип игры: ${script.name}\n" +
                            "Сыграно игр: ${stat.gamesPlayed}\n" +
                            "Выиграно: ${stat.wins}\n\n" +
                            "Статистика команд:\n" +
                            stat.teamStats.entries.joinToString("\n") {
                                teamNames.getOrDefault(it.key, it.key) + ": " + it.value
                            } + "\n\n" +
                            "Статистика ролей:\n" +
                            stat.roleStats.entries.joinToString("\n") {
                                roleNames.getOrDefault(it.key, it.key) + ": " + it.value
                            },
                    replyMarkup = inlineKeyboard {
                        row {
                            button(deleteMsgCommand, messageId)
                        }
                    }
                )
                return
            }
        }
    } catch (e: Exception) {
        log.error("Unable to show script stat menu", e)
    }
}

private fun getAlivePlayerDesc(game: Game): String {
    val desc = if (game.state == GameState.REVEAL) {
        val cons = game.connectionList
        val count = cons.size
        "Вживых: $count / $count\n\n" +
                "Игроки:\n" + cons.sortedBy { it.pos }.joinToString("\n") {
            "№" + it.pos + " " + it.name()
        }
    } else if (game.state == GameState.GAME) {
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
    return desc
}
