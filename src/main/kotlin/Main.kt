package org.example

import com.github.kotlintelegrambot.Bot
import com.github.kotlintelegrambot.bot
import com.github.kotlintelegrambot.dispatch
import com.github.kotlintelegrambot.dispatcher.callbackQuery
import com.github.kotlintelegrambot.dispatcher.handlers.CallbackQueryHandlerEnvironment
import com.github.kotlintelegrambot.dispatcher.text
import com.github.kotlintelegrambot.entities.*
import com.github.kotlintelegrambot.entities.keyboard.KeyboardButton
import com.mongodb.client.model.Filters
import com.mongodb.kotlin.client.coroutine.MongoClient
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.bson.codecs.pojo.annotations.BsonId
import org.bson.types.ObjectId
import org.luaj.vm2.Globals
import org.luaj.vm2.LoadState
import org.luaj.vm2.LuaValue
import org.luaj.vm2.compiler.LuaC
import org.luaj.vm2.lib.PackageLib
import org.luaj.vm2.lib.jse.CoerceJavaToLua
import org.luaj.vm2.lib.jse.CoerceLuaToJava
import org.luaj.vm2.lib.jse.JseBaseLib
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.*
import kotlin.math.ceil
import kotlin.math.max
import kotlin.reflect.KClass

const val author = "dev_vlad"
const val botToken = "7381702333:AAFDKZrYiSMi0Ugunm55v7syJcysS9gmcBY"
const val connectionString = "mongodb://localhost:27017/?retryWrites=true&w=majority"
const val gameDurationLimitHours = 3
const val gameHistoryTtlHours = 24
const val sendPendingAfterSec = 3
val lobbyStates = setOf(GameState.Connect, GameState.Rename, GameState.Dummy)
val numbers = arrayOf("0️⃣", "1️⃣", "2️⃣", "3️⃣", "4️⃣", "5️⃣", "6️⃣", "7️⃣", "8️⃣", "9️⃣")
val dateFormat: DateTimeFormatter = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm")

val client = MongoClient.create(connectionString = connectionString)
val db = client.getDatabase("mafia")
val database = Database(Config().path + "/data")
val accounts = database.collection("accounts", Account::chatId)
val games = database.collection("games", Game::id)
val gameHistory = database.collection("gameHistory", GameSummary::id)
val connections = database.collection("connections", Connection::id)
val pending = database.collection("pending", Pending::host)
val roles = database.collection("roles", Role::id)
val setups = database.collection("setups", Setup::id)
val pairings = database.collection("pairings", Pairing::id)
val orders = database.collection("orders", TypeOrder::id)
val types = database.collection("types", Type::id)
val ads = database.collection("ads", Message::id)
val bombs = database.collection("bombs", Bomb::id)
val checks = database.collection("checks", Check::name)
val kicks = database.collection("kicks", Kick::id)
val modes = database.collection("modes", GameMode::gameId)
val selections = database.collection("selections", Selection::id)
val hostInfos = database.collection("hostInfos", HostInfo::chatId)
val hostRequests = database.collection("hostRequests", UserId::chatId)
val admins = database.collection("admins", UserId::chatId)
val adminMenus = database.collection("adminMenus", AdminMenu::chatId)
val timers = database.collection("timers", Timer::chatId)
val internal = database.collection("internal", String::toString)
val adPopups = database.collection("adPopups", AdPopup::chatId)

val accountsMongo = db.getCollection<AccountOld>("accounts")
val gamesMongo = db.getCollection<Game>("games")
val connectionsMongo = db.getCollection<Connection>("connections")
val pendingMongo = db.getCollection<Pending>("pending")
val rolesMongo = db.getCollection<Role>("roles")
val setupsMongo = db.getCollection<Setup>("setups")
val pairingsMongo = db.getCollection<Pairing>("pairings")
val ordersMongo = db.getCollection<TypeOrder>("orders")
val typesMongo = db.getCollection<Type>("types")
val adsMongo = db.getCollection<Message>("ads")
val bombsMongo = db.getCollection<Bomb>("bombs")
val checksMongo = db.getCollection<Check>("checks")
val kicksMongo = db.getCollection<Kick>("kicks")
val modesMongo = db.getCollection<GameMode>("modes")
val selectionsMongo = db.getCollection<Selection>("selections")
val hostInfosMongo = db.getCollection<HostInfo>("hostInfos")
val hostRequestsMongo = db.getCollection<UserId>("hostRequests")
val adminsMongo = db.getCollection<UserId>("admins")
val adminMenusMongo = db.getCollection<AdminMenu>("adminMenus")
val timersMongo = db.getCollection<Timer>("timers")

val blankCommand = command("`", "default")
val deleteMsgCommand = command("Закрыть", "deleteMsg", 1)

val joinCommand = command("", "join", 2)
val updateCommand = command("Обновить список игр", "update", 1)

val playerNumCommand = command("", "playerNum", 3)
val playerConfirmCommand = command("Ввести ▶️", "playerConfirm", 3)
val mainMenuCommand = command("🔙 Покинуть игру", "mainMenu", 1)

val detailsCommand = command("", "details", 2)
val renameCommand = command("Переименовать", "rename", 2)
val positionCommand = command("Указать номер", "posi", 3)
val handCommand = command("✋", "hand", 1)
val kickCommand = command("❌", "kick", 1)

val resetNumsCommand = command("Сбросить номера игроков", "resetNums", 1)
val confirmResetCommand = command("Да", "confirmReset", 2)

val unkickCommand = command("Впустить", "unkick", 2)

val hostBackCommand = command("Назад", "back", 1)
val menuKickCommand = command(" Список исключенных игроков", "menuKick", 1)

val menuLobbyCommand = command("◀️ Меню игроков", "menuLobby", 1)
val menuRolesCommand = command("Меню ролей ▶️", "menuRoles", 1)
val menuPreviewCommand = command("Меню распределения ▶️", "menuPreview", 1)
val gameCommand = command("Начать игру 🎮", "game", 2)

val posSetCommand = command("Ввести ▶️", "posSet", 3)

val nameCancelCommand = command("Отмена", "nameCancel", 1)

val dummyCommand = command("➕ Добавить игрока", "dummy", 1)
val roleCommand = command("", "role", 2)
val incrCommand = command("➕", "incr", 2)
val decrCommand = command("➖", "decr", 2)

val resetRolesCommand = command("Сбросить выбор ролей", "resetRoles", 2)
val previewCommand = command("🔀 Раздать роли", "preview", 2)
val updateRolesCommand = command("🔄 Перераздать", "updRoles", 2)
val gameModeCommand = command("", "mode", 2)

val filterCommand = command("Фильтр: Ошибка", "fltr", 1)

val dayDetailsCommand = command("", "dayDetails", 2)
val statusCommand = command("Статус: Ошибка", "status", 2)
val killCommand = command("💀", "kill", 2)
val reviveCommand = command("🏩", "rviv", 2)
val fallCommand = command("", "fall", 2)

val dayBackCommand = command("◀️ Назад", "dayBack", 1)

val settingsCommand = command("Настройки", "settings", 1)
val timerCommand = command("Таймер", "timer")
val nightCommand = command("Начать ночь", "night", 1)

val selectCommand = command("", "select", 2)
val nextRoleCommand = command("Следующая роль", "nextRole", 1)
val skipRoleCommand = command("Пропустить", "skipRole", 1)

// todo add this coomand to all night menus
val cancelActionCommand = command("Отменить последнее действие", "cancelAction", 1)
val dayCommand = command("Начать день", "day", 1)

val fallModeCommand = command("Режим фоллов", "fallMode", 2)
val detailedViewCommand = command("Состояние игроков", "detailedMode", 2)
val timerDeleteCommand = command("❌️", "timerDelete", 1)
val timerStateCommand = command("", "timerState", 1)
val timerResetCommand = command("🔄", "timerReset", 1)

val gameInfoCommand = command("Информация об игре", "gameInfo", 1)

val updateCheckCommand = command("", "updateCheck", 2)

val hostRequestCommand = command("Запросы на ведение", "hostRequests", 1)
val hostSettingsCommand = command("Список ведущих", "hostSettings", 1)

val timeLimitOnCommand = command("Off", "timeLimitOn", 2)
val timeLimitOffCommand = command("❌", "timeLimitOff", 2)
val gameLimitOnCommand = command("Off", "gameLimitOn", 2)
val gameLimitOffCommand = command("❌", "gameLimitOff", 2)
val shareCommand = command("Off", "share", 2)
val deleteHostCommand = command("❌", "deleteHost", 2)
val allowHostCommand = command("✅", "allowHost", 2)
val denyHostCommand = command("❌", "denyHost", 2)
val adminBackCommand = command("Назад", "adminBack", 1)

val sendAdCommand = command("", "sendAd", 1)
val sendAdHistoryCommand = command("", "sendAdHistory", 1)

val acceptNameCommand = command("Да", "nameAccept", 3)
val cancelName = command("Нет", "nameDeny", 2)

val acceptStopCommand = command("Да", "stopAccept", 2)
val acceptLeaveCommand = command("Да", "leaveAccept", 2)

val adCommand = command("/ad")
val adNewCommand = command("/newad")
val adminCommand = command("/admin")

val hostCommand = command("/host")
val rehostCommand = command("/rehost")
val updateForcedCommand = command("/update")
val startCommand = command("/start")
val menuCommand = command("/menu")

val changeNameCommand = command("Сменить имя")
val stopGameCommand = command("Завершить игру")
val leaveGameCommand = command("Покинуть игру")

val resetAccount: Account.() -> Unit = {
    state = AccountState.Menu
    menuMessageId = -1L
    hostMessageId = -1L
    setupMessageId = -1L
    dayMessageId = -1L
    connectionId = null
}
val gameFilter: Connection.(Game) -> Boolean = { game -> gameId == game.id }

fun main() {
    //val connectionString = "mongodb://EdgeDom:WontH4CKAGA1n@localhost:44660/?retryWrites=true&w=majority"
    val roleNameLen = 32
    val roleDescLen = 280
    val path = Config().path

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

        if (isAdmin(chatId, username)) {
            simple(adCommand) {
                val chat = ChatId.fromId(chatId)
                bot.deleteMessage(chat, messageId ?: -1L)
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
                                button(sendAdCommand named name(it, connections.find { gameId == it.id }), it.id)
                            }
                            if (recent.isNotEmpty()) {
                                button(blankCommand named "Недавние")
                            }
                            recent.forEach {
                                button(sendAdHistoryCommand named name(it.game, it.connections), it.id)
                            }
                            button(deleteMsgCommand, res.get().messageId)
                        }
                    )
                }
            }
            simple(adNewCommand) {
                val msgId = sendClosable("Введите текст рекламного сообщения")
                adPopups.save(AdPopup(ObjectId(), chatId, msgId))
            }
            simple(adminCommand) {
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
                        e.printStackTrace()
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

                        showLobby(chatId, account.menuMessageId, connections, game, bot, accounts)
                    }
                }
                simple(stopGameCommand, menuCommand) {
                    val chat = ChatId.fromId(chatId)
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
                                setPlayerNum(game, con, value, accounts.get(chatId)?.menuMessageId ?: -1L, chatId, bot)
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
                sendClosable("Название: ${role.name}\nОписание: ${role.desc}")
            }
        }
        adminQueries()
        hostQueries(path, towns, roleNameLen, roleDescLen)
        playerQueries(towns)
    }

    handleMigrations()

    val bot = bot {
        token = botToken
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
                    e.printStackTrace()
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
                    e.printStackTrace()
                }
                delay(60000)
            }
        }

        launch {
            while (true) {
                try {
                    val set = mutableSetOf<Long>()
                    pending.find { date.toInstant().isBefore(Instant.now().minusSeconds(sendPendingAfterSec.toLong())) }.forEach {
                        set.add(it.host)
                    }
                    pending.deleteMany { true }
                    set.forEach {
                        accounts.get(it)?.let {
                            games.find { host == it.chatId }.singleOrNull()?.let { game ->
                                if (game.state == GameState.Connect) {
                                    showLobby(it.chatId, it.menuMessageId, connections, game, bot, accounts)
                                }
                            }
                        }
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
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
                    e.printStackTrace()
                }
                delay(200)
            }
        }

        launch {
            while (true) {
                try {
                    games.find {
                        createdAt.toInstant().isBefore(Instant.now().minusSeconds(gameDurationLimitHours * 60 * 60L))
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
                    e.printStackTrace()
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
                    e.printStackTrace()
                }
                delay(60000)
            }
        }
    }
}

private fun isAdmin(chatId: Long, username: String) =
    username == author || admins.get(chatId) != null

private fun initGame(game: Game?, path: String, chatId: Long, messageId: Long, bot: Bot, deleteId: Long) {
    if (game != null) {
        updateSetup(path, roles, game, types, orders)
        val chat = ChatId.fromId(chatId)
        bot.deleteMessage(chat, deleteId)
        bot.sendMessage(
            chat,
            "Игра создана. Ожидайте присоединения игроков.",
            replyMarkup = footerKeyboard {
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
    if (isAdmin(chatId, username)) {
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
                showAd(game, connections.find { gameId == game.id }, bot)
            } else {
                gameHistory.find { this.game.id == id(0) }.lastOrNull()?.let {
                    showAd(it.game, it.connections, bot)
                }
            }
        }
        parametrized(sendAdHistoryCommand) {
            gameHistory.get(id(0))?.let {
                showAd(it.game, it.connections, bot)
            }
        }
    }
}

fun showAd(game: Game, connections: List<Connection>, bot: Bot) {
    val host = game.host
    val ad = ads.find().firstOrNull()
    fun send(chatId: Long) {
        if (ad != null) {
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
                replyMarkup = footerKeyboard {
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
            // todo check if it can be replaced by simple edit reply
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
                setPlayerNum(game, con, int(1), long(2), chatId, bot)
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
        connections.update(connectionId) {
            this.pos = pos
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
    messageId: Long,
    chatId: Long,
    bot: Bot
) {
    if (pos > 0) {
        connections.update(con.id) {
            this.pos = pos
        }
    }
    pending.save(Pending(ObjectId(), game.host))
    if (messageId != -1L) {
        bot.editMessageReplyMarkup(
            ChatId.fromId(chatId),
            messageId,
            replyMarkup = inlineKeyboard {
                button(blankCommand named "Статус игры:")
                button(blankCommand named "Ожидаем присоединения игроков")
            }
        )
        accounts.update(con.player) {
            menuMessageId = messageId
        }
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

private suspend fun ContainerBlock.ParametrizedContext.hostQueries(
    path: String,
    towns: MutableMap<Long, Town>,
    roleNameLen: Int,
    roleDescLen: Int
) {
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
                    setups.save(Setup(ObjectId(), it.id, game.id, it.name, it.index))
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
                bot.deleteMessage(ChatId.fromId(chatId), long(1))
                startGame(
                    accounts,
                    setups,
                    roles,
                    path,
                    pairings,
                    orders,
                    types,
                    chatId,
                    towns,
                    roleNameLen,
                    roleDescLen,
                    games,
                    bot,
                    modes
                )
            }
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
                accounts.update(chatId) {
                    menuMessageId = -1L
                }
                deleteUserTimers(timers, bot) { this.chatId == chatId }
                towns[chatId]?.let { town ->
                    bot.sendMessage(
                        ChatId.fromId(chatId),
                        "Результат дня:\n${shortLog(town)}"
                    )
                    town.actions.clear()
                    town.updateTeams()
                    town.prepNight()

                    showNightRole(town, chatId, bot)
                }
            } catch (e: Exception) {
                e.printStackTrace()
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
                    showPlayerDayDesc(town, mode?.fallMode?: false, int(0), msgId, chatId, bot)
                }
            }
            parametrized(dayBackCommand) {
                showDay(town, chatId, long(0), towns, accounts, bot, "", "", modes, game)
            }
            parametrized(statusCommand) {
                town.changeProtected(int(0))
                if (mode?.detailedView == true) {
                    accounts.get(chatId)?.let { acc ->
                        val msgId = if (acc.menuMessageId != -1L) acc.menuMessageId else long(1)
                        showPlayerDayDesc(town, modes.get(game.id)?.fallMode ?: false, int(0), msgId, chatId, bot)
                    }
                } else {
                    showDay(town, chatId, long(1), towns, accounts, bot, "", "", modes, game)
                }
            }
            parametrized(killCommand) {
                town.setAlive(int(0), false)
                if (mode?.detailedView == true) {
                    accounts.get(chatId)?.let { acc ->
                        val msgId = if (acc.menuMessageId != -1L) acc.menuMessageId else long(1)
                        showPlayerDayDesc(town, modes.get(game.id)?.fallMode ?: false, int(0), msgId, chatId, bot)
                    }
                } else {
                    showDay(town, chatId, long(1), towns, accounts, bot, "", "", modes, game)
                }
            }
            parametrized(reviveCommand) {
                town.setAlive(int(0), true)
                if (mode?.detailedView == true) {
                    accounts.get(chatId)?.let { acc ->
                        val msgId = if (acc.menuMessageId != -1L) acc.menuMessageId else long(1)
                        showPlayerDayDesc(town, modes.get(game.id)?.fallMode ?: false, int(0), msgId, chatId, bot)
                    }
                } else {
                    showDay(town, chatId, long(1), towns, accounts, bot, "", "", modes, game)
                }
            }
            parametrized(fallCommand) {
                val pos = int(0)
                val person = town.playerMap[pos]
                if (person != null) {
                    person.fallCount += 1
                }
                if (mode?.detailedView == true) {
                    accounts.get(chatId)?.let { acc ->
                        val msgId = if (acc.menuMessageId != -1L) acc.menuMessageId else long(1)
                        showPlayerDayDesc(town, modes.get(game.id)?.fallMode ?: false, int(0), msgId, chatId, bot)
                    }
                } else {
                    showDay(town, chatId, long(1), towns, accounts, bot, "", "", modes, game)
                }
            }
            parametrized(selectCommand) {
                nightSelection(town, int(0), chatId, long(1), bot)
            }
            parametrized(nextRoleCommand) {
                showNightRole(town, chatId, bot)
            }
            parametrized(skipRoleCommand) {
                town.index++
                showNightRole(town, chatId, bot)
            }
            parametrized(dayCommand) {
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
                        showDay(town, chatId, long(0), towns, accounts, bot, "", "", modes, game)

                    }
                }
                parametrized(fallModeCommand) {
                    modes.update(game.id) { fallMode = !fallMode }
                    showDay(town, chatId, long(1), towns, accounts, bot, "", "", modes, game)
                    bot.editMessageReplyMarkup(
                        ChatId.fromId(chatId),
                        long(0),
                        replyMarkup = settingsButtons(mode.copy(fallMode = !mode.fallMode), long(0), long(1))
                    )
                }
                parametrized(detailedViewCommand) {
                    modes.update(game.id) { detailedView = !detailedView }
                    showDay(town, chatId, long(1), towns, accounts, bot, "", "", modes, game)
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
                if (game.state == GameState.Game) {
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
                    bot.sendMessage(
                        ChatId.fromId(chatId),
                        desc,
                        parseMode = ParseMode.HTML
                    )
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
    messageId: Long,
    bot: Bot
) {
    if (town.night.size > town.index) {
        val wake = town.night[town.index]
        town.selections.add(num)
        if (town.selections.size <= wake.type.choice) {
            bot.sendMessage(
                ChatId.fromId(chatId),
                "Игрок номер $num выбран. Выбрано ${town.selections.size} / ${wake.type.choice} игроков."
            )
        }
        if (wake.type.choice <= town.selections.size) {
            val players = town.selections.map {
                town.playerMap[it]
            }
            val arg = CoerceJavaToLua.coerce(players.toTypedArray())
            val script = town.scripts[wake.players.first().roleData.name]
            val priority =
                wake.players.filter { it.alive }.maxOfOrNull { it.roleData.priority } ?: 1
            val actors =
                wake.players.filter { it.roleData.priority == priority && it.alive }
                    .map { it.pos }
            if (script != null) {
                try {
                    script.set("CONST", CoerceJavaToLua.coerce(Const(actors, players, town)))
                    val scriptRes = script.get("action").call(arg)
                    val ret =
                        CoerceLuaToJava.coerce(
                            scriptRes,
                            Return::class.java
                        )
                    val actorsSet = actors.toSet()

                    if (ret !is Return) {
                        return
                    }

                    val text = ret.results.mapIndexed { _, res ->
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
                                            && it.selection.map { it?.pos ?: -1 }.toSet()
                                        .intersect(actorsSet).isNotEmpty()
                                }
                            val result = res.text
                            town.actions.add(
                                InfoResult(
                                    if (blocker == null) result else "Действие заблокировано",
                                    actors,
                                    res.selection
                                )
                            )
                            res.text
                            start + if (blocker == null) result else "Действие заблокировано"
                        } else {
                            if (res is NoneResult) {
                                return
                            }
                            if (res is Result) {
                                res.selection.filterNotNull().forEach {
                                    try {
                                        val pos = it.pos
                                        val lua =
                                            town.scripts[town.playerMap[pos]?.roleData?.name]
                                        lua?.set(
                                            "CONST",
                                            CoerceJavaToLua.coerce(
                                                Const(
                                                    listOf(pos),
                                                    wake.players,
                                                    town
                                                )
                                            )
                                        )
                                        val pArg = CoerceJavaToLua.coerce(res)

                                        val passive = lua?.get("passive")?.call(pArg)?.let {
                                            CoerceLuaToJava.coerce(
                                                it,
                                                Return::class.java
                                            )
                                        }

                                        town.actions.add(res)
                                        if (passive != null && passive is Return) {
                                            for (result in passive.results) {
                                                if (result !is NoneResult) {
                                                    town.actions.add(result)
                                                }
                                            }
                                        }
                                    } catch (e: Exception) {
                                        e.printStackTrace()
                                    }
                                }
                            }
                            start + "Действие зарегистрировано"
                        }
                        return@mapIndexed text
                    }.joinToString("\n")
                    town.index++
                    bot.sendMessage(
                        ChatId.fromId(chatId),
                        if (ret.results.isNotEmpty()) text else "Роль не совершила действий",
                        replyMarkup = inlineKeyboard {
                            if (town.index >= town.night.size) {
                                button(dayCommand, -1L)
                            } else {
                                // todo replace -1L with messageId
                                button(nextRoleCommand, -1L)
                            }
                        }
                    )
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
            return
        }
    }
}

private fun handleMigrations() {
    if (internal.get("migration") == null) {
        runBlocking {
            migrate(accountsMongo, accounts, AccountOld::toAccount)
            migrate(gamesMongo, games)
            //migrate(connectionsMongo, connections)
            migrate(pendingMongo, pending)
            migrate(adsMongo, ads)
            migrate(bombsMongo, bombs)
            migrate(checksMongo, checks)
            migrate(kicksMongo, kicks)
            //migrate(modesMongo, modes)
            migrate(selectionsMongo, selections)
            migrate(hostInfosMongo, hostInfos)
            migrate(hostRequestsMongo, hostRequests)
            migrate(adminsMongo, admins)
            migrate(adminMenusMongo, adminMenus)
            migrate(timersMongo, timers)
        }
        internal.save("migration")
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
    path: String,
    pairings: Collection<Pairing, ObjectId>,
    orders: Collection<TypeOrder, ObjectId>,
    types: Collection<Type, ObjectId>,
    chatId: Long,
    towns: MutableMap<Long, Town>,
    roleNameLen: Int,
    roleDescLen: Int,
    games: Collection<Game, ObjectId>,
    bot: Bot,
    modes: Collection<GameMode, ObjectId>
) {
    try {
        games.find { host == chatId }.singleOrNull()?.let { game ->
            gameHistory.save(GameSummary(ObjectId(), game, connections.find { gameId == game.id }))

            accounts.update(chatId) {
                menuMessageId = -1L
            }
            val roleMap = getRoles(setups, game, roles)
            //val roleDesc = roleDesc(roleMap)

            val scriptMap = roleMap.keys.filter { it.scripted }.associate {
                val lua = Globals()
                lua.load(JseBaseLib())
                lua.load(PackageLib())
                LoadState.install(lua)
                LuaC.install(lua)
                lua.get("dofile").call(LuaValue.valueOf("$path/scripts/${it.script}.lua"))
                it.name to lua
            }


            val pairs = mutableListOf<Pairing>()
            pairings.find { gameId == game.id }.forEach {
                pairs.add(it)
            }

            val orderList = mutableListOf<TypeOrder>()
            orders.find { gameId == game.id }.forEach {
                orderList.add(it)
            }
            val typeList = mutableListOf<Type>()
            types.find { gameId == game.id }.forEach {
                typeList.add(it)
            }
            val mode = modes.find { gameId == game.id }.singleOrNull()?.mode ?: Mode.OPEN
            val town1 = Town(
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
            towns[chatId] = town1


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
                            replyMarkup = footerKeyboard {
                                button(leaveGameCommand)
                            }
                        )
                        bot.sendMessage(
                            ChatId.fromId(con.player),
                            "Ваша роль: <span class=\"tg-spoiler\">${role.name.padEnd(roleNameLen, '_')}</span>\n" +
                                    "Описание: <span class=\"tg-spoiler\">${
                                        role.desc.padEnd(
                                            roleDescLen,
                                            '_'
                                        )
                                    }</span>",
                            parseMode = ParseMode.HTML,
                            replyMarkup = inlineKeyboard {
                                button(gameInfoCommand, game.id)
                            }
                        )
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }
            }

            games.updateMany(
                { host == chatId },
                { state = GameState.Game }
            )
            bot.sendMessage(
                ChatId.fromId(chatId),
                "Роли в игре:\n" + roleMap.entries
                    .filter { it.value > 0 }
                    .sortedBy { it.key.index }
                    .joinToString("\n") { "- " + it.key.name },
                replyMarkup = dayKeyboard()
            )

            accounts.update(chatId) {
                menuMessageId = -1L
            }
            towns[chatId]?.let { town ->
                showDay(town, chatId, -1L, towns, accounts, bot, "", "", modes, game)
            }
        }
    } catch (e: Exception) {
        e.printStackTrace()
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
                val arg = CoerceJavaToLua.coerce(town.players.toTypedArray())
                script?.get("type")?.call(arg)?.toString() ?: "none"
            } else {
                "none"
            }.split(",")
            types.forEach {
                wakeMap.getOrPut(it) { mutableListOf() }.add(pair)
            }
            conMap[pair.connectionId] = types
        }

        pairs[connection.id]?.let {
            val names = checks.get(CheckOption.NAMES)
            val cover = checks.get(CheckOption.COVER)
            val players =
                if (!names || !conMap.containsKey(it.connectionId) || "none" in (conMap[it.connectionId]
                        ?: emptyList())
                ) {
                    emptyList()
                } else {
                    conMap[it.connectionId]
                        ?.asSequence()
                        ?.flatMap { wakeMap[it] ?: emptyList() }
                        ?.toSet()
                        ?.map { connections.get(it.connectionId) to roles.get(it.roleId) }
                        ?.sortedBy { it.first?.pos ?: -1 }
                        ?.map {
                            "№${it.first!!.pos} - " + it.first!!.name() + " - " + it.second!!.name(
                                cover
                            )
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
            roleDesc += "<b>" + entry.key.name + "</b>\nКоличество: ${entry.value}\nОписание: ${entry.key.desc}\n\n"
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
    println(json)
    try {
        val data = Json.decodeFromString<GameSet>(json)

        roles.deleteMany { gameId == game.id }
        data.roles.forEachIndexed { index, it ->
            val role = Role(
                ObjectId(),
                game.id,
                it.name,
                it.desc,
                it.scripted,
                it.defaultTeam,
                it.script,
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
        e.printStackTrace()
    }
}

private fun showNumPrompt(
    chatId: Long,
    messageId: Long,
    bot: Bot,
    connectionId: ObjectId
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
            0,
            msgId
        )
    )
    return msgId
}

private fun dayKeyboard() = footerKeyboard {
    button(stopGameCommand)
}

private fun showNightRole(
    town: Town,
    chatId: Long,
    bot: Bot
) {
    town.selections.clear()
    val wake = if (town.night.size > town.index) town.night[town.index] else null
    if (wake == null) {
        bot.sendMessage(
            ChatId.fromId(chatId),
            "Ночь завершена",
            replyMarkup = inlineKeyboard {
                // todo replace -1L with messageId
                button(dayCommand, -1L)
            }
        )
        //startDay(town, chatId, towns, accounts, bot)
        return
    }
    val players = wake.players.sortedBy { it.roleData.priority }.reversed()
    val alive = players.filter { it.alive }
    val action =
        alive.firstOrNull()?.roleData?.desc ?: "Все персонажи мертвы."
    bot.sendMessage(
        ChatId.fromId(chatId),
        "Просыпаются: " + players.map { it.roleData.name }.toSet().joinToString(", ") + "\n" +
                "Действие: " + action + "\n" +
                "Игроки: " + players.filter { it.alive }.joinToString(", ") { desc(it, " - ") } +
                if (alive.isNotEmpty()) "\n\nВыберите ${wake.type.choice} игроков:" else "",
        replyMarkup = inlineKeyboard {
            if (alive.isEmpty()) {
                // todo replace -1L with messageId
                button(skipRoleCommand named "Пропустить", -1L)
            } else {
                reordered(town.players.filter { it.alive }.sortedBy { it.pos }).chunked(2).forEach {
                    row {
                        it.forEach {
                            // todo replace -1L with messageId
                            button(selectCommand named desc(it), it.pos, -1L)
                        }
                        if (it.size == 1) {
                            button(blankCommand)
                        }
                    }
                }
            }
        }
    )
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
    // todo replace -1L with messageId
    showDay(town, chatId, -1L, towns, accounts, bot, fullLog, shortLog(town), modes, game)
}

private fun showDay(
    town: Town,
    chatId: Long,
    messageId: Long,
    towns: MutableMap<Long, Town>,
    accounts: Collection<Account, Long>,
    bot: Bot,
    fullLog: String,
    shortLog: String,
    modes: Collection<GameMode, ObjectId>,
    game: Game
) {
    val mode = modes.get(game.id)
    val view = mode?.dayView ?: DayView.ALL
    val fallMode = mode?.fallMode ?: false
    val keyboard = inlineKeyboard {
        row { button(filterCommand named "Фильтр: ${view.desc}", messageId) }
        for (player in town.players.sortedBy { it.pos }) {
            if (view.filter(player)) {
                row {
                    button((if (mode?.detailedView == true) blankCommand else dayDetailsCommand) named desc(player), player.pos, messageId)
                }
                if (mode?.detailedView == true) {
                    row {
                        playerDayDesc(player, messageId, fallMode)
                    }
                }
            }
        }
        button(settingsCommand, messageId)
        button(timerCommand)
        button(nightCommand, messageId)
    }

    withAccount(accounts, chatId) { acc ->
        if (acc.menuMessageId != -1L) {
            bot.editMessageReplyMarkup(
                ChatId.fromId(chatId),
                acc.menuMessageId,
                replyMarkup = keyboard
            )
            return@withAccount
        }

        if (fullLog.isNotBlank()) {
            bot.sendMessage(
                ChatId.fromId(chatId),
                "Все события:\n${fullLog}"
            )
        }

        bot.sendMessage(
            ChatId.fromId(chatId),
            "Результат ночи:\n" + shortLog.ifBlank { "Не произошло никаких событий" }
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
            "Начинаем день",
            replyMarkup = dayKeyboard()
        )
        val res = bot.sendMessage(
            ChatId.fromId(chatId),
            "День ${towns[chatId]?.day}\n" +
                    "Вживых:\n" + teamSet.joinToString("\n") {
                it + ": " + mapAlive.getOrDefault(it, 0) + " / " + mapAll.getOrDefault(it, 0)
            },
            replyMarkup = keyboard
        )
        if (res.isSuccess) {
            accounts.update(chatId) {
                menuMessageId = res.get().messageId
            }
        }
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
                    "Игрок номер ${it.pos} ${it.desc()}"
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

private fun desc(player: Person?, sep: String = ". ") = if (player != null)
    "${player.pos}$sep${player.name} (${player.roleData.name})"
else "Неизвестный игрок"

//private fun desc(player: Person, sep: String = ". ") = "${player.pos}$sep${player.name} (${player.role.name})"

@OptIn(ExperimentalSerializationApi::class)
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
        replyMarkup = footerKeyboard {
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
                button(blankCommand named (pair?.roleId?.let { id -> roles.get(id)?.name } ?: "Роль не выдана"))
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

fun CallbackQueryHandlerEnvironment.withAccount(
    accounts: Collection<Account, Long>,
    func: (Account, Long) -> Unit
) {
    callbackQuery.message?.chat?.id?.let { chatId ->
        withAccount(accounts, chatId) { func(it, -1L) }
    }
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

fun setupLayout() = KeyboardReplyMarkup(
    keyboard = listOf(
        listOf(KeyboardButton("Перейти к определению ролей")),
        listOf(KeyboardButton("Завершить игру"))
    ),
    resizeKeyboard = true
)

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

data class Account(
    @BsonId val id: ObjectId,
    val chatId: Long,
    var userName: String,
    var name: String = "",
    var state: AccountState = AccountState.Init,
    var menuMessageId: Long = -1L,
    var hostMessageId: Long = -1L,
    var setupMessageId: Long = -1L,
    var dayMessageId: Long = -1L,
    var connectionId: ObjectId? = null
) {
    fun fullName() = name + if (userName.isNotBlank()) " (@$userName)" else ""
}

data class Game(
    @BsonId val id: ObjectId,
    var host: Long,
    var playerCount: Int = -1,
    var state: GameState = GameState.Connect,
    val createdAt: Date = Date()
) {
    val creator: Long = host
}

data class Connection(
    @BsonId val id: ObjectId,
    val gameId: ObjectId,
    val player: Long,
    var name: String = "",
    var handle: String = "",
    var bot: Boolean = false,
    var pos: Int = Int.MAX_VALUE,
) {
    val createdAt: Date = Date()
    fun name() = name + if (handle.isNotBlank()) " ($handle)" else ""
}

data class Pending(
    @BsonId val id: ObjectId,
    val host: Long,
    val date: Date = Date()
)

data class Role(
    @BsonId val id: ObjectId,
    val gameId: ObjectId,
    val name: String,
    val desc: String,
    val scripted: Boolean,
    val defaultTeam: String,
    val script: String = "",
    val priority: Int = 1,
    val coverName: String = "",
) {
    var index: Int = -1

    fun name(cover: Boolean = false): String {
        if (cover && coverName.isNotBlank()) {
            return coverName
        }
        return name
    }
}

data class Setup(
    @BsonId val id: ObjectId,
    var roleId: ObjectId,
    var gameId: ObjectId,
    var role: String,
    val index: Int,
    var count: Int = 0
)

data class Pairing(
    @BsonId val id: ObjectId,
    val gameId: ObjectId,
    val connectionId: ObjectId,
    val roleId: ObjectId
)

data class TypeOrder(
    @BsonId val id: ObjectId,
    val gameId: ObjectId,
    val type: String,
    val pos: Int
)

data class Bomb(
    @BsonId val id: ObjectId,
    val chatId: Long,
    val messageId: Long,
    val date: Date
)

data class Message(
    @BsonId val id: ObjectId,
    val text: String
)

data class Check(
    @BsonId val id: ObjectId,
    val name: String,
    var state: Boolean
)

data class Kick(
    @BsonId val id: ObjectId,
    val gameId: ObjectId,
    val player: Long
)

data class GameMode(
    @BsonId val id: ObjectId,
    val gameId: ObjectId,
    var mode: Mode,
    var dayView: DayView = DayView.ALL,
    var fallMode: Boolean = false,
    var detailedView: Boolean = false
)

enum class Mode(val type: String, val desc: String) {
    OPEN(
        "Открытая",
        "Роль игрока вскрывается после смерти. Запрещено называть роли вовремя игры, а также слишком явно на них намекать."
    ),
    CLOSED(
        "Закрытая",
        "После смерти игрок выхоит из игры не называя роли. Вовремя игры можно называть роли и блефовать."
    )
}

enum class DayView(val desc: String, val filter: (Person) -> Boolean) {
    ALL("Все игроки", { true }),
    ALIVE("Живые игроки", { it.alive })
}

sealed class Action(
    val pos: Int,
    val actors: List<Int>
) {
    var blocked = false
    abstract fun desc(): String
}

class KillAction(
    pos: Int,
    actors: List<Int>
) : Action(pos, actors) {
    override fun desc(): String {
        return "убит"
    }
}

class SilenceAction(
    pos: Int,
    actors: List<Int>
) : Action(pos, actors) {
    override fun desc(): String {
        return "не принимает участие в обсуждении"
    }
}

class Const(val actors: List<Int>, val selection: List<Person?>, val town: Town) {
    fun NONE() = NoneResult
    fun INFO(result: String) = Return(InfoResult(result, actors, selection))
    fun KILL() = KILL(selection)
    fun KILL(select: Person?) = KILL(listOf(select))
    fun KILL(select: List<Person?>) = Return(KillResult(actors, select))
    fun HEAL() = HEAL(selection)
    fun HEAL(select: Person?) = HEAL(listOf(select))
    fun HEAL(select: List<Person?>) = Return(HealResult(actors, select))
    fun BLOCK() = BLOCK(selection)
    fun BLOCK(select: Person?) = BLOCK(listOf(select))
    fun BLOCK(select: List<Person?>) = Return(BlockResult(actors, select))
    fun SILENCE() = SILENCE(selection)
    fun SILENCE(select: Person?) = SILENCE(listOf(select))
    fun SILENCE(select: List<Person?>) = Return(SilenceResult(actors, select))

    fun ALLOW() = Return(NoneResult)
    fun CANCEL(blocked: Result) = Return(CancelResult(blocked, actors, blocked.actors.map { town.playerMap[it] }))
    fun CANCEL(blocked: Return) = Return(
        blocked.results.map {
            CancelResult(it, actors, it.actors.map { town.playerMap[it] })
        }
    )

    fun STORE(value: Any) = town.store(value)
    fun STORED(key: Int) = town.get(key)

    fun IS_INFO(result: Result) = result is InfoResult
    fun IS_KILL(result: Result) = result is KillResult
    fun IS_HEAL(result: Result) = result is HealResult
    fun IS_BLOCK(result: Result) = result is BlockResult
    fun IS_SILENCE(result: Result) = result is SilenceResult

    fun GET_ACTORS() = actors.map { town.playerMap[it] }
    fun TWO(ret1: Return, ret2: Return) = Return(ret1.results + ret2.results)
    fun THREE(ret1: Return, ret2: Return, ret3: Return) = TWO(TWO(ret1, ret2), ret3)
}

data class Return(val results: List<Result>) {
    constructor(result: Result) : this(listOf(result))
}

sealed class Result(val actors: List<Int>, val selection: List<Person?>) {
    var blocked = false
    abstract fun desc(): String
    abstract fun actions(): List<Action>
}

class InfoResult(val text: String, actors: List<Int>, selection: List<Person?>) : Result(actors, selection) {
    override fun desc(): String {
        return "Проверить"
    }

    override fun actions(): List<Action> {
        return emptyList()
    }
}

sealed class TargetedResult(actors: List<Int>, selection: List<Person?>) : Result(actors, selection)
class KillResult(actors: List<Int>, selection: List<Person?>) : TargetedResult(actors, selection) {
    override fun desc(): String {
        return "Убить"
    }

    override fun actions(): List<Action> {
        return selection.filterNotNull().map { KillAction(it.pos, actors) }
    }
}

class HealResult(actors: List<Int>, selection: List<Person?>) : TargetedResult(actors, selection) {
    override fun desc(): String {
        return "Вылечить"
    }

    override fun actions(): List<Action> {
        return emptyList()
    }
}

class BlockResult(actors: List<Int>, selection: List<Person?>) : TargetedResult(actors, selection) {
    override fun desc(): String {
        return "Заблокировать роль"
    }

    override fun actions(): List<Action> {
        return emptyList()
    }
}

class SilenceResult(actors: List<Int>, selection: List<Person?>) : TargetedResult(actors, selection) {
    override fun desc(): String {
        return "Заблокировать обсуждение"
    }

    override fun actions(): List<Action> {
        return selection.filterNotNull().map { SilenceAction(it.pos, actors) }
    }
}

class CancelResult(val canceled: Result, actors: List<Int>, selection: List<Person?>) :
    TargetedResult(actors, selection) {
    override fun desc(): String {
        return "Отменить действие: ${canceled.desc()}"
    }

    override fun actions(): List<Action> {
        return emptyList()
    }
}

data object NoneResult : Result(emptyList(), emptyList()) {
    override fun desc(): String {
        return "Действие не указано"
    }

    override fun actions(): List<Action> {
        return emptyList()
    }
}

data class Person(
    val pos: Int,
    val name: String,
    val roleData: Role,
    var team: String,
    var alive: Boolean = true,
    var protected: Boolean = false,
    var fallCount: Int = 0
) {
    fun isAlive() = alive
    fun getRole() = roleData.name
}

data class Type(
    @BsonId val id: ObjectId,
    val gameId: ObjectId,
    val name: String,
    val choice: Int
)

data class Wake(
    val type: Type,
    val players: List<Person>
)

data class Town(
    val host: Long,
    val players: List<Person>,
    val order: List<String>,
    val types: Map<String, Type>,
    val scripts: Map<String, Globals>,
    var mode: Mode = Mode.OPEN,
    var day: Int = 1
) {
    val playerMap = players.associateBy { it.pos }
    val actions = mutableListOf<Result>()
    val night = mutableListOf<Wake>()
    var index = 0
    val storage: MutableMap<Int, Any> = mutableMapOf()
    var storageIndex = 1

    val selections = mutableSetOf<Int>()

    fun setAlive(pos: Int, alive: Boolean) {
        players.firstOrNull { it.pos == pos }?.let {
            it.alive = alive
            if (alive) {
                actions.removeIf { res -> res is KillResult && it in res.selection }
            } else {
                actions.add(KillResult(emptyList(), listOf(it)))
            }
        }
    }

    fun changeProtected(pos: Int) {
        playerMap[pos]?.protected = !(playerMap[pos]?.protected ?: true)
    }

    fun store(value: Any): Int {
        storage[storageIndex] = value
        return storageIndex++
    }

    fun get(key: Int) = storage[key]

    fun rollback() {
        if (index == 0) {
            return
        }
        index--
        night[index].let {
            val actors = it.players.map { it.pos }.toSet()
            actions.removeIf { it.actors.any { it in actors } }
        }
    }

    fun prepNight() {
        val map = mutableMapOf<String, MutableList<Person>>()
        for (person in players) {
            if (person.roleData.scripted) {
                val script = scripts[person.roleData.name]
                val arg = CoerceJavaToLua.coerce(players.toTypedArray())
                script?.get("type")?.call(arg)?.toString() ?: "none"
            } else {
                "none"
            }.split(",").forEach {
                map.getOrPut(it.trim()) { mutableListOf() }.add(person)
            }
        }
        night.clear()
        index = 0
        for (type in order) {
            if ((map[type]?.size ?: 0) > 0 && (mode == Mode.CLOSED || (map[type]?.filter { it.alive }?.size
                    ?: 0) > 0)
            ) {
                night.add(Wake(types[type]!!, map[type]?.sortedBy { it.roleData.priority }?.reversed() ?: emptyList()))
            }
        }
    }

    fun updateTeams() {
        players.forEach {
            val script = scripts[it.roleData.name]
            val team = if (script == null) {
                it.team
            } else {
                val arg = CoerceJavaToLua.coerce(players.toTypedArray())
                script.get("team").call(arg).toString()
            }
            it.team = team
        }
    }

    fun endNight() {
        try {
            players.forEach { it.protected = false }
            /*val blockedActors = mutableSetOf<Int>()
            var index = 0
            while (index < actions.size) {
                val it = actions[index]
                if (it.actors.toSet().intersect(blockedActors).isNotEmpty()) {
                    it.blocked = true
                    index++
                    continue
                }
                when (it) {
                    is BlockResult -> {
                        blockedActors.addAll(it.selection.filterNotNull().map { it.pos })
                        index++
                        continue
                    }
                    is CancelResult -> TODO()
                    is HealResult -> TODO()
                    is KillResult -> TODO()
                    is SilenceResult -> {
                        it.selection.filterNotNull().forEach {
                            playerMap[it.pos]?.protected = true
                        }
                    }
                    else -> {}
                }
                index++
            }*/

            val blocks = actions.filterIsInstance<BlockResult>()
            for (block in blocks) {
                val select = block.selection.filterNotNull()
                actions.removeIf {
                    select.map { it.pos }.toSet().intersect(it.actors.toSet()).isNotEmpty()
                }
            }
            val cancels = actions.filterIsInstance<CancelResult>().map { it as CancelResult }
            for (cancel in cancels) {
                actions.remove(cancel.canceled)
            }
            val heals = actions.filterIsInstance<HealResult>().map { it as HealResult }
            for (heal in heals) {
                val select = heal.selection.filterNotNull()
                actions.removeIf {
                    select.intersect(it.selection.toSet()).isNotEmpty() && it is KillResult
                }
            }
            val kills = actions.filterIsInstance<KillResult>()
            for (it in kills) {
                it.selection.filterNotNull().forEach {
                    playerMap[it.pos]?.alive = false
                }
            }
            val mutes = actions.filterIsInstance<SilenceResult>()
            for (it in mutes) {
                it.selection.filterNotNull().forEach {
                    playerMap[it.pos]?.protected = true
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}

@Serializable
data class GameSet(
    val name: String,
    val order: List<String>,
    val type: List<Choice>,
    val roles: List<RoleData>
)

@Serializable
data class Choice(
    val name: String,
    val choice: Int
)

@Serializable
data class RoleData(
    val name: String,
    val desc: String,
    val scripted: Boolean,
    val defaultTeam: String,
    val script: String,
    val priority: Int = 1,
    val coverName: String = ""
)

enum class AccountState {
    Init, Menu, Host, Lobby, Presets, Admin
}

enum class GameState {
    Connect, Roles, Preview, Game, Dummy, Rename, Num
}

data class Selection(
    @BsonId val id: ObjectId,
    val chatId: Long,
    val choice: String
)

data class HostInfo(
    @BsonId val id: ObjectId,
    val chatId: Long,
    var timeLimit: Boolean = false,
    var until: Date = Date(),
    var gameLimit: Boolean = false,
    var left: Int = -1,
    var canShare: Boolean = true
)

enum class CheckOption(val key: String, val display: String) {
    NAMES("names", "Показывать список игроков в команде"),
    COVER("cover", "Использовать `coverName`"),
    HOST_KNOWN("host_known", "Только известные ведущие"),
    HOST_REQUEST("host_request", "Сохранять запросы на ведение")
}

data class UserId(
    @BsonId val id: ObjectId,
    val chatId: Long
)

enum class AdminState {
    NONE, HOST_TIME, HOST_GAMES
}

data class AdminMenu(
    @BsonId val id: ObjectId,
    val chatId: Long,
    var state: AdminState = AdminState.NONE,
    var editId: Long = -1,
    var messageId: Long = -1,
    var descId: Long = -1
)

data class Timer(
    @BsonId val id: ObjectId,
    val chatId: Long,
    val messageId: Long,
    var timestamp: Long,
    var time: Long,
    var active: Boolean = true,
    var updated: Boolean = true
)

data class GameSummary(
    val id: ObjectId,
    val game: Game,
    val connections: List<Connection>,
    val playedAt: Date = Date()
)

data class AdPopup(
    val id: ObjectId,
    val chatId: Long,
    val messageId: Long
)