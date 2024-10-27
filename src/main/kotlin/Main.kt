package org.example

import com.github.kotlintelegrambot.Bot
import com.github.kotlintelegrambot.bot
import com.github.kotlintelegrambot.dispatch
import com.github.kotlintelegrambot.dispatcher.callbackQuery
import com.github.kotlintelegrambot.dispatcher.handlers.CallbackQueryHandlerEnvironment
import com.github.kotlintelegrambot.dispatcher.text
import com.github.kotlintelegrambot.entities.*
import com.github.kotlintelegrambot.entities.keyboard.InlineKeyboardButton
import com.github.kotlintelegrambot.entities.keyboard.KeyboardButton
import com.mongodb.client.model.Filters
import com.mongodb.client.model.Updates
import com.mongodb.kotlin.client.coroutine.MongoClient
import com.mongodb.kotlin.client.coroutine.MongoCollection
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.count
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.singleOrNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.bson.codecs.pojo.annotations.BsonId
import org.bson.conversions.Bson
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
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.*
import kotlin.math.max
import kotlin.reflect.KClass

val lobbyStates = setOf(GameState.Connect, GameState.Rename, GameState.Dummy)
val numbers = arrayOf("0️⃣", "1️⃣", "2️⃣", "3️⃣", "4️⃣", "5️⃣", "6️⃣", "7️⃣", "8️⃣", "9️⃣")

fun main(args: Array<String>) {
    //val connectionString = "mongodb://EdgeDom:WontH4CKAGA1n@localhost:44660/?retryWrites=true&w=majority"
    val author = "dev_vlad"
    val roleNameLen = 32
    val roleDescLen = 280

    val connectionString = "mongodb://localhost:27017/?retryWrites=true&w=majority"
    val client = MongoClient.create(connectionString = connectionString)
    val db = client.getDatabase("mafia")
    val presets = client.getDatabase("presets")
    val accounts = db.getCollection<Account>("accounts")
    val games = db.getCollection<Game>("games")
    val connections = db.getCollection<Connection>("connections")
    val pending = db.getCollection<Pending>("pending")
    val roles = db.getCollection<Role>("roles")
    val setups = db.getCollection<Setup>("setups")
    val pairings = db.getCollection<Pairing>("pairings")
    val orders = db.getCollection<TypeOrder>("orders")
    val types = db.getCollection<Type>("types")
    val ads = db.getCollection<Message>("ads")
    val bombs = db.getCollection<Bomb>("bombs")
    val checks = db.getCollection<Check>("checks")
    val kicks = db.getCollection<Kick>("kicks")
    val modes = db.getCollection<GameMode>("modes")
    val selections = db.getCollection<Selection>("selections")
    val hostInfos = db.getCollection<HostInfo>("hostInfos")

    val path = args[0]
    val towns = mutableMapOf<Long, Town>()

    val bot = bot {
        token = "7381702333:AAFDKZrYiSMi0Ugunm55v7syJcysS9gmcBY"

        val resetAccount = Updates.combine(
            Updates.set("state", AccountState.Menu),
            Updates.set("menuMessageId", -1L),
            Updates.set("hostMessageId", -1L),
            Updates.set("setupMessageId", -1L),
            Updates.set("dayMessageId", -1L),
            Updates.set("connectionId", "")
        )

        dispatch {
            val backText = "Отмена"
            text {
                val chatId = message.chat.id
                val filter = Filters.eq("chatId", chatId)
                val result = accounts.find(filter).singleOrNull()

                if (result == null) {
                    initAccount(message.from?.username ?: "", accounts, chatId, roles, types, orders, bot, path)
                    return@text
                }

                fun error(text: String = "Неизвестная команда.") {
                    bot.sendMessage(
                        ChatId.fromId(chatId),
                        text
                    )
                }

                val connectKeyboard = KeyboardReplyMarkup(
                    keyboard = listOf(
                        listOf(KeyboardButton("Перейти к определению ролей")),
                        listOf(KeyboardButton("Список исключенных игроков")),
                        listOf(KeyboardButton("Сбросить номера игроков")),
                        listOf(KeyboardButton("Завершить игру"))
                    ),
                    resizeKeyboard = true
                )

                if (message.from?.username == author) {
                    if (message.text == "/ad") {
                        val lobbies = with(result) {
                            val lobbies = mutableListOf<List<InlineKeyboardButton>>()
                            games.find().collect {
                                accounts.find(Filters.eq("chatId", it.host)).singleOrNull()?.let { host ->
                                    lobbies.add(
                                        listOf(
                                            InlineKeyboardButton.CallbackData(
                                                host.fullName(),
                                                "send: ${it.id.toHexString()}"
                                            )
                                        )
                                    )
                                }
                            }
                            InlineKeyboardMarkup.create(
                                lobbies
                            )
                        }
                        bot.sendMessage(
                            ChatId.fromId(chatId),
                            "Активные игры:",
                            replyMarkup = lobbies
                        )
                        return@text
                    } else if (message.text?.startsWith("/newad") == true) {
                        val text = (message.text?.drop(6) ?: "").trim()
                        if (text.isNotBlank()) {
                            ads.find().singleOrNull()?.let {
                                ads.deleteOne(Filters.eq("_id", it.id))
                            }
                            ads.insertOne(Message(ObjectId(), text))
                        }
                        return@text
                    } else if (message.text?.startsWith("/checks ") == true) {
                        val param = message.text?.drop(8)?.trim() ?: ""
                        if (param.isNotBlank()) {
                            val filter1 = Filters.eq("name", param)
                            val check = checks.find(filter1).singleOrNull()
                            val res = if (check == null) {
                                checks.insertOne(Check(ObjectId(), param, true))
                                true
                            } else {
                                checks.updateOne(
                                    filter1,
                                    Updates.set("state", !check.state)
                                )
                                !check.state
                            }
                            bot.sendMessage(
                                ChatId.fromId(chatId),
                                "Состояние `$param` изменено на `$res`"
                            )
                        }
                        return@text
                    }
                }


                when (result.state) {
                    AccountState.Init -> {
                        accounts.updateOne(
                            filter,
                            Updates.combine(
                                Updates.set("name", message.text),
                                Updates.set("state", AccountState.Menu)
                            )
                        )
                        showMenu(chatId, result, games, accounts, "Добро пожаловать, ${message.text}", bot)
                    }

                    AccountState.Menu -> {
                        if (message.text?.startsWith("/host ") == true) {
                            val canHost = try {
                                hostInfos.find(filter).first()
                                true
                            } catch (e: NoSuchElementException) {
                                false
                            }
                            if (!canHost) {
                                error()
                                return@text
                            }

                            var num = -1
                            try {
                                val text = message.text?.drop(6)
                                num = text?.toInt() ?: -1
                            } catch (e: NumberFormatException) {
                                e.printStackTrace()
                            }

                            updateSetup(path, roles, chatId, types, orders)

                            val id = games.insertOne(Game(ObjectId(), result.chatId, num)).insertedId
                            val game = games.find(Filters.eq("_id", id)).singleOrNull()
                            if (game != null) {
                                accounts.updateOne(
                                    filter,
                                    Updates.combine(
                                        Updates.set("state", AccountState.Host),
                                        Updates.set("menuMessageId", -1L)
                                    )
                                )
                                bot.sendMessage(
                                    ChatId.fromId(chatId),
                                    "Игра создана. Ожидайте присоединения игроков.",
                                    replyMarkup = connectKeyboard
                                )
                                showLobby(result, connections, game, bot, accounts, true)
                            } else {
                                error("Не удалось создать игру. Попробуйте еще раз.")
                            }
                            return@text
                        }

                        when (message.text) {
                            "Обновить список игр", "/update" -> {
                                val res = showGames(result, chatId, bot, games, accounts)
                                if (res != -1L) {
                                    accounts.updateOne(
                                        filter,
                                        Updates.set("menuMessageId", res)
                                    )
                                }
                            }

                            "Сменить имя" -> {
                                withAccount(accounts, chatId) { acc, _ ->
                                    val chat = ChatId.fromId(chatId)
                                    if (acc.menuMessageId != -1L) {
                                        bot.deleteMessage(chat, acc.menuMessageId)
                                    }
                                    bot.sendMessage(
                                        chat,
                                        "Пожалуйста, введите свое имя. Это имя смогут видеть ведущие игр, к которым вы присоединяетесь.",
                                        replyMarkup = ReplyKeyboardRemove(true)
                                    )
                                    accounts.updateOne(filter, resetAccount)
                                    accounts.updateOne(
                                        filter,
                                        Updates.combine(
                                            Updates.set("state", AccountState.Init),
                                            Updates.set("menuMessageId", -1)
                                        )
                                    )
                                }
                            }

                            "/start", "/menu" -> {
                                showMenu(chatId, result, games, accounts, "Добро пожаловать", bot, true)
                            }

                            else -> error()
                        }
                    }

                    AccountState.Lobby -> {
                        connections.find(Filters.eq("player", chatId)).singleOrNull()?.let { con ->
                            if (con.pos == Int.MAX_VALUE) {
                                try {
                                    val num = text.toInt()
                                    if (num <= 0) {
                                        error()
                                    }
                                    connections.updateOne(
                                        Filters.eq("_id", con.id),
                                        Updates.set("pos", num)
                                    )
                                    pending.insertOne(Pending(ObjectId(), con.host))
                                    bot.sendMessage(
                                        ChatId.fromId(chatId),
                                        "Номер игрока сохранен. Ожидайте начала игры.",
                                        replyMarkup = KeyboardReplyMarkup(
                                            keyboard = listOf(listOf(KeyboardButton("Покинуть"))),
                                            resizeKeyboard = true
                                        )
                                    )
                                    return@text
                                } catch (e: NumberFormatException) {

                                }
                            }

                            val text = message.text
                            when (text) {
                                "Покинуть", "/menu", "/start" -> {
                                    val game = games.find(Filters.eq("host", con.host)).singleOrNull()
                                    if (game?.state == GameState.Roles) {
                                        val confirmButton = listOf(InlineKeyboardButton.CallbackData("Да", "leave"))
                                        val res = bot.sendMessage(
                                            ChatId.fromId(chatId),
                                            "Ведущий перешел к выдаче ролей. Вы уверены, что хотите покинуть игру?",
                                            replyMarkup = InlineKeyboardMarkup.create(
                                                listOf(confirmButton)
                                            )
                                        )
                                        if (res.isSuccess) {
                                            val id = res.get().messageId
                                            bot.editMessageReplyMarkup(
                                                ChatId.fromId(chatId),
                                                id,
                                                replyMarkup = InlineKeyboardMarkup.create(
                                                    listOf(
                                                        confirmButton,
                                                        listOf(
                                                            InlineKeyboardButton.CallbackData(
                                                                "Нет",
                                                                "deleteMsg: $id"
                                                            )
                                                        )
                                                    )
                                                )
                                            )
                                        }
                                        return@text
                                    } else {
                                        leaveGame(
                                            accounts,
                                            chatId,
                                            resetAccount,
                                            pending,
                                            con,
                                            connections,
                                            result,
                                            games,
                                            bot
                                        )
                                    }
                                }

                                else -> {
                                    games.find(Filters.eq("host", con.host)).singleOrNull()?.let { game ->
                                        if (game.state == GameState.Game && message.text != null) {
                                            bot.sendMessage(
                                                ChatId.fromId(con.host),
                                                "Игрок ${con.pos} - ${con.name()} пишет:\n" + message.text
                                            )
                                            return@text
                                        }
                                    }
                                    error()
                                }
                            }
                        }
                    }

                    AccountState.Host -> {
                        val hostFilter = Filters.eq("host", chatId)
                        val game = games.find(hostFilter).singleOrNull()
                        if (game != null) {
                            if (message.text?.startsWith("/rehost") == true) {
                                var num = -1
                                try {
                                    val text = message.text?.drop(8)
                                    println(text)
                                    num = text?.toInt() ?: -1
                                } catch (e: NumberFormatException) {
                                }

                                updateSetup(path, roles, chatId, types, orders)

                                games.updateOne(
                                    Filters.eq("_id", game.id),
                                    Updates.combine(
                                        Updates.set("state", GameState.Connect),
                                        Updates.set("playerCount", num)
                                    )
                                )
                                pending.deleteMany(hostFilter)
                                setups.deleteMany(hostFilter)
                                pairings.deleteMany(hostFilter)
                                towns.remove(chatId)
                                accounts.updateOne(
                                    Filters.eq("chatId", chatId),
                                    Updates.combine(
                                        Updates.set("menuMessageId", -1L),
                                        Updates.set("hostMessageId", -1L),
                                        Updates.set("setupMessageId", -1L),
                                        Updates.set("dayMessageId", -1L),
                                        Updates.set("connectionId", "")
                                    )
                                )
                                withAccount(accounts, chatId) { acc, chat ->
                                    bot.sendMessage(
                                        ChatId.fromId(chatId),
                                        "Игра перезапущена.",
                                        replyMarkup = connectKeyboard
                                    )
                                    showLobby(acc, connections, game, bot, accounts)
                                }
                                return@text
                            }

                            if (game.state == GameState.Connect && result.connectionId.isNotEmpty()) {
                                try {
                                    val num = text.toInt()
                                    if (num <= 0) {
                                        error()
                                    }
                                    val id = ObjectId(result.connectionId)
                                    connections.updateOne(
                                        Filters.eq("_id", id),
                                        Updates.set("pos", num)
                                    )
                                    showLobby(result, connections, game, bot, accounts)
                                    return@text
                                } catch (e: NumberFormatException) {

                                }
                            } else if (message.text != null && message.text != backText) {
                                if (game.state == GameState.Dummy) {
                                    connections.insertOne(
                                        Connection(
                                            ObjectId(),
                                            chatId,
                                            -1,
                                            message.text!!,
                                            "оффлайн",
                                            true
                                        )
                                    )
                                    games.updateOne(
                                        hostFilter,
                                        Updates.set("state", GameState.Connect)
                                    )
                                    showLobby(result, connections, game, bot, accounts)
                                    bot.sendMessage(
                                        ChatId.fromId(chatId),
                                        "Игрок `${message.text} (оффлайн)` добавлен",
                                        replyMarkup = connectKeyboard
                                    )
                                    return@text
                                } else if (game.state == GameState.Rename && result.connectionId.isNotEmpty()) {
                                    val id = ObjectId(result.connectionId)
                                    val idFilter = Filters.eq("_id", id)
                                    connections.find(idFilter).singleOrNull()?.let {
                                        val newName = message.text ?: ""
                                        connections.updateOne(
                                            idFilter,
                                            Updates.set("name", newName)
                                        )
                                        games.updateOne(
                                            Filters.eq("_id", game.id),
                                            Updates.set("state", GameState.Connect)
                                        )
                                        bot.sendMessage(
                                            ChatId.fromId(chatId),
                                            "Имя игрока " + (if (it.pos < Int.MAX_VALUE) "номер ${it.pos} " else "") +
                                                    "изменено с ${it.name} на $newName.",
                                            replyMarkup = connectKeyboard
                                        )
                                        showLobby(result, connections, game, bot, accounts)
                                        return@text
                                    }
                                }
                            }

                            when (message.text) {
                                "Список исключенных игроков" -> {
                                    val list = mutableListOf<List<InlineKeyboardButton>>()
                                    kicks.find(hostFilter).collect {
                                        accounts.find(Filters.eq("chatId", it.player)).singleOrNull()?.let { acc ->
                                            list.add(
                                                listOf(
                                                    InlineKeyboardButton.CallbackData(
                                                        acc.fullName(),
                                                        "kicked: ${it.id}"
                                                    ),
                                                    InlineKeyboardButton.CallbackData(
                                                        "Впустить",
                                                        "ukic: ${it.id}"
                                                    )
                                                )
                                            )
                                        }
                                    }
                                    bot.sendMessage(
                                        ChatId.fromId(chatId),
                                        "Исключенные игроки:",
                                        replyMarkup = InlineKeyboardMarkup.create(
                                            list
                                        )
                                    )
                                }

                                "Сбросить номера игроков" -> {
                                    connections.find(hostFilter).collect {
                                        bot.sendMessage(
                                            ChatId.fromId(it.player),
                                            "Ведущий инициировал повторый ввод номеров."
                                        )
                                        showNumPrompt(game.playerCount, it.player, bot)
                                    }
                                }

                                "Перейти к определению ролей" -> {
                                    accounts.updateOne(
                                        filter,
                                        Updates.set("menuMessageId", -1L)
                                    )
                                    val playerCount = connections.find(hostFilter).count()
                                    val nums = (1..playerCount).toMutableSet()
                                    val encountered = mutableMapOf<Int, Int>()
                                    connections.find(hostFilter).collect {
                                        if (it.pos != Int.MAX_VALUE) {
                                            nums.remove(it.pos)
                                            val cur = encountered.getOrPut(it.pos) { 0 }
                                            encountered[it.pos] = cur + 1
                                        }
                                    }
                                    for (entry in encountered) {
                                        if (entry.value > 1) {
                                            bot.sendMessage(
                                                ChatId.fromId(chatId),
                                                "Невозможно перейти к определению ролей. " +
                                                        "Обнаружено несколько игроков с номером ${entry.key}. " +
                                                        "Номера должны быть уникальны."
                                            )
                                            return@text
                                        }
                                    }
                                    val cons = mutableListOf<Connection>()
                                    connections.find(hostFilter).collect {
                                        if (it.pos == Int.MAX_VALUE) {
                                            val n = nums.first()
                                            cons.add(it.copy(pos = n))
                                            nums.remove(n)
                                        }
                                    }
                                    for (con in cons) {
                                        connections.updateOne(
                                            Filters.eq("_id", con.id),
                                            Updates.set("pos", con.pos)
                                        )
                                    }
                                    games.updateOne(
                                        hostFilter,
                                        Updates.set("state", GameState.Roles)
                                    )
                                    roles.find(Filters.eq("owner", chatId)).collect {
                                        setups.insertOne(Setup(ObjectId(), it.id, chatId, it.name))
                                    }
                                    bot.sendMessage(
                                        ChatId.fromId(chatId),
                                        "Выберите $playerCount ролей, чтобы продолжить.",
                                        replyMarkup = KeyboardReplyMarkup(
                                            keyboard = listOf(
                                                listOf(KeyboardButton("Выдать роли")),
                                                listOf(KeyboardButton("Завершить игру"))
                                            ),
                                            resizeKeyboard = true
                                        )
                                    )
                                    showRoles(chatId, setups, accounts, bot)
                                }

                                "Выдать роли", "Раздать роли заново" -> {
                                    modes.deleteMany(hostFilter)
                                    val conList = mutableListOf<Connection>()
                                    connections.find(hostFilter).collect {
                                        conList.add(it)
                                    }

                                    val playerCount = conList.size
                                    var roleCount = 0
                                    val roleList = mutableListOf<Role>()
                                    setups.find(hostFilter).collect {
                                        roleCount += it.count
                                        val role = roles.find(Filters.eq("_id", it.roleId)).singleOrNull()!!
                                        (1..it.count).forEach { _ ->
                                            roleList.add(role)
                                        }
                                    }

                                    if (roleCount != playerCount) {
                                        bot.sendMessage(
                                            ChatId.fromId(chatId),
                                            "Невозможно выдать роли. Задано $roleCount ролей для $playerCount игроков."
                                        )
                                        return@text
                                    }

                                    games.updateOne(
                                        hostFilter,
                                        Updates.set("state", GameState.Preview)
                                    )
                                    pairings.deleteMany(Filters.eq("host", chatId))
                                    val cons = mutableListOf<Connection>()
                                    connections.find(hostFilter).collect {
                                        cons.add(it)
                                    }
                                    roleList.shuffle()
                                    roleList.indices.forEach {
                                        val role = roleList.get(it)
                                        val con = cons.get(it)
                                        pairings.insertOne(
                                            Pairing(
                                                ObjectId(),
                                                chatId,
                                                con,
                                                role
                                            )
                                        )
                                    }
                                    showPreview(bot, chatId, pairings, accounts)
                                }

                                "Начать игру" -> {
                                    modes.insertOne(
                                        GameMode(
                                            ObjectId(),
                                            chatId,
                                            Mode.OPEN
                                        )
                                    )
                                    hostInfos.updateMany(
                                        Filters.and(
                                            filter,
                                            Filters.eq("gameLimit", true)
                                        ),
                                        Updates.inc("left", -1)
                                    )
                                    bot.sendMessage(
                                        chatId = ChatId.fromId(chatId),
                                        text = "Выберите тип игры:",
                                        replyMarkup = InlineKeyboardMarkup.create(
                                            Mode.entries.map {
                                                listOf(InlineKeyboardButton.CallbackData(it.type, "mode: ${it.name}"))
                                            }
                                        )
                                    )
                                }

                                "Начать ночь" -> {
                                    try {
                                        accounts.updateOne(
                                            filter,
                                            Updates.set("menuMessageId", -1L)
                                        )
                                        towns[chatId]?.let { town ->
                                            bot.sendMessage(
                                                ChatId.fromId(chatId),
                                                "Результат дня:\n${shortLog(town)}"
                                            )
                                            town.actions.clear()
                                            bot.sendMessage(
                                                ChatId.fromId(chatId),
                                                "Начинается ночь",
                                                replyMarkup = KeyboardReplyMarkup(
                                                    keyboard = listOf(
                                                        listOf(KeyboardButton("Раздать роли заново")),
                                                        listOf(KeyboardButton("Отменить последнее действие")),
                                                        listOf(KeyboardButton("Завершить игру"))
                                                    ),
                                                    resizeKeyboard = true
                                                )
                                            )
                                            town.updateTeams()
                                            town.prepNight()

                                            showNightRole(town, chatId, modes, bot)
                                        }
                                    } catch (e: Exception) {
                                        e.printStackTrace()
                                    }
                                }

                                "Отменить последнее действие" -> {
                                    towns[chatId]?.let { town ->
                                        if (town.index == 0) {
                                            bot.sendMessage(
                                                ChatId.fromId(chatId),
                                                "Невозможно отменить действие: список ночных действий пуст"
                                            )
                                        } else {
                                            town.rollback()
                                            bot.sendMessage(
                                                ChatId.fromId(chatId),
                                                "Действие отменено"
                                            )
                                            showNightRole(town, chatId, modes, bot)
                                        }
                                    }
                                }

                                // закрытие лобби
                                "Завершить игру", "/menu", "/start" -> {
                                    connections.find(hostFilter).collect {
                                        if (it.bot) {
                                            return@collect
                                        }
                                        withAccount(accounts, it.player) { acc, chat ->
                                            accounts.updateOne(
                                                Filters.eq("_id", acc.id),
                                                resetAccount
                                            )
                                            showMenu(
                                                chat,
                                                acc,
                                                games,
                                                accounts,
                                                "Ведущий завершил игру. Возвращаемся в меню.",
                                                bot,
                                                true
                                            )
                                        }
                                    }
                                    connections.deleteMany(hostFilter)
                                    kicks.deleteMany(hostFilter)
                                    games.deleteOne(Filters.eq("_id", game.id))
                                    pending.deleteMany(hostFilter)
                                    setups.deleteMany(hostFilter)
                                    pairings.deleteMany(hostFilter)
                                    modes.deleteMany(hostFilter)
                                    towns.remove(chatId)
                                    accounts.updateOne(
                                        Filters.eq("chatId", chatId),
                                        resetAccount
                                    )
                                    showMenu(chatId, result, games, accounts, "Возвращаемся в главное меню.", bot, true)
                                }

                                // выход из ввода номера игрока
                                "Назад" -> {
                                    var all = 0
                                    var set = 0
                                    connections.find(hostFilter).collect {
                                        all++
                                        if (it.pos != Int.MAX_VALUE) {
                                            set++
                                        }
                                    }
                                    bot.sendMessage(
                                        ChatId.fromId(chatId),
                                        "Заполнены номера $set / $all игроков." + if (set < all) " При переходе к определению ролей, оставшиеся номера будут заполнены автоматически." else "",
                                        replyMarkup = setupLayout()
                                    )
                                }

                                // выход из создания dummy-игрока
                                backText -> {
                                    games.updateOne(
                                        hostFilter,
                                        Updates.combine(
                                            Updates.set("state", GameState.Connect),
                                            Updates.set("connectionId", -1)
                                        )
                                    )
                                    bot.sendMessage(
                                        ChatId.fromId(chatId),
                                        "Действие отменено",
                                        replyMarkup = connectKeyboard
                                    )
                                }

                                else -> error()
                            }
                        } else {
                            error("Невозможно обработать команду. Не удалось найти игру.")
                        }
                    }

                    else -> {
                        error()
                    }
                }
            }

            callbackQuery {
                val chatId = callbackQuery.message?.chat?.id
                if (chatId == null) {
                    return@callbackQuery
                }

                callbackQuery.message?.chat?.id?.let {
                    val count = accounts.find(Filters.eq("chatId", it)).count()
                    if (count == 0) {
                        initAccount(callbackQuery.from.username ?: "", accounts, it, roles, types, orders, bot, path)
                        return@callbackQuery
                    }
                }

                val data = callbackQuery.data
                val cancelKeyboard = KeyboardReplyMarkup(
                    keyboard = listOf(listOf(KeyboardButton(backText))),
                    resizeKeyboard = true
                )
                val hostFilter = Filters.eq("host", chatId)
                if (data.startsWith("join:")) {
                    val id = ObjectId(data.substring(6))
                    val game = games.find(Filters.eq("_id", id)).singleOrNull()
                    withAccount(accounts) { acc, chat ->
                        if (game == null || game.state !in lobbyStates) {
                            bot.sendMessage(
                                ChatId.fromId(chat),
                                "Не удалось подключиться к игре. Обновляем список игр доступных для подключения."
                            )
                            showGames(acc, chat, bot, games, accounts)
                            return@withAccount
                        }

                        if (acc.state != AccountState.Menu) {
                            bot.sendMessage(
                                ChatId.fromId(chat),
                                "Не удалось подключиться к игре. Вернитесь в меню прежде чем подключаться к играм."
                            )
                            return@withAccount
                        }

                        val chatId = callbackQuery.message?.chat?.id
                        if (chatId == null) {
                            bot.sendMessage(
                                ChatId.fromId(chat),
                                "Не удалось подключиться к игре. Обновляем список игр доступных для подключения."
                            )
                            return@withAccount
                        }

                        val kick = kicks.find(
                            Filters.and(
                                Filters.eq("host", game.host),
                                Filters.eq("player", chatId)
                            )
                        ).singleOrNull()
                        if (kick != null) {
                            bot.sendMessage(
                                ChatId.fromId(chat),
                                "Не удалось подключиться к игре. Ведущий исключил вас из игры."
                            )
                            return@withAccount
                        }

                        if (acc.menuMessageId != -1L) {
                            bot.deleteMessage(ChatId.fromId(chat), acc.menuMessageId)
                        }
                        bot.sendMessage(
                            ChatId.fromId(chat),
                            "Подключение к игре выполнено.",
                            replyMarkup = KeyboardReplyMarkup(
                                listOf(
                                    listOf(KeyboardButton("Покинуть"))
                                ),
                                resizeKeyboard = true
                            )
                        )
                        val id = connections.insertOne(
                            Connection(
                                ObjectId(),
                                game.host,
                                chat,
                                acc.name,
                                if (acc.userName.isNotBlank()) "@${acc.userName}" else ""
                            )
                        ).insertedId
                        accounts.updateOne(
                            Filters.eq("chatId", chat),
                            Updates.combine(
                                Updates.set("state", AccountState.Lobby),
                                Updates.set("menuMessageId", -1L),
                                Updates.set("hostMessageId", -1L)
                            )
                        )
                        pending.insertOne(Pending(ObjectId(), game.host))
                        showNumPrompt(game.playerCount, chat, bot)
                    }
                } else if (data.startsWith("hand:")) {
                    val id = ObjectId(data.substring(6))
                    connections.find(Filters.eq("_id", id)).singleOrNull()?.let { con ->
                        if (con.bot) {
                            return@callbackQuery
                        }
                        callbackQuery.message?.chat?.id?.let { chatId ->
                            if (con.host == chatId) {
                                bot.sendMessage(
                                    ChatId.fromId(con.player),
                                    "Ведущий просит вас поднять руку"
                                )
                            }
                        }
                    }
                } else if (data.startsWith("kick:")) {
                    val id = ObjectId(data.substring(6))
                    val filter = Filters.eq("_id", id)
                    connections.find(filter).singleOrNull()?.let { con ->
                        if (con.bot) {
                            return@callbackQuery
                        }
                        callbackQuery.message?.chat?.id?.let { chatId ->
                            if (con.host == chatId) {
                                accounts.updateOne(
                                    Filters.eq("chatId", con.player),
                                    Updates.set(
                                        "state", AccountState.Menu
                                    )
                                )

                                withAccount(accounts, con.player) { acc, chat ->
                                    showMenu(
                                        chat,
                                        acc,
                                        games,
                                        accounts,
                                        "Ведущий исключил вас из игры. Возвращаемся в главное меню.",
                                        bot
                                    )
                                }
                                connections.deleteOne(filter)
                                kicks.insertOne(
                                    Kick(
                                        ObjectId(),
                                        con.host,
                                        con.player
                                    )
                                )
                                pending.insertOne(Pending(ObjectId(), chatId))
                            }
                        }
                    }
                } else if (data.startsWith("ukic:")) {
                    val id = ObjectId(data.substring(6))
                    val filter = Filters.eq("_id", id)
                    kicks.find(filter).singleOrNull()?.let { kick ->
                        accounts.find(Filters.eq("chatId", kick.player)).singleOrNull()?.let { acc ->
                            callbackQuery.message?.chat?.id?.let { chat ->
                                kicks.deleteOne(filter)
                                bot.sendMessage(
                                    ChatId.fromId(chat),
                                    "Игрок ${acc.fullName()} снова может войти в игру."
                                )
                            }
                        }
                    }
                } else if (data.startsWith("conn:")) {
                    val id = ObjectId(data.substring(6))
                    connections.find(Filters.eq("_id", id)).singleOrNull()?.let { con ->
                        callbackQuery.message?.chat?.id?.let { chatId ->
                            games.find(hostFilter).singleOrNull()?.let { game ->
                                if (game.state != GameState.Connect) {
                                    return@callbackQuery
                                }
                                games.updateOne(
                                    Filters.eq("_id", game.id),
                                    Updates.set("state", GameState.Rename)
                                )
                                accounts.updateOne(
                                    Filters.eq("chatId", chatId),
                                    Updates.set("connectionId", con.id.toHexString())
                                )

                                bot.sendMessage(
                                    ChatId.fromId(chatId),
                                    "Введите новое имя для игрока ${con.name()}.",
                                    replyMarkup = KeyboardReplyMarkup(
                                        keyboard = listOf(listOf(KeyboardButton("Назад"))),
                                        resizeKeyboard = true
                                    )
                                )
                            }
                        }
                    }
                } else if (data.startsWith("send:")) {
                    val id = ObjectId(data.substring(6))
                    games.find(Filters.eq("_id", id)).singleOrNull()?.let { game ->
                        val host = game.host
                        val ad = ads.find().firstOrNull()
                        suspend fun send(chatId: Long) {
                            if (ad != null) {
                                val res = bot.sendMessage(
                                    ChatId.fromId(chatId),
                                    ad.text
                                )
                                if (res.isSuccess) {
                                    bombs.insertOne(
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
                        connections.find(Filters.eq("host", host)).collect {
                            send(it.player)
                        }
                    }
                } else if (data.startsWith("posi:")) {
                    val id = ObjectId(data.substring(6))
                    connections.find(Filters.eq("_id", id)).singleOrNull()?.let { con ->
                        callbackQuery.message?.chat?.id?.let { chatId ->
                            if (con.host == chatId) {
                                accounts.updateOne(
                                    Filters.eq("chatId", chatId),
                                    Updates.set("connectionId", con.id.toHexString())
                                )
                                games.find(Filters.eq("host", chatId)).singleOrNull()?.let { game ->
                                    games.updateOne(
                                        Filters.eq("_id", game.id),
                                        Updates.set("state", GameState.Connect)
                                    )
                                    val playerCount = if (game.playerCount > 0) {
                                        game.playerCount
                                    } else {
                                        connections.find(Filters.eq("chatId", chatId)).count()
                                    }
                                    val res = (1..playerCount).chunked(4).map { list ->
                                        list.map { KeyboardButton("" + it) }
                                    } + listOf(listOf(KeyboardButton("Назад")))
                                    bot.sendMessage(
                                        ChatId.fromId(chatId),
                                        "Выберите номер для игрока ${con.name()}",
                                        replyMarkup = KeyboardReplyMarkup(
                                            keyboard = res,
                                            resizeKeyboard = true
                                        )
                                    )
                                }
                            }
                        }
                    }
                } else if (data.startsWith("decr:")) {
                    val id = ObjectId(data.substring(6))
                    val filter = Filters.eq("_id", id)
                    setups.find(filter).singleOrNull()?.let { setup ->
                        callbackQuery.message?.chat?.id?.let { chatId ->
                            if (setup.host == chatId) {
                                setups.updateOne(
                                    filter,
                                    Updates.set("count", max(setup.count - 1, 0))
                                )
                                showRoles(chatId, setups, accounts, bot)
                            }
                        }
                    }
                } else if (data.startsWith("incr:")) {
                    val id = ObjectId(data.substring(6))
                    val filter = Filters.eq("_id", id)
                    setups.find(filter).singleOrNull()?.let { setup ->
                        callbackQuery.message?.chat?.id?.let { chatId ->
                            if (setup.host == chatId) {
                                setups.updateOne(
                                    filter,
                                    Updates.set("count", setup.count + 1)
                                )
                                showRoles(chatId, setups, accounts, bot)
                            }
                        }
                    }
                } else if (data.startsWith("role:")) {
                    val id = ObjectId(data.substring(6))
                    callbackQuery.message?.chat?.id?.let { chatId ->
                        roles.find(Filters.eq("_id", id)).firstOrNull()?.let { role ->
                            bot.sendMessage(
                                ChatId.fromId(chatId),
                                text = "Название: ${role.name}\nОписание: ${role.desc}"
                            )
                        }
                    }
                } else if (data.startsWith("mode:")) {
                    callbackQuery.message?.chat?.id?.let { chatId ->
                        try {
                            Mode.valueOf(data.substring(6)).let { mode ->
                                modes.updateOne(hostFilter, Updates.set("mode", mode))
                                startGame(
                                    accounts,
                                    setups,
                                    hostFilter,
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
                        } catch (e: Exception) {
                            bot.sendMessage(
                                ChatId.fromId(chatId),
                                "Ошибка: неверный тип игры"
                            )
                        }
                    }
                } else if (data.startsWith("rviv:")) {
                    callbackQuery.message?.chat?.id?.let { chatId ->
                        towns[chatId]?.let { town ->
                            town.setAlive(data.drop(6).toInt(), true)
                            showDay(town, chatId, towns, accounts, bot, "", "", modes)
                        }
                    }
                } else if (data.startsWith("kill:")) {
                    callbackQuery.message?.chat?.id?.let { chatId ->
                        towns[chatId]?.let { town ->
                            town.setAlive(data.drop(6).toInt(), false)
                            showDay(town, chatId, towns, accounts, bot, "", "", modes)
                        }
                    }
                } else if (data.startsWith("fall:")) {
                    callbackQuery.message?.chat?.id?.let { chatId ->
                        towns[chatId]?.let { town ->
                            val pos = data.drop(6).toInt()
                            val person = town.playerMap[pos]
                            if (person != null) {
                                person.fallCount += 1
                            }
                            showDay(town, chatId, towns, accounts, bot, "", "", modes)
                        }
                    }
                } else if (data.startsWith("stts:")) {
                    callbackQuery.message?.chat?.id?.let { chatId ->
                        towns[chatId]?.let { town ->
                            town.changeProtected(data.drop(6).toInt())
                            showDay(town, chatId, towns, accounts, bot, "", "", modes)
                        }
                    }
                } else if (data.startsWith("select:")) {
                    callbackQuery.message?.chat?.id?.let { chatId ->
                        towns[chatId]?.let { town ->
                            if (town.night.size > town.index) {
                                val wake = town.night[town.index]
                                val num = data.drop(8).toInt()
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
                                                return@let
                                            }

                                            val text = ret.results.mapIndexed { i, res ->
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
                                                        return@let
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
                                                                    for (it in passive.results) {
                                                                        if (it !is NoneResult) {
                                                                            town.actions.add(it)
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
                                                replyMarkup = InlineKeyboardMarkup.create(
                                                    listOf(
                                                        listOf(
                                                            if (town.index >= town.night.size) {
                                                                InlineKeyboardButton.CallbackData(
                                                                    "Начать день",
                                                                    "startDay"
                                                                )
                                                            } else {
                                                                InlineKeyboardButton.CallbackData(
                                                                    "Следующая роль",
                                                                    "nextRole"
                                                                )
                                                            }
                                                        )
                                                    )
                                                )
                                            )
                                        } catch (e: Exception) {
                                            e.printStackTrace()
                                        }
                                    }
                                    return@let
                                }
                            }
                        }
                    }
                } else if (data.startsWith("deleteMsg:")) {
                    callbackQuery.message?.chat?.id?.let { chatId ->
                        bot.deleteMessage(ChatId.fromId(chatId), data.drop(11).toLong())
                    }
                } else if (data.startsWith("fallMode:")) {
                    callbackQuery.message?.chat?.id?.let { chatId ->
                        modes.find(hostFilter).singleOrNull()?.let {
                            towns[chatId]?.let { town ->
                                val messageId = data.drop(10).toLong()
                                modes.updateOne(hostFilter, Updates.set("fallMode", !it.fallMode))
                                showDay(town, chatId, towns, accounts, bot, "", "", modes)
                                bot.editMessageReplyMarkup(
                                    ChatId.fromId(chatId),
                                    messageId,
                                    replyMarkup = settingsButtons(it.copy(fallMode = !it.fallMode), messageId)
                                )
                            }
                        }
                    }
                } else if (data == "dummy") {
                    withAccount(accounts) { acc, chat ->
                        if (acc.state == AccountState.Host) {
                            val filter = Filters.eq("host", chat)
                            games.find(filter).singleOrNull()?.let { game ->
                                if (game.state == GameState.Connect) {
                                    games.updateOne(
                                        filter,
                                        Updates.set("state", GameState.Dummy)
                                    )
                                    bot.sendMessage(
                                        ChatId.fromId(chat),
                                        "Введите имя игрока",
                                        replyMarkup = cancelKeyboard
                                    )
                                }
                            }
                        }
                    }
                } else if (data == "fltr") {
                    callbackQuery.message?.chat?.id?.let { chatId ->
                        towns[chatId]?.let { town ->
                            modes.find(hostFilter).singleOrNull()?.let { mode ->
                                val index =
                                    if (DayView.entries.size > mode.dayView.ordinal + 1) mode.dayView.ordinal + 1 else 0
                                val next = DayView.values()[index]
                                modes.updateOne(hostFilter, Updates.set("dayView", next))
                                showDay(town, chatId, towns, accounts, bot, "", "", modes)
                            }
                        }
                    }
                } else if (data == "leave") {
                    withAccount(accounts) { result, chatId ->
                        val con = connections.find(Filters.eq("player", chatId)).singleOrNull()
                        if (con != null) {
                            leaveGame(
                                accounts,
                                chatId,
                                resetAccount,
                                pending,
                                con,
                                connections,
                                result,
                                games,
                                bot
                            )
                            bot.sendMessage(
                                ChatId.fromId(con.host),
                                "Игрок ${con.name()} покинул игру"
                            )
                        } else {
                            bot.sendMessage(
                                ChatId.fromId(chatId),
                                "Не удалось покинуть игру. Не найдено подключения к игре."
                            )
                        }
                    }
                } else if (data == "update") {
                    withAccount(accounts) { result, chatId ->
                        showGames(result, chatId, bot, games, accounts)
                    }
                } else if (data == "nextType") {
                    callbackQuery.message?.chat?.id?.let { chatId ->
                        towns[chatId]?.let { town ->
                            bot.sendMessage(
                                ChatId.fromId(chatId),
                                "Роль пропущена"
                            )
                            town.index++
                            showNightRole(town, chatId, modes, bot)
                        }
                    }
                } else if (data == "startDay") {
                    callbackQuery.message?.chat?.id?.let { chatId ->
                        towns[chatId]?.let { town ->
                            startDay(town, chatId, towns, accounts, bot, modes)
                        }
                    }
                } else if (data == "nextRole") {
                    callbackQuery.message?.chat?.id?.let { chatId ->
                        towns[chatId]?.let { town ->
                            showNightRole(town, chatId, modes, bot)
                        }
                    }
                } else if (data == "gameInfo") {
                    withAccount(accounts) { acc, chat ->
                        if (acc.state == AccountState.Lobby) {
                            connections.find(Filters.eq("player", chat)).singleOrNull()?.let { con ->
                                val hostFilter = Filters.eq("host", con.host)
                                games.find(hostFilter).singleOrNull()?.let { game ->
                                    if (game.state == GameState.Game) {
                                        val mode = modes.find(hostFilter).singleOrNull()?.mode
                                        val roleMap = getRoles(setups, hostFilter, roles)
                                        val playerCount = roleMap.map { it.value }.sum()
                                        val players = towns[con.host]?.let { getPlayerDescs(checks, con, pairings, it) }
                                            ?: emptyList()
                                        val desc =
                                            (if (mode != null) "<b>Тип игры</b>: ${mode.type}\n${mode.desc}\n\n" else "") +
                                                    "<b>Количество игроков</b>: $playerCount\n\n${roleDesc(roleMap)}" +
                                                    (if (players.size > 1) "\n\n<b>Игроки в команде</b>:\n" + players.joinToString(
                                                        "\n"
                                                    ) else "")
                                        bot.sendMessage(
                                            ChatId.fromId(chat),
                                            desc,
                                            parseMode = ParseMode.HTML
                                        )
                                    }
                                }
                            }
                        }
                    }
                } else if (data == "settings") {
                    withAccount(accounts) { acc, chat ->
                        if (acc.state == AccountState.Host) {
                            modes.find(hostFilter).singleOrNull()?.let {
                                val res = bot.sendMessage(
                                    ChatId.fromId(chat),
                                    "Настройки",
                                    replyMarkup = InlineKeyboardMarkup.create(
                                        listOf(InlineKeyboardButton.CallbackData("Загрузка...", "loading"))
                                    )
                                )
                                if (res.isSuccess) {
                                    val messageId = res.get().messageId
                                    bot.editMessageReplyMarkup(
                                        ChatId.fromId(chat),
                                        messageId,
                                        replyMarkup = settingsButtons(it, messageId)
                                    )
                                }
                            }
                        }
                    }
                }
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
                    val filter = Filters.lt("date", Date())
                    bombs.find(filter).collect {
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
                    val filter = Filters.or(
                        Filters.and(
                            Filters.eq("timeLimit", true),
                            Filters.lt("until", Date())
                        ),
                        Filters.and(
                            Filters.eq("gameLimit", true),
                            Filters.lt("left", 1)
                        )
                    )
                    hostInfos.deleteMany(filter)
                } catch (e: Exception) {
                    e.printStackTrace()
                }
                delay(60000)
            }
        }

        launch {
            while (true) {
                try {
                    pending.find().collect {
                        accounts.find(Filters.eq("chatId", it.host)).singleOrNull()?.let {
                            games.find(Filters.eq("host", it.chatId)).singleOrNull()?.let { game ->
                                if (game.state in lobbyStates) {
                                    showLobby(it, connections, game, bot, accounts)
                                }
                            }
                        }
                    }
                    pending.drop()
                } catch (e: Exception) {
                    e.printStackTrace()
                }
                delay(1000)
            }
        }
    }
}

private suspend fun leaveGame(
    accounts: MongoCollection<Account>,
    chatId: Long,
    resetAccount: Bson,
    pending: MongoCollection<Pending>,
    con: Connection,
    connections: MongoCollection<Connection>,
    result: Account,
    games: MongoCollection<Game>,
    bot: Bot
): Long {
    accounts.updateOne(
        Filters.eq("chatId", chatId),
        resetAccount
    )

    pending.insertOne(Pending(ObjectId(), con.host))
    connections.deleteMany(Filters.eq("player", chatId))
    return showMenu(chatId, result, games, accounts, "Возвращаемся в главное меню.", bot, true)
}

private fun settingsButtons(
    it: GameMode,
    messageId: Long
) = InlineKeyboardMarkup.create(
    listOf(
        InlineKeyboardButton.CallbackData(
            "Режим фоллов: " + if (it.fallMode) "Включен" else "Отключен",
            "fallMode: $messageId"
        )
    ),
    listOf(InlineKeyboardButton.CallbackData("Закрыть", "deleteMsg: $messageId"))
)

private suspend fun startGame(
    accounts: MongoCollection<Account>,
    setups: MongoCollection<Setup>,
    hostFilter: Bson,
    roles: MongoCollection<Role>,
    path: String,
    pairings: MongoCollection<Pairing>,
    orders: MongoCollection<TypeOrder>,
    types: MongoCollection<Type>,
    chatId: Long,
    towns: MutableMap<Long, Town>,
    roleNameLen: Int,
    roleDescLen: Int,
    games: MongoCollection<Game>,
    bot: Bot,
    modes: MongoCollection<GameMode>
) {
    accounts.updateOne(
        hostFilter,
        Updates.set("menuMessageId", -1L)
    )
    val roleMap = getRoles(setups, hostFilter, roles)
    //val roleDesc = roleDesc(roleMap)

    try {
        val scriptMap = roleMap.keys.filter { it.scripted }.map {
            val lua = Globals()
            lua.load(JseBaseLib())
            lua.load(PackageLib())
            LoadState.install(lua)
            LuaC.install(lua)
            lua.get("dofile").call(LuaValue.valueOf("$path/${it.script}.lua"))
            it.name to lua
        }.toMap()


        val pairs = mutableListOf<Pairing>()
        pairings.find(hostFilter).collect {
            pairs.add(it)
        }

        val orderList = mutableListOf<TypeOrder>()
        orders.find(hostFilter).collect {
            orderList.add(it)
        }
        val typeList = mutableListOf<Type>()
        types.find(hostFilter).collect {
            typeList.add(it)
        }
        val mode = modes.find(hostFilter).singleOrNull()?.mode ?: Mode.OPEN
        val town1 = Town(
            chatId,
            pairs.map {
                Person(
                    it.connection.pos,
                    it.connection.name(),
                    it.role,
                    it.role.defaultTeam
                )
            },
            orderList.sortedBy { it.pos }.map { it.type },
            typeList.map { it.name to it }.toMap(),
            scriptMap,
            mode
        )
        towns[chatId] = town1


        for (it in pairs) {
            if (!it.connection.bot) {
                /*bot.sendMessage(
                                                    ChatId.fromId(it.connection.player),
                                                    roleDesc
                                                )*/
                try {
                    val replyMarkup = KeyboardReplyMarkup(
                        keyboard = listOf(listOf(KeyboardButton("Покинуть"))),
                        resizeKeyboard = true
                    )
                    bot.sendMessage(
                        ChatId.fromId(it.connection.player),
                        "Ведущий начал игру",
                        replyMarkup = replyMarkup
                    )
                    bot.sendMessage(
                        ChatId.fromId(it.connection.player),
                        "Ваша роль: <span class=\"tg-spoiler\">${it.role.name.padEnd(roleNameLen, '_')}</span>\n" +
                                "Описание: <span class=\"tg-spoiler\">${
                                    it.role.desc.padEnd(
                                        roleDescLen,
                                        '_'
                                    )
                                }</span>",
                        parseMode = ParseMode.HTML,
                        replyMarkup = InlineKeyboardMarkup.create(
                            listOf(
                                InlineKeyboardButton.CallbackData(
                                    "Информация об игре",
                                    "gameInfo"
                                )
                            )
                        )
                    )
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }

        games.updateOne(
            hostFilter,
            Updates.set("state", GameState.Game)
        )
        bot.sendMessage(
            ChatId.fromId(chatId),
            "Роли отправлены игрокам.",
            replyMarkup = dayKeyboard()
        )
        bot.sendMessage(
            ChatId.fromId(chatId),
            " Роли в игре:\n" + roleMap.entries
                .filter { it.value > 0 }
                .joinToString("\n") { "- " + it.key.name }
        )

        towns[chatId]?.let { town ->
            showDay(town, chatId, towns, accounts, bot, "", "", modes)
        }
    } catch (e: Exception) {
        e.printStackTrace()
    }
}

private suspend fun getPlayerDescs(
    checks: MongoCollection<Check>,
    connection: Connection,
    pairings: MongoCollection<Pairing>,
    town: Town
): List<String> {
    val conMap = mutableMapOf<Connection, List<String>>()
    val wakeMap = mutableMapOf<String, MutableList<Pairing>>()
    val hostFilter = Filters.eq("host", connection.host)
    val pairs = mutableMapOf<Connection, Pairing>()
    pairings.find(hostFilter).collect {
        pairs[it.connection] = it
    }
    for (pair in pairs.values) {
        val types = if (pair.role.scripted) {
            val script = town.scripts[pair.role.name]
            val arg = CoerceJavaToLua.coerce(town.players.toTypedArray())
            script?.get("type")?.call(arg)?.toString() ?: "none"
        } else {
            "none"
        }.split(",")
        types.forEach {
            wakeMap.getOrPut(it) { mutableListOf() }.add(pair)
        }
        conMap[pair.connection] = types
    }

    pairs[connection]?.let {
        val names = checks.get(CheckOption.NAMES)
        val cover = checks.get(CheckOption.COVER)
        val players =
            if (!names || !conMap.containsKey(it.connection) || "none" in (conMap[it.connection]
                    ?: emptyList())
            ) {
                emptyList()
            } else {
                conMap[it.connection]
                    ?.flatMap { wakeMap[it] ?: emptyList() }
                    ?.toSet()
                    ?.sortedBy { it.connection.pos }
                    ?.map {
                        "№${it.connection.pos} - " + it.connection.name() + " - " + it.role.name(
                            cover
                        )
                    }
                    ?: emptyList()
            }
        return players
    }
    return emptyList()
}

private suspend fun MongoCollection<Check>.get(option: CheckOption) =
    (find(option.filter()).singleOrNull()?.state
        ?: false)

private suspend fun getRoles(
    setups: MongoCollection<Setup>,
    hostFilter: Bson,
    roles: MongoCollection<Role>
): MutableMap<Role, Int> {
    val roleMap = mutableMapOf<Role, Int>()
    setups.find(hostFilter).collect { setup ->
        roles.find(Filters.eq("_id", setup.roleId)).singleOrNull()?.let { role ->
            roleMap.put(role, setup.count)
        }
    }
    return roleMap

}

private fun roleDesc(roleMap: MutableMap<Role, Int>): String {
    var roleDesc = "<b>Роли в игре</b>:\n\n"
    for (entry in roleMap.entries) {
        if (entry.value > 0) {
            roleDesc += "<b>" + entry.key.name + "</b>\nКоличество: ${entry.value}\nОписание: ${entry.key.desc}\n\n"
        }
    }
    roleDesc = roleDesc.dropLast(2)
    return roleDesc
}

private suspend fun updateSetup(
    path: String,
    roles: MongoCollection<Role>,
    chatId: Long,
    types: MongoCollection<Type>,
    orders: MongoCollection<TypeOrder>
) {
    val json = File(path + "/template.json").readText()
    println(json)
    try {
        val data = Json {}.decodeFromString<GameSet>(json)

        roles.deleteMany(Filters.eq("owner", chatId))
        data.roles.forEach {
            roles.insertOne(
                Role(
                    ObjectId(),
                    chatId,
                    it.name,
                    it.desc,
                    it.scripted,
                    it.defaultTeam,
                    it.script,
                    it.priority,
                    it.coverName
                )
            )
        }
        types.deleteMany(Filters.eq("host", chatId))
        data.type.forEach {
            types.insertOne(Type(ObjectId(), chatId, it.name, it.choice))
        }
        orders.deleteMany(Filters.eq("host", chatId))
        data.order.forEachIndexed { index, s ->
            orders.insertOne(TypeOrder(ObjectId(), chatId, s, index))
        }
    } catch (e: Exception) {
        e.printStackTrace()
    }
}

private fun showNumPrompt(
    playerCount: Int,
    chat: Long,
    bot: Bot
) {
    val res = if (playerCount > 0) {
        (1..playerCount).chunked(5).map { list ->
            list.map { KeyboardButton("" + it) }
        }.reversed()
    } else {
        emptyList()
    } + listOf(listOf(KeyboardButton("Покинуть")))
    bot.sendMessage(
        ChatId.fromId(chat),
        "Пожалуйста, введите свой номер игрока.",
        replyMarkup = KeyboardReplyMarkup(
            keyboard = res,
            resizeKeyboard = true
        )
    )
}

private fun dayKeyboard() = KeyboardReplyMarkup(
    keyboard = listOf(
        listOf(KeyboardButton("Начать ночь")),
        listOf(KeyboardButton("Раздать роли заново")),
        listOf(KeyboardButton("Завершить игру"))
    ),
    resizeKeyboard = true
)

private suspend fun showNightRole(
    town: Town,
    chatId: Long,
    modes: MongoCollection<GameMode>,
    bot: Bot
) {
    town.selections.clear()
    val wake = if (town.night.size > town.index) town.night[town.index] else null
    if (wake == null) {
        bot.sendMessage(
            ChatId.fromId(chatId),
            "Ночь завершена",
            replyMarkup = InlineKeyboardMarkup.create(
                listOf(
                    listOf(InlineKeyboardButton.CallbackData("Начать день", "startDay"))
                )
            )
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
                "Игроки: " + players.joinToString(", ") { desc(it, " - ") } +
                if (alive.isNotEmpty()) "\n\nВыберите ${wake.type.choice} игроков:" else "",
        replyMarkup = InlineKeyboardMarkup.create(
            if (alive.isEmpty()) {
                listOf(
                    listOf(
                        InlineKeyboardButton.CallbackData(
                            "Пропустить",
                            "nextType"
                        )
                    )
                )
            } else {
                town.players.filter { it.alive }.sortedBy { it.pos }.map {
                    listOf(
                        InlineKeyboardButton.CallbackData(
                            desc(it),
                            "select: ${it.pos}"
                        )
                    )
                }
            }
        )
    )
}

private suspend fun startDay(
    town: Town,
    chatId: Long,
    towns: MutableMap<Long, Town>,
    accounts: MongoCollection<Account>,
    bot: Bot,
    modes: MongoCollection<GameMode>
) {
    town.day++
    val fullLog = fullLog(town)
    town.endNight()
    showDay(town, chatId, towns, accounts, bot, fullLog, shortLog(town), modes)
}

private suspend fun showDay(
    town: Town,
    chatId: Long,
    towns: MutableMap<Long, Town>,
    accounts: MongoCollection<Account>,
    bot: Bot,
    fullLog: String,
    shortLog: String,
    modes: MongoCollection<GameMode>
) {
    val filter = Filters.eq("host", chatId)
    val buttons = mutableListOf<List<InlineKeyboardButton>>()
    val view = modes.find(filter).singleOrNull()?.dayView ?: DayView.ALL
    buttons.add(
        listOf(
            InlineKeyboardButton.CallbackData("Фильтр: ${view.desc}", "fltr")
        )
    )

    for (player in town.players.sortedBy { it.pos }) {
        if (view.filter(player)) {
            buttons.add(
                listOf(
                    InlineKeyboardButton.CallbackData(
                        desc(player),
                        "plyr: ${player.pos}"
                    )
                )
            )
            var row = listOf(
                InlineKeyboardButton.CallbackData(
                    "Статус: " + if (player.protected) "Защищен" else "Жив",
                    "stts: ${player.pos}"
                ),
                if (player.alive) {
                    InlineKeyboardButton.CallbackData(
                        "💀",
                        "kill: ${player.pos}"
                    )
                } else {
                    InlineKeyboardButton.CallbackData(
                        "🏩",
                        "rviv: ${player.pos}"
                    )
                }
            )
            modes.find(filter).singleOrNull()?.let {
                if (it.fallMode) {
                    row += InlineKeyboardButton.CallbackData(
                        "" + numbers[player.fallCount % numbers.size],
                        "fall: ${player.pos}"
                    )
                }
            }
            buttons.add(
                row
            )
        }
    }
    buttons.add(listOf(InlineKeyboardButton.CallbackData("Настройки", "settings")))

    withAccount(accounts, chatId) { acc, chat ->
        if (acc.menuMessageId != -1L) {
            bot.editMessageReplyMarkup(
                ChatId.fromId(chat),
                acc.menuMessageId,
                replyMarkup = InlineKeyboardMarkup.create(buttons)
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
            "Результат ночи:\n" + if (shortLog.isNotBlank()) shortLog else "Не произошло никаких событий"
        )
        town.actions.clear()

        val mapAll = mutableMapOf<String, Int>()
        val mapAlive = mutableMapOf<String, Int>()
        val teamSet = mutableSetOf<String>("all")
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
            replyMarkup = InlineKeyboardMarkup.create(buttons)
        )
        if (res.isSuccess) {
            accounts.updateOne(
                Filters.eq("chatId", chatId),
                Updates.set("menuMessageId", res.get().messageId)
            )
        }
    }
}

private fun shortLog(town: Town): String {
    return if (town.actions.isNotEmpty()) {
        val set = mutableSetOf<Pair<KClass<out Action>, Int>>()
        val text = town.actions.map { it.actions() }.flatten().sortedBy { it.pos }.map {
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
private suspend fun initAccount(
    userName: String,
    accounts: MongoCollection<Account>,
    chatId: Long,
    roles: MongoCollection<Role>,
    types: MongoCollection<Type>,
    orders: MongoCollection<TypeOrder>,
    bot: Bot,
    path: String
) {
    accounts.insertOne(Account(ObjectId(), chatId, userName))
    bot.sendMessage(
        ChatId.fromId(chatId),
        "Пожалуйста, введите свое имя. Это имя смогут видеть ведущие игр, к которым вы присоединяетесь.",
        replyMarkup = null
    )
}

private suspend fun showMenu(
    chatId: Long,
    result: Account,
    games: MongoCollection<Game>,
    accounts: MongoCollection<Account>,
    menuText: String,
    bot: Bot,
    forceUpdate: Boolean = false
): Long {
    bot.sendMessage(
        ChatId.fromId(chatId),
        menuText,
        replyMarkup = KeyboardReplyMarkup(
            keyboard = listOf(
                listOf(KeyboardButton("Сменить имя"))
            ),
            resizeKeyboard = true
        )
    )
    val res = showGames(result, chatId, bot, games, accounts, forceUpdate)
    accounts.updateOne(
        Filters.eq("_id", result.id),
        Updates.set("menuMessageId", res)
    )
    return res
}

private suspend fun showLobby(
    it: Account,
    connections: MongoCollection<Connection>,
    game: Game,
    bot: Bot,
    accounts: MongoCollection<Account>,
    forceUpdate: Boolean = false
) {
    val chatId = ChatId.fromId(it.chatId)
    val keyboardMarkup = lobby(it, connections, game.state, accounts)
    val text = "Количество игроков: ${keyboardMarkup.inlineKeyboard.size - 1}"
    if (!forceUpdate && it.menuMessageId != -1L) {
        bot.editMessageText(
            chatId,
            it.menuMessageId,
            text = text
        )
        bot.editMessageReplyMarkup(
            chatId,
            it.menuMessageId,
            replyMarkup = keyboardMarkup
        )
    } else {
        val res = bot.sendMessage(
            chatId,
            text = text,
            replyMarkup = keyboardMarkup
        )
        if (res.isSuccess) {
            accounts.updateOne(
                Filters.eq("chatId", it.chatId),
                Updates.set("menuMessageId", res.get().messageId)
            )
        }
    }
}

suspend fun showRoles(chatId: Long, setups: MongoCollection<Setup>, accounts: MongoCollection<Account>, bot: Bot) {
    val list = mutableListOf<List<InlineKeyboardButton>>()
    setups.find(Filters.eq("host", chatId)).collect {
        list.add(
            listOf(
                InlineKeyboardButton.CallbackData(it.role, "role: ${it.roleId}")
            )
        )
        list.add(
            listOf(
                InlineKeyboardButton.CallbackData("-", "decr: ${it.id}"),
                InlineKeyboardButton.CallbackData("" + it.count, "cont: ${it.id}"),
                InlineKeyboardButton.CallbackData("+", "incr: ${it.id}")
            )
        )
    }
    val keyboard = InlineKeyboardMarkup.create(
        list
    )
    withAccount(accounts, chatId) { acc, chat ->
        if (acc.menuMessageId == -1L) {
            val res = bot.sendMessage(
                ChatId.fromId(chatId),
                "Роли:",
                replyMarkup = keyboard
            )
            if (res.isSuccess) {
                accounts.updateOne(
                    Filters.eq("_id", acc.id),
                    Updates.set("menuMessageId", res.get().messageId)
                )
            }
        } else {
            bot.editMessageReplyMarkup(
                ChatId.fromId(chatId),
                acc.menuMessageId,
                replyMarkup = keyboard
            )
        }
    }
}

suspend fun showPreview(
    bot: Bot,
    chatId: Long,
    pairings: MongoCollection<Pairing>,
    accounts: MongoCollection<Account>
) {
    val pairs = mutableListOf<Pairing>()
    pairings.find(Filters.eq("host", chatId)).collect {
        pairs.add(it)
    }
    bot.sendMessage(
        ChatId.fromId(chatId),
        "Распределение ролей:\n" +
                pairs.sortedBy { it.connection.pos }.map {
                    "" + it.connection.pos + ". " + it.connection.name() +
                            " - " + it.role.name
                }.joinToString("\n") +
                "\n\nВы можете продолжать редактировать список ролей в игре. Изменения будут учтены если вы раздадите роли заново.",
        replyMarkup = KeyboardReplyMarkup(
            keyboard = listOf(
                listOf(KeyboardButton("Начать игру")),
                listOf(KeyboardButton("Раздать роли заново")),
                listOf(KeyboardButton("Завершить игру"))
            ),
            resizeKeyboard = true
        )
    )
}

suspend fun CallbackQueryHandlerEnvironment.withAccount(
    accounts: MongoCollection<Account>,
    func: suspend (Account, Long) -> Unit
) {
    callbackQuery.message?.chat?.id?.let {
        withAccount(accounts, it, func)
    }
}

suspend fun withAccount(accounts: MongoCollection<Account>, chatId: Long, func: suspend (Account, Long) -> Unit) {
    accounts.find(Filters.eq("chatId", chatId)).singleOrNull()?.let {
        func(it, chatId)
    }
}

suspend fun menuButtons(account: Account, games: MongoCollection<Game>, accounts: MongoCollection<Account>) =
    with(account) {
        val lobbies = mutableListOf<List<InlineKeyboardButton>>()
        games.find(
            Filters.`in`("state", lobbyStates)
        ).collect {
            accounts.find(Filters.eq("chatId", it.host)).singleOrNull()?.let { host ->
                lobbies.add(
                    listOf(
                        InlineKeyboardButton.CallbackData(
                            host.fullName(),
                            "join: ${it.id.toHexString()}"
                        )
                    )
                )
            }
        }
        InlineKeyboardMarkup.create(
            lobbies + listOf(listOf(InlineKeyboardButton.CallbackData("Обновить список игр", "update")))
        )
    }

suspend fun setupLayout() = KeyboardReplyMarkup(
    keyboard = listOf(
        listOf(KeyboardButton("Перейти к определению ролей")),
        listOf(KeyboardButton("Завершить игру"))
    ),
    resizeKeyboard = true
)

suspend fun lobby(
    account: Account,
    connections: MongoCollection<Connection>,
    state: GameState,
    accounts: MongoCollection<Account>
) = with(account) {
    val players = mutableListOf<Connection>()
    connections.find(Filters.eq("host", chatId)).collect {
        players.add(
            it
        )
    }
    val res = players.sortedBy { it.pos }.map {
        val name = it.name()
        return@map if (it.bot) {
            listOf(
                InlineKeyboardButton.CallbackData(
                    name,
                    "conn: ${it.id.toHexString()}"
                ),
                InlineKeyboardButton.CallbackData(
                    "" + if (it.pos < Int.MAX_VALUE) it.pos else "Указать номер",
                    "posi: ${it.id.toHexString()}"
                )
            )
        } else {
            listOf(
                InlineKeyboardButton.CallbackData(
                    name,
                    "conn: ${it.id.toHexString()}"
                ),
                InlineKeyboardButton.CallbackData(
                    "" + if (it.pos < Int.MAX_VALUE) it.pos else "Указать номер",
                    "posi: ${it.id.toHexString()}"
                ),
                InlineKeyboardButton.CallbackData(
                    "✋",
                    "hand: ${it.id.toHexString()}"
                ),
                InlineKeyboardButton.CallbackData(
                    "❌",
                    "kick: ${it.id.toHexString()}"
                )
            )
        }
    } + listOf(listOf(InlineKeyboardButton.CallbackData("Добавить игрока", "dummy")))
    InlineKeyboardMarkup.create(
        res
    )
}

suspend fun showGames(
    account: Account,
    chatId: Long,
    bot: Bot,
    games: MongoCollection<Game>,
    accounts: MongoCollection<Account>,
    forceUpdate: Boolean = false
): Long {
    if (account.menuMessageId != -1L && !forceUpdate) {
        bot.editMessageReplyMarkup(
            ChatId.fromId(account.chatId),
            account.menuMessageId,
            replyMarkup = menuButtons(account, games, accounts)
        )
        return account.menuMessageId
    }
    val answer = bot.sendMessage(
        ChatId.fromId(chatId),
        "Доступные игры (нажмите на игру чтобы присоединиться):",
        replyMarkup = menuButtons(account, games, accounts)
    )
    if (answer.isSuccess) {
        return answer.get().messageId
    }
    return -1
}

data class Account(
    @BsonId val id: ObjectId,
    val chatId: Long,
    val userName: String,
    val name: String = "",
    val state: AccountState = AccountState.Init,
    val menuMessageId: Long = -1,
    val hostMessageId: Long = -1,
    val setupMessageId: Long = -1,
    val dayMessageId: Long = -1,
    val connectionId: String = ""
) {
    fun fullName() = name + if (userName.isNotBlank()) " (@$userName)" else ""
}

data class Game(
    @BsonId val id: ObjectId,
    val host: Long,
    val playerCount: Int = -1,
    val state: GameState = GameState.Connect
)

data class Connection(
    @BsonId val id: ObjectId,
    val host: Long,
    val player: Long,
    val name: String = "",
    val handle: String = "",
    val bot: Boolean = false,
    val pos: Int = Int.MAX_VALUE
) {
    fun name() = name + if (handle.isNotBlank()) " ($handle)" else ""
}

data class Pending(
    @BsonId val id: ObjectId,
    val host: Long
)

data class Role(
    @BsonId val id: ObjectId,
    val owner: Long,
    val name: String,
    val desc: String,
    val scripted: Boolean,
    val defaultTeam: String,
    val script: String = "",
    val priority: Int = 1,
    val coverName: String = ""
) {
    fun name(cover: Boolean = false): String {
        if (cover && coverName.isNotBlank()) {
            return coverName
        }
        return name
    }
}

data class Setup(
    @BsonId val id: ObjectId,
    val roleId: ObjectId,
    val host: Long,
    val role: String,
    val count: Int = 0
)

data class Pairing(
    @BsonId val id: ObjectId,
    val host: Long,
    val connection: Connection,
    val role: Role
)

data class TypeOrder(
    @BsonId val id: ObjectId,
    val host: Long,
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
    val state: Boolean
)

data class Kick(
    @BsonId val id: ObjectId,
    val host: Long,
    val player: Long
)

data class GameMode(
    @BsonId val id: ObjectId,
    val host: Long,
    val mode: Mode,
    val dayView: DayView = DayView.ALL,
    val fallMode: Boolean = false
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

class CancelResult(val blocked: Result, actors: List<Int>, selection: List<Person?>) :
    TargetedResult(actors, selection) {
    override fun desc(): String {
        return "Отменить действие: ${blocked.desc()}"
    }

    override fun actions(): List<Action> {
        return emptyList()
    }
}

object NoneResult : Result(emptyList(), emptyList()) {
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
    val host: Long,
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
    val playerMap = players.map { it.pos to it }.toMap()
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
            val blocks = actions.filter { it is BlockResult }
            for (block in blocks) {
                val select = block.selection.filterNotNull()
                actions.removeIf {
                    select.map { it.pos }.toSet().intersect(it.actors.toSet()).isNotEmpty()
                }
            }
            val cancels = actions.filter { it is CancelResult }.map { it as CancelResult }
            for (cancel in cancels) {
                actions.remove(cancel.blocked)
            }
            val heals = actions.filter { it is HealResult }.map { it as HealResult }
            for (heal in heals) {
                val select = heal.selection.filterNotNull()
                actions.removeIf {
                    select.intersect(it.selection.toSet()).isNotEmpty() && it is KillResult
                }
            }
            val kills = actions.filter { it is KillResult }
            for (it in kills) {
                it.selection.filterNotNull().forEach {
                    playerMap[it.pos]?.alive = false
                }
            }
            players.forEach { it.protected = false }
            val mutes = actions.filter { it is SilenceResult }
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
    Init, Menu, Host, Lobby, Presets
}

enum class GameState {
    Connect, Roles, Preview, Game, Dummy, Rename
}

data class Selection(
    @BsonId val id: ObjectId,
    val chatId: Long,
    val choice: String
)

data class HostInfo(
    @BsonId val id: ObjectId,
    val chatId: Long,
    val timeLimit: Boolean,
    val until: Date,
    val gameLimit: Boolean,
    val left: Int,
    val canShare: Boolean
)

enum class CheckOption(val key: String) {
    NAMES("names"), COVER("cover"), HOST("host");

    fun filter() = Filters.eq("name", key)
}