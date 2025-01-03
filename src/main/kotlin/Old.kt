package org.example

/*
import com.github.kotlintelegrambot.dispatcher.handlers.CallbackQueryHandlerEnvironment
import com.github.kotlintelegrambot.dispatcher.handlers.TextHandlerEnvironment
import com.github.kotlintelegrambot.entities.ChatId
import com.github.kotlintelegrambot.entities.InlineKeyboardMarkup
import com.github.kotlintelegrambot.entities.KeyboardReplyMarkup
import com.github.kotlintelegrambot.entities.ReplyKeyboardRemove
import com.github.kotlintelegrambot.entities.keyboard.InlineKeyboardButton
import com.github.kotlintelegrambot.entities.keyboard.KeyboardButton
import com.mongodb.client.model.Filters
import org.bson.types.ObjectId
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.*
import kotlin.math.max

suspend fun TextHandlerEnvironment.oldTextQueries(
    author: String,
    path: String,
    connections: Collection<Connection, ObjectId>,
    accounts: Collection<Account, Long>,
    resetAccount: Account.() -> Unit,
    pending: Collection<Pending, ObjectId>,
    games: Collection<Game, ObjectId>,
    towns: MutableMap<Long, Town>,
    backText: String,
    setups: Collection<Setup, ObjectId>,
    pairings: Collection<Pairing, ObjectId>,
    modes: Collection<GameMode, ObjectId>
) {
    val chatId = message.chat.id
    val filter = Filters.eq("chatId", chatId)
    val result = accounts.get(chatId)

    if (result == null) {
        initAccount(message.from?.username ?: "", accounts, chatId, bot)
        return
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

    val admin = admins.get(chatId)
    if (message.from?.username == author || admin != null) {
        if (message.text == "/ad") {
            val lobbies = with(result) {
                val lobbies = mutableListOf<List<InlineKeyboardButton>>()
                games.find().forEach {
                    accounts.get(chatId)?.let { host ->
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
            return
        } else if (message.text?.startsWith("/newad") == true) {
            val text = (message.text?.drop(6) ?: "").trim()
            if (text.isNotBlank()) {
                ads.find().forEach {
                    ads.delete(it.id)
                }
                ads.save(Message(ObjectId(), text))
            }
            return
        } else if (message.text?.startsWith("/checks ") == true) {
            val param = message.text?.drop(8)?.trim() ?: ""
            if (param.isNotBlank()) {
                val res = updateCheck(param, checks)
                bot.sendMessage(
                    ChatId.fromId(chatId),
                    "Параметр `$param` имеет состояние: $res"
                )
            }
            return
        } else if (message.text == "/admin") {
            bot.deleteMessage(ChatId.fromId(chatId), message.messageId)
            val res = bot.sendMessage(
                ChatId.fromId(chatId),
                "Меню администратора:",
                replyMarkup = InlineKeyboardMarkup.create(
                    listOf(listOf(InlineKeyboardButton.CallbackData("Загрузка...", "loading")))
                )
            )
            if (res.isSuccess) {
                val messageId = res.get().messageId
                showAdmin(checks, chatId, messageId, bot)
            }
            return
        }
    }


    when (result.state) {
        AccountState.Init -> {
            accounts.update(chatId) {
                name = message.text ?: ""
                state = AccountState.Menu
            }
            showMenu(chatId, games, accounts, "Добро пожаловать, ${message.text}", bot)
        }

        AccountState.Menu -> {
            if (message.text?.startsWith("/host ") == true) {
                if (!canHost(checks, hostInfos, { this.chatId == chatId }, hostRequests, chatId)) {
                    error("Возможность создания игры недоступна для вашего аккаунта.")
                    return
                }

                var num = -1
                try {
                    val text = message.text?.drop(6)
                    num = text?.toInt() ?: -1
                } catch (e: NumberFormatException) {
                    e.printStackTrace()
                }


                val id = games.save(Game(ObjectId(), result.chatId, num))
                val game = games.get(id)
                if (game != null) {
                    updateSetup(path, roles, game, types, orders)
                    accounts.update(chatId) {
                        state = AccountState.Host
                        menuMessageId = -1L
                    }
                    bot.sendMessage(
                        ChatId.fromId(chatId),
                        "Игра создана. Ожидайте присоединения игроков.",
                        replyMarkup = connectKeyboard
                    )
                    //showLobby(result, connections, game, bot, accounts, true)
                } else {
                    error("Не удалось создать игру. Попробуйте еще раз.")
                }
                return
            }

            when (message.text) {
                "Обновить список игр", "/update" -> {
                    showGames(chatId, -1, bot, games, accounts)
                }

                "Сменить имя" -> {
                    withAccount(accounts, chatId) { acc ->
                        val chat = ChatId.fromId(chatId)
                        if (acc.menuMessageId != -1L) {
                            bot.deleteMessage(chat, acc.menuMessageId)
                        }
                        bot.sendMessage(
                            chat,
                            "Пожалуйста, введите свое имя. Это имя смогут видеть ведущие игр, к которым вы присоединяетесь.",
                            replyMarkup = ReplyKeyboardRemove(true)
                        )
                        accounts.update(chatId, resetAccount)
                        accounts.update(chatId) {
                            state = AccountState.Init
                            menuMessageId = -1L
                        }
                    }
                }

                "/start", "/menu" -> {
                    showMenu(chatId, games, accounts, "Добро пожаловать", bot, true)
                }

                else -> error()
            }
        }

        AccountState.Lobby -> {
            connections.get(*/
/*chatId*//*
ObjectId())?.let { con ->
                if (con.pos == Int.MAX_VALUE) {
                    try {
                        val num = text.toInt()
                        if (num <= 0) {
                            error()
                        }
                        */
/*connections.update(con.player) {
                            pos = num
                        }*//*

                        games.get(con.gameId)?.host?.let {
                            pending.save(Pending(ObjectId(), it))
                        }
                        bot.sendMessage(
                            ChatId.fromId(chatId),
                            "Номер игрока сохранен. Ожидайте начала игры.",
                            replyMarkup = KeyboardReplyMarkup(
                                keyboard = listOf(listOf(KeyboardButton("Покинуть"))),
                                resizeKeyboard = true
                            )
                        )
                        return
                    } catch (e: NumberFormatException) {

                    }
                }

                val text = message.text
                when (text) {
                    "Покинуть", "/menu", "/start" -> {
                        val game = games.get(con.gameId)
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
                            return
                        } else {*/
/*leaveGame(
                                accounts,
                                chatId,
                                resetAccount,
                                pending,
                                con,
                                connections,
                                games,
                                bot
                            )*//*


                        }
                    }

                    else -> {
                        games.get(con.gameId)?.let { game ->
                            if (game.state == GameState.Game && message.text != null) {
                                bot.sendMessage(
                                    ChatId.fromId(game.host),
                                    "Игрок ${con.pos} - ${con.name()} пишет:\n" + message.text
                                )
                                return
                            }
                        }
                        error()
                    }
                }
            }
        }

        AccountState.Host -> {
            val game = games.find { host == chatId }.singleOrNull()
            if (game != null) {
                if (message.text?.startsWith("/rehost") == true) {
                    if (!canHost(checks, hostInfos, { this.chatId == chatId }, hostRequests, chatId)) {
                        error("Возможность создания игры недоступна для вашего аккаунта.")
                        return
                    }

                    var num = -1
                    try {
                        val text = message.text?.drop(8)
                        println(text)
                        num = text?.toInt() ?: -1
                    } catch (e: NumberFormatException) {
                    }

                    updateSetup(path, roles, game, types, orders)

                    games.update(game.id) {
                        state = GameState.Connect
                        playerCount = num
                    }
                    pending.deleteMany { host == chatId }
                    setups.deleteMany { gameId == game.id }
                    pairings.deleteMany { gameId == game.id }
                    towns.remove(chatId)
                    accounts.update(chatId) {
                        menuMessageId = -1L
                        hostMessageId = -1L
                        setupMessageId = -1L
                        dayMessageId = -1L
                        connectionId = null
                    }
                    */
/*withAccount(accounts, chatId) { acc, chat ->
                        bot.sendMessage(
                            ChatId.fromId(chatId),
                            "Игра перезапущена.",
                            replyMarkup = connectKeyboard
                        )
                        showLobby(acc, connections, game, bot, accounts)
                    }*//*

                    return
                }

                if (game.state == GameState.Connect && result.connectionId != null) {
                    try {
                        val num = text.toInt()
                        if (num <= 0) {
                            error()
                        }
                        */
/*connections.update(result.connectionId) {
                            pos = num
                        }*//*

                        //showLobby(result, connections, game, bot, accounts)
                        return
                    } catch (e: NumberFormatException) {

                    }
                } else if (message.text != null && message.text != backText) {
                    if (game.state == GameState.Dummy) {
                        connections.save(
                            Connection(
                                ObjectId(),
                                game.id,
                                -1,
                                message.text!!,
                                "оффлайн",
                                true
                            )
                        )
                        games.updateMany(
                            { host == chatId },
                            { state = GameState.Connect }
                        )
                        //showLobby(result, connections, game, bot, accounts)
                        bot.sendMessage(
                            ChatId.fromId(chatId),
                            "Игрок `${message.text} (оффлайн)` добавлен",
                            replyMarkup = connectKeyboard
                        )
                        return
                    } else if (game.state == GameState.Rename && result.connectionId != null) {
                        */
/*connections.get(result.connectionId)?.let {
                            val newName = message.text ?: ""
                            *//*
*/
/*connections.update(result.connectionId) {
                                name = newName
                            }*//*
*/
/*
                            games.update(game.id) {
                                state = GameState.Connect
                            }
                            bot.sendMessage(
                                ChatId.fromId(chatId),
                                "Имя игрока " + (if (it.pos < Int.MAX_VALUE) "номер ${it.pos} " else "") +
                                        "изменено с ${it.name} на $newName.",
                                replyMarkup = connectKeyboard
                            )
                            //showLobby(result, connections, game, bot, accounts)
                            return
                        }*//*

                    }
                }

                val gameFilter: Connection.() -> Boolean = { gameId == game.id }
                when (message.text) {
                    "Список исключенных игроков" -> {
                        val list = mutableListOf<List<InlineKeyboardButton>>()
                        kicks.find { gameId == game.id }.forEach {
                            accounts.get(it.player)?.let { acc ->
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
                        connections.find(gameFilter).forEach {
                            bot.sendMessage(
                                ChatId.fromId(it.player),
                                "Ведущий инициировал повторый ввод номеров."
                            )
                            //showNumPrompt(game.playerCount, it.player, bot)
                        }
                    }

                    "Перейти к определению ролей" -> {
                        accounts.update(chatId) {
                            menuMessageId = -1L
                        }
                        val playerCount = connections.find(gameFilter).size
                        val nums = (1..playerCount).toMutableSet()
                        val encountered = mutableMapOf<Int, Int>()
                        connections.find(gameFilter).forEach {
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
                                return
                            }
                        }
                        val cons = mutableListOf<Connection>()
                        connections.find(gameFilter).forEach {
                            if (it.pos == Int.MAX_VALUE) {
                                val n = nums.first()
                                cons.add(it.copy(pos = n))
                                nums.remove(n)
                            }
                        }
                        for (con in cons) {
                            */
/*connections.update(con.player) {
                                pos = con.pos
                            }*//*

                        }
                        games.update(game.id) { state = GameState.Roles }

                        roles.find { gameId == game.id }.forEach {
                            setups.save(Setup(ObjectId(), it.id, game.id, it.displayName, -1))
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
                        //showRoles(chatId, setups, accounts, bot, games)

                    }

                    "Выдать роли", "Раздать роли заново" -> {
                        modes.deleteMany { gameId == game.id }
                        val conList = mutableListOf<Connection>()
                        connections.find(gameFilter).forEach {
                            conList.add(it)
                        }
                        //deleteUserTimers(timers) { this.chatId == chatId }

                        val playerCount = conList.size
                        var roleCount = 0
                        val roleList = mutableListOf<Role>()
                        setups.find { gameId == game.id }.forEach {
                            roleCount += it.count
                            val role = roles.get(it.roleId)!!
                            (1..it.count).forEach { _ ->
                                roleList.add(role)
                            }
                        }

                        if (roleCount != playerCount) {
                            bot.sendMessage(
                                ChatId.fromId(chatId),
                                "Невозможно выдать роли. Задано $roleCount ролей для $playerCount игроков."
                            )
                            return
                        }

                        games.update(game.id) {
                            state = GameState.Preview
                        }
                        pairings.deleteMany { gameId == game.id }
                        val cons = mutableListOf<Connection>()
                        connections.find(gameFilter).forEach {
                            cons.add(it)
                        }
                        roleList.shuffle()
                        */
/*roleList.indices.forEach {
                            val role = roleList.get(it)
                            val con = cons.get(it)
                            pairings.save(
                                Pairing(
                                    ObjectId(),
                                    game.id,
                                    con,
                                    role
                                )
                            )
                        }*//*

                        //showPreview(bot, chatId, pairings, game)
                    }

                    "Начать игру" -> {
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
                            accounts.update(chatId) {
                                menuMessageId = -1L
                            }
                            //deleteUserTimers(timers) { this.chatId == chatId }
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

                                //showNightRole(town, chatId, modes, bot)
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
                                //showNightRole(town, chatId, modes, bot)
                            }
                        }
                    }

                    // закрытие лобби
                    "Завершить игру", "/menu", "/start" -> {
                        connections.find(gameFilter).forEach {
                            if (it.bot) {
                                return@forEach
                            }
                            */
/*withAccount(accounts, it.player) { acc, chat ->
                                accounts.update(acc.chatId, resetAccount)
                                showMenu(
                                    chat,
                                    games,
                                    accounts,
                                    "Ведущий завершил игру. Возвращаемся в меню.",
                                    bot,
                                    true
                                )
                            }*//*

                        }
                        connections.deleteMany(gameFilter)
                        kicks.deleteMany({ gameId == game.id })
                        games.delete(game.id)
                        pending.deleteMany { host == chatId }
                        setups.deleteMany { gameId == game.id }
                        pairings.deleteMany { gameId == game.id }
                        modes.deleteMany { gameId == game.id }
                        towns.remove(chatId)
                        accounts.update(chatId, resetAccount)
                        //deleteUserTimers(timers) { this.chatId == chatId }
                        showMenu(chatId, games, accounts, "Возвращаемся в главное меню.", bot, true)
                    }

                    // выход из ввода номера игрока
                    "Назад" -> {
                        var all = 0
                        var set = 0
                        connections.find(gameFilter).forEach {
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
                        games.update(game.id) {
                            state = GameState.Connect
                        }
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

        AccountState.Admin -> {
            val adminMenu = adminMenus.get(chatId)
            if (adminMenu?.state == AdminState.HOST_TIME || adminMenu?.state == AdminState.HOST_GAMES) {
                var num = -1
                try {
                    num = message.text?.toInt() ?: -1
                } catch (e: NumberFormatException) {
                }

                if (num > 0) {
                    val editFilter: HostInfo.() -> Boolean = { this.chatId == adminMenu.editId }
                    val hostInfo = hostInfos.find(editFilter).singleOrNull()
                    if (hostInfo != null) {
                        hostInfos.update(hostInfo.chatId) {
                            if (adminMenu?.state == AdminState.HOST_TIME) {
                                timeLimit = true
                                until = Date.from(Instant.now().plus(num.toLong(), ChronoUnit.DAYS))
                            } else if (adminMenu?.state == AdminState.HOST_GAMES) {
                                gameLimit = true
                                left = num
                            }
                        }

                        showHostSettings(adminMenu.messageId, hostInfos, accounts, chatId, bot)
                        bot.deleteMessage(ChatId.fromId(chatId), adminMenu.descId)
                        bot.deleteMessage(ChatId.fromId(chatId), message.messageId)
                        adminMenus.update(chatId) {
                            state = AdminState.NONE
                            editId = -1L
                            messageId = -1L
                            descId = -1L
                        }
                    }

                    return
                }
            }
            error()
        }

        else -> {
            error()
        }
    }
}

suspend fun CallbackQueryHandlerEnvironment.oldCallbacks(
    backText: String,
    setups: Collection<Setup, ObjectId>,
    accounts: Collection<Account, Long>,
    games: Collection<Game, ObjectId>,
    path: String,
    towns: MutableMap<Long, Town>,
    roleNameLen: Int,
    roleDescLen: Int,
    modes: Collection<GameMode, ObjectId>,
    resetAccount: Account.() -> Unit,
    connections: Collection<Connection, ObjectId>
) {
    val chatId = callbackQuery.message?.chat?.id
    if (chatId == null) {
        return
    }

    callbackQuery.message?.chat?.id?.let {
        val account = accounts.get(it)
        if (account == null) {
            initAccount(callbackQuery.from.username ?: "", accounts, it, bot)
            return
        }
    }

    val data = callbackQuery.data
    val cancelKeyboard = KeyboardReplyMarkup(
        keyboard = listOf(listOf(KeyboardButton(backText))),
        resizeKeyboard = true
    )


    if (data.startsWith("join:")) {
        val params = data.substring(6).split(", ")
        val id = ObjectId(params[0])
        val game = games.get(id)
        withAccount(accounts) { acc, chat ->
            if (game == null || game.state !in lobbyStates) {
                bot.sendMessage(
                    ChatId.fromId(chat),
                    "Не удалось подключиться к игре. Обновляем список игр доступных для подключения."
                )
                showGames(chat, params[1].toLong(), bot, games, accounts)
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

            val kick = kicks.find { gameId == game.id && player == chatId }.singleOrNull()
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
            connections.save(
                Connection(
                    ObjectId(),
                    game.id,
                    chat,
                    acc.name,
                    if (acc.userName.isNotBlank()) "@${acc.userName}" else ""
                )
            )
            accounts.update(chat) {
                state = AccountState.Lobby
                menuMessageId = -1L
                hostMessageId = -1L
            }
            pending.save(Pending(ObjectId(), game.host))
            //showNumPrompt(game.playerCount, chat, bot)
        }
    } else if (data.startsWith("hand:")) {
        val id = data.substring(6).toLong()
        */
/*connections.get(id)?.let { con ->
            if (con.bot) {
                return
            }
            callbackQuery.message?.chat?.id?.let { chatId ->
                if (games.get(con.gameId)?.host == chatId) {
                    bot.sendMessage(
                        ChatId.fromId(con.player),
                        "Ведущий просит вас поднять руку"
                    )
                }
            }
        }*//*

    } else if (data.startsWith("kick:")) {
        val id = data.substring(6).toLong()
        */
/*connections.get(id)?.let { con ->
            if (con.bot) {
                return@callbackQuery
            }
            callbackQuery.message?.chat?.id?.let { chatId ->
                if (games.get(con.gameId)?.host == chatId) {
                    accounts.update(con.player) {
                        state = AccountState.Menu
                    }

                    withAccount(accounts, con.player) { acc, chat ->
                        showMenu(
                            chat,
                            games,
                            accounts,
                            "Ведущий исключил вас из игры. Возвращаемся в главное меню.",
                            bot
                        )
                    }
                    connections.delete(con.player)
                    kicks.save(
                        Kick(
                            ObjectId(),
                            con.gameId,
                            con.player
                        )
                    )
                    pending.save(Pending(ObjectId(), chatId))
                }
            }
        }*//*

    } else if (data.startsWith("ukic:")) {
        val id = ObjectId(data.substring(6))
        kicks.get(id)?.let { kick ->
            accounts.get(kick.player)?.let { acc ->
                callbackQuery.message?.chat?.id?.let { chat ->
                    kicks.delete(id)
                    bot.sendMessage(
                        ChatId.fromId(chat),
                        "Игрок ${acc.fullName()} снова может войти в игру."
                    )
                }
            }
        }
    } else if (data.startsWith("conn:")) {
        val id = data.substring(6).toLong()
        */
/*connections.get(id)?.let { con ->
            callbackQuery.message?.chat?.id?.let { chatId ->
                games.find { host == chatId }.singleOrNull()?.let { game ->
                    if (game.state != GameState.Connect) {
                        return@callbackQuery
                    }
                    games.update(game.id) {
                        state == GameState.Rename
                    }
                    accounts.update(chatId) {
                        connectionId = con.player
                    }

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
        }*//*

    } else if (data.startsWith("send:")) {
        val id = ObjectId(data.substring(6))
        games.get(id)?.let { game ->
            val host = game.host
            val ad = ads.find().firstOrNull()
            suspend fun send(chatId: Long) {
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
            connections.find { this.gameId == game.id }.forEach {
                send(it.player)
            }
        }
    } else if (data.startsWith("posi:")) {
        val id = data.substring(6).toLong()
        */
/*connections.get(id)?.let { con ->
            callbackQuery.message?.chat?.id?.let { chatId ->
                val game = games.get(con.gameId)
                if (game?.host == chatId) {
                    accounts.update(chatId) {
                        connectionId = con.player
                    }
                    games.update(game.id) {
                        state = GameState.Connect
                    }
                    val playerCount = if (game.playerCount > 0) {
                        game.playerCount
                    } else {
                        connections.find { gameId == game.id }.size
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
        }*//*

    } else if (data.startsWith("decr:")) {
        val id = ObjectId(data.substring(6))
        setups.get(id)?.let { setup ->
            callbackQuery.message?.chat?.id?.let { chatId ->
                if (games.get(setup.gameId)?.host == chatId) {
                    setups.update(id) {
                        count = max(setup.count - 1, 0)
                    }
                    //showRoles(chatId, setups, accounts, bot, games)
                }
            }
        }
    } else if (data.startsWith("incr:")) {
        val id = ObjectId(data.substring(6))
        val filter = Filters.eq("_id", id)
        setups.get(id)?.let { setup ->
            callbackQuery.message?.chat?.id?.let { chatId ->
                if (games.get(setup.gameId)?.host == chatId) {
                    setups.update(id) {
                        count = setup.count + 1
                    }
                    //showRoles(chatId, setups, accounts, bot, games)
                }
            }
        }
    } else if (data.startsWith("role:")) {
        val id = ObjectId(data.substring(6))
        callbackQuery.message?.chat?.id?.let { chatId ->
            roles.get(id)?.let { role ->
                bot.sendMessage(
                    ChatId.fromId(chatId),
                    text = "Название: ${role.displayName}\nОписание: ${role.desc}"
                )
            }
        }
    } else if (data.startsWith("mode:")) {
        callbackQuery.message?.chat?.id?.let { chatId ->
            try {
                games.find { host == chatId }.singleOrNull()?.let { game ->
                    Mode.valueOf(data.substring(6)).let { mode ->
                        modes.update(game.id) { this.mode = mode }
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
                            games,
                            bot,
                            modes
                        )
                    }
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
            games.find { host == chatId }.singleOrNull()?.let { game ->
                towns[chatId]?.let { town ->
                    town.setAlive(data.drop(6).toInt(), true)
                    //showDay(town, chatId, towns, accounts, bot, "", "", modes, game)
                }
            }
        }
    } else if (data.startsWith("kill:")) {
        callbackQuery.message?.chat?.id?.let { chatId ->
            games.find { host == chatId }.singleOrNull()?.let { game ->
                towns[chatId]?.let { town ->
                    town.setAlive(data.drop(6).toInt(), false)
                    //showDay(town, chatId, towns, accounts, bot, "", "", modes, game)
                }
            }
        }
    } else if (data.startsWith("fall:")) {
        callbackQuery.message?.chat?.id?.let { chatId ->
            games.find { host == chatId }.singleOrNull()?.let { game ->
                towns[chatId]?.let { town ->
                    val pos = data.drop(6).toInt()
                    val person = town.playerMap[pos]
                    if (person != null) {
                        person.fallCount += 1
                    }
                    //showDay(town, chatId, towns, accounts, bot, "", "", modes, game)
                }
            }
        }
    } else if (data.startsWith("stts:")) {
        callbackQuery.message?.chat?.id?.let { chatId ->
            games.find { host == chatId }.singleOrNull()?.let { game ->
                towns[chatId]?.let { town ->
                    town.changeProtected(data.drop(6).toInt())
                    //showDay(town, chatId, towns, accounts, bot, "", "", modes, game)
                }
            }
        }
    } else if (data.startsWith("select:")) {
        callbackQuery.message?.chat?.id?.let { chatId ->
            towns[chatId]?.let { town ->
                //nightSelection(town, data.drop(8).toInt(), chatId, bot)
            }
        }
    } else if (data.startsWith("deleteMsg:")) {
        callbackQuery.message?.chat?.id?.let { chatId ->
            bot.deleteMessage(ChatId.fromId(chatId), data.drop(11).toLong())
        }
    } else if (data.startsWith("updateCheck:")) {
        callbackQuery.message?.chat?.id?.let { chatId ->
            val params = data.drop(13).split(", ")
            updateCheck(params[0], checks)
            showAdmin(checks, chatId, params[1].toLong(), bot)
        }
    } else if (data.startsWith("hostRequest:")) {
        callbackQuery.message?.chat?.id?.let { chatId ->
            val param = data.drop(13).toLong()
            showHostRequests(hostRequests, accounts, param, chatId, bot)
        }
    } else if (data.startsWith("hostSettings:")) {
        callbackQuery.message?.chat?.id?.let { chatId ->
            val param = data.drop(14).toLong()
            showHostSettings(param, hostInfos, accounts, chatId, bot)
            accounts.update(chatId) {
                state = AccountState.Admin
            }
            adminMenus.save(AdminMenu(ObjectId(), chatId))
        }
    } else if (data.startsWith("share:")) {
        callbackQuery.message?.chat?.id?.let { chatId ->
            val params = data.drop(7).split(", ")
            val target = params[0].toLong()
            hostInfos.get(target)?.let {
                hostInfos.update(target) { canShare = !it.canShare }
                showHostSettings(params[1].toLong(), hostInfos, accounts, chatId, bot)
            }
        }
    } else if (data.startsWith("timeLimitOff:")) {
        callbackQuery.message?.chat?.id?.let { chatId ->
            val params = data.drop(14).split(", ")
            val target = params[0].toLong()
            hostInfos.update(target) { timeLimit = false }
            showHostSettings(params[1].toLong(), hostInfos, accounts, chatId, bot)
        }
    } else if (data.startsWith("timeLimitOn:") || data.startsWith("timeLimitUntil:")) {
        callbackQuery.message?.chat?.id?.let { chatId ->
            val params = data.dropWhile { it != ':' }.drop(2).split(", ")
            val res = bot.sendMessage(ChatId.fromId(chatId), "Введите срок действия разрешения в днях:")
            if (res.isSuccess) {
                val desc = res.get().messageId
                adminMenus.update(chatId) {
                    state = AdminState.HOST_TIME
                    editId = params[0].toLong()
                    messageId = params[1].toLong()
                    descId = desc
                }
            }
        }
    } else if (data.startsWith("gameLimitOn:") || data.startsWith("gameLimitLeft:")) {
        callbackQuery.message?.chat?.id?.let { chatId ->
            val params = data.dropWhile { it != ':' }.drop(2).split(", ")
            val res = bot.sendMessage(ChatId.fromId(chatId), "Введите количество разрешенных игр:")
            if (res.isSuccess) {
                val desc = res.get().messageId
                adminMenus.update(chatId) {
                    state = AdminState.HOST_GAMES
                    editId = params[0].toLong()
                    messageId = params[1].toLong()
                    descId = desc
                }
            }
        }
    } else if (data.startsWith("gameLimitOff:")) {
        callbackQuery.message?.chat?.id?.let { chatId ->
            val params = data.drop(14).split(", ")
            hostInfos.update(params[0].toLong()) {
                gameLimit = false
            }
            showHostSettings(params[1].toLong(), hostInfos, accounts, chatId, bot)
        }
    } else if (data.startsWith("adminBack:")) {
        callbackQuery.message?.chat?.id?.let { chatId ->
            val param = data.drop(11).toLong()
            showAdmin(checks, chatId, param, bot)
            accounts.update(chatId) {
                state = AccountState.Menu
            }
            adminMenus.delete(chatId)
        }
    } else if (data.startsWith("denyHost:")) {
        callbackQuery.message?.chat?.id?.let { chatId ->
            val params = data.drop(10).split(", ")
            hostRequests.delete(params[0].toLong())
            showHostRequests(hostRequests, accounts, params[1].toLong(), chatId, bot)
        }
    } else if (data.startsWith("allowHost:")) {
        callbackQuery.message?.chat?.id?.let { chatId ->
            val params = data.drop(11).split(", ")
            val hostId = params[0].toLong()
            hostInfos.save(HostInfo(ObjectId(), hostId))
            hostRequests.delete(hostId)
            showHostRequests(hostRequests, accounts, params[1].toLong(), chatId, bot)
        }
    } else if (data.startsWith("fallMode:")) {
        callbackQuery.message?.chat?.id?.let { chatId ->
            games.find { host == chatId }?.singleOrNull()?.let { game ->
                modes.get(game.id)?.let {
                    towns[chatId]?.let { town ->
                        val messageId = data.drop(10).toLong()
                        modes.update(it.id) { fallMode = !it.fallMode }
                        //showDay(town, chatId, towns, accounts, bot, "", "", modes, game)
                        */
/*bot.editMessageReplyMarkup(
                            ChatId.fromId(chatId),
                            messageId,
                            replyMarkup = settingsButtons(it.copy(fallMode = !it.fallMode), messageId)
                        )*//*

                    }
                }
            }
        }
    } else if (data.startsWith("timerReset:")) {
        callbackQuery.message?.chat?.id?.let { chatId ->
            val host = data.drop(12).toLong()
            timers.update(host) {
                updated = true
                time = 0L
                timestamp = System.currentTimeMillis()
            }
        }
    } else if (data.startsWith("timerState:")) {
        callbackQuery.message?.chat?.id?.let { chatId ->
            val host = data.drop(12).toLong()
            timers.get(host)?.let {
                timers.update(host) {
                    active = !it.active
                    updated = true
                    timestamp = System.currentTimeMillis()
                }
            }
        }
    } else if (data.startsWith("timerDelete:")) {
        callbackQuery.message?.chat?.id?.let { chatId ->
            val host = data.drop(13).toLong()
            timers.get(host)?.let {
                deleteTimer(timers, it, bot)
            }
        }
    } else if (data == "dummy") {
        withAccount(accounts) { acc, chat ->
            if (acc.state == AccountState.Host) {
                games.find { host == chat }.singleOrNull()?.let { game ->
                    if (game.state == GameState.Connect) {
                        games.update(game.id) {
                            state = GameState.Dummy
                        }
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
            games.find { host == chatId }.singleOrNull()?.let { game ->
                towns[chatId]?.let { town ->
                    modes.get(game.id)?.let { mode ->
                        val index =
                            if (DayView.entries.size > mode.dayView.ordinal + 1) mode.dayView.ordinal + 1 else 0
                        val next = DayView.values()[index]
                        modes.update(game.id) {
                            dayView = next
                        }
                        //showDay(town, chatId, towns, accounts, bot, "", "", modes, game)
                    }
                }
            }
        }
    } else if (data == "leave") {
        withAccount(accounts) { result, chatId ->
            */
/*val con = connections.get(chatId)
            if (con != null) {
                leaveGame(
                    accounts,
                    chatId,
                    resetAccount,
                    pending,
                    con,
                    connections,
                    games,
                    bot
                )
                games.get(con.gameId)?.host?.let {
                    bot.sendMessage(
                        ChatId.fromId(it),
                        "Игрок ${con.name()} покинул игру"
                    )
                }
            } else {
                bot.sendMessage(
                    ChatId.fromId(chatId),
                    "Не удалось покинуть игру. Не найдено подключения к игре."
                )
            }*//*

        }
    } else if (data == "update") {
        val msgId = data.drop(8).toLong()
        withAccount(accounts) { result, chatId ->
            showGames(chatId, msgId, bot, games, accounts)
        }
    } else if (data == "nextType") {
        callbackQuery.message?.chat?.id?.let { chatId ->
            towns[chatId]?.let { town ->
                bot.sendMessage(
                    ChatId.fromId(chatId),
                    "Роль пропущена"
                )
                town.index++
                //showNightRole(town, chatId, modes, bot)
            }
        }
    } else if (data == "startDay") {
        callbackQuery.message?.chat?.id?.let { chatId ->
            games.find { host == chatId }.singleOrNull()?.let { game ->
                towns[chatId]?.let { town ->
                    startDay(town, chatId, towns, accounts, bot, modes, game)
                }
            }
        }
    } else if (data == "nextRole") {
        callbackQuery.message?.chat?.id?.let { chatId ->
            towns[chatId]?.let { town ->
                //showNightRole(town, chatId, modes, bot)
            }
        }
    } else if (data == "gameInfo") {
        withAccount(accounts) { acc, chat ->
            if (acc.state == AccountState.Lobby) {
                */
/*connections.get(chat)?.let { con ->
                    games.get(con.gameId)?.let { game ->
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
                                ChatId.fromId(chat),
                                desc,
                                parseMode = ParseMode.HTML
                            )
                        }
                    }
                }*//*

            }
        }
    } else if (data == "settings") {
        withAccount(accounts) { acc, chat ->
            if (acc.state == AccountState.Host) {
                games.find { host == chatId }.singleOrNull()?.let { game ->
                    modes.get(game.id)?.let {
                        val res = bot.sendMessage(
                            ChatId.fromId(chat),
                            "Настройки",
                            replyMarkup = InlineKeyboardMarkup.create(
                                listOf(InlineKeyboardButton.CallbackData("Загрузка...", "loading"))
                            )
                        )
                        if (res.isSuccess) {
                            val messageId = res.get().messageId
                            */
/*bot.editMessageReplyMarkup(
                                ChatId.fromId(chat),
                                messageId,
                                replyMarkup = settingsButtons(it, messageId)
                            )*//*

                        }
                    }
                }
            }
        }
    } else if (data == "timer") {
        withAccount(accounts) { acc, chat ->
            if (acc.state == AccountState.Host) {
                if (timers.get(chatId) == null) {
                    val res = bot.sendMessage(
                        ChatId.fromId(chatId),
                        "Таймер: 00:00",
                        replyMarkup = InlineKeyboardMarkup.create(
                            listOf(listOf(InlineKeyboardButton.CallbackData("Загрузка", "loading")))
                        )
                    )
                    if (res.isSuccess) {
                        val messageId = res.get().messageId
                        val timer = Timer(ObjectId(), chatId, messageId, System.currentTimeMillis(), 0L)
                        timers.save(
                            timer
                        )
                        //updateTimer(timer, bot, timers)
                    }
                }
            }
        }
    }
}*/
