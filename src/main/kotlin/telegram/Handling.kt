package org.example.telegram

import com.github.kotlintelegrambot.Bot
import com.github.kotlintelegrambot.entities.ChatId
import com.github.kotlintelegrambot.entities.ParseMode
import org.bson.types.ObjectId
import org.example.*
import org.example.Timer
import org.example.game.*
import org.example.game.hostSetPlayerNum
import org.example.game.initGame
import org.example.game.stopGames
import org.example.lua.Script
import org.example.lua.prepareScripts
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.*
import kotlin.math.max

object MafiaHandler {
    val textHandler = TextHandler(errorProcessor = { bot.error(chatId) }) {
        val account = accounts.get(chatId)
        block(account == null) {
            any {
                initAccount(username, chatId, bot)
            }
        }

        adminCommands()

        simple(editSettingsCommand) {
            bot.deleteMessage(ChatId.fromId(chatId), messageId ?: -1L)
            hostSettings.get(chatId)?.let {
                showSettingsMenu(it, chatId, -1L, -1L, bot)
            }
        }

        when (account!!.state) {
            AccountState.Init -> {
                initCommands()
            }

            AccountState.Menu -> {
                menuCommands(account)
            }

            AccountState.Admin -> {
                hostSetupCommands()
            }

            AccountState.Host -> {
                hostCommands(account)
            }


            AccountState.Lobby -> {
                lobbyCommands()
            }

            else -> bot.error(chatId)
        }
    }

    val queryHandler = QueryHandler {
        val account = accounts.get(chatId)
        if (account == null) {
            initAccount(username, chatId, bot)
            return@QueryHandler
        }

        parametrized(blankCommand) {

        }
        parametrized(deleteMsgCommand) {
            bot.deleteMessage(ChatId.fromId(chatId), long(0))
        }

        val chat = ChatId.fromId(chatId)
        parametrized(acceptNameCommand) {
            accounts.update(chatId) {
                name = str(0)
                state = AccountState.Menu
            }
            showMainMenu(chatId, "Добро пожаловать, ${str(0)}", bot)
            bot.deleteMessage(chat, long(1))
            bot.deleteMessage(chat, long(2))
        }
        parametrized(cancelName) {
            bot.deleteMessage(chat, long(0))
            bot.deleteMessage(chat, long(1))
        }

        if (account.state == AccountState.Init) {
            initAccount(username, chatId, bot)
            return@QueryHandler
        }

        menuQueries()

        connectionManagingQueries()
        parametrized(roleCommand) {
            roles.get(id(0))?.let { role ->
                sendClosable("Название: ${role.displayName}\nОписание: ${role.desc}")
            }
        }
        adminQueries()
        hostQueries(towns)
        playerQueries()
    }

    private suspend fun ContainerBlock.BasicContext.lobbyCommands() {
        connections.find { playerId == chatId }.singleOrNull()?.let { con ->
            simple(leaveGameCommand, menuCommand, startCommand, leaveGameLegacyCommand) {
                val chat = ChatId.fromId(chatId)
                bot.deleteMessage(chat, messageId ?: -1L)
                val res = bot.sendMessage(
                    chat,
                    "Вы уверены, что хотите покинуть игру?"
                )
                if (res.isSuccess) {
                    val msgId = res.get().messageId
                    bot.editMessageReplyMarkup(
                        chat,
                        msgId,
                        replyMarkup = inlineKeyboard {
                            row {
                                // todo replace -1L with messageId
                                button(acceptLeaveCommand, -1L, msgId)
                                button(deleteMsgCommand named "Нет", msgId)
                            }
                        }
                    )
                }
            }
            any {
                games.get(con.gameId)?.let { game ->
                    if (game.state == GameState.Game) {
                        val chat = ChatId.fromId(game.hostId)
                        val res = bot.sendMessage(
                            chat,
                            "Игрок ${con.pos} - ${con.name()} пишет:\n" + query,
                        )
                        if (res.isSuccess) {
                            val msgId = res.get().messageId
                            bot.editMessageReplyMarkup(
                                chat,
                                msgId,
                                replyMarkup = inlineKeyboard {
                                    button(deleteMsgCommand, msgId)
                                }
                            )
                        }
                        return@any
                    } else {
                        val num: Int

                        try {
                            num = query.toInt()
                        } catch (e: NumberFormatException) {
                            bot.error(chatId)
                            return@any
                        }

                        val value = if (con.pos == Int.MAX_VALUE) num else -1
                        bot.deleteMessage(ChatId.fromId(chatId), messageId ?: -1L)
                        setPlayerNum(game, con, value, chatId, bot)
                        return@any
                    }
                }
                bot.error(chatId)
            }
        }
    }

    private suspend fun ContainerBlock.BasicContext.hostCommands(account: Account) {
        simple(rehostCommand, restartGameCommand, hostCommand, restartGameLegacyCommand) {
            bot.deleteMessage(ChatId.fromId(chatId), messageId ?: -1L)
            val chat = ChatId.fromId(chatId)
            val res = bot.sendMessage(
                chat,
                "Вы уверены, что хотите перезапустить игру?"
            )
            if (res.isSuccess) {
                val msgId = res.get().messageId
                bot.editMessageReplyMarkup(
                    chat,
                    msgId,
                    replyMarkup = inlineKeyboard {
                        row {
                            button(acceptRehostCommand, msgId)
                            button(deleteMsgCommand named "Нет", msgId)
                        }
                    }
                )
            }
        }
        simple(stopGameCommand, menuCommand, stopGameLegacyCommand) {
            val chat = ChatId.fromId(chatId)
            bot.deleteMessage(chat, messageId ?: -1L)
            val res = bot.sendMessage(
                chat,
                "Вы уверены, что хотите завершить игру?"
            )
            if (res.isSuccess) {
                val msgId = res.get().messageId
                bot.editMessageReplyMarkup(
                    chat,
                    msgId,
                    replyMarkup = inlineKeyboard {
                        row {
                            button(acceptStopCommand, accounts.get(chatId)?.menuMessageId ?: -1L, msgId)
                            button(deleteMsgCommand named "Нет", msgId)
                        }
                    }
                )
            }
        }
        simple(startCommand) {
            stopGames(games.find { hostId == chatId }, chatId, bot)
        }
        any {
            games.find { hostId == chatId }.singleOrNull()?.let { game ->
                if (game.state == GameState.Dummy) {
                    connections.save(
                        Connection(
                            ObjectId(),
                            game.id,
                            -1,
                            query,
                            "оффлайн",
                            true
                        )
                    )
                    games.updateMany(
                        { hostId == chatId },
                        { state = GameState.Connect }
                    )
                    showLobbyMenu(chatId, account.menuMessageId, game, bot)
                    bot.deleteMessage(ChatId.fromId(chatId), messageId ?: -1L)
                    return@any
                } else if (game.state == GameState.Rename && account.connectionId != null) {
                    connections.get(account.connectionId!!)?.let { con ->
                        if (con.gameId == game.id) {
                            val newName = query
                            connections.update(con.id) {
                                name = newName
                            }
                            games.update(game.id) {
                                state = GameState.Connect
                            }
                            bot.deleteMessage(ChatId.fromId(chatId), messageId ?: -1L)
                            /*bot.sendMessage(
                                        ChatId.fromId(chatId),
                                        "Имя игрока " + (if (con.pos < Int.MAX_VALUE) "номер ${con.pos} " else "") +
                                                "изменено с ${con.name} на $newName.",
                                    )*/
                            showLobbyMenu(chatId, account.menuMessageId, game, bot)
                        }
                        return@any
                    }
                } else if (game.state == GameState.Num) {
                    val num = try {
                        query.toInt()
                    } catch (e: NumberFormatException) {
                        bot.error(chatId)
                        return@any
                    }
                    bot.deleteMessage(ChatId.fromId(chatId), messageId ?: -1L)
                    if (num > 0) {
                        hostSetPlayerNum(game, account.connectionId, num, account.menuMessageId, chatId, bot)
                    }
                }
            }
        }
    }

    private suspend fun ContainerBlock.BasicContext.hostSetupCommands() {
        any {
            val adminMenu = adminContexts.get(chatId)
            if (adminMenu?.state == AdminState.HOST_TIME || adminMenu?.state == AdminState.HOST_GAMES) {
                bot.deleteMessage(ChatId.fromId(chatId), adminMenu.descId)
                val num: Int
                try {
                    num = query.toInt()
                } catch (e: NumberFormatException) {
                    bot.error(chatId, "Не удалось распознать число")
                    return@any
                }

                if (num > 0) {
                    val editFilter: HostInfo.() -> Boolean = { this.chatId == adminMenu.editId }
                    val hostInfo = hostInfos.find(editFilter).singleOrNull()
                    if (hostInfo != null) {
                        hostInfos.update(hostInfo.chatId) {
                            if (adminMenu.state == AdminState.HOST_TIME) {
                                timeLimit = true
                                until = Date.from(Instant.now().plus(num.toLong(), ChronoUnit.DAYS))
                            } else if (adminMenu.state == AdminState.HOST_GAMES) {
                                gameLimit = true
                                left = num
                            }
                        }

                        showListHostSettingsMenu(chatId, adminMenu.messageId, bot)
                        messageId?.let { id -> bot.deleteMessage(ChatId.fromId(chatId), id) }
                        adminContexts.update(chatId) {
                            state = AdminState.NONE
                            editId = -1L
                            messageId = -1L
                            descId = -1L
                        }
                    }

                    return@any
                } else {
                    bot.error(chatId, "Число должно быть положительным")
                }
            }
            bot.error(chatId)
        }
    }

    private suspend fun ContainerBlock.BasicContext.menuCommands(account: Account) {
        simple(hostCommand, startGameCommand, startGameLegacyCommand) {
            val chat = ChatId.fromId(chatId)
            bot.deleteMessage(chat, account.menuMessageId)
            bot.deleteMessage(chat, messageId ?: -1L)
            if (!canHost(chatId)) {
                createHostRequest(chatId)
                bot.error(chatId, "Возможность создания игры недоступна для вашего аккаунта.")
                return@simple
            }

            try {
                val id = games.save(Game(ObjectId(), chatId))
                val game = games.get(id)
                initGame(game, Config().path, chatId, -1L, bot)
            } catch (e: Exception) {
                log.error("Unable to host game for ${account.fullName()}", e)
            }
        }
        simple(menuCommand, startCommand) {
            if (messageId != null) {
                bot.deleteMessage(ChatId.fromId(chatId), messageId)
                showMainMenu(chatId, "Открываем главное меню", bot)
            }
        }
        simple(changeNameCommand) {
            bot.deleteMessage(ChatId.fromId(chatId), messageId ?: -1L)
            initAccount(username, chatId, bot)
        }
    }

    private suspend fun ContainerBlock.BasicContext.initCommands() {
        any {
            val chat = ChatId.fromId(chatId)
            val name = query.replace("\n", " ")
            val res = bot.sendMessage(
                chat,
                "Введено имя: <b>$name</b>\nВы хотите его установить?",
                parseMode = ParseMode.HTML
            )

            if (res.isSuccess) {
                val msgId = res.get().messageId
                bot.editMessageReplyMarkup(
                    chat,
                    msgId,
                    replyMarkup = inlineKeyboard {
                        row {
                            button(acceptNameCommand, name, msgId, messageId ?: -1)
                            button(cancelName, msgId, messageId ?: -1)
                        }
                    }
                )
            }
        }
    }

    private suspend fun ContainerBlock.BasicContext.adminCommands() {
        if (isAdmin(chatId)) {
            simple(adCommand) {
                val chat = ChatId.fromId(chatId)
                bot.deleteMessage(chat, messageId ?: -1L)
                showAdMenu(chat, bot)
            }
            simple(adNewCommand) {
                val chat = ChatId.fromId(chatId)
                val res = bot.sendMessage(
                    chat,
                    Const.Message.enterAdText
                )
                if (res.isSuccess) {
                    val msgId = res.get().messageId
                    bot.editMessageReplyMarkup(
                        chat,
                        msgId,
                        replyMarkup = inlineKeyboard {
                            button(closePopupCommand, chatId)
                        }
                    )
                    adPopups.save(AdPopup(ObjectId(), chatId, msgId))
                }
            }
            simple(adminCommand, adminPanelCommand, adminPanelLegacyCommand) {
                if (messageId != null) {
                    bot.deleteMessage(ChatId.fromId(chatId), messageId)
                }
                val res = bot.sendMessage(
                    ChatId.fromId(chatId),
                    "Меню администратора:"
                )
                if (res.isSuccess) {
                    val messageId = res.get().messageId
                    showAdmin(chatId, messageId, bot)
                }
            }
            adPopups.get(chatId)?.let {
                any {
                    val chat = ChatId.fromId(chatId)
                    bot.deleteMessage(chat, messageId ?: -1L)
                    bot.deleteMessage(chat, it.messageId)
                    ads.save(Message(ObjectId(), query))
                    adPopups.delete(chatId)
                }
            }
        }
    }

    private suspend fun ContainerBlock.ParametrizedContext.menuQueries() {
        parametrized(joinCommand) {
            val game = games.get(id(0))
            val messageId = long(1)
            if (game == null) {
                sendClosable(
                    "Не удалось подключиться к игре. Обновляем список игр доступных для подключения."
                )
                showGames(chatId, messageId, bot)
                return@parametrized
            }

            if (game.state in inGameStates) {
                sendClosable(
                    "Не удалось подключиться к игре. Ведущий уже начал игру."
                )
                showGames(chatId, messageId, bot)
                return@parametrized
            }

            withAccount(chatId) { account ->
                if (account.state != AccountState.Menu) {
                    sendClosable(
                        "Не удалось подключиться к игре. Вернитесь в меню прежде чем подключаться к играм."
                    )
                    return@withAccount
                }

                val kick = kicks.find { gameId == game.id && player == chatId }.singleOrNull()
                if (kick != null) {
                    sendClosable(
                        "Не удалось подключиться к игре. Ведущий исключил вас из игры."
                    )
                    return@withAccount
                }

                if (messageId != -1L) {
                    bot.deleteMessage(ChatId.fromId(chatId), messageId)
                }
                joinGame(game, account, chatId, messageId, bot)
            }
        }
        parametrized(updateCommand) {
            showGames(chatId, long(0), bot)
        }
    }

    private suspend fun ContainerBlock.ParametrizedContext.connectionManagingQueries() {
        /** with Connection **/
        block({ notNull { if (isId(0)) connections.get(id(0)) else null } }) { con ->
            /** with Game **/
            block({ notNull { games.get(con.gameId) } }) { game ->
                parametrized(playerNumCommand) {
                    connections.update(id(0)) {
                        pos = int(1).let { if (it > 0) it else Int.MAX_VALUE }
                    }
                    pendings.save(
                        Pending(
                            ObjectId(),
                            game.hostId,
                            game.id,
                            Date(System.currentTimeMillis() + sendPendingAfterSec * 1000L)
                        )
                    )
                    numpadKeyboardLambda(
                        chatId,
                        long(2),
                        bot,
                        "Номер игрока",
                        playerNumCommand,
                        playerConfirmCommand,
                        mainMenuCommand,
                        id(0),
                        int(1)
                    )
                }
                parametrized(playerConfirmCommand) {
                    setPlayerNum(game, con, int(1), chatId, bot)
                }

                /** is game host **/
                block(game.hostId == chatId) {
                    parametrized(newHostCommand) {
                        games.update(game.id) {
                            state = GameState.ChangeHost
                        }
                        inlineKeyboardLambda(
                            chatId,
                            long(1),
                            bot,
                            {
                                button(blankCommand named "Ожидаем ответа")
                                button(stopRehostingCommand, long(1))
                            }
                        )
                        val spacedHostName = " " + (accounts.get(chatId)?.fullName() ?: " ") + ""
                        inlineKeyboardLambdaSendMessage(
                            chatId,
                            bot,
                            "Ведущий${spacedHostName} предлагает вам стать новым ведущим игры.\nПринять?",
                            { newMessageId ->
                                row {
                                    button(acceptHostingCommand, game.id, chatId, newMessageId)
                                    button(declineHostingCommand, game.id, chatId, newMessageId)
                                }
                            },
                            { newMessageId ->
                                rehosts.save(Rehost(ObjectId(), game.id, con.playerId, newMessageId))
                            }
                        )
                    }

                    parametrized(detailsCommand) {
                        bot.editMessageReplyMarkup(
                            ChatId.fromId(chatId),
                            long(1),
                            replyMarkup = inlineKeyboard {
                                row {
                                    button(blankCommand named con.name())
                                    button(
                                        positionCommand named (if (con.pos < Int.MAX_VALUE) con.pos.toString() else "Указать номер"),
                                        con.id,
                                        0,
                                        messageId ?: -1L
                                    )
                                }
                                row {
                                    button(renameCommand, id(0), long(1))
                                    if (!con.bot) {
                                        button(handCommand, id(0))
                                    }
                                    button(kickCommand, id(0), long(1))
                                }
                                button(menuLobbyCommand, long(1))
                            }
                        )
                    }
                    parametrized(renameCommand) {
                        if (game.state != GameState.Connect) {
                            return@parametrized
                        }
                        games.update(game.id) {
                            state = GameState.Rename
                        }
                        accounts.update(chatId) {
                            connectionId = con.id
                        }

                        bot.editMessageReplyMarkup(
                            ChatId.fromId(chatId),
                            long(1),
                            replyMarkup = inlineKeyboard {
                                row { button(command("Введите новое имя для игрока ${con.name()}", "default")) }
                                row { button(nameCancelCommand, long(1)) }
                            }
                        )
                    }
                    parametrized(positionCommand) {
                        accounts.update(chatId) {
                            connectionId = con.id
                        }
                        games.update(game.id) {
                            state = GameState.Num
                        }
                        bot.editMessageReplyMarkup(
                            ChatId.fromId(chatId),
                            long(2),
                            replyMarkup = numpadKeyboard(
                                "Введите номер для игрока ${con.name()}",
                                positionCommand,
                                posSetCommand,
                                hostBackCommand,
                                id(0),
                                int(1),
                                long(2)
                            )
                        )
                    }
                    parametrized(handCommand) {
                        if (!con.bot) {
                            bot.sendMessage(
                                ChatId.fromId(con.playerId),
                                "Ведущий просит вас поднять руку"
                            )
                        }
                    }

                    parametrized(kickCommand) {
                        connections.delete(con.id)
                        if (!con.bot) {
                            accounts.update(con.playerId) {
                                state = AccountState.Menu
                            }
                            showMainMenu(
                                con.playerId,
                                "Ведущий исключил вас из игры. Возвращаемся в главное меню.",
                                bot
                            )
                            kicks.save(
                                Kick(
                                    ObjectId(),
                                    con.gameId,
                                    con.playerId
                                )
                            )
                        }
                        showLobbyMenu(chatId, long(1), game, bot)
                    }
                    parametrized(posSetCommand) {
                        hostSetPlayerNum(game, id(0), int(1), long(2), chatId, bot)
                    }
                    parametrized(markBotCommand) {
                        connections.update(id(0)) {
                            notified = !notified
                        }
                        showRevealMenu(game, bot, chatId, long(1))
                    }
                }
            }
        }
    }

    private suspend fun ContainerBlock.ParametrizedContext.adminQueries() {
        if (isAdmin(chatId)) {
            parametrized(closePopupCommand) {
                adPopups.get(long(0))?.let { popup ->
                    bot.deleteMessage(ChatId.fromId(popup.chatId), popup.messageId)
                    adPopups.delete(long(0))
                }
            }
            parametrized(advertCommand) {
                showAdMenu(ChatId.fromId(chatId), bot)
            }
            parametrized(listActiveGamesCommand) {
                showActiveGamesMenu(chatId, long(0), bot, int(1))
            }
            parametrized(listRecentGamesCommand) {
                showRecentGamesMenu(chatId, long(0), bot, int(1))
            }
            parametrized(updateCheckCommand) {
                updateCheck(str(0))
                showAdmin(chatId, long(1), bot)
            }
            parametrized(hostRequestCommand) {
                showHostRequests(long(0), chatId, bot, int(1))
            }
            parametrized(listHostSettingsCommand) {
                showListHostSettingsMenu(chatId, long(0), bot, int(1))
            }
            parametrized(adminSettingsCommand) {
                showAdminListMenu(chatId, long(0), bot, int(1))
            }
            parametrized(timeLimitOnCommand) {
                val res = bot.sendMessage(ChatId.fromId(chatId), "Введите срок действия разрешения в днях:")
                if (res.isSuccess) {
                    val desc = res.get().messageId
                    createAdminContext(desc, AdminState.HOST_TIME)
                    bot.deleteMessage(ChatId.fromId(chatId), long(1))
                }
            }
            parametrized(timeLimitOffCommand) {
                hostInfos.update(long(0)) { timeLimit = false }
                showChosenHostSettingsMenu(chatId, long(1), bot, long(0))
            }
            parametrized(gameLimitOnCommand) {
                val res = bot.sendMessage(ChatId.fromId(chatId), "Введите количество разрешенных игр:")
                if (res.isSuccess) {
                    val desc = res.get().messageId
                    createAdminContext(desc, AdminState.HOST_GAMES)
                    bot.deleteMessage(ChatId.fromId(chatId), long(1))
                }
            }
            parametrized(gameLimitOffCommand) {
                hostInfos.update(long(0)) { gameLimit = false }
                showChosenHostSettingsMenu(chatId, long(1), bot, long(0))
            }
            parametrized(shareCommand) {
                hostInfos.get(long(0))?.let {
                    hostInfos.update(long(0)) { canShare = !it.canShare }
                    showChosenHostSettingsMenu(chatId, long(1), bot, long(0))
                }
            }
            parametrized(canReassignCommand) {
                hostInfos.get(long(0))?.let {
                    hostInfos.update(long(0)) { canReassign = !it.canReassign }
                    showChosenHostSettingsMenu(chatId, long(1), bot, long(0))
                }
            }
            parametrized(deleteHostCommand) {
                hostInfos.delete(long(0))
                showListHostSettingsMenu(chatId, long(1), bot, int(2))
            }
            parametrized(promoteHostCommand) {
                accounts.get(long(0))?.let {
                    val chat = ChatId.fromId(chatId)
                    val res = bot.sendMessage(chat, "Вы уверены, что хотите сделать ${it.fullName()} администратором?")
                    if (res.isSuccess) {
                        val msgId = res.get().messageId
                        bot.editMessageReplyMarkup(
                            chat,
                            msgId,
                            replyMarkup = inlineKeyboard {
                                row {
                                    button(confirmPromoteCommand, long(0), long(1), msgId)
                                    button(deleteMsgCommand named "Нет", msgId)
                                }
                            }
                        )
                    }
                }
            }
            parametrized(confirmPromoteCommand) {
                bot.deleteMessage(ChatId.fromId(chatId), long(1))
                bot.deleteMessage(ChatId.fromId(chatId), long(2))
                admins.save(UserId(ObjectId(), long(0)))
                showChosenHostSettingsMenu(chatId, -1L, bot, long(0))
            }
            parametrized(allowHostCommand) {
                hostInfos.save(HostInfo(ObjectId(), long(0)))
                hostRequests.delete(long(0))
                if (accounts.get(long(0))?.state == AccountState.Menu) {
                    bot.sendMessage(
                        ChatId.fromId(long(0)),
                        "Вашему аккаунту предоставлена возможность вести игры",
                        replyMarkup = mafiaKeyboard(long(0))
                    )
                }
                showHostRequests(long(1), chatId, bot)
            }
            parametrized(denyHostCommand) {
                hostRequests.delete(long(0))
                showHostRequests(long(1), chatId, bot)
            }
            parametrized(removeAdminCommand) {
                admins.delete(long(0))
                showAdminListMenu(chatId, long(1), bot, int(2))
            }
            parametrized(chooseHostOptionsCommand) {
                showChosenHostOptionsMenu(chatId, long(0), bot, long(1))
            }
            parametrized(changeHostOptionsCommand) {
                hostSettings.update(long(1)) { HostOptions.valueOf(str(2)).update(this) }
                showChosenHostOptionsMenu(chatId, long(0), bot, long(1))
            }
            parametrized(chooseHostSettingsCommand) {
                showChosenHostSettingsMenu(chatId, long(0), bot, long(1))
            }
            parametrized(adminBackCommand) {
                showAdmin(chatId, long(0), bot)
                accounts.update(chatId) {
                    state = AccountState.Menu
                }
                adminContexts.delete(chatId)
            }
            parametrized(gamesSettingsCommand) {
                showGameStatusMenu(chatId, long(0), bot, int(1))
            }
            parametrized(listHostOptionsCommand) {
                showListHostOptionsMenu(chatId, long(0), bot, int(1))
            }
            parametrized(terminateGameCommand) {
                games.get(id(0))?.let { game ->
                    val chat = ChatId.fromId(chatId)
                    val res = bot.sendMessage(
                        chat,
                        "Игра ${game.name()} будет завершена. Все игроки и ведущий отправлены в меню.\nВы уверены, что хотите завершить игру?",
                    )
                    if (res.isSuccess) {
                        val msgId = res.get().messageId
                        bot.editMessageReplyMarkup(
                            chat,
                            msgId,
                            replyMarkup = inlineKeyboard {
                                row {
                                    button(confirmTerminateCommand, id(0), msgId, long(1))
                                    button(deleteMsgCommand named "Нет", msgId)
                                }
                            }
                        )
                    }
                }
            }
            parametrized(confirmTerminateCommand) {
                val chat = ChatId.fromId(chatId)
                bot.deleteMessage(chat, long(1))
                bot.deleteMessage(chat, long(2))
                games.get(id(0))?.let { game ->
                    stopGame(game, game.hostId, bot)
                }
            }
            parametrized(sendAdCommand) {
                val game = games.get(id(0))
                if (game != null) {
                    showAd(game, connections.find { gameId == game.id }, bot, long(1), chatId)
                } else {
                    gameHistory.find { this.game.id == id(0) }.lastOrNull()?.let {
                        showAd(it.game, it.connections, bot, long(1), chatId)
                    }
                }
            }
            parametrized(sendAdHistoryCommand) {
                gameHistory.get(id(0))?.let {
                    showAd(it.game, it.connections, bot, long(1), chatId)
                }
            }
            parametrized(adSelectCommand) {
                ads.get(id(0))?.let { ad ->
                    adTargets.get(id(1))?.let { target ->
                        adTargets.delete(id(1))
                        val chat = ChatId.fromId(chatId)
                        target.messages.forEach {
                            bot.deleteMessage(chat, it)
                        }
                        selectAd(target.game, target.connections, bot, ad)
                    }
                }
            }
            parametrized(adClearCommand) {
                adTargets.get(id(0))?.let { target ->
                    adTargets.delete(id(0))
                    val chat = ChatId.fromId(chatId)
                    target.messages.forEach {
                        bot.deleteMessage(chat, it)
                    }
                }
            }
        }
    }

    private suspend fun ContainerBlock.ParametrizedContext.hostQueries(towns: MutableMap<GameId, Town>) {
        parametrized(acceptStopCommand) {
            stopGames(games.find { hostId == chatId }, chatId, bot, long(0), long(1))
        }

        /** with Game of this host **/
        block({ notNull { games.find { hostId == chatId }.singleOrNull() } }) { game ->
            parametrized(menuKickCommand) {
                showKickMenu(game, long(0), bot, chatId, int(1))
            }
            parametrized(changeHostCommand) {
                games.update(game.id) {
                    state = GameState.ChangeHost
                }
                fun name(connection: Connection) =
                    (if (connection.pos > 0 && connection.pos != Int.MAX_VALUE) connection.pos.toString() + ". " else "") + connection.name()
                bot.editMessageReplyMarkup(
                    ChatId.fromId(chatId),
                    long(0),
                    replyMarkup = inlineKeyboard {
                        button(blankCommand named "Выбор нового ведушего")
                        connections.find { gameId == game.id }.sortedBy { it.pos }.forEach { con ->
                            button(newHostCommand named name(con), con.id, long(0))
                        }
                        button(hostBackCommand, long(0))
                    }
                )
            }
            parametrized(stopRehostingCommand) {
                rehosts.get(game.id)?.let {
                    bot.deleteMessage(ChatId.fromId(it.hostId), it.messageId)
                    games.update(game.id) {
                        state = GameState.Connect
                    }
                    rehosts.delete(it.gameId)
                    showLobbyMenu(chatId, game.host?.menuMessageId ?: -1L, game, bot)
                }
            }
            parametrized(resetNumsCommand) {
                val chat = ChatId.fromId(chatId)
                val res = bot.sendMessage(
                    chat,
                    "Вы уверены, что хотите сбросить номера игроков?",
                )
                if (res.isSuccess) {
                    val msgId = res.get().messageId
                    bot.editMessageReplyMarkup(
                        chat,
                        msgId,
                        replyMarkup = inlineKeyboard {
                            row {
                                button(confirmResetCommand, long(0), msgId)
                                button(deleteMsgCommand named "Нет", msgId)
                            }
                        }
                    )
                }
            }
            parametrized(confirmResetCommand) {
                bot.deleteMessage(ChatId.fromId(chatId), long(1))
                connections.find { gameId == game.id }.forEach { con ->
                    connections.update(con.id) {
                        pos = Int.MAX_VALUE
                    }
                    if (!con.bot) {
                        accounts.get(con.playerId)?.let { acc ->
                            bot.deleteMessage(ChatId.fromId(acc.chatId), acc.menuMessageId)
                            val msgId = showPlayerMenu(acc.chatId, -1L, bot, con.id)
                            accounts.update(con.playerId) {
                                menuMessageId = msgId
                            }
                        }
                    }
                }
                accounts.get(chatId)?.let {
                    showLobbyMenu(chatId, it.menuMessageId, game, bot)
                }
            }
            parametrized(hostBackCommand, menuLobbyCommand) {
                games.update(game.id) {
                    state = GameState.Connect
                }
                showLobbyMenu(chatId, long(0), game, bot)
            }
            parametrized(toggleHideRolesModePreviewCommand) {
                hostSettings.update(chatId, HostOptions.HideRolesMode.update)
                showPreview(bot, chatId, long(0), game)
            }
            parametrized(menuRolesCommand) {
                games.update(game.id) {
                    state = GameState.Roles
                }
                if (setups.find { gameId == game.id && count > 0 }.isEmpty()) {
                    setups.deleteMany { gameId == game.id }
                    roles.find { gameId == game.id }.forEach {
                        setups.save(Setup(ObjectId(), it.id, game.id, it.index))
                    }
                }
                showRoles(chatId, long(0), bot, game)
            }
            parametrized(menuPreviewCommand) {
                games.update(game.id) {
                    state = GameState.Preview
                }
                reassignments.delete(game.id)
                showPreview(bot, chatId, long(0), game)
            }
            parametrized(gameCommand) {
                val cons = connections.find { gameId == game.id }
                val noNum = cons.filter { it.pos == Int.MAX_VALUE }
                if (noNum.isNotEmpty()) {
                    sendClosable("Невозможно начать игру:\n" + noNum.joinToString("\n") { "Не указан номер для игрока ${it.name()}" })
                    return@parametrized
                }

                val notPositive = cons.filter { it.pos < 1 }
                if (notPositive.isNotEmpty()) {
                    sendClosable("Невозможно начать игру:\n" + notPositive.joinToString("\n") { "Номер игрока ${it.name()} должен быть позитвным, обнаружен номер ${it.pos}" })
                    return@parametrized
                }

                val numMap = mutableMapOf<Int, Int>()
                cons.forEach {
                    numMap.compute(it.pos) { _, v ->
                        if (v == null) 1 else v + 1
                    }
                }
                val errors = numMap.filter { it.value > 1 }.toList()
                if (errors.isNotEmpty()) {
                    sendClosable("Невозможно начать игру:\n" + errors.joinToString("\n") { "Обнаружено несколько игроков с номером ${it.first}" })
                    return@parametrized
                }

                val roleList = setups.find { gameId == game.id }
                val roleCount = roleList.sumOf { it.count }
                if (cons.size != roleCount) {
                    sendClosable("Невозможно начать игру:\nКоличество игроков не совпадает с количеством ролей.\nИгроков: ${cons.size}\nРолей: $roleCount")
                    return@parametrized
                }
                val pairs =
                    pairings.find { gameId == game.id }
                        .associate { connections.get(it.connectionId) to roles.get(it.roleId) }
                val errorCons = cons.filter { !pairs.containsKey(it) }
                if (errorCons.isNotEmpty()) {
                    sendClosable("Невозможно начать игру:\n" + errorCons.joinToString("\n") { "Не указана роль для игрока ${it.name()}" })
                    return@parametrized
                }

                games.update(game.id) {
                    state = GameState.Type
                }
                modes.save(
                    GameMode(
                        ObjectId(),
                        game.id,
                        Mode.OPEN
                    )
                )
                if (game.host?.settings == null) {
                    hostSettings.save(HostSettings(ObjectId(), chatId))
                }
                hostInfos.updateMany(
                    { this.chatId == game.creatorId && this.gameLimit },
                    { left -= 1 }
                )
                bot.editMessageReplyMarkup(
                    ChatId.fromId(chatId),
                    long(1),
                    replyMarkup = inlineKeyboard {
                        button(blankCommand named "Выберите тип игры")
                        Mode.entries.forEach {
                            button(gameModeCommand named it.type, it.name, long(1))
                        }
                        button(menuPreviewCommand named "Назад", long(1))
                    }
                )
            }

            parametrized(nameCancelCommand) {
                if (game.state !in setOf(GameState.Rename, GameState.Dummy)) {
                    return@parametrized
                }
                games.update(game.id) {
                    state = GameState.Connect
                }
                accounts.update(chatId) {
                    connectionId = null
                }
                showLobbyMenu(chatId, long(0), game, bot)
            }

            parametrized(dummyCommand) {
                if (game.state != GameState.Connect) {
                    return@parametrized
                }
                games.update(game.id) {
                    state = GameState.Dummy
                }
                bot.editMessageReplyMarkup(
                    ChatId.fromId(chatId),
                    long(0),
                    replyMarkup = inlineKeyboard {
                        row { button(command("Введите имя для нового игрока", "default")) }
                        row { button(nameCancelCommand, long(0)) }
                    }
                )
            }
            parametrized(decrCommand) {
                setups.update(id(0)) {
                    count = max(count - 1, 0)
                }
                showRoles(chatId, long(1), bot, game)
            }
            parametrized(incrCommand) {
                setups.update(id(0)) {
                    count = max(count + 1, 0)
                }
                showRoles(chatId, long(1), bot, game)
            }
            parametrized(resetRolesCommand) {
                setups.updateMany({ gameId == id(0) }) {
                    count = 0
                }
                showRoles(chatId, long(1), bot, game)
            }
            parametrized(previewCommand) {
                modes.deleteMany { gameId == game.id }
                deleteGameTimers(bot, game.id)

                var roleCount = 0
                val roleList = mutableListOf<Role>()
                setups.find { gameId == game.id }.forEach {
                    roleCount += it.count
                    val role = roles.get(it.roleId)!!
                    (1..it.count).forEach { _ ->
                        roleList.add(role)
                    }
                }

                pairings.deleteMany { gameId == game.id }
                val cons = mutableListOf<Connection>()
                connections.find { gameFilter(game) }.sortedWith(compareBy({ it.pos }, { it.createdAt })).forEach {
                    cons.add(it)
                }

                games.update(game.id) {
                    state = GameState.Preview
                }

                roleList.shuffle()
                roleList.indices.forEach {
                    val role = roleList[it]
                    if (cons.size > it) {
                        val con = cons[it]
                        pairings.save(
                            Pairing(
                                ObjectId(),
                                game.id,
                                con.id,
                                role.id
                            )
                        )
                    }
                }
                showPreview(bot, chatId, long(1), game)
            }

            parametrized(reassignRoleCommand) {
                reassignments.save(
                    Reassignment(
                        game.id,
                        id(1)
                    )
                )
                val gameSetups = setups.find { gameId == game.id }.associate { it.roleId to it.count }.toMutableMap()
                game.pairingList.forEach { pair ->
                    if (gameSetups.containsKey(pair.roleId)) {
                        gameSetups[pair.roleId] = gameSetups[pair.roleId]!! - 1
                    }
                }
                val roleOptions = gameSetups.filter { it.value > 0 }
                    .mapNotNull { roles.get(it.key) }
                    .sortedBy { it.index }
                showReassignMenu(roleOptions, bot, chatId, long(0), id(1), reassignAnyCommand)
            }

            parametrized(reassignAnyCommand) {
                reassignments.save(
                    Reassignment(
                        game.id,
                        id(1)
                    )
                )
                val roleOptions = roles.find { gameId == game.id }
                showReassignMenu(roleOptions, bot, chatId, long(0), id(1), reassignRoleCommand)
            }

            parametrized(swapPairsCommand) {
                val res = bot.editMessageReplyMarkup(
                    ChatId.fromId(chatId),
                    long(0),
                    replyMarkup = inlineKeyboard {
                        row {
                            button(blankCommand named "Доступные игроки")
                        }
                        pairings.find { gameId == game.id && connectionId != game.reassignment?.connectionId }
                            .sortedBy { it.connectionId }.forEach { pair ->
                                row {
                                    button(
                                        swapConfirmCommand named
                                                (pair.connection?.name() ?: "Неизвестный игрок"),
                                        long(0), pair.id
                                    )
                                    button(
                                        swapConfirmCommand named
                                                (pair.role?.displayName ?: "Роль не указана"),
                                        long(0), pair.id
                                    )
                                }
                            }
                        button(menuPreviewCommand named "Назад", long(0))
                    }
                )
            }

            parametrized(swapConfirmCommand) {
                game.reassignment?.let { reassignment ->
                    pairings.get(id(1))?.let { pair ->
                        val role = pairings.find { connectionId == reassignment.connectionId }.firstOrNull()?.roleId
                        pairings.deleteMany { connectionId == reassignment.connectionId }
                        if (role == null) {
                            pairings.delete(pair.id)
                        } else {
                            pairings.update(id(1)) {
                                roleId = role
                            }
                        }
                        pairings.save(
                            Pairing(
                                ObjectId(),
                                game.id,
                                reassignment.connectionId,
                                pair.roleId
                            )
                        )
                    }
                }
                reassignments.delete(game.id)
                showPreview(bot, chatId, long(0), game)
            }

            parametrized(reassignConfirmCommand) {
                reassignments.get(game.id)?.let { reassignment ->
                    pairings.deleteMany { connectionId == reassignment.connectionId }
                    pairings.save(
                        Pairing(
                            ObjectId(),
                            game.id,
                            reassignment.connectionId,
                            id(1)
                        )
                    )
                }
                reassignments.delete(game.id)
                showPreview(
                    bot,
                    chatId,
                    long(0),
                    game
                )
            }

            parametrized(deletePairCommand) {
                reassignments.get(game.id)?.let { reassignment ->
                    pairings.deleteMany { connectionId == reassignment.connectionId }
                }
                reassignments.delete(game.id)
                showPreview(
                    bot,
                    chatId,
                    long(0),
                    game
                )
            }

            parametrized(gameModeCommand) {
                Mode.valueOf(str(0)).let { mode ->
                    modes.update(game.id) { this.mode = mode }
                    prepareScripts()
                    val scriptMap = roles.find { gameId == game.id }.filter { it.scripted }
                        .associate { it.name to Script(it.name, scriptDir) }
                    scripts[game.id] = scriptMap
                    if (checks.get(CheckOption.REVEAL_MENU)) {
                        games.update(game.id) {
                            state = GameState.Reveal
                        }
                        val list = game.pairingList.mapNotNull { pair ->
                            connections.update(pair.connectionId) { notified = false }
                            pair.connection?.let { con ->
                                if (con.bot) {
                                    return@mapNotNull null
                                }
                                con.player?.let { acc ->
                                    val playerChat = ChatId.fromId(con.playerId)
                                    bot.deleteMessage(playerChat, acc.menuMessageId)
                                    val res = bot.sendMessage(
                                        playerChat,
                                        "Роли выданы"
                                    )
                                    if (res.isSuccess) {
                                        val msgId = res.get().messageId
                                        val test = bot.editMessageReplyMarkup(
                                            playerChat,
                                            msgId,
                                            replyMarkup = inlineKeyboard {
                                                button(revealRoleCommand, pair.roleId, msgId)
                                                button(gameInfoCommand, pair.roleId, msgId)
                                                if (checks.get(CheckOption.ONE_MSG_PLAYER_INFO)) {
                                                    button(aliveInfoCommand, pair.roleId, msgId)
                                                }
                                            }
                                        )
                                        MessageLink(ObjectId(), game.id, con.playerId, msgId)
                                    } else {
                                        null
                                    }
                                }
                            }
                        }
                        if (checks.get(CheckOption.GAME_MESSAGES)) {
                            list.forEach {
                                messageLinks.save(it)
                            }
                        }
                        showRevealMenu(game, bot, chatId, long(1))
                    } else {
                        bot.deleteMessage(ChatId.fromId(chatId), long(1))
                        startGame(
                            chatId,
                            bot
                        )
                    }
                }
            }
            parametrized(proceedCommand) {
                bot.deleteMessage(ChatId.fromId(chatId), long(0))
                startGame(
                    chatId,
                    bot
                )
            }
            parametrized(settingsCommand) {
                game.host?.settings?.let {
                    showSettingsMenu(it, chatId, -1L, long(0), bot)
                }
            }
            parametrized(nightCommand) {
                towns.get(game.id)?.let { town ->
                    try {
                        bot.deleteMessage(ChatId.fromId(chatId), long(0))
                        accounts.update(chatId) {
                            menuMessageId = -1L
                        }
                        deleteGameTimers(bot, game.id)
                        bot.sendMessage(
                            ChatId.fromId(chatId),
                            "Результат дня:\n${shortLog(town).ifBlank { "Не произошло никаких событий" }}"
                        )
                        town.updateTeams()
                        town.prepNight()
                        showNightRoleMenu(town, chatId, bot, -1L)
                    } catch (e: Exception) {
                        log.error("Unable to start night, game: $game", e)
                    }
                }
            }
            parametrized(timerCommand) {
                if (timers.get(chatId) == null) {
                    val res = bot.sendMessage(
                        ChatId.fromId(chatId),
                        "Таймер:"
                    )
                    if (res.isSuccess) {
                        val messageId = res.get().messageId
                        val timer = Timer(ObjectId(), chatId, game.id, messageId, System.currentTimeMillis(), 0L)
                        timers.save(
                            timer
                        )
                    }
                }
            }
            parametrized(timerDeleteCommand) {
                timers.get(chatId)?.let {
                    deleteTimer(it, bot)
                }
            }
            parametrized(timerStateCommand) {
                timers.update(chatId) {
                    active = !active
                    updated = true
                    timestamp = System.currentTimeMillis()
                }
            }
            parametrized(timerResetCommand) {
                timers.update(chatId) {
                    updated = true
                    time = 0L
                    timestamp = System.currentTimeMillis()
                }
            }

            parametrized(unkickCommand) {
                kicks.get(id(0))?.let { kick ->
                    accounts.get(kick.player)?.let { _ ->
                        kicks.delete(id(0))
                        showKickMenu(game, long(1), bot, chatId)
                    }
                }
            }

            parametrized(acceptRehostCommand) {
                bot.deleteMessage(ChatId.fromId(chatId), long(0))
                val gameList = games.find { hostId == chatId }
                if (gameList.size > 1) {
                    gameList.dropLast(1).forEach {
                        deleteGame(it, bot)
                    }
                }
                gameList.lastOrNull()?.let { game ->
                    if (!canHost(game.creatorId)) {
                        createHostRequest(chatId)
                        bot.error(chatId, "Возможность перезапуска игры недоступна для создателя этого лобби.")
                        return@parametrized
                    }

                    //updateSetup(path, roles, game, types, orders)
                    if (checks.get(CheckOption.GAME_MESSAGES)) {
                        game.messages.forEach { msg ->
                            bot.deleteMessage(
                                ChatId.fromId(msg.chatId),
                                msg.messageId
                            )
                        }
                    }

                    games.update(game.id) {
                        state = GameState.Connect
                    }
                    pendings.deleteMany { host == chatId }
                    //setups.deleteMany { gameId == game.id }
                    pairings.deleteMany { gameId == game.id }
                    towns.remove(game.id)
                    accounts.update(chatId) {
                        connectionId = null
                    }
                    connections.updateMany({ gameId == game.id }) {
                        notified = false
                    }
                    connections.find { gameId == game.id }.forEach { con ->
                        if (!con.bot) {
                            showPlayerMenu(con.playerId, -1L, bot, con.id, con.pos)
                        }
                    }

                    showLobbyMenu(chatId, game.host?.menuMessageId ?: -1L, game, bot)
                }
            }

            block({ notNull { towns[game.id] } }) { town ->
                val mode = game.host?.settings
                parametrized(dayDetailsCommand) {
                    accounts.get(chatId)?.let { acc ->
                        val msgId = if (acc.menuMessageId != -1L) acc.menuMessageId else long(1)
                        showPlayerDayDesc(town, int(0), msgId, chatId, bot)
                    }
                }
                parametrized(dayBackCommand) {
                    showDayMenu(town, chatId, long(0), bot, game)
                }
                parametrized(statusCommand) {
                    town.changeProtected(int(0))
                    if (mode?.detailedView != true && checks.get(CheckOption.KEEP_DETAILS)) {
                        accounts.get(chatId)?.let { acc ->
                            val msgId = if (acc.menuMessageId != -1L) acc.menuMessageId else long(1)
                            showPlayerDayDesc(town, int(0), msgId, chatId, bot)
                        }
                    } else {
                        showDayMenu(town, chatId, long(1), bot, game)
                    }
                }
                parametrized(killCommand) {
                    town.setAlive(int(0), false)
                    if (mode?.detailedView != true && checks.get(CheckOption.KEEP_DETAILS)) {
                        accounts.get(chatId)?.let { acc ->
                            val msgId = if (acc.menuMessageId != -1L) acc.menuMessageId else long(1)
                            showPlayerDayDesc(town, int(0), msgId, chatId, bot)
                        }
                    } else {
                        showDayMenu(town, chatId, long(1), bot, game)
                    }
                }
                parametrized(reviveCommand) {
                    town.setAlive(int(0), true)
                    if (mode?.detailedView != true && checks.get(CheckOption.KEEP_DETAILS)) {
                        accounts.get(chatId)?.let { acc ->
                            val msgId = if (acc.menuMessageId != -1L) acc.menuMessageId else long(1)
                            showPlayerDayDesc(town, int(0), msgId, chatId, bot)
                        }
                    } else {
                        showDayMenu(town, chatId, long(1), bot, game)
                    }
                }
                parametrized(fallCommand) {
                    val pos = int(0)
                    val person = town.playerMap[pos]
                    if (person != null) {
                        person.fallCount += 1
                    }
                    if (mode?.detailedView != true) {
                        accounts.get(chatId)?.let { acc ->
                            val msgId = if (acc.menuMessageId != -1L) acc.menuMessageId else long(1)
                            showPlayerDayDesc(town, int(0), msgId, chatId, bot)
                        }
                    } else {
                        showDayMenu(town, chatId, long(1), bot, game)
                    }
                }
                parametrized(selectCommand) {
                    if (isId(2)) {
                        nightSelection(town, int(0), chatId, bot, long(1), id(2))
                    }
                }
                parametrized(executeActionCommand) {
                    if (town.index < town.night.size) {
                        executeNightAction(town, town.night[town.index], bot, chatId, long(0))
                    } else {
                        skipNightRole(town, chatId, long(0), bot)
                    }
                }
                parametrized(nextRoleCommand) {
                    town.selections.clear()
                    showNightRoleMenu(town, chatId, bot, long(0))
                }
                parametrized(skipRoleCommand) {
                    skipNightRole(town, chatId, long(0), bot)
                }
                parametrized(cancelActionCommand) {
                    town.index--
                    val last = town.actions.last { it.dependencies.isEmpty() }
                    town.actions.removeIf { last in it.dependencies }
                    town.actions.remove(last)
                    showNightRoleMenu(town, chatId, bot, long(0))
                }
                parametrized(dayCommand) {
                    bot.deleteMessage(ChatId.fromId(chatId), long(0))
                    town.startDay(chatId, bot, game)
                    messageLinks.find { gameId == game.id && type == LinkType.ALIVE }.forEach { link ->
                        connections.find { gameId == game.id && playerId == link.chatId }.forEach { con ->
                            town.playerMap[con.pos]?.let { player ->
                                showAliveMenu(game, con, bot, link.messageId, player.roleData.id)
                            }
                        }
                    }
                }
                game.host?.settings?.let { settings ->
                    parametrized(filterCommand) {
                        towns[game.id]?.let { town ->
                            val index =
                                if (DayView.entries.size > settings.dayView.ordinal + 1) settings.dayView.ordinal + 1 else 0
                            val next = DayView.entries[index]
                            hostSettings.update(game.hostId) {
                                dayView = next
                            }
                            showDayMenu(town, chatId, long(0), bot, game)
                        }
                    }
                    parametrized(hidePlayersCommand) {
                        hostSettings.update(chatId) { playersHidden = !playersHidden }
                        if (long(0) != -1L) {
                            showDayMenu(town, chatId, long(0), bot, game)
                        }
                    }
                    parametrized(hostSettingCommand) {
                        updateSettingsView(
                            chatId,
                            long(0),
                            long(1),
                            game,
                            town,
                            bot,
                            HostOptions.valueOf(str(2)).update
                        )
                    }
                    parametrized(settingDescCommand) {
                        hostSettings.get(chatId)?.let { settings ->
                            showSettingsMenu(
                                settings,
                                chatId,
                                long(0),
                                long(1),
                                bot,
                                HostOptions.valueOf(str(2)).fullName
                            )
                        }
                    }
                }
            }
        }
    }

    private suspend fun ContainerBlock.ParametrizedContext.playerQueries() {
        parametrized(mainMenuCommand) {
            bot.deleteMessage(ChatId.fromId(chatId), long(0))
            leaveGame(
                chatId,
                accounts.get(chatId)?.menuMessageId ?: -1L,
                resetAccount,
                bot
            )
        }
        parametrized(acceptLeaveCommand) {
            bot.deleteMessage(ChatId.fromId(chatId), long(0))
            bot.deleteMessage(ChatId.fromId(chatId), long(1))
            leaveGame(
                chatId,
                accounts.get(chatId)?.menuMessageId ?: -1L,
                resetAccount,
                bot
            )
        }

        block({ notNull { connections.find { playerId == chatId }.singleOrNull() } }) { con ->
            block({ notNull { games.get(con.gameId) } }) { game ->
                parametrized(acceptHostingCommand) {
                    if (game.id == id(0)
                        && game.state == GameState.ChangeHost
                        && game.hostId == long(1)
                    ) {
                        rehosts.get(game.id)?.let { rehost ->
                            if (rehost.hostId == chatId) {
                                game.host?.let { prevHost ->
                                    con.player?.let { newHost ->
                                        bot.deleteMessage(ChatId.fromId(prevHost.chatId), prevHost.menuMessageId)
                                        bot.sendMessage(
                                            ChatId.fromId(prevHost.chatId),
                                            "Игрок ${newHost.fullName()} стал новым ведущим этой игры"
                                        )
                                        accounts.update(prevHost.chatId) {
                                            state = AccountState.Lobby
                                        }
                                        joinGame(game, prevHost, prevHost.chatId, -1L, bot)

                                        connections.delete(con.id)
                                        val chat = ChatId.fromId(newHost.chatId)
                                        bot.deleteMessage(chat, newHost.menuMessageId)
                                        bot.deleteMessage(chat, long(2))
                                        accounts.update(newHost.chatId) {
                                            state = AccountState.Host
                                        }
                                        games.update(game.id) {
                                            state = GameState.Connect
                                            hostId = chatId
                                        }
                                        rehosts.delete(game.id)
                                        bot.sendMessage(
                                            chat,
                                            "Вы стали новым ведущим игры",
                                            replyMarkup = mafiaKeyboard(chatId)
                                        )
                                    }
                                    showLobbyMenu(chatId, -1L, game, bot, true)
                                    hostRequests.save(UserId(ObjectId(), chatId))
                                }
                            }
                        }
                    }
                }
                parametrized(declineHostingCommand) {
                    if (game.id == id(0)
                        && game.state == GameState.ChangeHost
                        && game.hostId == long(1)
                    ) {
                        rehosts.get(game.id)?.let { rehost ->
                            if (rehost.hostId == chatId) {
                                game.host?.let { prevHost ->
                                    con.player?.let { newHost ->
                                        val chat = ChatId.fromId(prevHost.chatId)
                                        val res = bot.sendMessage(
                                            chat,
                                            "Игрок ${newHost.fullName()} отказался вести игру"
                                        )
                                        if (res.isSuccess) {
                                            val msgId = res.get().messageId
                                            bot.editMessageReplyMarkup(
                                                chat,
                                                msgId,
                                                replyMarkup = inlineKeyboard {
                                                    button(deleteMsgCommand, msgId)
                                                }
                                            )
                                        }
                                    }
                                    bot.deleteMessage(ChatId.fromId(con.playerId), long(2))
                                    games.update(game.id) {
                                        state = GameState.Connect
                                    }
                                    rehosts.delete(game.id)
                                    showLobbyMenu(prevHost.chatId, prevHost.menuMessageId, game, bot)
                                }
                            }
                        }
                    }
                }
                parametrized(gameInfoCommand) {
                    if (game.state == GameState.Game || game.state == GameState.Reveal) {
                        val mode = modes.get(game.id)?.mode
                        val roleMap = getRoles(game)
                        val playerCount = roleMap.map { it.value }.sum()
                        val players = getPlayerDescs(con)
                        val desc =
                            (if (mode != null) "<b>Тип игры</b>: ${mode.type}\n${mode.desc}\n\n" else "") +
                                    "<b>Количество игроков</b>: $playerCount\n\n${roleDesc(roleMap)}" +
                                    (if (players.size > 1) "\n\n<b>Игроки в команде</b>:\n" + players.joinToString(
                                        "\n"
                                    ) else "")
                        val chat = ChatId.fromId(chatId)
                        if (checks.get(CheckOption.ONE_MSG_PLAYER_INFO)) {
                            bot.editMessageText(
                                chat,
                                long(1),
                                text = desc,
                                replyMarkup = inlineKeyboard {
                                    button(revealRoleCommand, id(0), long(1))
                                    button(aliveInfoCommand, id(0), long(1))
                                },
                                parseMode = ParseMode.HTML
                            )
                            messageLinks.updateMany({
                                messageId == long(1)
                                        && chatId == con.playerId
                                        && gameId == game.id
                            }) {
                                type = LinkType.INFO
                            }
                        } else {
                            val res = bot.sendMessage(
                                chat,
                                desc,
                                parseMode = ParseMode.HTML
                            )
                            if (res.isSuccess && checks.get(CheckOption.GAME_MESSAGES)) {
                                messageLinks.save(MessageLink(ObjectId(), game.id, con.playerId, res.get().messageId))
                            }
                        }
                    }
                }
                parametrized(revealRoleCommand) {
                    if (game.state == GameState.Game || game.state == GameState.Reveal) {
                        try {
                            roles.get(id(0))?.let { role ->
                                val chat = ChatId.fromId(con.playerId)
                                val desc = getRoleDesc(role)
                                if (checks.get(CheckOption.ONE_MSG_PLAYER_INFO)) {
                                    bot.editMessageText(
                                        chat,
                                        long(1),
                                        text = desc,
                                        replyMarkup = inlineKeyboard {
                                            button(gameInfoCommand, role.id, long(1))
                                            button(aliveInfoCommand, role.id, long(1))
                                        },
                                        parseMode = ParseMode.HTML
                                    )
                                    messageLinks.updateMany({
                                        messageId == long(1)
                                                && chatId == con.playerId
                                                && gameId == game.id
                                    }) {
                                        type = LinkType.ROLE
                                    }
                                } else {
                                    val res = bot.sendMessage(
                                        chat,
                                        desc,
                                        parseMode = ParseMode.HTML
                                    )
                                    if (res.isSuccess && checks.get(CheckOption.GAME_MESSAGES)) {
                                        messageLinks.save(
                                            MessageLink(
                                                ObjectId(),
                                                game.id,
                                                con.playerId,
                                                res.get().messageId
                                            )
                                        )
                                    }
                                    bot.editMessageReplyMarkup(
                                        chat,
                                        long(1),
                                        replyMarkup = inlineKeyboard {
                                            button(gameInfoCommand, role.id, long(1))
                                        }
                                    )
                                }
                                connections.update(con.id) {
                                    notified = true
                                }
                                pendings.save(Pending(ObjectId(), game.hostId, game.id))
                            }
                        } catch (e: Exception) {
                            log.error("Unable to reveal role, game: $game, connection: $con, role: ${id(0)}", e)
                        }
                    }
                }
                parametrized(aliveInfoCommand) {
                    showAliveMenu(game, con, bot, long(1), id(0))
                }
            }
        }
    }
}

private fun showReassignMenu(
    roleOptions: List<Role>, bot: Bot, chatId: Long, messageId: Long,
    connectionId: ConnectionId, menuCommand: Command
) {
    inlineKeyboardLambda(
        chatId,
        messageId,
        bot,
        {
            button(
                blankCommand named
                        (if (roleOptions.isNotEmpty()) "Новая роль" else "Все роли распределены")
            )
            roleOptions.sortedBy { it.index }.chunked(2).forEach { pair ->
                row {
                    val first = pair[0]
                    button(
                        reassignConfirmCommand named first.displayName,
                        messageId,
                        first.id
                    )
                    if (pair.size > 1) {
                        val second = pair[1]
                        button(
                            reassignConfirmCommand named second.displayName,
                            messageId,
                            second.id
                        )
                    } else {
                        button(blankCommand)
                    }
                }
            }
            button(deletePairCommand, messageId)
            button(swapPairsCommand, messageId)
            button(menuCommand, messageId, connectionId)
            button(menuPreviewCommand named "Назад", messageId)
        }
    )
}