package org.example.game

import com.github.kotlintelegrambot.Bot
import com.github.kotlintelegrambot.entities.ChatId
import com.github.kotlintelegrambot.entities.ParseMode
import org.bson.types.ObjectId
import org.example.*
import org.example.lua.BlockAction
import org.example.lua.InfoAction
import org.example.lua.LuaInterface
import org.example.lua.NoneAction
import org.example.telegram.*
import org.example.telegram.showLobbyMenu
import org.example.telegram.showNightRoleMenu
import org.example.telegram.showPlayerMenu
import java.util.*

internal fun initGame(game: Game?, path: String, chatId: Long, messageId: Long, bot: Bot) {
    if (game != null) {
        updateSetup(path, game)
        val chat = ChatId.fromId(chatId)
        accounts.update(chatId) {
            state = AccountState.Host
        }
        bot.sendMessage(
            chat,
            "Игра создана. Ожидайте присоединения игроков.",
            replyMarkup = mafiaKeyboard(chatId)
        )
        val msgId = showLobbyMenu(chatId, messageId, game, bot, true)
        accounts.update(chatId) {
            menuMessageId = msgId
        }
    } else {
        error("Не удалось создать игру. Попробуйте еще раз.")
    }
}

fun startGame(
    chatId: Long,
    bot: Bot
) {
    try {
        games.find { hostId == chatId }.singleOrNull()?.let { game ->
            games.update(game.id) {
                playedAt = Date()
            }
            gameHistory.save(GameSummary(ObjectId(), game, connections.find { gameId == game.id }))
            accounts.update(chatId) {
                menuMessageId = -1L
            }

            val roleMap = getRoles(game)
            val pairs = pairings.find { gameId == game.id }
            val orderList = orders.find { gameId == game.id }
            val typeList = types.find { gameId == game.id }
            val mode = modes.find { gameId == game.id }.singleOrNull()?.mode ?: Mode.OPEN
            val town = Town(
                game.id,
                pairs.mapNotNull {
                    val con = connections.get(it.connectionId)
                    val role = roles.get(it.roleId)
                    if (con != null && role != null) {
                        Person(
                            con.pos,
                            con.name(),
                            role,
                            role.defaultTeam
                        )
                    } else {
                        null
                    }
                },
                orderList.sortedBy { it.pos }.map { it.type },
                typeList.associateBy { it.name },
                mode
            )
            towns[game.id] = town


            if (!checks.get(CheckOption.REVEAL_MENU)) {
                sendPlayerInfo(pairs, bot, chatId, game)
            }

            games.updateMany(
                { hostId == chatId },
                { state = GameState.Game }
            )
            bot.sendMessage(
                ChatId.fromId(chatId),
                "Игра начата\n" +
                        "Роли в игре:\n" +
                        roleMap.entries
                            .filter { it.value > 0 }
                            .sortedBy { it.key.index }
                            .joinToString("\n") { "- " + it.key.displayName },
                replyMarkup = mafiaKeyboard(chatId)
            )

            accounts.update(chatId) {
                menuMessageId = -1L
            }
            towns[game.id]?.let { town ->
                showDayMenu(town, chatId, -1L, bot, game)
            }
        }
    } catch (e: Exception) {
        log.error("Unable to start game for chatId: $chatId", e)
    }
}

internal fun stopGame(
    game: Game,
    chatId: Long,
    bot: Bot,
    gameMessageId: Long = -1L,
    popupMessageId: Long = -1L
) {
    games.update(game.id) {
        actual = false
    }
    sendPlayersToMenu(game, bot)
    sendHostToMenu(chatId, bot, gameMessageId, popupMessageId)
    try {
        deleteGame(game, bot)
    } catch (e: Exception) {
        log.error("Error during ending game ${game.name()}", e)
    }
}

internal fun stopGames(
    gameList: List<Game>,
    chatId: Long,
    bot: Bot,
    gameMessageId: Long = -1L,
    popupMessageId: Long = -1L
) {
    val ids = gameList.map { it.id }.toSet()
    games.updateMany({ id in ids }) {
        actual = false
    }
    sendHostToMenu(chatId, bot, gameMessageId, popupMessageId)
    gameList.forEach { game ->
        sendPlayersToMenu(game, bot)
        try {
            deleteGame(game, bot)
        } catch (e: Exception) {
            log.error("Error during ending game ${game.name()}", e)
        }
    }
}

internal fun deleteGame(game: Game, bot: Bot) {
    games.delete(game.id)
    connections.find { gameId == game.id }.forEach { accounts.update(it.playerId, resetAccount) }
    connections.deleteMany {
        gameId == game.id
    }
    game.messages.forEach {
        bot.deleteMessage(ChatId.fromId(it.chatId), it.messageId)
    }
    kicks.deleteMany { gameId == game.id }
    orders.deleteMany { gameId == game.id }
    pendings.deleteMany { gameId == game.id }
    setups.deleteMany { gameId == game.id }
    pairings.deleteMany { gameId == game.id }
    modes.deleteMany { gameId == game.id }
    roles.deleteMany { gameId == game.id }
    types.deleteMany { gameId == game.id }
    messageLinks.deleteMany { gameId == game.id }
    towns.remove(game.id)
    deleteGameTimers(bot, game.id)
}

private fun sendPlayersToMenu(game: Game, bot: Bot) {
    connections.find { gameId == game.id }.forEach {
        if (it.bot) {
            return@forEach
        }
        accounts.update(it.playerId, resetAccount)
        showMainMenu(
            it.playerId,
            "Ведущий завершил игру. Возвращаемся в меню.",
            bot,
            forceUpdate = true,
            silent = true
        )
    }
}

private fun sendHostToMenu(
    chatId: Long,
    bot: Bot,
    gameMessageId: Long,
    popupMessageId: Long
) {
    accounts.update(chatId, resetAccount)
    val chat = ChatId.fromId(chatId)
    bot.deleteMessage(chat, gameMessageId)
    bot.deleteMessage(chat, popupMessageId)
    showMainMenu(chatId, "Возвращаемся в главное меню.", bot, true)
}

internal fun deleteGameTimers(
    bot: Bot,
    gameId: GameId
) {
    timers.find { this.gameId == gameId }.forEach {
        deleteTimer(it, bot)
    }
}

internal fun hostSetPlayerNum(
    game: Game,
    connectionId: ObjectId?,
    pos: Int,
    messageId: Long,
    chatId: Long,
    bot: Bot
) {
    if (pos > 0 && connectionId != null) {
        connections.get(connectionId)?.let { con ->
            if (con.gameId == game.id) {
                connections.update(connectionId) {
                    this.pos = pos
                }
                accounts.get(con.playerId)?.let { acc ->
                    showPlayerMenu(acc.chatId, acc.menuMessageId, bot, connectionId, pos)
                }
            }
        }
    }
    games.update(game.id) {
        state = GameState.Connect
    }
    showLobbyMenu(chatId, messageId, game, bot)
}

internal fun setPlayerNum(
    game: Game,
    con: Connection,
    pos: Int,
    chatId: Long,
    bot: Bot
) {
    if (pos > 0) {
        connections.update(con.id) {
            this.pos = pos
        }
    }
    pendings.save(
        Pending(
            ObjectId(),
            game.hostId,
            game.id,
            Date(System.currentTimeMillis() + sendPendingAfterSec * 1000)
        )
    )
    val res = bot.sendMessage(
        ChatId.fromId(chatId),
        Const.Message.numSaved
    )
    if (res.isSuccess) {
        bombs.save(
            TimedMessage(
                ObjectId(),
                chatId,
                res.get().messageId,
                Date(System.currentTimeMillis() + deleteNumUpdateMsgAfterSec * 1000)
            )
        )
    }
}

internal fun joinGame(
    game: Game,
    account: Account,
    chatId: Long,
    messageId: Long,
    bot: Bot
) {
    val id = ObjectId()
    connections.save(
        Connection(
            id,
            game.id,
            chatId,
            account.name,
            if (account.userName.isNotBlank()) "@${account.userName}" else ""
        )
    )
    accounts.update(chatId) {
        state = AccountState.Lobby
        menuMessageId = -1L
    }
    pendings.save(Pending(ObjectId(), game.hostId, game.id))
    if (messageId != -1L) {
        bot.deleteMessage(ChatId.fromId(chatId), messageId)
    }
    bot.sendMessage(
        ChatId.fromId(chatId),
        "Подключение к игре выполнено.",
        replyMarkup = mafiaKeyboard(chatId)
    )
    val msgId = showPlayerMenu(chatId, -1L, bot, id)
    accounts.update(chatId) {
        menuMessageId = msgId
    }
}

internal fun desc(player: Person?, sep: String = ". ", icons: Boolean = true, hideRolesMode: Boolean = false) = if (player != null)
    "${player.pos}$sep" +
            (if (!icons) "" else if (player.protected) "⛑️" else if (player.alive) "" else "☠️") +
            (if (!icons) "" else if (player.fallCount > 0) numbers[player.fallCount % numbers.size] else "") +
            " ${player.name} " +
            if (hideRolesMode) "" else "(${player.roleData.displayName})"
else "Неизвестный игрок"

internal fun nightRoleDesc(wake: Wake): String {
    val players = wake.players.sortedWith(compareBy({ -it.roleData.priority }, { it.pos }))
    val alive = players.filter { it.alive }
    val action =
        alive.firstOrNull()?.roleData?.desc ?: "Все персонажи мертвы."
    val text = "Просыпаются: " + players.map { it.roleData }.distinctBy { it.name }.sortedBy { -it.priority }
        .joinToString(", ") { it.displayName } + "\n" +
            "Игроки: " + alive.joinToString(", ") { desc(it, " - ") } + "\n" +
            "Действие: " + action +
            if (alive.isNotEmpty()) "\n\nВыберите ${wake.type.choice} игроков:" else ""
    return text
}

internal fun KeyboardContext.RowContext.playerDayDesc(
    player: Person,
    messageId: Long,
    fallMode: Boolean
) {
    button(
        statusCommand named
                "Статус: " + if (player.protected) "Защищен" else if (player.alive) "Жив" else "Мертв",
        player.pos,
        messageId
    )
    if (player.alive) {
        button(killCommand, player.pos, messageId)
    } else {
        button(reviveCommand, player.pos, messageId)
    }
    if (fallMode) {
        button(
            fallCommand named "" + numbers[player.fallCount % numbers.size],
            player.pos,
            messageId
        )
    }
}

internal fun nightSelection(
    town: Town,
    num: Int,
    chatId: Long,
    bot: Bot,
    messageId: Long,
    roleId: RoleId
) {
    if (num in town.selections) {
        town.selections.remove(num)
    } else {
        town.selections.add(num)
    }
    if (town.night.size > town.index) {
        val wake = town.night[town.index]
        if (checks.get(CheckOption.CHECK_ROLE) && wake.actor()?.roleData?.id != roleId) {
            return
        }
        if (wake.type.choice <= town.selections.size) {
            val settings = accounts.get(chatId)?.settings
            if (settings?.confirmNightSelection == true) {
                showNightRoleMenu(town, chatId, bot, messageId)
            } else {
                executeNightAction(town, wake, bot, chatId, messageId)
            }
        } else {
            showNightRoleMenu(town, chatId, bot, messageId)
        }
    }
}

internal fun executeNightAction(
    town: Town,
    wake: Wake,
    bot: Bot,
    chatId: Long,
    messageId: Long
) {
    val players = town.selections.mapNotNull { town.playerMap[it] }
    val script = scripts[town.gameId]?.get(wake.players.first().roleData.name)
    val priority =
        wake.players.filter { it.alive }.maxOfOrNull { it.roleData.priority } ?: 1
    val actors =
        wake.players.filter { it.roleData.priority == priority && it.alive }
            .map { it.pos }
    if (script != null) {
        try {
            script.action(players, LuaInterface(actors, players, town)) { ret ->
                val actorsSet = actors.toSet()
                val text = ret.actions.map { res ->
                    val start =
                        res.desc() + " " + res.selection.joinToString {
                            desc(
                                it,
                                " - "
                            )
                        } + ": "
                    val text = if (res is InfoAction) {
                        val blocker =
                            town.actions.firstOrNull {
                                it is BlockAction
                                        && it.selection.map { person -> person.pos }.toSet()
                                    .intersect(actorsSet).isNotEmpty()
                            }
                        val result = res.text
                        town.actions.add(
                            InfoAction(
                                if (blocker == null) result else Const.Message.actionBlocked,
                                actors,
                                res.selection,
                                emptySet()
                            )
                        )
                        start + if (blocker == null) result else Const.Message.actionBlocked
                    } else {
                        if (res is NoneAction) {
                            return@map null
                        }
                        val list = mutableListOf(res)
                        var index = 0
                        while (index < list.size) {
                            val action = list[index]
                            action.selection.forEach {
                                try {
                                    val pos = it.pos
                                    val lua =
                                        scripts[town.gameId]?.get(town.playerMap[pos]?.roleData?.name)
                                    lua?.passive(
                                        action, LuaInterface(
                                            listOf(pos),
                                            wake.players,
                                            town,
                                            action.dependencies + action
                                        )
                                    ) { passive ->
                                        for (result in passive.actions) {
                                            if (result !is NoneAction) {
                                                list.add(result)
                                            }
                                        }
                                    }
                                } catch (e: Exception) {
                                    log.error(
                                        "Unable to process passive action for person: $it, param: $action",
                                        e
                                    )
                                }
                            }
                            index++
                        }
                        town.actions.addAll(list)
                        start + Const.Message.actionRegistered
                    }
                    return@map text
                }.filterNotNull().joinToString("\n")
                town.index++
                bot.editMessageText(
                    ChatId.fromId(chatId),
                    messageId,
                    text = if (ret.actions.isNotEmpty()) text else Const.Message.roleDidNothing,
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
        } catch (e: Exception) {
            log.error("Unable to process night action, script: $script, targets: $players", e)
        }
    }
}

internal fun skipNightRole(town: Town, chatId: Long, messageId: Long, bot: Bot) {
    town.index++
    town.selections.clear()
    showNightRoleMenu(town, chatId, bot, messageId)
}

internal fun getRoles(
    game: Game
): MutableMap<Role, Int> {
    val roleMap = mutableMapOf<Role, Int>()
    setups.find { gameId == game.id }.forEach { setup ->
        roles.get(setup.roleId)?.let { role ->
            roleMap[role] = setup.count
        }
    }
    return roleMap
}

internal fun getPlayerDescs(
    connection: Connection
): List<String> {
    val conMap = mutableMapOf<ObjectId, String>()
    val wakeMap = mutableMapOf<String, MutableList<Pairing>>()
    games.get(connection.gameId)?.let { game ->
        val pairs = mutableMapOf<ObjectId, Pairing>()
        pairings.find { gameId == game.id }.forEach {
            pairs[it.connectionId] = it
        }
        for (pair in pairs.values) {
            val role = pair.role
            val team = role?.defaultTeam ?: "none"
            wakeMap.getOrPut(team) { mutableListOf() }.add(pair)
            conMap[pair.connectionId] = team
        }

        pairs[connection.id]?.let { pair ->
            val names = checks.get(CheckOption.NAMES)
            val cover = checks.get(CheckOption.COVER)
            val players =
                if (!names || !conMap.containsKey(pair.connectionId) || conMap[pair.connectionId] in notKnowingTeams) {
                    emptyList()
                } else {
                    wakeMap[conMap[pair.connectionId]]
                        ?.map { connections.get(it.connectionId) to roles.get(it.roleId) }
                        ?.sortedBy { it.first?.pos ?: -1 }
                        ?.map {
                            "№${it.first!!.pos} - " + it.first!!.name() + " - " + it.second!!.teamName(cover)
                        }
                        ?.toList()
                        ?: emptyList()
                }
            return players
        }
    }
    return emptyList()
}

internal fun roleDesc(roleMap: MutableMap<Role, Int>): String {
    var roleDesc = "<b>Роли в игре</b>:\n\n"
    for (entry in roleMap.entries.sortedBy { it.key.index }) {
        if (entry.value > 0) {
            roleDesc += "<b>" + entry.key.displayName + "</b>\nКоличество: ${entry.value}\nОписание: ${entry.key.desc}\n\n"
        }
    }
    roleDesc = roleDesc.dropLast(2)
    return roleDesc
}

internal fun getRoleDesc(role: Role) = "Ваша роль: <span class=\"tg-spoiler\">${
    (role.displayName + " ").padEnd(
        roleNameLen,
        '_'
    )
}</span>\n" +
        "Описание: <span class=\"tg-spoiler\">${
            role.desc.padEnd(
                roleDescLen,
                '_'
            )
        }</span>"

internal fun sendPlayerInfo(
    pairs: List<Pairing>,
    bot: Bot,
    chatId: Long,
    game: Game
) {
    for (it in pairs) {
        val con = connections.get(it.connectionId)
        val role = roles.get(it.roleId)
        if (con != null && role != null && !con.bot) {
            /*bot.sendMessage(
                                                    ChatId.fromId(it.connection.player),
                                                    roleDesc
                                                )*/
            try {
                val chat = ChatId.fromId(con.playerId)
                bot.sendMessage(
                    chat,
                    "Ведущий начал игру",
                    replyMarkup = mafiaKeyboard(chatId)
                )
                val res = bot.sendMessage(
                    chat,
                    getRoleDesc(role),
                    parseMode = ParseMode.HTML,
                )
                if (res.isSuccess) {
                    val msgId = res.get().messageId
                    bot.editMessageReplyMarkup(
                        chat,
                        msgId,
                        replyMarkup = inlineKeyboard {
                            button(gameInfoCommand, role.id, msgId)
                        }
                    )
                    messageLinks.save(MessageLink(ObjectId(), game.id, chatId, msgId))
                }
            } catch (e: Exception) {
                log.error("Unable to send player info message to $con, role: $role", e)
            }
        }
    }
}
