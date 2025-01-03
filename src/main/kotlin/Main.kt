package org.example

import com.github.kotlintelegrambot.Bot
import com.github.kotlintelegrambot.bot
import com.github.kotlintelegrambot.dispatch
import com.github.kotlintelegrambot.dispatcher.callbackQuery
import com.github.kotlintelegrambot.dispatcher.text
import com.github.kotlintelegrambot.entities.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.bson.types.ObjectId
import org.example.game.Town
import org.example.lua.*
import org.slf4j.Logger
import java.io.File
import java.nio.file.Files
import java.nio.file.Files.createDirectories
import java.nio.file.Files.exists
import java.nio.file.Path
import java.nio.file.Paths
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.*
import kotlin.math.ceil
import kotlin.math.max
import kotlin.reflect.KClass

// todo replace objectid and get rid of mongo dependencies
// todo add getters for loading related object from db by their id, make it lazy, but updating on data change (generate getters with annotations?)
// todo optimize db by using better serialization formats and maybe create some mongoshell-like util for hand processing data (kotlin-script?)
// todo rework town, make it savable
// todo add ability for changing game host
// todo auto-update lobby list for some time after openning it or pressing 'update' button
// todo check why lobby message delete feature does not work
// todo add "playing host" mode
// todo get rid of footer keyboard for everything except for ending the game
// todo first send warning to host when game TTL is close, only then if host didn't react force-end game
// todo add capabilities for graceful shutdown for bot (make sure to delete temp folder before finishing)

// todo update, exposed in git history
const val botToken = "7381702333:AAFDKZrYiSMi0Ugunm55v7syJcysS9gmcBY"
const val gameDurationLimitHours = 6
const val gameHistoryTtlHours = 24
const val sendPendingAfterSec = 3
const val roleNameLen = 32
const val roleDescLen = 280
val numbers = arrayOf("0️⃣", "1️⃣", "2️⃣", "3️⃣", "4️⃣", "5️⃣", "6️⃣", "7️⃣", "8️⃣", "9️⃣")
val dateFormat: DateTimeFormatter = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm")

val resetAccount: Account.() -> Unit = {
    state = AccountState.Menu
    menuMessageId = -1L
    hostMessageId = -1L
    setupMessageId = -1L
    dayMessageId = -1L
    connectionId = null
}
val gameFilter: Connection.(Game) -> Boolean = { game -> gameId == game.id }
val scriptDir: Path = Files.createTempDirectory("scripts")
val log: Logger = logger<Main>()

class Main

fun main() {
    //val connectionString = "mongodb://EdgeDom:WontH4CKAGA1n@localhost:44660/?retryWrites=true&w=majority"
    val path = Config().path
    scriptDir.toFile().deleteOnExit()

    val towns = mutableMapOf<Long, Town>()

    fun Bot.error(chatId: Long, text: String = "Неизвестная команда.") {
        val chat = ChatId.fromId(chatId)
        val res = sendMessage(
            chat,
            text
        )
        if (res.isSuccess) {
            editMessageReplyMarkup(
                chat,
                res.get().messageId,
                replyMarkup = inlineKeyboard {
                    button(deleteMsgCommand, res.get().messageId)
                }
            )
        }
    }

    val textHandler = TextHandler(errorProcessor = { bot.error(chatId) }) {
        val account = accounts.get(chatId)
        block(account == null) {
            any {
                initAccount(username, accounts, chatId, bot)
            }
        }

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
            simple(adminCommand, adminPanelCommand) {
                if (messageId != null) {
                    bot.deleteMessage(ChatId.fromId(chatId), messageId)
                }
                val res = bot.sendMessage(
                    ChatId.fromId(chatId),
                    "Меню администратора:"
                )
                if (res.isSuccess) {
                    val messageId = res.get().messageId
                    showAdmin(checks, chatId, messageId, bot)
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

        when (account!!.state) {
            AccountState.Init -> {
                any {
                    val chat = ChatId.fromId(chatId)
                    val res = bot.sendMessage(
                        chat,
                        "Введено имя: <b>$query</b>\nВы хотите его установить?",
                        parseMode = ParseMode.HTML
                    )

                    if (res.isSuccess) {
                        val msgId = res.get().messageId
                        bot.editMessageReplyMarkup(
                            chat,
                            msgId,
                            replyMarkup = inlineKeyboard {
                                row {
                                    button(acceptNameCommand, query, msgId, messageId ?: -1)
                                    button(cancelName, msgId, messageId ?: -1)
                                }
                            }
                        )
                    }
                }
            }

            AccountState.Menu -> {
                simple(hostCommand) {
                    if (!canHost(checks, hostInfos, { this.chatId == chatId }, hostRequests, chatId)) {
                        bot.error(chatId, "Возможность создания игры недоступна для вашего аккаунта.")
                        return@simple
                    }

                    try {
                        val id = games.save(Game(ObjectId(), chatId, Int.MAX_VALUE))
                        val game = games.get(id)
                        initGame(game, path, chatId, -1L, bot, messageId ?: -1L)
                    } catch (e: Exception) {
                        log.error("Unable to host game for ${account.fullName()}", e)
                    }
                }
                simple(updateForcedCommand, menuCommand, startCommand) {
                    if (messageId != null) {
                        bot.deleteMessage(ChatId.fromId(chatId), messageId)
                        showGames(chatId, -1L, bot, games, accounts)
                    }
                }
                simple(changeNameCommand) {
                    bot.deleteMessage(ChatId.fromId(chatId), messageId ?: -1L)
                    initAccount(username, accounts, chatId, bot)
                }
            }

            AccountState.Admin -> {
                any {
                    val adminMenu = adminMenus.get(chatId)
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

                                showHostSettings(adminMenu.messageId, hostInfos, accounts, chatId, bot)
                                messageId?.let { id -> bot.deleteMessage(ChatId.fromId(chatId), id) }
                                adminMenus.update(chatId) {
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

            AccountState.Host -> {
                simple(rehostCommand) {
                    bot.deleteMessage(ChatId.fromId(chatId), messageId ?: -1L)
                    if (!canHost(checks, hostInfos, { this.chatId == chatId }, hostRequests, chatId)) {
                        bot.error(chatId, "Возможность создания игры недоступна для вашего аккаунта.")
                        return@simple
                    }

                    games.find { host == chatId }.singleOrNull()?.let { game ->
                        //updateSetup(path, roles, game, types, orders)
                        if (checks.get(CheckOption.GAME_MESSAGES)) {
                            gameMessages.get(game.id)?.let {
                                it.list.forEach { msg ->
                                    bot.deleteMessage(
                                        ChatId.fromId(msg.chatId),
                                        msg.messageId
                                    )
                                }
                            }
                        }

                        games.update(game.id) {
                            state = GameState.Connect
                            playerCount = Int.MAX_VALUE
                        }
                        pending.deleteMany { host == chatId }
                        //setups.deleteMany { gameId == game.id }
                        pairings.deleteMany { gameId == game.id }
                        towns.remove(chatId)
                        accounts.update(chatId) {
                            hostMessageId = -1L
                            setupMessageId = -1L
                            dayMessageId = -1L
                            connectionId = null
                        }
                        connections.updateMany({ gameId == game.id }) {
                            notified = false
                        }

                        showLobby(chatId, account.menuMessageId, connections, game, bot, accounts)
                    }
                }
                simple(stopGameCommand, menuCommand) {
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
                    games.find { host == chatId }.singleOrNull()
                        ?.let { game -> stopGame(gameFilter, game, towns, chatId, bot) }
                }
                any {
                    games.find { host == chatId }.singleOrNull()?.let { game ->
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
                                { host == chatId },
                                { state = GameState.Connect }
                            )
                            showLobby(chatId, account.menuMessageId, connections, game, bot, accounts)
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
                                    showLobby(chatId, account.menuMessageId, connections, game, bot, accounts)
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


            AccountState.Lobby -> {
                connections.find { player == chatId }.singleOrNull()?.let { con ->
                    simple(leaveGameCommand, menuCommand, startCommand) {
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
                                val chat = ChatId.fromId(game.host)
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

            else -> bot.error(chatId)
        }
    }

    val queryHandler = QueryHandler {
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
            showMenu(chatId, games, accounts, "Добро пожаловать, ${str(0)}", bot)
            bot.deleteMessage(chat, long(1))
            bot.deleteMessage(chat, long(2))
        }
        parametrized(cancelName) {
            bot.deleteMessage(chat, long(0))
            bot.deleteMessage(chat, long(1))
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
        playerQueries(towns)
    }

    val bot = bot {
        token = Config().botToken
        dispatch {
            text {
                handle(message.text ?: "") by textHandler
            }

            callbackQuery {
                handle(callbackQuery.data) by queryHandler
            }
        }
    }

    runBlocking {
        launch {
            bot.startPolling()
        }

        launch {
            while (true) {
                try {
                    val now = Date()
                    val filter: Bomb.() -> Boolean = { date.before(now) }
                    bombs.find(filter).forEach {
                        bot.deleteMessage(ChatId.fromId(it.chatId), it.messageId)
                    }
                    bombs.deleteMany(filter)
                } catch (e: Exception) {
                    log.error("Unable to process bomb messages", e)
                }
                delay(10000)
            }
        }

        launch {
            while (true) {
                try {
                    hostInfos.deleteMany {
                        (timeLimit && until.before(Date())) || (gameLimit && left < 1)
                    }
                } catch (e: Exception) {
                    log.error("Unable to process host info expires", e)
                }
                delay(60000)
            }
        }

        launch {
            while (true) {
                try {
                    val set = mutableSetOf<Pending>()
                    pending.find { date.toInstant().isBefore(Instant.now().minusSeconds(sendPendingAfterSec.toLong())) }
                        .forEach {
                            set.add(it)
                        }
                    pending.deleteMany { this in set }
                    set.forEach {
                        accounts.get(it.host)?.let {
                            games.find { host == it.chatId }.singleOrNull()?.let { game ->
                                if (game.state == GameState.Connect) {
                                    showLobby(it.chatId, it.menuMessageId, connections, game, bot, accounts)
                                } else if (game.state == GameState.Reveal) {
                                    showRevealMenu(game, bot, it.chatId, it.menuMessageId)
                                }
                            }
                        }
                    }
                } catch (e: Exception) {
                    log.error("Unable to process pending", e)
                }
                delay(1000)
            }
        }

        launch {
            while (true) {
                try {
                    timers.find { active }.forEach {
                        val now = System.currentTimeMillis()

                        val update = now - it.timestamp
                        timers.update(it.chatId) {
                            time += update
                            timestamp = now
                            if (timerText(it.time) != timerText(it.time + update)) {
                                updated = true
                            }
                        }
                    }
                    timers.find { updated }.forEach {
                        updateTimer(it, bot, timers)
                    }
                } catch (e: Exception) {
                    log.error("Unable to process timers", e)
                }
                delay(200)
            }
        }

        launch {
            while (true) {
                try {
                    games.find {
                        val date = playedAt?: createdAt
                        date.toInstant().isBefore(Instant.now().minusSeconds(gameDurationLimitHours * 60 * 60L))
                    }.forEach {
                        stopGame(
                            gameFilter,
                            it,
                            towns,
                            it.host,
                            bot,
                            accounts.get(it.host)?.menuMessageId ?: -1L
                        )
                    }
                } catch (e: Exception) {
                    log.error("Unable to process game TTLs", e)
                }
                delay(60000)
            }
        }

        launch {
            while (true) {
                try {
                    gameHistory.deleteMany {
                        playedAt.toInstant().isBefore(Date().toInstant().minusSeconds(gameHistoryTtlHours * 60 * 60L))
                    }
                } catch (e: Exception) {
                    log.error("Unable to process game history TTLs", e)
                }
                delay(60000)
            }
        }
    }
}

private fun showAdMenu(chat: ChatId.Id, bot: Bot) {
    val active = games.find()
    val recent = gameHistory.find()
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

                fun name(game: Game, connections: List<Connection>) = (accounts.get(game.host)?.fullName()
                    ?: "") + " (" + dateFormat.format(
                    ZonedDateTime.ofInstant(
                        game.createdAt.toInstant(),
                        ZoneId.systemDefault()
                    )
                ) + ") - ${connections.size} игроков"

                active.forEach {
                    button(sendAdCommand named name(it, connections.find { gameId == it.id }), it.id, msgId)
                }
                if (recent.isNotEmpty()) {
                    button(blankCommand named "Недавние")
                }
                recent.forEach {
                    button(sendAdHistoryCommand named name(it.game, it.connections), it.id, msgId)
                }
                button(deleteMsgCommand, res.get().messageId)
            }
        )
    }
}

private fun isAdmin(chatId: Long) = chatId == Config().author || admins.get(chatId) != null

private fun initGame(game: Game?, path: String, chatId: Long, messageId: Long, bot: Bot, deleteId: Long) {
    if (game != null) {
        updateSetup(path, roles, game, types, orders)
        val chat = ChatId.fromId(chatId)
        bot.deleteMessage(chat, deleteId)
        bot.sendMessage(
            chat,
            "Игра создана. Ожидайте присоединения игроков.",
            replyMarkup = adminKeyboard(chatId) {
                button(stopGameCommand)
            }
        )
        val msgId = showLobby(chatId, messageId, connections, game, bot, accounts, true)
        accounts.update(chatId) {
            state = AccountState.Host
            menuMessageId = msgId
        }
    } else {
        error("Не удалось создать игру. Попробуйте еще раз.")
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
            updateCheck(str(0), checks)
            showAdmin(checks, chatId, long(1), bot)
        }
        parametrized(hostRequestCommand) {
            showHostRequests(hostRequests, accounts, long(0), chatId, bot)
        }
        parametrized(hostSettingsCommand) {
            showHostSettings(long(0), hostInfos, accounts, chatId, bot)
        }
        parametrized(adminSettingsCommand) {
            val messageId = long(0)
            showAdminSettings(chatId, messageId, bot)
        }
        parametrized(timeLimitOnCommand) {
            val res = bot.sendMessage(ChatId.fromId(chatId), "Введите срок действия разрешения в днях:")
            if (res.isSuccess) {
                val desc = res.get().messageId
                openAdminMenu(desc, AdminState.HOST_TIME)
            }
        }
        parametrized(timeLimitOffCommand) {
            hostInfos.update(long(0)) { timeLimit = false }
            showHostSettings(long(1), hostInfos, accounts, chatId, bot)
        }
        parametrized(gameLimitOnCommand) {
            val res = bot.sendMessage(ChatId.fromId(chatId), "Введите количество разрешенных игр:")
            if (res.isSuccess) {
                val desc = res.get().messageId
                openAdminMenu(desc, AdminState.HOST_GAMES)
            }
        }
        parametrized(gameLimitOffCommand) {
            hostInfos.update(long(0)) { gameLimit = false }
            showHostSettings(long(1), hostInfos, accounts, chatId, bot)
        }
        parametrized(shareCommand) {
            hostInfos.get(long(0))?.let {
                hostInfos.update(long(0)) { canShare = !it.canShare }
                showHostSettings(long(1), hostInfos, accounts, chatId, bot)
            }
        }
        parametrized(deleteHostCommand) {
            hostInfos.delete(long(0))
            showHostSettings(long(1), hostInfos, accounts, chatId, bot)
        }
        parametrized(allowHostCommand) {
            hostInfos.save(HostInfo(ObjectId(), long(0)))
            hostRequests.delete(long(0))
            showHostRequests(hostRequests, accounts, long(1), chatId, bot)
        }
        parametrized(denyHostCommand) {
            hostRequests.delete(long(0))
            showHostRequests(hostRequests, accounts, long(1), chatId, bot)
        }
        parametrized(removeAdminCommand) {
            admins.delete(long(0))
            showAdminSettings(chatId, long(1), bot)
        }
        parametrized(adminBackCommand) {
            showAdmin(checks, chatId, long(0), bot)
            accounts.update(chatId) {
                state = AccountState.Menu
            }
            adminMenus.delete(chatId)
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

private fun showAdminSettings(chatId: Long, messageId: Long, bot: Bot) {
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

fun showAd(game: Game, connections: List<Connection>, bot: Bot, messageId: Long, chatId: Long) {
    val id = ObjectId()
    val chat = ChatId.fromId(chatId)
    bot.editMessageText(
        chat,
        messageId,
        text = "Возможные сообщения:"
    )
    val adList = ads.find()
    val messages = adList.map {
        bot.sendMessage(
            chat,
            it.text,
            replyMarkup = inlineKeyboard {
                button(adSelectCommand, it.id, id)
            }
        ).get().messageId
    }
    val lastId = messages.last()
    bot.editMessageReplyMarkup(
        chat,
        lastId,
        replyMarkup = inlineKeyboard {
            button(adSelectCommand, adList.last().id, id)
            button(adClearCommand, id)
        }
    )
    adTargets.save(AdTarget(id, game, connections, messages + listOf(messageId)))
}


fun selectAd(game: Game, connections: List<Connection>, bot: Bot, ad: Message) {
    val host = game.host
    fun send(chatId: Long) {
        val res = bot.sendMessage(
            ChatId.fromId(chatId),
            ad.text
        )
        if (res.isSuccess) {
            bombs.save(
                Bomb(
                    ObjectId(),
                    chatId,
                    res.get().messageId,
                    Date(System.currentTimeMillis() + 1000 * 60 * 60)
                )
            )
        }
    }
    send(host)
    connections.forEach {
        send(it.player)
    }
}

private fun ParametrizedProcessor.HandlerContext.openAdminMenu(desc: Long, state: AdminState) {
    accounts.update(chatId) {
        this.state = AccountState.Admin
    }
    if (adminMenus.get(chatId) == null) {
        adminMenus.save(
            AdminMenu(
                ObjectId(),
                chatId,
                state,
                long(0),
                long(1),
                desc
            )
        )
    } else {
        adminMenus.update(chatId) {
            this.state = state
            editId = long(0)
            messageId = long(1)
            descId = desc
        }
    }
}

private suspend fun ContainerBlock.ParametrizedContext.menuQueries() {
    parametrized(joinCommand) {
        val game = games.get(id(0))
        val messageId = long(1)
        if (game == null || game.state == GameState.Game) {
            bot.sendMessage(
                ChatId.fromId(chatId),
                if (game == null) {
                    "Не удалось подключиться к игре. Обновляем список игр доступных для подключения."
                } else {
                    "Невозможно подключиться к игре. Ведущий уже начал игру. Обновляем список игр доступных для подключения."
                }
            )
            showGames(chatId, messageId, bot, games, accounts)
            return@parametrized
        }

        withAccount(accounts, chatId) { account ->
            if (account.state != AccountState.Menu) {
                bot.sendMessage(
                    ChatId.fromId(chatId),
                    "Не удалось подключиться к игре. Вернитесь в меню прежде чем подключаться к играм."
                )
                return@withAccount
            }

            val kick = kicks.find { gameId == game.id && player == chatId }.singleOrNull()
            if (kick != null) {
                bot.sendMessage(
                    ChatId.fromId(chatId),
                    "Не удалось подключиться к игре. Ведущий исключил вас из игры."
                )
                return@withAccount
            }

            if (messageId != -1L) {
                bot.deleteMessage(ChatId.fromId(chatId), messageId)
            }
            bot.sendMessage(
                ChatId.fromId(chatId),
                "Подключение к игре выполнено.",
                replyMarkup = adminKeyboard(chatId) {
                    button(leaveGameCommand)
                }
            )
            val id = ObjectId()
            connections.save(
                Connection(
                    id,
                    game.id,
                    chatId,
                    account.name,
                    if (username.isNotBlank()) "@$username" else ""
                )
            )
            accounts.update(chatId) {
                state = AccountState.Lobby
                menuMessageId = -1L
                hostMessageId = -1L
            }
            pending.save(Pending(ObjectId(), game.host))
            bot.deleteMessage(ChatId.fromId(chatId), messageId)
            val msgId = showNumPrompt(chatId, -1L, bot, id)
            accounts.update(chatId) {
                menuMessageId = msgId
            }
        }
    }
    parametrized(updateCommand) {
        showGames(chatId, long(0), bot, games, accounts)
    }
}

private suspend fun ContainerBlock.ParametrizedContext.connectionManagingQueries() {
    /** with Connection **/
    block({ notNull { if (isId(0)) connections.get(id(0)) else null } }) { con ->
        /** with Game **/
        block({ notNull { games.get(con.gameId) } }) { game ->
            parametrized(playerNumCommand) {
                connections.update(id(0)) {
                    pos = int(1)
                }
                pending.save(Pending(ObjectId(), game.host))
                bot.editMessageReplyMarkup(
                    ChatId.fromId(chatId),
                    long(2),
                    replyMarkup = numpadKeyboard(
                        "Номер игрока",
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

            /** is game host **/
            block(game.host == chatId) {
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
                                button(kickCommand, id(0))
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
                            ChatId.fromId(con.player),
                            "Ведущий просит вас поднять руку"
                        )
                    }
                }

                parametrized(kickCommand) {
                    connections.delete(con.id)
                    if (!con.bot) {
                        accounts.update(con.player) {
                            state = AccountState.Menu
                        }
                        showMenu(
                            con.player,
                            games,
                            accounts,
                            "Ведущий исключил вас из игры. Возвращаемся в главное меню.",
                            bot
                        )
                        kicks.save(
                            Kick(
                                ObjectId(),
                                con.gameId,
                                con.player
                            )
                        )
                    }
                    // todo update by game id not host chat id
                    pending.save(Pending(ObjectId(), chatId))
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

private fun hostSetPlayerNum(
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
                accounts.get(con.player)?.let { acc ->
                    showNumPrompt(acc.chatId, acc.menuMessageId, bot, connectionId, pos)
                }
            }
        }
    }
    games.update(game.id) {
        state = GameState.Connect
    }
    showLobby(chatId, messageId, connections, game, bot, accounts)
}

private fun setPlayerNum(
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
    pending.save(Pending(ObjectId(), game.host))
    val res = bot.sendMessage(
        ChatId.fromId(chatId),
        Const.Message.numSaved
    )
    if (res.isSuccess) {
        bombs.save(Bomb(ObjectId(), chatId, res.get().messageId, Date(System.currentTimeMillis() + 1000 * 30)))
    }
}

private fun <T : Any> numpadKeyboard(
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
        row { button(blankCommand named text) }
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
            button(
                numCommand named "⌫",
                target,
                if (value.toString().length > 1) value.toString().dropLast(1) else "0",
                messageId
            )
        }
        row {
            button(cancelCommand, messageId)
            if (value != 0 && acceptCommand != null) {
                button(acceptCommand, target, value, messageId)
            }
        }
    }

private suspend fun ContainerBlock.ParametrizedContext.hostQueries(towns: MutableMap<Long, Town>) {
    /** with Game of this host **/
    block({ notNull { games.find { host == chatId }.singleOrNull() } }) { game ->
        parametrized(menuKickCommand) {
            showKickMenu(game, long(0), bot, chatId)
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
                    accounts.get(con.player)?.let { acc ->
                        bot.deleteMessage(ChatId.fromId(acc.chatId), acc.menuMessageId)
                        val msgId = showNumPrompt(acc.chatId, -1L, bot, con.id)
                        accounts.update(con.player) {
                            menuMessageId = msgId
                        }
                    }
                }
            }
            accounts.get(chatId)?.let {
                showLobby(chatId, it.menuMessageId, connections, game, bot, accounts)
            }
        }
        parametrized(hostBackCommand, menuLobbyCommand) {
            games.update(game.id) {
                state = GameState.Connect
            }
            showLobby(chatId, long(0), connections, game, bot, accounts)
        }
        parametrized(menuRolesCommand) {
            games.update(game.id) {
                state = GameState.Roles
            }
            if (setups.find { gameId == game.id && count > 0 }.isEmpty()) {
                setups.deleteMany { gameId == game.id }
                roles.find { gameId == game.id }.forEach {
                    setups.save(Setup(ObjectId(), it.id, game.id, it.displayName, it.index))
                }
            }
            showRoles(chatId, long(0), setups, connections, bot, game)
        }
        parametrized(menuPreviewCommand) {
            games.update(game.id) {
                state = GameState.Preview
            }
            showPreview(bot, chatId, long(0), pairings, connections, game)
        }
        parametrized(gameCommand) {
            val cons = connections.find { gameId == game.id }
            val numMap = mutableMapOf<Int, Int>()
            cons.forEach {
                numMap.compute(it.pos) { _, v ->
                    if (v == null) 1 else v + 1
                }
            }
            val noNum = cons.filter { it.pos == Int.MAX_VALUE }
            if (noNum.isNotEmpty()) {
                sendClosable("Невозможно начать игру:\n" + noNum.joinToString("\n") { "Не указан номер для игрока ${it.name()}" })
                return@parametrized
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
            if (errors.isNotEmpty()) {
                sendClosable("Невозможно начать игру:\n" + errorCons.joinToString("\n") { "Не указана роль для игрока ${it.name()}" })
                return@parametrized
            }

            modes.save(
                GameMode(
                    ObjectId(),
                    game.id,
                    Mode.OPEN
                )
            )
            hostInfos.updateMany(
                { this.chatId == chatId && this.gameLimit },
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
            showLobby(chatId, long(0), connections, game, bot, accounts)
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
            showRoles(chatId, long(1), setups, connections, bot, game)
        }
        parametrized(incrCommand) {
            setups.update(id(0)) {
                count = max(count + 1, 0)
            }
            showRoles(chatId, long(1), setups, connections, bot, game)
        }
        parametrized(resetRolesCommand) {
            setups.updateMany({ gameId == id(0) }) {
                count = 0
            }
            showRoles(chatId, long(1), setups, connections, bot, game)
        }
        parametrized(previewCommand, updateRolesCommand) {
            modes.deleteMany { gameId == game.id }
            deleteUserTimers(timers, bot) { this.chatId == chatId }

            var roleCount = 0
            val roleList = mutableListOf<Role>()
            setups.find { gameId == game.id }.forEach {
                roleCount += it.count
                val role = roles.get(it.roleId)!!
                (1..it.count).forEach { _ ->
                    roleList.add(role)
                }
            }

            games.update(game.id) {
                state = GameState.Preview
            }
            pairings.deleteMany { gameId == game.id }
            val cons = mutableListOf<Connection>()
            connections.find { gameFilter(game) }.sortedWith(compareBy({ it.pos }, { it.createdAt })).forEach {
                cons.add(it)
            }
            roleList.shuffle()
            roleList.indices.forEach {
                val role = roleList[it]
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
            showPreview(bot, chatId, long(1), pairings, connections, game)
        }

        parametrized(gameModeCommand) {
            Mode.valueOf(str(0)).let { mode ->
                modes.update(game.id) { this.mode = mode }
                if (checks.get(CheckOption.REVEAL_MENU)) {
                    games.update(game.id) {
                        state = GameState.Reveal
                    }
                    val list = pairings.find { gameId == game.id }.mapNotNull { pair ->
                        connections.update(pair.connectionId) { notified = false }
                        connections.get(pair.connectionId)?.let { con ->
                            if (con.bot) {
                                return@mapNotNull null
                            }
                            accounts.get(con.player)?.let { acc ->
                                val playerChat = ChatId.fromId(con.player)
                                bot.deleteMessage(playerChat, acc.menuMessageId)
                                val res = bot.sendMessage(
                                    playerChat,
                                    "Роли выданы"
                                )
                                if (res.isSuccess) {
                                    val msgId = res.get().messageId
                                    bot.editMessageReplyMarkup(
                                        playerChat,
                                        msgId,
                                        replyMarkup = inlineKeyboard {
                                            button(revealRoleCommand, pair.roleId, msgId)
                                            button(gameInfoCommand, game.id)
                                        }
                                    )
                                    MessageLink(con.player, msgId)
                                } else {
                                    null
                                }
                            }
                        }
                    }
                    if (checks.get(CheckOption.GAME_MESSAGES)) {
                        gameMessages.save(
                            GameMessage(
                                ObjectId(),
                                game.id,
                                list
                            )
                        )
                    }
                    showRevealMenu(game, bot, chatId, long(1))
                } else {
                    bot.deleteMessage(ChatId.fromId(chatId), long(1))
                    startGame(
                        accounts,
                        setups,
                        roles,
                        pairings,
                        orders,
                        types,
                        chatId,
                        towns,
                        games,
                        bot,
                        modes
                    )
                }
            }
        }
        parametrized(proceedCommand) {
            bot.deleteMessage(ChatId.fromId(chatId), long(0))
            startGame(
                accounts,
                setups,
                roles,
                pairings,
                orders,
                types,
                chatId,
                towns,
                games,
                bot,
                modes
            )
        }
        parametrized(settingsCommand) {
            modes.get(game.id)?.let {
                val res = bot.sendMessage(
                    ChatId.fromId(chatId),
                    "Настройки",
                    replyMarkup = inlineKeyboard { button(blankCommand named "Загрузка...") }
                )
                if (res.isSuccess) {
                    val messageId = res.get().messageId
                    bot.editMessageReplyMarkup(
                        ChatId.fromId(chatId),
                        messageId,
                        replyMarkup = settingsButtons(it, messageId, long(0))
                    )
                }
            }
        }
        parametrized(nightCommand) {
            try {
                bot.deleteMessage(ChatId.fromId(chatId), long(0))
                accounts.update(chatId) {
                    menuMessageId = -1L
                }
                deleteUserTimers(timers, bot) { this.chatId == chatId }
                towns[chatId]?.let { town ->
                    bot.sendMessage(
                        ChatId.fromId(chatId),
                        "Результат дня:\n${shortLog(town).ifBlank { "Не произошло никаких событий" }}"
                    )
                    town.updateTeams()
                    town.prepNight()
                    showNightRole(town, chatId, bot, -1L)
                }
            } catch (e: Exception) {
                log.error("Unable to start night, game: $game", e)
            }
        }
        parametrized(timerCommand) {
            if (timers.get(chatId) == null) {
                val res = bot.sendMessage(
                    ChatId.fromId(chatId),
                    "Таймер: 00:00"
                )
                if (res.isSuccess) {
                    val messageId = res.get().messageId
                    val timer = Timer(ObjectId(), chatId, messageId, System.currentTimeMillis(), 0L)
                    timers.save(
                        timer
                    )
                }
            }
        }
        parametrized(timerDeleteCommand) {
            timers.get(chatId)?.let {
                deleteTimer(timers, it, bot)
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

        parametrized(acceptStopCommand) {
            stopGame(gameFilter, game, towns, chatId, bot, long(0), long(1))
        }

        block({ notNull { towns[chatId] } }) { town ->
            val mode = modes.get(game.id)
            parametrized(dayDetailsCommand) {
                accounts.get(chatId)?.let { acc ->
                    val msgId = if (acc.menuMessageId != -1L) acc.menuMessageId else long(1)
                    showPlayerDayDesc(town, mode?.fallMode ?: false, int(0), msgId, chatId, bot)
                }
            }
            parametrized(dayBackCommand) {
                showDay(town, chatId, long(0), towns, accounts, bot, modes, game)
            }
            parametrized(statusCommand) {
                town.changeProtected(int(0))
                if (mode?.detailedView != true && checks.get(CheckOption.KEEP_DETAILS)) {
                    accounts.get(chatId)?.let { acc ->
                        val msgId = if (acc.menuMessageId != -1L) acc.menuMessageId else long(1)
                        showPlayerDayDesc(town, modes.get(game.id)?.fallMode ?: false, int(0), msgId, chatId, bot)
                    }
                } else {
                    showDay(town, chatId, long(1), towns, accounts, bot, modes, game)
                }
            }
            parametrized(killCommand) {
                town.setAlive(int(0), false)
                if (mode?.detailedView != true && checks.get(CheckOption.KEEP_DETAILS)) {
                    accounts.get(chatId)?.let { acc ->
                        val msgId = if (acc.menuMessageId != -1L) acc.menuMessageId else long(1)
                        showPlayerDayDesc(town, modes.get(game.id)?.fallMode ?: false, int(0), msgId, chatId, bot)
                    }
                } else {
                    showDay(town, chatId, long(1), towns, accounts, bot, modes, game)
                }
            }
            parametrized(reviveCommand) {
                town.setAlive(int(0), true)
                if (mode?.detailedView != true && checks.get(CheckOption.KEEP_DETAILS)) {
                    accounts.get(chatId)?.let { acc ->
                        val msgId = if (acc.menuMessageId != -1L) acc.menuMessageId else long(1)
                        showPlayerDayDesc(town, modes.get(game.id)?.fallMode ?: false, int(0), msgId, chatId, bot)
                    }
                } else {
                    showDay(town, chatId, long(1), towns, accounts, bot, modes, game)
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
                        showPlayerDayDesc(town, modes.get(game.id)?.fallMode ?: false, int(0), msgId, chatId, bot)
                    }
                } else {
                    showDay(town, chatId, long(1), towns, accounts, bot, modes, game)
                }
            }
            parametrized(selectCommand) {
                nightSelection(town, int(0), chatId, bot, long(1))
            }
            parametrized(nextRoleCommand) {
                town.selections.clear()
                showNightRole(town, chatId, bot, long(0))
            }
            parametrized(skipRoleCommand) {
                town.index++
                town.selections.clear()
                showNightRole(town, chatId, bot, long(0))
            }
            parametrized(dayCommand) {
                bot.deleteMessage(ChatId.fromId(chatId), long(0))
                startDay(town, chatId, towns, accounts, bot, modes, game)
            }
            if (mode != null) {
                parametrized(filterCommand) {
                    towns[chatId]?.let { town ->
                        val index =
                            if (DayView.entries.size > mode.dayView.ordinal + 1) mode.dayView.ordinal + 1 else 0
                        val next = DayView.entries[index]
                        modes.update(game.id) {
                            dayView = next
                        }
                        showDay(town, chatId, long(0), towns, accounts, bot, modes, game)

                    }
                }
                parametrized(fallModeCommand) {
                    modes.update(game.id) { fallMode = !fallMode }
                    showDay(town, chatId, long(1), towns, accounts, bot, modes, game)
                    bot.editMessageReplyMarkup(
                        ChatId.fromId(chatId),
                        long(0),
                        replyMarkup = settingsButtons(mode.copy(fallMode = !mode.fallMode), long(0), long(1))
                    )
                }
                parametrized(detailedViewCommand) {
                    modes.update(game.id) { detailedView = !detailedView }
                    showDay(town, chatId, long(1), towns, accounts, bot, modes, game)
                    bot.editMessageReplyMarkup(
                        ChatId.fromId(chatId),
                        long(0),
                        replyMarkup = settingsButtons(mode.copy(detailedView = !mode.detailedView), long(0), long(1))
                    )
                }
            }
        }
    }
}

private fun showRevealMenu(game: Game, bot: Bot, chatId: Long, messageId: Long) {
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
                    if (leftCon != null) {
                        button(blankCommand named "${leftCon.pos}. ${leftCon.name()}")
                        val textLeft = if (leftCon.notified) "🫡" else "🌚"
                        if (leftCon.bot) {
                            button(markBotCommand named textLeft, leftCon.id, messageId)
                        } else {
                            button(blankCommand named textLeft)
                        }
                    } else {
                        button(blankCommand)
                        button(blankCommand)
                    }
                    if (rightCon != null) {
                        button(blankCommand named "${rightCon.pos}. ${rightCon.name()}")
                        val textRight = if (rightCon.notified) "🫡" else "🌚"
                        if (rightCon.bot) {
                            button(markBotCommand named textRight, rightCon.id, messageId)
                        } else {
                            button(blankCommand named textRight)
                        }
                    } else {
                        button(blankCommand)
                        button(blankCommand)
                    }
                }
                row {
                    val leftName = list[0].role?.displayName
                    button(if (leftName != null) blankCommand named leftName else blankCommand)
                    val rightName = if (list.size < 2) null else list[1].role?.displayName
                    button(if (rightName != null) blankCommand named rightName else blankCommand)
                }
            }

            button(blankCommand named "Ознакомлены: $notified / ${cons.size}")
            button(proceedCommand, messageId)
        }
    )
}

fun showPlayerDayDesc(town: Town, fallMode: Boolean, playerPos: Int, messageId: Long, chatId: Long, bot: Bot) {
    town.playerMap[playerPos]?.let<Person, Unit> { player ->
        bot.editMessageReplyMarkup(
            ChatId.fromId(chatId),
            messageId,
            replyMarkup = inlineKeyboard {
                button(blankCommand named "Детали")
                button(dayDetailsCommand named desc(player), playerPos, messageId)
                row {
                    playerDayDesc(player, messageId, fallMode)
                }
                button(dayBackCommand, messageId)
            }
        )
        return@let
    }
}

private fun stopGame(
    gameFilter: Connection.(Game) -> Boolean,
    game: Game,
    towns: MutableMap<Long, Town>,
    chatId: Long,
    bot: Bot,
    gameMessageId: Long = -1L,
    popupMessageId: Long = -1L
) {
    games.delete(game.id)
    connections.find { gameFilter(game) }.forEach {
        if (it.bot) {
            return@forEach
        }
        accounts.update(it.player, resetAccount)
        showMenu(
            it.player,
            games,
            accounts,
            "Ведущий завершил игру. Возвращаемся в меню.",
            bot,
            forceUpdate = true,
            silent = true
        )
    }
    connections.deleteMany {
        gameFilter(game)
    }
    kicks.deleteMany { gameId == game.id }
    pending.deleteMany { host == chatId }
    setups.deleteMany { gameId == game.id }
    pairings.deleteMany { gameId == game.id }
    modes.deleteMany { gameId == game.id }
    roles.deleteMany { gameId == game.id }
    types.deleteMany { gameId == game.id }
    towns.remove(chatId)
    accounts.update(chatId, resetAccount)
    deleteUserTimers(timers, bot) { this.chatId == chatId }
    val chat = ChatId.fromId(chatId)
    bot.deleteMessage(chat, gameMessageId)
    bot.deleteMessage(chat, popupMessageId)
    showMenu(chatId, games, accounts, "Возвращаемся в главное меню.", bot, true)
}

private fun showKickMenu(game: Game, messageId: Long, bot: Bot, chatId: Long) {
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

private suspend fun ContainerBlock.ParametrizedContext.playerQueries(towns: MutableMap<Long, Town>) {
    block({ notNull { connections.find { player == chatId }.singleOrNull() } }) { con ->
        block({ notNull { games.get(con.gameId) } }) { game ->
            parametrized(gameInfoCommand) {
                if (game.state == GameState.Game || game.state == GameState.Reveal) {
                    val mode = modes.get(game.id)?.mode
                    val roleMap = getRoles(setups, game, roles)
                    val playerCount = roleMap.map { it.value }.sum()
                    val players =
                        towns[game.host]?.let { getPlayerDescs(checks, con, pairings, it, games) }
                            ?: emptyList()
                    val desc =
                        (if (mode != null) "<b>Тип игры</b>: ${mode.type}\n${mode.desc}\n\n" else "") +
                                "<b>Количество игроков</b>: $playerCount\n\n${roleDesc(roleMap)}" +
                                (if (players.size > 1) "\n\n<b>Игроки в команде</b>:\n" + players.joinToString(
                                    "\n"
                                ) else "")
                    val res = bot.sendMessage(
                        ChatId.fromId(chatId),
                        desc,
                        parseMode = ParseMode.HTML
                    )
                    if (res.isSuccess && checks.get(CheckOption.GAME_MESSAGES)) {
                        gameMessages.update(game.id) {
                            list = list + listOf(MessageLink(con.player, res.get().messageId))
                        }
                    }
                }
            }
            parametrized(revealRoleCommand) {
                if (game.state == GameState.Game || game.state == GameState.Reveal) {
                    try {
                        roles.get(id(0))?.let { role ->
                            val chat = ChatId.fromId(con.player)
                            val res = bot.sendMessage(
                                chat,
                                getRoleDesc(role),
                                parseMode = ParseMode.HTML
                            )
                            if (res.isSuccess && checks.get(CheckOption.GAME_MESSAGES)) {
                                gameMessages.update(game.id) {
                                    list = list + listOf(MessageLink(con.player, res.get().messageId))
                                }
                            }
                            bot.editMessageReplyMarkup(
                                chat,
                                long(1),
                                replyMarkup = inlineKeyboard {
                                    button(gameInfoCommand, game.id)
                                }
                            )
                            connections.update(con.id) {
                                notified = true
                            }
                            pending.save(Pending(ObjectId(), game.host))
                        }
                    } catch (e: Exception) {
                        log.error("Unable to reveal role, game: $game, connection: $con, role: ${id(0)}", e)
                    }
                }
            }
            parametrized(mainMenuCommand) {
                bot.deleteMessage(ChatId.fromId(chatId), long(0))
                leaveGame(
                    accounts,
                    chatId,
                    accounts.get(chatId)?.menuMessageId ?: -1L,
                    resetAccount,
                    pending,
                    con,
                    connections,
                    games,
                    bot
                )
            }
            parametrized(acceptLeaveCommand) {
                bot.deleteMessage(ChatId.fromId(chatId), long(0))
                bot.deleteMessage(ChatId.fromId(chatId), long(1))
                leaveGame(
                    accounts,
                    chatId,
                    accounts.get(chatId)?.menuMessageId ?: -1L,
                    resetAccount,
                    pending,
                    con,
                    connections,
                    games,
                    bot
                )
            }
        }
    }
}

private fun nightSelection(
    town: Town,
    num: Int,
    chatId: Long,
    bot: Bot,
    messageId: Long
) {
    val wake = town.night[town.index]
    town.selections.add(num)
    if (town.night.size > town.index) {
        if (wake.type.choice <= town.selections.size) {
            val players = town.selections.map {
                town.playerMap[it]
            }
            val script = town.scripts[wake.players.first().roleData.name]
            val priority =
                wake.players.filter { it.alive }.maxOfOrNull { it.roleData.priority } ?: 1
            val actors =
                wake.players.filter { it.roleData.priority == priority && it.alive }
                    .map { it.pos }
            if (script != null) {
                try {
                    script.action(players, LuaInterface(actors, players, town)) { ret ->
                        val actorsSet = actors.toSet()
                        val text = ret.results.map { res ->
                            val start =
                                res.desc() + " " + res.selection.joinToString {
                                    desc(
                                        it,
                                        " - "
                                    )
                                } + ": "
                            val text = if (res is InfoResult) {
                                val blocker =
                                    town.actions.firstOrNull {
                                        it is BlockResult
                                                && it.selection.map { person -> person?.pos ?: -1 }.toSet()
                                            .intersect(actorsSet).isNotEmpty()
                                    }
                                val result = res.text
                                town.actions.add(
                                    InfoResult(
                                        if (blocker == null) result else Const.Message.actionBlocked,
                                        actors,
                                        res.selection
                                    )
                                )
                                res.text
                                start + if (blocker == null) result else Const.Message.actionBlocked
                            } else {
                                if (res is NoneResult) {
                                    return@map null
                                }
                                town.actions.add(res)
                                res.selection.filterNotNull().forEach {
                                    try {
                                        val pos = it.pos
                                        val lua =
                                            town.scripts[town.playerMap[pos]?.roleData?.name]
                                        lua?.passive(
                                            res, LuaInterface(
                                                listOf(pos),
                                                wake.players,
                                                town
                                            )
                                        ) { passive ->
                                            for (result in passive.results) {
                                                if (result !is NoneResult) {
                                                    town.actions.add(result)
                                                }
                                            }
                                        }
                                    } catch (e: Exception) {
                                        log.error(
                                            "Unable to process passive action for person: $it, param: $res",
                                            e
                                        )
                                    }
                                }
                                start + Const.Message.actionRegistered
                            }
                            return@map text
                        }.filterNotNull().joinToString("\n")
                        town.index++
                        bot.editMessageText(
                            ChatId.fromId(chatId),
                            messageId,
                            text = if (ret.results.isNotEmpty()) text else Const.Message.roleDidNothing,
                            replyMarkup = inlineKeyboard {
                                if (town.index >= town.night.size) {
                                    button(dayCommand, messageId)
                                } else {
                                    button(nextRoleCommand, messageId)
                                }
                            }
                        )
                    }
                } catch (e: Exception) {
                    log.error("Unable to process night action, script: $script, targets: $players", e)
                }
            }
        } else {
            showNightRole(town, chatId, bot, messageId)
        }
    }
}

private fun deleteUserTimers(
    timers: Collection<Timer, Long>,
    bot: Bot,
    filter: Timer.() -> Boolean
) {
    val timerList = mutableListOf<Timer>()
    timers.find(filter).forEach {
        timerList.add(it)
    }
    timerList.forEach {
        deleteTimer(timers, it, bot)
    }
}

fun deleteTimer(
    timers: Collection<Timer, Long>,
    it: Timer,
    bot: Bot
) {
    timers.delete(it.chatId)
    bot.deleteMessage(ChatId.fromId(it.chatId), it.messageId)
}

private fun updateTimer(
    timer: Timer,
    bot: Bot,
    timers: Collection<Timer, Long>
) {
    val text = timerText(timer.time)
    bot.editMessageText(
        ChatId.fromId(timer.chatId),
        timer.messageId,
        text = text,
        replyMarkup = inlineKeyboard {
            row {
                button(timerResetCommand, timer.chatId)
                button(
                    timerStateCommand named (if (timer.active) "⏸️" else "▶️"),
                    timer.chatId
                )
                button(timerDeleteCommand, timer.chatId)
            }
        }
    )
    timers.update(timer.chatId) { updated = false }
}

private fun timerText(time: Long): String {
    val timePassed = time / 1000
    val min = (timePassed / 60).toString().padStart(2, '0')
    val sec = (timePassed % 60).toString().padStart(2, '0')
    val text = "Таймер: $min:$sec"
    return text
}

fun showHostSettings(
    messageId: Long,
    hostInfos: Collection<HostInfo, Long>,
    accounts: Collection<Account, Long>,
    chatId: Long,
    bot: Bot
) {
    bot.editMessageReplyMarkup(
        ChatId.fromId(chatId),
        messageId,
        replyMarkup = inlineKeyboard {
            button(blankCommand named "Список ведущих")
            hostInfos.find().forEach {
                accounts.get(it.chatId)?.let { acc ->
                    row {
                        button(blankCommand named acc.fullName())
                        button(deleteHostCommand, it.chatId, messageId)
                    }
                    row {
                        button(blankCommand named "🎮")
                        if (it.gameLimit) {
                            button(gameLimitOnCommand named it.left.toString(), it.chatId, messageId)
                            button(gameLimitOffCommand, it.chatId, messageId)
                        } else {
                            button(gameLimitOnCommand, it.chatId, messageId)
                        }
                    }
                    row {
                        button(blankCommand named "⏰")
                        if (it.timeLimit) {
                            button(timeLimitOnCommand named it.until.toString(), it.chatId, messageId)
                            button(timeLimitOffCommand, it.chatId, messageId)
                        } else {
                            button(timeLimitOnCommand, it.chatId, messageId)
                        }
                    }
                    row {
                        button(blankCommand named "👥")
                        button(shareCommand named if (it.canShare) "On" else "Off", it.chatId, messageId)
                    }
                }
            }
            button(adminBackCommand, messageId)
        }
    )
}

fun showHostRequests(
    hostRequests: Collection<UserId, Long>,
    accounts: Collection<Account, Long>,
    messageId: Long,
    chatId: Long,
    bot: Bot
) {
    bot.editMessageReplyMarkup(
        ChatId.fromId(chatId),
        messageId,
        replyMarkup = inlineKeyboard {
            button(blankCommand named "Запросы на ведение")
            hostRequests.find().forEach {
                accounts.get(it.chatId)?.let { acc ->
                    button(blankCommand named acc.fullName())
                    row {
                        button(allowHostCommand, it.chatId, messageId)
                        button(denyHostCommand, it.chatId, messageId)
                    }
                }
            }
            button(adminBackCommand, messageId)
        }
    )
}

fun showAdmin(
    checks: Collection<Check, String>,
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
            button(advertCommand)
            button(deleteMsgCommand, messageId)
        }
    )
}

fun updateCheck(
    param: String,
    checks: Collection<Check, String>
) {
    val check = checks.get(param)
    if (check == null) {
        checks.save(Check(ObjectId(), param, true))
    } else {
        checks.update(param) {
            state = !check.state
        }
    }
}

fun canHost(
    checks: Collection<Check, String>,
    hostInfos: Collection<HostInfo, Long>,
    filter: HostInfo.() -> Boolean,
    hostRequests: Collection<UserId, Long>,
    chatId: Long
): Boolean {
    val canHost = if (checks.get(CheckOption.HOST_KNOWN)) {
        try {
            hostInfos.find(filter).first()
            true
        } catch (e: NoSuchElementException) {
            false
        }
    } else {
        true
    }

    if (checks.get(CheckOption.HOST_REQUEST)) {
        hostRequests.save(UserId(ObjectId(), chatId))
    }
    return canHost
}

private fun leaveGame(
    accounts: Collection<Account, Long>,
    chatId: Long,
    messageId: Long,
    resetAccount: Account.() -> Unit,
    pending: Collection<Pending, Long>,
    con: Connection,
    connections: Collection<Connection, ObjectId>,
    games: Collection<Game, ObjectId>,
    bot: Bot
) {
    accounts.update(chatId, resetAccount)

    games.get(con.gameId)?.host?.let {
        pending.save(Pending(ObjectId(), it))
    }
    connections.deleteMany { player == chatId }
    bot.deleteMessage(
        ChatId.fromId(chatId),
        messageId
    )
    showMenu(chatId, games, accounts, "Возвращаемся в главное меню.", bot, true)
}

private fun settingsButtons(
    it: GameMode,
    messageId: Long,
    gameMessageId: Long
) = inlineKeyboard {
    button(
        fallModeCommand named "Режим фоллов: " + if (it.fallMode) "Включен" else "Отключен",
        messageId,
        gameMessageId
    )
    button(
        detailedViewCommand named "Показ состояния игроков: " + if (it.detailedView) "Включен" else "Отключен",
        messageId,
        gameMessageId
    )
    button(deleteMsgCommand named "Закрыть", messageId)
}

fun startGame(
    accounts: Collection<Account, Long>,
    setups: Collection<Setup, ObjectId>,
    roles: Collection<Role, ObjectId>,
    pairings: Collection<Pairing, ObjectId>,
    orders: Collection<TypeOrder, ObjectId>,
    types: Collection<Type, ObjectId>,
    chatId: Long,
    towns: MutableMap<Long, Town>,
    games: Collection<Game, ObjectId>,
    bot: Bot,
    modes: Collection<GameMode, ObjectId>
) {
    try {
        games.find { host == chatId }.singleOrNull()?.let { game ->
            games.update(game.id) {
                playedAt = Date()
            }
            gameHistory.save(GameSummary(ObjectId(), game, connections.find { gameId == game.id }))
            accounts.update(chatId) {
                menuMessageId = -1L
            }

            val roleMap = getRoles(setups, game, roles)
            prepareScripts()

            val scriptMap = roleMap.keys.filter { it.scripted }.associate { it.name to Script(it.name, scriptDir) }
            val pairs = pairings.find { gameId == game.id }
            val orderList = orders.find { gameId == game.id }
            val typeList = types.find { gameId == game.id }
            val mode = modes.find { gameId == game.id }.singleOrNull()?.mode ?: Mode.OPEN
            val town = Town(
                chatId,
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
                scriptMap,
                mode
            )
            towns[chatId] = town


            if (!checks.get(CheckOption.REVEAL_MENU)) {
                sendPlayerInfo(pairs, roles, bot, chatId, game)
            }

            games.updateMany(
                { host == chatId },
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
                replyMarkup = dayKeyboard(chatId)
            )

            accounts.update(chatId) {
                menuMessageId = -1L
            }
            towns[chatId]?.let { town ->
                showDay(town, chatId, -1L, towns, accounts, bot, modes, game)
            }
        }
    } catch (e: Exception) {
        log.error("Unable to start game for chatId: $chatId", e)
    }
}

private fun sendPlayerInfo(
    pairs: List<Pairing>,
    roles: Collection<Role, ObjectId>,
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
                bot.sendMessage(
                    ChatId.fromId(con.player),
                    "Ведущий начал игру",
                    replyMarkup = adminKeyboard(chatId) {
                        button(leaveGameCommand)
                    }
                )
                bot.sendMessage(
                    ChatId.fromId(con.player),
                    getRoleDesc(role),
                    parseMode = ParseMode.HTML,
                    replyMarkup = inlineKeyboard {
                        button(gameInfoCommand, game.id)
                    }
                )
            } catch (e: Exception) {
                log.error("Unable to send player info message to $con, role: $role", e)
            }
        }
    }
}

private fun getRoleDesc(role: Role) = "Ваша роль: <span class=\"tg-spoiler\">${
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

private fun prepareScripts() {
    val dir = Path.of(Config().path, "scripts")
    if (!exists(dir)) {
        createDirectories(dir)
    }
    if (!exists(scriptDir)) {
        createDirectories(scriptDir)
    }
    scriptDir.toFile().listFiles()?.forEach { it.delete() }
    dir.toFile()
        .listFiles { file -> file.name.endsWith(".lua") }
        ?.forEach {
            val script = it.readText().replace("$", "UTIL:")
            Paths.get(scriptDir.toFile().absolutePath, it.name).toFile().writeText(script)
        }
}

private fun getPlayerDescs(
    checks: Collection<Check, String>,
    connection: Connection,
    pairings: Collection<Pairing, ObjectId>,
    town: Town,
    games: Collection<Game, ObjectId>
): List<String> {
    val conMap = mutableMapOf<ObjectId, List<String>>()
    val wakeMap = mutableMapOf<String, MutableList<Pairing>>()
    games.get(connection.gameId)?.let { game ->
        val pairs = mutableMapOf<ObjectId, Pairing>()
        pairings.find { gameId == game.id }.forEach {
            pairs[it.connectionId] = it
        }
        for (pair in pairs.values) {
            val role = roles.get(pair.roleId)!!
            val types = if (role.scripted) {
                val script = town.scripts[role.name]
                script?.type(town.players) ?: "none"
            } else {
                "none"
            }.split(",")
            types.forEach {
                wakeMap.getOrPut(it) { mutableListOf() }.add(pair)
            }
            conMap[pair.connectionId] = types
        }

        pairs[connection.id]?.let { pair ->
            val names = checks.get(CheckOption.NAMES)
            val cover = checks.get(CheckOption.COVER)
            val players =
                if (!names || !conMap.containsKey(pair.connectionId) || "none" in (conMap[pair.connectionId]
                        ?: emptyList())
                ) {
                    emptyList()
                } else {
                    conMap[pair.connectionId]
                        ?.asSequence()
                        ?.flatMap { wakeMap[it] ?: emptyList() }
                        ?.toSet()
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

private fun Collection<Check, String>.get(option: CheckOption) =
    (get(option.key)?.state ?: false)

private fun getRoles(
    setups: Collection<Setup, ObjectId>,
    game: Game,
    roles: Collection<Role, ObjectId>
): MutableMap<Role, Int> {
    val roleMap = mutableMapOf<Role, Int>()
    setups.find { gameId == game.id }.forEach { setup ->
        roles.get(setup.roleId)?.let { role ->
            roleMap[role] = setup.count
        }
    }
    return roleMap

}

private fun roleDesc(roleMap: MutableMap<Role, Int>): String {
    var roleDesc = "<b>Роли в игре</b>:\n\n"
    for (entry in roleMap.entries.sortedBy { it.key.index }) {
        if (entry.value > 0) {
            roleDesc += "<b>" + entry.key.displayName + "</b>\nКоличество: ${entry.value}\nОписание: ${entry.key.desc}\n\n"
        }
    }
    roleDesc = roleDesc.dropLast(2)
    return roleDesc
}

fun updateSetup(
    path: String,
    roles: Collection<Role, ObjectId>,
    game: Game,
    types: Collection<Type, ObjectId>,
    orders: Collection<TypeOrder, ObjectId>
) {
    val json = File("$path/scripts/template.json").readText()
    try {
        val data = Json.decodeFromString<GameSet>(json)
        roles.deleteMany { gameId == game.id }
        data.roles.forEachIndexed { index, it ->
            val role = Role(
                ObjectId(),
                game.id,
                it.displayName,
                it.desc,
                it.scripted,
                it.defaultTeam,
                it.name,
                it.priority,
                it.coverName,
            )
            role.index = index
            roles.save(
                role
            )
        }
        types.deleteMany { gameId == game.id }
        data.type.forEach {
            types.save(Type(ObjectId(), game.id, it.name, it.choice))
        }
        orders.deleteMany { gameId == game.id }
        data.order.forEachIndexed { index, s ->
            orders.save(TypeOrder(ObjectId(), game.id, s, index))
        }
    } catch (e: Exception) {
        log.error("Unable to update setups for game: $game, path: $path", e)
    }
}

private fun showNumPrompt(
    chatId: Long,
    messageId: Long,
    bot: Bot,
    connectionId: ObjectId,
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
    return msgId
}

private fun dayKeyboard(chatId: Long) = adminKeyboard(chatId) {
    button(stopGameCommand)
}

private fun showNightRole(
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
                button(skipRoleCommand, msgId)
            } else {
                reordered(town.players.filter { it.alive }.sortedBy { it.pos }).chunked(2).forEach { list ->
                    row {
                        list.forEach {
                            // todo handle message id in command
                            button(selectCommand named ((if (it.pos in town.selections) "✅ " else "") + desc(it)), it.pos, msgId)
                        }
                        if (list.size == 1) {
                            button(blankCommand)
                        }
                    }
                }
                button(skipRoleCommand, msgId)
            }
        }
    )
}

private fun nightRoleDesc(wake: Wake): String {
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

fun startDay(
    town: Town,
    chatId: Long,
    towns: MutableMap<Long, Town>,
    accounts: Collection<Account, Long>,
    bot: Bot,
    modes: Collection<GameMode, ObjectId>,
    game: Game
) {
    town.day++
    val fullLog = fullLog(town)
    town.endNight()
    if (fullLog.isNotBlank()) {
        bot.sendMessage(
            ChatId.fromId(chatId),
            "Все события:\n${fullLog}"
        )
    }

    bot.sendMessage(
        ChatId.fromId(chatId),
        "Результат ночи:\n" + shortLog(town).ifBlank { "Не произошло никаких событий" }
    )
    town.actions.clear()

    val mapAll = mutableMapOf<String, Int>()
    val mapAlive = mutableMapOf<String, Int>()
    val teamSet = mutableSetOf("all")
    for (player in town.players) {
        teamSet.add(player.team)

        mapAll.getOrPut("all") { 0 }.let {
            mapAll["all"] = it + 1
        }
        mapAll.getOrPut(player.team) { 0 }.let {
            mapAll[player.team] = it + 1
        }

        if (player.alive) {
            mapAlive.getOrPut("all") { 0 }.let {
                mapAlive["all"] = it + 1
            }
            mapAlive.getOrPut(player.team) { 0 }.let {
                mapAlive[player.team] = it + 1
            }
        }
    }

    bot.sendMessage(
        ChatId.fromId(chatId),
        "День ${towns[chatId]?.day}\n" +
                "Вживых:\n" + teamSet.joinToString("\n") {
            it + ": " + mapAlive.getOrDefault(it, 0) + " / " + mapAll.getOrDefault(it, 0)
        }
    )

    // todo replace -1L with messageId
    showDay(town, chatId, -1L, towns, accounts, bot, modes, game)
}

private fun showDay(
    town: Town,
    chatId: Long,
    messageId: Long,
    towns: MutableMap<Long, Town>,
    accounts: Collection<Account, Long>,
    bot: Bot,
    modes: Collection<GameMode, ObjectId>,
    game: Game
) {
    withAccount(accounts, chatId) { acc ->
        val mode = modes.get(game.id)
        val view = mode?.dayView ?: DayView.ALL
        val fallMode = mode?.fallMode ?: false

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
            row { button(filterCommand named "Фильтр: ${view.desc}", msgId) }
            for (player in town.players.sortedBy { it.pos }) {
                if (view.filter(player)) {
                    row {
                        button(
                            (if (mode?.detailedView == true) blankCommand else dayDetailsCommand) named desc(player),
                            player.pos,
                            msgId
                        )
                    }
                    if (mode?.detailedView == true) {
                        row {
                            playerDayDesc(player, msgId, fallMode)
                        }
                    }
                }
            }
            button(settingsCommand, msgId)
            button(timerCommand)
            button(nightCommand, msgId)
        }
        bot.editMessageReplyMarkup(
            ChatId.fromId(chatId),
            msgId,
            replyMarkup = keyboard
        )
    }
}

private fun KeyboardContext.RowContext.playerDayDesc(
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

fun shortLog(town: Town): String {
    return if (town.actions.isNotEmpty()) {
        val set = mutableSetOf<Pair<KClass<out Action>, Int>>()
        val text =
            town.actions.asSequence().filter { !it.blocked }.map { it.actions() }.flatten().sortedBy { it.pos }.map {
                val pair = it::class to it.pos
                if (pair !in set) {
                    set.add(pair)
                    "${it.symbol()} Игрок ${desc(town.playerMap[it.pos], " - ", false)} ${it.desc()}"
                } else {
                    null
                }
            }.filterNotNull().joinToString("\n")
        text
    } else {
        ""
    }
}

private fun fullLog(town: Town): String {
    return if (town.actions.isNotEmpty()) {
        val text = town.actions.mapIndexed { i, it ->
            val action = actionDesc(it)

            val alive = it.actors.mapNotNull { town.playerMap[it] }.filter { it.alive }
            val who = if (alive.isNotEmpty()) {
                alive.joinToString(", ") { desc(it) }
            } else {
                "Действующее лицо не указно"
            }
            val target = it.selection.joinToString { desc(it, " - ") }
            "Событие ${i + 1}.\n" +
                    "Кто: $who\n" +
                    "Действие: $action\n" +
                    "Цель: $target\n" +
                    (if (it is InfoResult) "Результат: ${it.text}" else "")
        }.joinToString("\n\n")
        text
    } else {
        ""
    }
}

private fun actionDesc(it: Result): String = it.desc()

private fun desc(player: Person?, sep: String = ". ", icons: Boolean = true) = if (player != null)
    "${player.pos}$sep" +
            (if (!icons) "" else if (player.protected) "⛑️" else if (player.alive) "" else "☠️") +
            (if (!icons) "" else if (player.fallCount > 0) numbers[player.fallCount % numbers.size] else "") +
            " ${player.name} (${player.roleData.displayName})"
else "Неизвестный игрок"

//private fun desc(player: Person, sep: String = ". ") = "${player.pos}$sep${player.name} (${player.role.name})"

fun initAccount(
    userName: String,
    accounts: Collection<Account, Long>,
    chatId: Long,
    bot: Bot
) {
    if (accounts.get(chatId) == null) {
        accounts.save(Account(ObjectId(), chatId, userName))
    } else {
        accounts.update(chatId) {
            state = AccountState.Init
            menuMessageId = -1L
            hostMessageId = -1L
            setupMessageId = -1L
            dayMessageId = -1L
            connectionId = null
        }
    }
    bot.sendMessage(
        ChatId.fromId(chatId),
        "Пожалуйста, введите свое имя. Это имя смогут видеть ведущие игр, к которым вы присоединяетесь.",
        replyMarkup = ReplyKeyboardRemove(true)
    )
}

fun showMenu(
    chatId: Long,
    games: Collection<Game, ObjectId>,
    accounts: Collection<Account, Long>,
    menuText: String,
    bot: Bot,
    forceUpdate: Boolean = false,
    silent: Boolean = false
) {
    bot.sendMessage(
        ChatId.fromId(chatId),
        menuText,
        disableNotification = silent,
        replyMarkup = adminKeyboard(chatId) {
            button(changeNameCommand)
        }
    )
    showGames(chatId, -1L, bot, games, accounts, forceUpdate)
}

private fun showLobby(
    chatId: Long,
    messageId: Long,
    connections: Collection<Connection, ObjectId>,
    game: Game,
    bot: Bot,
    accounts: Collection<Account, Long>,
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
            accounts.update(chatId) {
                msgId = res.get().messageId
            }
        }
    }
    bot.editMessageReplyMarkup(
        id,
        msgId,
        replyMarkup = lobby(msgId, connections, game)
    )
    return msgId
}

fun showRoles(
    chatId: Long,
    messageId: Long,
    setups: Collection<Setup, ObjectId>,
    connections: Collection<Connection, ObjectId>,
    bot: Bot,
    game: Game
) {
    val players = connections.find { gameId == game.id }
    val pairs = pairings.find { gameId == game.id }
    val gameSetups = setups.find { gameId == game.id }
    val keyboard = inlineKeyboard {
        gameSetups.sortedBy { it.index }.chunked(2).forEach {
            val left = it[0]
            val right = if (it.size > 1) it[1] else null
            row {
                button(roleCommand named left.role, left.roleId, messageId)
                if (right != null) {
                    button(roleCommand named right.role, right.roleId, messageId)
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
            button(blankCommand named "Выбрано ролей: ${gameSetups.sumOf { it.count }}")
        }
        button(resetRolesCommand, game.id, messageId)
        if (pairs.isNotEmpty()) {
            row {
                button(updateRolesCommand, game.id, messageId)
            }
        }
        row {
            button(menuLobbyCommand, messageId)
            if (pairs.isNotEmpty()) {
                button(menuPreviewCommand, messageId)
            } else {
                button(previewCommand, game.id, messageId)
            }
        }
    }
    bot.editMessageReplyMarkup(
        ChatId.fromId(chatId),
        messageId,
        replyMarkup = keyboard
    )
}

fun showPreview(
    bot: Bot,
    chatId: Long,
    messageId: Long,
    pairings: Collection<Pairing, ObjectId>,
    connections: Collection<Connection, ObjectId>,
    game: Game
) {
    val players = connections.find { gameId == game.id }
    val pairs = pairings.find { gameId == game.id }.associateBy { it.connectionId }
    val keyboard = inlineKeyboard {
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
                button(blankCommand named (pair?.roleId?.let { id -> roles.get(id)?.displayName } ?: "Роль не выдана"))
            }
        }
        row {
            button(command("Игроков: ${players.size}", "default"))
        }
        row {
            button(blankCommand named "Распределено ролей: ${pairs.size}")
        }
        button(updateRolesCommand, game.id, messageId)
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

fun withAccount(accounts: Collection<Account, Long>, chatId: Long, func: (Account) -> Unit) {
    accounts.get(chatId)?.let {
        func(it)
    }
}

fun menuButtons(
    messageId: Long,
    games: Collection<Game, ObjectId>,
    accounts: Collection<Account, Long>
): InlineKeyboardMarkup {
    return inlineKeyboard {
        games.find { state != GameState.Game }.forEach {
            accounts.get(it.host)?.let { host ->
                row {
                    button(joinCommand named host.fullName(), it.id, messageId)
                }
            }
        }
        row { button(updateCommand, messageId) }
    }
}

fun lobby(messageId: Long, connections: Collection<Connection, ObjectId>, game: Game): InlineKeyboardMarkup {
    val players = connections.find { gameId == game.id }
    return inlineKeyboard {
        val playerList = players.sortedWith(compareBy({ it.pos }, { it.createdAt }))
        val ordered = reordered(playerList)
        ordered.chunked(2).forEach {
            val first = it[0]
            row {
                button(detailsCommand named first.name(), first.id, messageId)
                button(
                    if (first.pos == Int.MAX_VALUE)
                        positionCommand
                    else positionCommand named first.pos.toString(),
                    first.id,
                    0,
                    messageId
                )
                if (it.size > 1) {
                    val second = it[1]
                    button(detailsCommand named second.name(), second.id, messageId)
                    button(
                        if (second.pos == Int.MAX_VALUE)
                            positionCommand
                        else positionCommand named second.pos.toString(),
                        second.id,
                        0,
                        messageId
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
        row { button(dummyCommand, messageId) }
        row { button(menuKickCommand, messageId) }
        row { button(resetNumsCommand, messageId) }
        button(menuRolesCommand, messageId)
    }
}

private fun <T> reordered(list: List<T>) = with(ceil(list.size / 2.0).toInt()) {
    List(list.size) {
        list[if (it % 2 == 0) it / 2 else this + it / 2]
    }
}

fun showGames(
    chatId: Long,
    messageId: Long,
    bot: Bot,
    games: Collection<Game, ObjectId>,
    accounts: Collection<Account, Long>,
    forceUpdate: Boolean = false
) {
    var message = messageId
    if (message == -1L || forceUpdate) {
        val answer = bot.sendMessage(
            ChatId.fromId(chatId),
            "Доступные игры (нажмите на игру чтобы присоединиться):",
        )
        if (answer.isSuccess) {
            message = answer.get().messageId
        }
    }
    bot.editMessageReplyMarkup(
        ChatId.fromId(chatId),
        message,
        replyMarkup = menuButtons(message, games, accounts)
    )
}

fun adminKeyboard(chatId: Long, definition: FooterContext.() -> Unit) = footerKeyboard {
    definition()
    if (isAdmin(chatId)) {
        button(adminPanelCommand)
    }
}