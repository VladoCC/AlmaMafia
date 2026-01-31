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
        simple(statCommand) {
            if (messageId != null) {
                bot.deleteMessage(ChatId.fromId(chatId), messageId)
            }
            if (checks.get(CheckOption.SHOW_STATS)) {
                val res = bot.sendMessage(
                    ChatId.fromId(chatId),
                    "Статистика игрока:"
                )
                if (res.isSuccess) {
                    val messageId = res.get().messageId
                    showStatMenu(chatId, messageId, bot)
                }
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

        parametrized(scriptStatCommand) {
            showScriptStatMenu(
                chatId,
                long(0),
                id(1),
                bot
            )
        }

        menuQueries()

        connectionManagingQueries()
        parametrized(roleCommand) {
            roles.get(id(0))?.let { role ->
                sendClosable("Название: ${role.displayName}\nОписание: ${role.desc}")
            }
        }
        adminQueries()
        hostQueries()
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
                    if (game.state == GameState.GAME) {
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
                        } catch (_: NumberFormatException) {
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
                "Вы уверены, что хотите завершить игру и вернуться в лобби?"
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
        simple(endGameCommand) {
            games.find { hostId == chatId }.firstOrNull()?.let { game ->
                val chat = ChatId.fromId(chatId)
                bot.deleteMessage(chat, messageId ?: -1L)
                val res = bot.sendMessage(
                    chat,
                    "Для завершения игры, выберите все команды, которые будут объявлены победителями:"
                )
                if (res.isSuccess) {
                    val msgId = res.get().messageId
                    showEndGameMenu(chatId, msgId, game, bot, true)
                }
            }
        }
        simple(stopGameCommand, menuCommand, stopGameLegacyCommand) {
            val chat = ChatId.fromId(chatId)
            bot.deleteMessage(chat, messageId ?: -1L)
            val res = bot.sendMessage(
                chat,
                "Вы уверены, что хотите завершить игру и вернуться в главное меню?"
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
                if (game.state == GameState.DUMMY) {
                    connections.save(
                        Connection(
                            ObjectId(),
                            game.id,
                            -1,
                            query,
                            bot = true
                        )
                    )
                    games.updateMany(
                        { hostId == chatId },
                        { state = GameState.CONNECT }
                    )
                    showLobbyMenu(chatId, account.menuMessageId, game, bot)
                    bot.deleteMessage(ChatId.fromId(chatId), messageId ?: -1L)
                    return@any
                } else if (game.state == GameState.RENAME && account.connectionId != null) {
                    connections.get(account.connectionId!!)?.let { con ->
                        if (con.gameId == game.id) {
                            val newName = query
                            connections.update(con.id) {
                                name = newName
                            }
                            games.update(game.id) {
                                state = GameState.CONNECT
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
                } else if (game.state == GameState.NUM) {
                    val num = try {
                        query.toInt()
                    } catch (_: NumberFormatException) {
                        bot.error(chatId)
                        return@any
                    }
                    bot.deleteMessage(ChatId.fromId(chatId), messageId ?: -1L)
                    if (num > 0) {
                        hostSetPlayerNum(game, account.connectionId, num, account.menuMessageId, chatId, bot)
                    }
                } else if (autoNightInputs.get(chatId) != null) {
                    val input = autoNightInputs.get(chatId)!!
                    hostSettings.get(chatId)?.let { settings ->
                        val num = try {
                            query.toInt()
                        } catch (_: NumberFormatException) {
                            bot.error(chatId)
                            return@any
                        }
                        if (num <= 0) {
                            bot.error(chatId, "Число должно быть положительным")
                            return@any
                        }
                        when (input.type) {
                            AutoNightInputType.SINGLE -> {
                                settings.autoNight?.actionSingleLimitSec = num
                            }

                            AutoNightInputType.TEAM -> {
                                settings.autoNight?.actionTeamLimitSec = num
                            }
                        }
                        hostSettings.update(settings.hostId) {
                            autoNight = settings.autoNight
                        }
                        bot.deleteMessage(ChatId.fromId(chatId), messageId ?: -1L)
                        showSettingsMenu(
                            settings,
                            chatId,
                            input.settingsMessageId,
                            input.gameMessageId,
                            bot
                        )
                        return@any
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
                } catch (_: NumberFormatException) {
                    bot.error(chatId, "Не удалось распознать число")
                    return@any
                }

                if (num > 0) {
                    val hostInfo = hostInfos.find { chatId == adminMenu.editId }.singleOrNull()
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

                        showHostSettings(adminMenu.messageId, chatId, bot)
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
                val script = account.let { host ->
                    if (host.scriptSelection != null) {
                        return@let host.scriptSelection!!.script!!
                    }

                    val res = host.scripts
                        .firstOrNull()?.script ?: defaultGameScript()!!.also {
                        scriptAccess.save(
                            ScriptLink(
                                ObjectId(),
                                it.id,
                                host.chatId
                            )
                        )
                    }
                    res.also {
                        scriptSelections.save(
                            ScriptLink(
                                ObjectId(),
                                it.id,
                                host.chatId
                            )
                        )
                    }
                }
                val id = games.save(Game(ObjectId(), chatId, script.id))
                val game = games.get(id)
                initGame(game, chatId, -1L, bot)
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
                    showAdminMenu(chatId, messageId, bot)
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
                    bot.editMessageReplyMarkup(
                        ChatId.fromId(chatId),
                        long(2),
                        replyMarkup = numpadKeyboard(
                            "Номер игрока:",
                            playerNumCommand,
                            playerConfirmCommand,
                            mainMenuCommand,
                            id(0),
                            int(1),
                            long(2)
                        )
                    )
                }
                parametrized(playerConfirmCommand) {
                    setPlayerNum(game, con, int(1), chatId, bot)
                }
                parametrized(confirmNumCommand) {
                    bot.editMessageText(
                        ChatId.fromId(chatId),
                        long(1),
                        text = Const.Message.numSaved
                    )
                }

                /** is game host **/
                block(game.hostId == chatId) {
                    parametrized(newHostCommand) {
                        games.update(game.id) {
                            state = GameState.REHOST
                        }
                        bot.editMessageReplyMarkup(
                            ChatId.fromId(chatId),
                            long(1),
                            replyMarkup = inlineKeyboard {
                                button(blankCommand named "Ожидаем ответа")
                                button(stopRehostingCommand, long(1))
                            }
                        )
                        val spacedHostName = " " + (accounts.get(chatId)?.fullName() ?: " ") + ""
                        val chat = ChatId.fromId(con.playerId)
                        val res = bot.sendMessage(
                            chat,
                            "Ведущий${spacedHostName} предлагает вам стать новым ведущим игры.\nПринять?",
                        )
                        if (res.isSuccess) {
                            val msgId = res.get().messageId
                            rehosts.save(Rehost(ObjectId(), game.id, con.playerId, msgId))
                            bot.editMessageReplyMarkup(
                                chat,
                                msgId,
                                replyMarkup = inlineKeyboard {
                                    row {
                                        button(acceptHostingCommand, game.id, chatId, msgId)
                                        button(declineHostingCommand, game.id, chatId, msgId)
                                    }
                                }
                            )
                        }
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
                        if (game.state != GameState.CONNECT) {
                            return@parametrized
                        }
                        games.update(game.id) {
                            state = GameState.RENAME
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
                            state = GameState.NUM
                        }
                        bot.editMessageReplyMarkup(
                            ChatId.fromId(chatId),
                            long(2),
                            replyMarkup = numpadKeyboard(
                                "Введите номер для игрока ${con.name()}:",
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
                            con.player?.let {
                                if (it.menuMessageId != -1L) {
                                    bot.deleteMessage(ChatId.fromId(it.chatId), it.menuMessageId)
                                }
                            }
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
            parametrized(updateCheckCommand) {
                updateCheck(str(0))
                showAdminMenu(chatId, long(1), bot)
            }
            parametrized(hostRequestCommand) {
                showHostRequests(long(0), chatId, bot)
            }
            parametrized(hostSettingsCommand) {
                showHostSettings(long(0), chatId, bot)
            }
            parametrized(adminSettingsCommand) {
                val messageId = long(0)
                showAdminListMenu(chatId, messageId, bot)
            }
            parametrized(timeLimitOnCommand) {
                val res = bot.sendMessage(ChatId.fromId(chatId), "Введите срок действия разрешения в днях:")
                if (res.isSuccess) {
                    val desc = res.get().messageId
                    createAdminContext(desc, AdminState.HOST_TIME)
                }
            }
            parametrized(timeLimitOffCommand) {
                hostInfos.update(long(0)) { timeLimit = false }
                showHostSettings(long(1), chatId, bot)
            }
            parametrized(gameLimitOnCommand) {
                val res = bot.sendMessage(ChatId.fromId(chatId), "Введите количество разрешенных игр:")
                if (res.isSuccess) {
                    val desc = res.get().messageId
                    createAdminContext(desc, AdminState.HOST_GAMES)
                }
            }
            parametrized(gameLimitOffCommand) {
                hostInfos.update(long(0)) { gameLimit = false }
                showHostSettings(long(1), chatId, bot)
            }
            parametrized(shareCommand) {
                hostInfos.get(long(0))?.let {
                    hostInfos.update(long(0)) { canShare = !it.canShare }
                    showHostSettings(long(1), chatId, bot)
                }
            }
            parametrized(canReassignCommand) {
                hostInfos.get(long(0))?.let {
                    hostInfos.update(long(0)) { canReassign = !it.canReassign }
                    showHostSettings(long(1), chatId, bot)
                }
            }
            parametrized(distributionCommand) {
                hostInfos.get(long(0))?.let {
                    hostInfos.update(long(0)) { showDistribution = !it.showDistribution }
                    showHostSettings(long(1), chatId, bot)
                }
            }
            parametrized(deleteHostCommand) {
                hostInfos.delete(long(0))
                showHostSettings(long(1), chatId, bot)
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
                bot.deleteMessage(ChatId.fromId(chatId), long(2))
                admins.save(UserId(ObjectId(), long(0)))
                showHostSettings(long(1), chatId, bot)
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
                showAdminListMenu(chatId, long(1), bot)
            }
            parametrized(chooseHostAdminCommand) {
                showChosenSettingsMenu(chatId, long(0), bot, long(1))
            }
            parametrized(changeHostAdminSettingCommand) {
                hostSettings.update(long(1)) { HostOptions.valueOf(str(2)).update(this) }
                showChosenSettingsMenu(chatId, long(0), bot, long(1))
            }
            parametrized(adminBackCommand) {
                showAdminMenu(chatId, long(0), bot)
                accounts.update(chatId) {
                    state = AccountState.Menu
                }
                adminContexts.delete(chatId)
            }
            parametrized(gamesSettingsCommand) {
                showGameStatusMenu(chatId, long(0), bot)
            }
            parametrized(hostAdminSettingsCommand) {
                showHostAdminSettingsMenu(chatId, long(0), bot)
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

    private suspend fun ContainerBlock.ParametrizedContext.hostQueries() {
        parametrized(acceptStopCommand) {
            stopGames(games.find { hostId == chatId }, chatId, bot, long(0), long(1))
        }
        parametrized(stopLobbyCommand) {
            stopGames(games.find { hostId == chatId }, chatId, bot, long(0))
        }

        /** with Game of this host **/
        block({ notNull { games.find { hostId == chatId }.singleOrNull() } }) { game ->
            parametrized(menuKickCommand) {
                showKickMenu(game, long(0), bot, chatId)
            }
            parametrized(changeHostCommand) {
                games.update(game.id) {
                    state = GameState.REHOST
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
                        state = GameState.CONNECT
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
                            val msgId = showPlayerLobbyMenu(acc.chatId, -1L, bot, con.id)
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
                    state = GameState.CONNECT
                }
                showLobbyMenu(chatId, long(0), game, bot)
            }
            parametrized(toggleHideRolesCommand) {
                hostSettings.update(chatId, HostOptions.HideRolesMode.update)
                showPreviewMenu(bot, chatId, long(0), game)
            }
            parametrized(menuRolesCommand) {
                games.update(game.id) {
                    state = GameState.ROLES
                }
                if (setups.find { gameId == game.id && count > 0 }.isEmpty()) {
                    setups.deleteMany { gameId == game.id }
                    roles.find { gameId == game.id }.forEach {
                        setups.save(Setup(ObjectId(), it.id, game.id, it.index))
                    }
                }
                showRolesMenu(chatId, long(0), bot, game)
            }
            parametrized(menuPreviewCommand) {
                games.update(game.id) {
                    state = GameState.PREVIEW
                }
                reassignments.delete(game.id)
                showPreviewMenu(bot, chatId, long(0), game)
            }
            parametrized(menuWeightCommand) {
                showWeightMenu(bot, chatId, long(0), game)
            }
            parametrized(menuDistributionCommand) {
                showDistributionMenu(bot, chatId, long(0), game, id(1))
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
                    state = GameState.TYPE
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
                if (game.state !in setOf(GameState.RENAME, GameState.DUMMY)) {
                    return@parametrized
                }
                games.update(game.id) {
                    state = GameState.CONNECT
                }
                accounts.update(chatId) {
                    connectionId = null
                }
                showLobbyMenu(chatId, long(0), game, bot)
            }

            parametrized(dummyCommand) {
                if (game.state != GameState.CONNECT) {
                    return@parametrized
                }
                games.update(game.id) {
                    state = GameState.DUMMY
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
                showRolesMenu(chatId, long(1), bot, game)
            }
            parametrized(incrCommand) {
                setups.update(id(0)) {
                    count = max(count + 1, 0)
                }
                showRolesMenu(chatId, long(1), bot, game)
            }
            parametrized(resetRolesCommand) {
                setups.updateMany({ gameId == id(0) }) {
                    count = 0
                }
                showRolesMenu(chatId, long(1), bot, game)
            }
            parametrized(previewCommand) {
                modes.deleteMany { gameId == game.id }
                deleteGameTimers(bot, game.id)

                pairings.deleteMany { gameId == game.id }
                val cons = mutableListOf<Connection>()
                connections.find { gameId == game.id }.sortedWith(compareBy({ it.pos }, { it.createdAt })).forEach {
                    cons.add(it)
                }

                games.update(game.id) {
                    state = GameState.PREVIEW
                }

                val pairs = when (game.script?.roleDistribution) {
                    RoleDistribution.WEIGHTED -> try {
                        distributeRolesWeighted(cons, game)
                    } catch (e: Exception) {
                        log.error("Failed to perform a weighted distribution of roles", e)
                        distributeRolesRandom(cons, game)
                    }
                    RoleDistribution.RANDOM -> {
                        distributeRolesRandom(cons, game)
                    }

                    else -> distributeRolesRandom(cons, game)
                }

                pairs.filter { it.value != null }.forEach { (con, role) ->
                    pairings.save(
                        Pairing(
                            ObjectId(),
                            game.id,
                            con.id,
                            role!!.id
                        )
                    )
                }
                showPreviewMenu(bot, chatId, long(1), game)
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
                            .sortedBy { it.connection?.pos ?: Int.MAX_VALUE }.forEach { pair ->
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
                showPreviewMenu(bot, chatId, long(0), game)
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
                showPreviewMenu(
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
                showPreviewMenu(
                    bot,
                    chatId,
                    long(0),
                    game
                )
            }

            parametrized(gameModeCommand) {
                Mode.valueOf(str(0)).let { mode ->
                    modes.update(game.id) { this.mode = mode }
                    val scriptDir = game.script!!.path
                    val scriptPaths = prepareScripts(game, scriptDir)
                    scripts[game.id] = scriptPaths.entries
                        .associate { it.key to Script(it.value) }
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
                                    showPlayerGameMenu(con, playerChat, msgId, pair.roleId, LinkType.NONE, game, bot)
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
                    if (checks.get(CheckOption.REVEAL_MENU)) {
                        games.update(game.id) {
                            state = GameState.REVEAL
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
                restartGame(chatId, long(0), bot, false)
            }

            parametrized(acceptEndCommand) {
                restartGame(chatId, long(0), bot, true)
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
                parametrized(nightCommand) {
                    towns.get(game.id)?.let { town ->
                        try {
                            endDay(game, town, long(0), bot)
                            town.startDusk()
                            town.prepNight()
                            showNightRoleMenu(town, chatId, bot, -1L)
                        } catch (e: Exception) {
                            log.error("Unable to start night, game: $game", e)
                        }
                    }
                }
                parametrized(autoNightCommand) {
                    towns.get(game.id)?.let { town ->
                        try {
                            endDay(game, town, long(0), bot)
                            town.prepNight()
                            val map = mutableMapOf<Int, MutableList<AutoNightAction>>()
                            town.night.forEach { wake ->
                                val action = AutoNightAction(
                                    ObjectId(),
                                    game.id,
                                    wake.id
                                )
                                autoNightActions.save(
                                    action
                                )
                                wake.players.forEach { person ->
                                    if (person.pos !in map) {
                                        map.getOrPut(person.pos) { mutableListOf() }.add(action)
                                    }
                                }
                            }
                            val alive = town.night.associateWith { wake ->
                                wake.alivePlayers().sortedWith(
                                    compareBy(
                                        { -it.roleData.priority },
                                        { it.pos }
                                    )
                                )
                            }
                            game.connectionList.forEach { con ->
                                if (!con.bot && town.playerMap[con.pos]?.alive == true) {
                                    con.pairing?.role?.let { role ->
                                        val actions = map[con.pos]
                                        var firstWake: Wake? = null
                                        val actor = if (actions?.isNotEmpty() == true) {
                                            val actor = AutoNightActor(
                                                ObjectId(),
                                                con.id,
                                            )
                                            actions.forEach { action ->
                                                action.wake?.let { wake ->
                                                    alive[wake]?.let { currentAlive ->
                                                        ActorActionLink(
                                                            ObjectId(),
                                                            actor.id,
                                                            action.id,
                                                            currentAlive.firstOrNull()?.pos == con.pos
                                                        ).also {
                                                            actorActionLinks.save(it)
                                                            if (firstWake == null || firstWake!!.id > wake.id) {
                                                                firstWake = wake
                                                            }
                                                        }
                                                    }
                                                }
                                            }
                                            actor
                                        } else {
                                            AutoNightActor(
                                                ObjectId(),
                                                con.id
                                            )
                                        }

                                        autoNightActors.save(
                                            actor
                                        )

                                        val msgId = showAutoNightPrepMenu(
                                            actor.id,
                                            firstWake,
                                            role,
                                            con.playerId,
                                            bot
                                        )
                                        nightPlayerMessages.save(
                                            NightPlayerMessage(
                                                con.playerId,
                                                msgId,
                                                con.id,
                                                game.id
                                            )
                                        )
                                    }
                                }
                            }
                            showAutoNightHostMenu(town, chatId, bot, -1L)
                        } catch (e: Exception) {
                            log.error("Unable to start auto-night, game: $game", e)
                        }
                    }
                }
                parametrized(autoNightUpdCommand) {
                    town.night.forEach { it.updateStatus() }
                    showAutoNightHostMenu(town, chatId, bot, long(0))
                }
                parametrized(selectCommand) {
                    if (isId(2)) {
                        nightSelection(town, int(0), chatId, bot, long(1), id(2))
                    }
                }
                parametrized(executeActionCommand) {
                    if (town.index < town.night.size) {
                        showNightActionMenu(town, town.night[town.index], bot, chatId, long(0))
                        town.index++
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
                    town.night[town.index].status = WakeStatus.none()
                    town.index--
                    val last = town.actions.last { it.dependencies.isEmpty() }
                    town.actions.removeIf { last in it.dependencies }
                    town.actions.remove(last)
                    showNightRoleMenu(town, chatId, bot, long(0))
                }
                parametrized(dayCommand) {
                    deleteNightPlayerMenus(game, bot)
                    nightHostMessages.delete(chatId)

                    bot.deleteMessage(ChatId.fromId(chatId), long(0))

                    town.startDawn()
                    town.startDay(chatId, bot, game)
                    messageLinks.find { gameId == game.id && type == LinkType.ALIVE }.forEach { link ->
                        connections.find { gameId == game.id && playerId == link.chatId }.forEach { con ->
                            town.playerMap[con.pos]?.let { player ->
                                showPlayerGameMenu(
                                    con,
                                    ChatId.fromId(link.chatId),
                                    link.messageId,
                                    player.roleData.id,
                                    LinkType.ALIVE,
                                    game,
                                    bot
                                )
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
                    parametrized(settingsBackCommand) {
                        autoNightInputs.delete(chatId)
                        showSettingsMenu(settings, chatId, long(0), long(1), bot)
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
                    parametrized(autoSingLimDescCommand) {
                        hostSettings.get(chatId)?.let { settings ->
                            showSettingsMenu(
                                settings,
                                chatId,
                                long(0),
                                long(1),
                                bot,
                                "Лимит времени на действие ролей без команды вовремя авто-ночи"
                            )
                        }
                    }
                    parametrized(autoSingLimSelCommand) {
                        autoNightInputs.save(
                            AutoNightInput(
                                chatId,
                                AutoNightInputType.SINGLE,
                                long(0),
                                long(1)
                            )
                        )
                        hostSettings.get(chatId)?.let { settings ->
                            bot.editMessageText(
                                ChatId.fromId(chatId),
                                long(0),
                                text = "Актуальное значение: " +
                                        "${settings.autoNight?.actionSingleLimit?.toSeconds()?.pretty()}\n" +
                                        "Введите лимит времени для ролей без команды вовремя авто-ночи:",
                                replyMarkup = inlineKeyboard {
                                    button(settingsBackCommand, long(0), long(1))
                                }
                            )
                        }
                    }
                    parametrized(autoTeamLimDescCommand) {
                        hostSettings.get(chatId)?.let { settings ->
                            showSettingsMenu(
                                settings,
                                chatId,
                                long(0),
                                long(1),
                                bot,
                                "Лимит времени на действие командных ролей вовремя авто-ночи"
                            )
                        }
                    }
                    parametrized(autoTeamLimSelCommand) {
                        autoNightInputs.save(
                            AutoNightInput(
                                chatId,
                                AutoNightInputType.TEAM,
                                long(0),
                                long(1)
                            )
                        )
                        bot.editMessageText(
                            ChatId.fromId(chatId),
                            long(0),
                            text = "Актуальное значение: " +
                                    "${settings.autoNight?.actionTeamLimit?.toSeconds()?.pretty()}\n" +
                                    "Введите лимит времени для командных ролей вовремя авто-ночи:",
                            replyMarkup = inlineKeyboard {
                                button(settingsBackCommand, long(0), long(1))
                            }
                        )
                    }
                    parametrized(shareGameCommand) {
                        bot.editMessageText(
                            ChatId.fromId(chatId),
                            long(0),
                            text = "Выберите игрока:",
                            replyMarkup = inlineKeyboard {
                                connections.find { gameId == game.id && !bot }.sortedBy { it.pos }.forEach { con ->
                                    button(
                                        shareSelectCommand named (con.pos.toString() + ". " + con.name()),
                                        long(0),
                                        con.id
                                    )
                                }
                                button(deleteMsgCommand named "Закрыть", long(0))
                            }
                        )
                    }
                    parametrized(shareSelectCommand) {
                        connections.get(id(1))?.let { con ->
                            gameShares.save(
                                GameShare(
                                    con.id,
                                    game.id
                                )
                            )
                            bot.deleteMessage(ChatId.fromId(chatId), long(0))
                            messageLinks.find { chatId == con.playerId && gameId == game.id }
                                .firstOrNull()
                                ?.let {
                                    showPlayerGameMenu(
                                        con,
                                        ChatId.fromId(con.playerId),
                                        it.messageId,
                                        town.playerMap[con.pos]?.roleData?.id ?: ObjectId(),
                                        it.type,
                                        game,
                                        bot
                                    )
                                }
                        }
                    }
                }

                parametrized(selectWinnerCommand) {
                    val selected = winSelections.find { gameId == game.id }.associate { it.team to it.id }.toMap()
                    val team = str(0)
                    if (team in selected) {
                        winSelections.delete(selected[team]!!)
                    } else {
                        winSelections.save(
                            WinSelection(
                                ObjectId(),
                                game.id,
                                team
                            )
                        )
                    }
                    showEndGameMenu(chatId, long(1), game, bot)
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
                        && game.state == GameState.REHOST
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
                                            state = GameState.CONNECT
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
                        && game.state == GameState.REHOST
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
                                        state = GameState.CONNECT
                                    }
                                    rehosts.delete(game.id)
                                    showLobbyMenu(prevHost.chatId, prevHost.menuMessageId, game, bot)
                                }
                            }
                        }
                    }
                }
                parametrized(gameInfoCommand) {
                    if (game.state == GameState.GAME || game.state == GameState.REVEAL) {
                        val desc =
                            getGameInfo(game, con)
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
                                messageLinks.save(
                                    MessageLink(
                                        ObjectId(),
                                        game.id,
                                        con.playerId,
                                        res.get().messageId
                                    )
                                )
                            }
                        }
                    }
                }
                parametrized(revealRoleCommand) {
                    if (game.state == GameState.GAME || game.state == GameState.REVEAL) {
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
                parametrized(playerMenuCommand) {
                    showPlayerGameMenu(
                        con,
                        ChatId.fromId(con.playerId),
                        long(1),
                        id(0),
                        LinkType.valueOf(str(2)),
                        game,
                        bot
                    )
                }
                block({ notNull { towns[game.id] } }) { town ->
                    parametrized(autoNightPlayCommand) {
                        autoNightActors.get(id(1))?.let { actor ->
                            val id = int(2)
                            val action = actor.actionLinks.mapNotNull { it.action }.firstOrNull { it.wakeId == id }
                            if (action == null) {
                                bot.editMessageText(
                                    ChatId.fromId(chatId),
                                    long(0),
                                    text = "Действие выполнено.\n\nРезультат:\nВаша роль не имеет активных ночных действий.",
                                    replyMarkup = inlineKeyboard {
                                        button(deleteMsgCommand, long(0))
                                    }
                                )
                            } else {
                                if (town.night.size > action.wakeId) {
                                    val wake = town.night[action.wakeId]
                                    if (con.pos !in wake.players.map { it.pos }) {
                                        log.warn("Player ${con.playerId} tried to access action of another player in game ${game.id}")
                                        return@parametrized
                                    }
                                    wake.status = WakeStatus.action()

                                    action.actorLinks.forEach {
                                        it.actor?.connection?.nightPlayerMessage?.let { msg ->
                                            showAutoNightPlayerMenu(
                                                wake, town, it, msg.chatId, msg.messageId, bot
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                    parametrized(forceLeadCommand) {
                        val chat = ChatId.fromId(chatId)
                        val res = bot.sendMessage(
                            chat,
                            text = "Вы уверены, что хотите принимать решение за команду?"
                        )
                        if (res.isSuccess) {
                            val msgId = res.get().messageId
                            bot.editMessageReplyMarkup(
                                chat,
                                msgId,
                                replyMarkup = inlineKeyboard {
                                    row {
                                        button(leadConfirmCommand, long(0), int(1), msgId)
                                        button(deleteMsgCommand named "Нет", msgId)
                                    }
                                }
                            )
                            timedMessages.save(
                                TimedMessage(
                                    ObjectId(),
                                    chatId,
                                    msgId,
                                    Date(System.currentTimeMillis() + deleteForceLeadConfirmAfter.toMillis())
                                )
                            )
                        }
                    }
                    parametrized(leadConfirmCommand) {
                        bot.deleteMessage(ChatId.fromId(chatId), long(2))
                        actorActionLinks.get(id(1))?.let { link ->
                            link.action?.let { action ->
                                action.wake?.let { wake ->
                                    con.autoNightActor?.let { actor ->
                                        actorActionLinks.updateMany({ actionId == action.id }) {
                                            leader = false
                                        }
                                        actorActionLinks.save(
                                            link.copy(leader = true)
                                        )
                                        action.actorLinks.forEach { actorLink ->
                                            actorLink.actor?.let {
                                                it.connection?.nightPlayerMessage?.let { msg ->
                                                    showAutoNightPlayerMenu(
                                                        wake, town, actorLink, msg.chatId, msg.messageId, bot
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                    parametrized(selectTargetCommand) {
                        actorActionLinks.get(id(1))?.let { link ->
                            link.action?.let { action ->
                                action.wake?.let { wake ->
                                    con.autoNightActor?.let { actor ->
                                        val selected = link.selections.associate { it.selection to it.id }
                                        if (int(2) in selected) {
                                            selected[int(2)]?.let { autoNightSelections.delete(it) }
                                        } else {
                                            autoNightSelections.save(
                                                AutoNightSelection(
                                                    ObjectId(),
                                                    action.id,
                                                    link.id,
                                                    int(2)
                                                )
                                            )
                                        }
                                        nightMessageUpdates.save(
                                            NightMessageUpdate(action.id, game.id)
                                        )
                                        showAutoNightPlayerMenu(
                                            wake,
                                            town,
                                            link,
                                            chatId,
                                            long(0),
                                            bot
                                        )
                                    }
                                }
                            }
                        }
                    }
                    parametrized(autoNightSkipCommand) {
                        actorActionLinks.get(id(1))?.let { link ->
                            if (!link.leader) {
                                return@parametrized
                            }
                            link.action?.let { action ->
                                action.wake?.let { wake ->
                                    wake.status = WakeStatus.skipped()
                                    con.autoNightActor?.let { actor ->
                                        action.actorLinks.forEach { actorLink ->
                                            actorLink.actor?.let {
                                                it.connection?.nightPlayerMessage?.let { msg ->
                                                    showAutoNightPlayerMenu(
                                                        wake, town, actorLink, msg.chatId, msg.messageId, bot
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                    parametrized(autoNightDoneCommand) {
                        actorActionLinks.get(id(1))?.let { link ->
                            if (!link.leader) {
                                return@parametrized
                            }
                            link.action?.let { action ->
                                action.wake?.let { wake ->
                                    con.autoNightActor?.let { actor ->
                                        wake.selections.addAll(link.selections.map { it.selection }.toList())
                                        val text = executeNightAction(town, wake, showRoles = false)
                                        wake.status = WakeStatus.woke(text)
                                        action.actorLinks.forEach { actorLink ->
                                            actorLink.actor?.let {
                                                it.connection?.nightPlayerMessage?.let { msg ->
                                                    showAutoNightPlayerMenu(
                                                        wake,
                                                        town,
                                                        actorLink,
                                                        msg.chatId,
                                                        msg.messageId, bot
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

internal fun rolesInPlay(game: Game): MutableList<Role> {
    val roleList = mutableListOf<Role>()
    setups.find { gameId == game.id }.forEach {
        val role = roles.get(it.roleId)!!
        (1..it.count).forEach { _ ->
            roleList.add(role)
        }
    }
    return roleList
}

private fun restartGame(chatId: Long, messageId: Long, bot: Bot, finished: Boolean) {
    bot.deleteMessage(ChatId.fromId(chatId), messageId)
    val gameList = games.find { hostId == chatId }
    if (gameList.size > 1) {
        gameList.dropLast(1).forEach {
            deleteGame(it, bot)
        }
    }
    gameList.lastOrNull()?.let { game ->
        val winners = if (finished) winSelections.find { gameId == game.id }.map { it.team }.toSet() else emptySet()
        if (finished) {
            towns[game.id]?.let { town ->
                val teams = town.players.groupBy { it.team }.mapValues { it.value.size }
                town.players.forEach { player ->
                    connections.get(player.connectionId)?.let { con ->
                        if (!con.bot) {
                            val stat =
                                scriptStats.find { scriptId == game.scriptId && playerId == con.playerId }.firstOrNull()
                                    ?: ScriptStat(
                                        ObjectId(),
                                        game.scriptId,
                                        con.playerId
                                    )
                            scriptStats.save(
                                stat.copy(
                                    gamesPlayed = stat.gamesPlayed + 1,
                                    wins = stat.wins + if (player.team in winners) 1 else 0,
                                    roleStats = stat.roleStats.toMutableMap().apply {
                                        player.roleData.name.let { name ->
                                            put(name, getOrDefault(name, 0) + 1)
                                        }
                                    },
                                    teamStats = stat.teamStats.toMutableMap().apply {
                                        player.team.let { team ->
                                            put(team, getOrDefault(team, 0) + 1)
                                        }
                                    }
                                ))
                            val teamInfo = teamHistories.find { scriptId == game.scriptId && playerId == con.playerId }
                            if (teamInfo.size > Config().teamHistorySize - 1) {
                                teamHistories.delete(teamInfo.minBy { it.date }.id)
                            }
                            teamHistories.save(
                                TeamHistory(
                                    ObjectId(),
                                    game.scriptId,
                                    con.playerId,
                                    player.team,
                                    game.id,
                                    town.players.size,
                                    teams[player.team] ?: 1
                                )
                            )
                        }
                    }
                }
            }
        }

        games.update(game.id) {
            state = GameState.CONNECT
        }

        val names = teamNames.find { gameId == game.id }.associate { it.team to it.name }
        val winText = "Игра завершена! Победители:\n" +
                winners.map { names.getOrDefault(it, it) }.distinct().joinToString("\n")
        resetGame(game, bot)
        accounts.update(chatId) {
            connectionId = null
        }
        connections.updateMany({ gameId == game.id }) {
            notified = false
        }
        connections.find { gameId == game.id }.forEach { con ->
            if (!con.bot) {
                if (winners.isNotEmpty()) {
                    bot.sendMessage(
                        chatId = ChatId.fromId(con.playerId),
                        text = winText
                    )
                }
                showPlayerLobbyMenu(
                    con.playerId,
                    -1L,
                    bot,
                    con.id,
                    if (con.pos < Int.MAX_VALUE) con.pos else 0
                )
            }
        }

        val chat = ChatId.fromId(chatId)
        game.host?.menuMessageId?.let {
            bot.deleteMessage(
                chat,
                it
            )
        }
        bot.sendMessage(
            chat,
            text = if (winners.isEmpty())
                "Игра перезапущена. Возвращаемся в лобби."
            else winText,
            replyMarkup = mafiaKeyboard(chatId)
        )
        showLobbyMenu(chatId, -1L, game, bot, true)
    }
}

private fun endDay(
    game: Game,
    town: Town,
    messageId: Long,
    bot: Bot,
) {
    bot.deleteMessage(ChatId.fromId(game.hostId), messageId)
    accounts.update(game.hostId) {
        menuMessageId = -1L
    }
    deleteGameTimers(bot, game.id)
    val hideRoles = game.host?.settings?.hideRolesMode ?: false
    bot.sendMessage(
        ChatId.fromId(game.hostId),
        "Результат дня:\n${shortLog(town, !hideRoles).ifBlank { "Не произошло никаких событий" }}"
    )
}

fun getGameInfo(game: Game, con: Connection): String {
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
    return desc
}

private fun showReassignMenu(
    roleOptions: List<Role>, bot: Bot, chatId: Long, messageId: Long,
    connectionId: ConnectionId, menuCommand: Command
) {
    val res = bot.editMessageReplyMarkup(
        ChatId.fromId(chatId),
        messageId,
        replyMarkup = inlineKeyboard {
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