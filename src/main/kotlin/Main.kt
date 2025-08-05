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
import org.example.game.*
import org.example.game.desc
import org.example.game.stopGame
import org.example.lua.*
import org.example.telegram.*
import org.slf4j.Logger
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.*
import kotlin.reflect.KClass

// todo keep 'leave game' button under game message, remove footer
// todo add ability to rehost game while it is in progress
// todo improve naming in hosts alive player count message
// todo add win rate stat for player
// todo rework town, make it savable
// todo make lazy, but updating on data change getters for loading related object from db by their id (generate getters with annotations?)
// todo replace objectid and get rid of mongo dependencies
// todo optimize db by using better serialization formats and maybe create some mongoshell-like util for hand processing data (kotlin-script?)
// todo add capabilities for graceful shutdown for bot (make sure to delete temp folder before finishing)
// todo refactor EVERYTHING

const val gameDurationLimitHours = 6
const val gameHistoryTtlHours = 24
const val sendPendingAfterSec = 3
const val deleteNumUpdateMsgAfterSec = 30
const val timerTtlMin = 60L
const val timerMaxTimeMin = 5L
const val timerInactiveTimeMin = 2L
const val roleNameLen = 32
const val roleDescLen = 280
val numbers = arrayOf("0️⃣", "1️⃣", "2️⃣", "3️⃣", "4️⃣", "5️⃣", "6️⃣", "7️⃣", "8️⃣", "9️⃣")
val notKnowingTeams = arrayOf("none", "city")
val dateFormat: DateTimeFormatter = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm")
val inGameStates = setOf(GameState.Type, GameState.Reveal, GameState.Game)

val resetAccount: Account.() -> Unit = {
    state = AccountState.Menu
    menuMessageId = -1L
    connectionId = null
}
val gameFilter: Connection.(Game) -> Boolean = { game -> gameId == game.id }
val scriptDir: Path = Files.createTempDirectory("scripts")
val log: Logger = logger<Main>()
val towns = mutableMapOf<GameId, Town>()
val scripts: MutableMap<GameId, Map<String, Script>> = mutableMapOf()

class Main

fun main() {
    //val connectionString = "mongodb://EdgeDom:WontH4CKAGA1n@localhost:44660/?retryWrites=true&w=majority"
    scriptDir.toFile().deleteOnExit()

    val bot = bot {
        token = Config().botToken
        dispatch {
            text {
                handle(message.text?.trim() ?: "") by MafiaHandler.textHandler
            }

            callbackQuery {
                handle(callbackQuery.data) by MafiaHandler.queryHandler
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
                    val filter: TimedMessage.() -> Boolean = { date.before(now) }
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
                    pendings.find {
                        date.toInstant().isBefore(Instant.now())
                    }.forEach {
                        set.add(it)
                    }
                    pendings.deleteMany { this in set }
                    set.forEach {
                        accounts.get(it.host)?.let {
                            games.find { hostId == it.chatId }.singleOrNull()?.let { game ->
                                if (game.state == GameState.Connect) {
                                    showLobbyMenu(it.chatId, it.menuMessageId, game, bot)
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
                    timers.find {
                        val ttl = Duration.ofMillis(System.currentTimeMillis() - creationTime)
                            .minus(Duration.ofMinutes(timerTtlMin)).isPositive
                        val timeLimit = Duration.ofMillis(time).minus(Duration.ofMinutes(timerMaxTimeMin)).isPositive
                        val inactiveTime = Duration.ofMillis(System.currentTimeMillis() - timestamp)
                            .minus(Duration.ofMinutes(timerInactiveTimeMin)).isPositive
                        ttl || timeLimit || inactiveTime
                    }.forEach {
                        deleteTimer(it, bot)
                    }
                    timers.find { updated }.forEach {
                        updateTimer(it, bot)
                    }
                } catch (e: Exception) {
                    log.error("Unable to process timers", e)
                }
                delay(1000)
            }
        }

        launch {
            while (true) {
                try {
                    games.find {
                        val date = playedAt ?: createdAt
                        date.toInstant().isBefore(Instant.now().minusSeconds(gameDurationLimitHours * 60 * 60L))
                    }.forEach {
                        stopGame(
                            it,
                            it.hostId,
                            bot,
                            accounts.get(it.hostId)?.menuMessageId ?: -1L
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

fun Bot.error(chatId: Long, text: String = "Неизвестная команда.") {
    sendMessageInline(
        this,
        chatId,
        text,
        { msgId -> button(deleteMsgCommand, msgId) }
    )
}

private fun isKnownHost(chatId: Long) = hostInfos.get(chatId) != null

fun showAd(game: Game, connections: List<Connection>, bot: Bot, messageId: Long, chatId: Long) {
    val id = ObjectId()
    editMessage(
        bot,
        chatId,
        messageId,
        text = "Возможные сообщения:"
    )
    val adList = ads.find()
    val messages = adList.map { message ->
        sendMessageInline(
            bot,
            chatId,
            message.text,
            { button(adSelectCommand, message.id, id) }
        )
    }
    val lastId = messages.last()
    editMessageInline(
        bot,
        chatId,
        lastId,
        {
            button(adSelectCommand, adList.last().id, id)
            button(adClearCommand, id)
        }
    )
    adTargets.save(AdTarget(id, game, connections, messages + listOf(messageId)))
}


fun selectAd(game: Game, connections: List<Connection>, bot: Bot, ad: Message) {
    val host = game.hostId
    fun send(chatId: Long) {
        sendMessage(
            bot, chatId, ad.text,
            { msgId ->
                bombs.save(
                    TimedMessage(
                        ObjectId(),
                        chatId,
                        msgId,
                        Date(System.currentTimeMillis() + 1000 * 60 * 60)
                    )
                )
            }
        )
    }
    send(host)
    connections.forEach {
        send(it.playerId)
    }
}

// todo move
internal fun updateSettingsView(
    hostId: Long,
    messageId: Long,
    gameMessageId: Long,
    game: Game,
    town: Town,
    bot: Bot,
    update: HostSettings.() -> Unit
) {
    hostSettings.update(hostId, update)
    hostSettings.get(hostId)?.let { settings ->
        if (gameMessageId != -1L) {
            showDayMenu(town, hostId, gameMessageId, bot, game)
        }
        showSettingsMenu(settings, hostId, messageId, gameMessageId, bot)
        return
    }
}

fun showPlayerDayDesc(town: Town, playerPos: Int, messageId: Long, chatId: Long, bot: Bot) {
    town.playerMap[playerPos]?.let<Person, Unit> { player ->
        val fallMode = games.get(town.gameId)?.host?.settings?.fallMode ?: false
        editMessageInline(
            bot,
            chatId,
            messageId,
            {
                button(blankCommand named "Детали")
                button(dayDetailsCommand named desc(
                    player,
                    hideRolesMode = getHideRolesMode(games.get(town.gameId))
                ), playerPos, messageId)
                row {
                    playerDayDesc(player, messageId, fallMode)
                }
                button(dayBackCommand, messageId)
            }
        )
        return@let
    }
}

fun getHideRolesMode(game: Game?): Boolean {
    return game?.let { game ->
        game.host?.settings?.hideRolesMode
    } ?: false
}

fun deleteTimer(
    it: Timer,
    bot: Bot
) {
    timers.delete(it.chatId)
    bot.deleteMessage(ChatId.fromId(it.chatId), it.messageId)
}

private fun updateTimer(
    timer: Timer,
    bot: Bot
) {
    val text = timerText(timer.time)
    editMessageInline(
        bot,
        timer.chatId,
        timer.messageId,
        {
            button(blankCommand named text)
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
    val min = (timePassed / 60).toString().padStart(2, '0').map { numbers[it.toString().toInt()] }.joinToString("")
    val sec = (timePassed % 60).toString().padStart(2, '0').map { numbers[it.toString().toInt()] }.joinToString("")
    val text = "$min：$sec"
    return text
}

fun showHostSettings(
    chatId: Long,
    messageId: Long,
    bot: Bot,
    pageIndex: Int = 0
) {
    showPaginatedAdminSubmenu(
        chatId,
        messageId,
        bot,
        "Список ведущих",
        hostInfos,
        {
            accounts.get(it.chatId)?.let { acc ->
                row {
                    button(chooseHostSettingsCommand named ("👤 " + acc.fullName()), -1L, it.chatId)
                    button(deleteHostCommand, it.chatId, messageId, pageIndex)
                }
            }
        },
        hostSettingsCommand,
        pageIndex
    )
}

fun showHostRequests(
    messageId: Long,
    chatId: Long,
    bot: Bot,
    pageIndex: Int = 0
) {
    showPaginatedAdminSubmenu(
        chatId,
        messageId,
        bot,
        "Запросы на ведение",
        hostRequests,
        {
            accounts.get(it.chatId)?.let { acc ->
                button(blankCommand named acc.fullName())
                row {
                    button(allowHostCommand, it.chatId, messageId)
                    button(denyHostCommand, it.chatId, messageId)
                }
            }
        },
        hostRequestCommand,
        pageIndex
    )
}

fun showAdmin(
    chatId: Long,
    messageId: Long,
    bot: Bot
) {
   editMessageInline(
        bot,
        chatId,
        messageId,
        {
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
            button(hostRequestCommand, messageId, 0)
            button(hostSettingsCommand, messageId, 0)
            button(adminSettingsCommand, messageId, 0)
            button(gamesSettingsCommand, messageId, 0)
            button(hostAdminSettingsCommand, messageId, 0)
            button(advertCommand)
            button(deleteMsgCommand, messageId)
        }
    )
}

fun updateCheck(
    param: String
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
    chatId: Long
): Boolean {
    return if (checks.get(CheckOption.HOST_KNOWN)) {
        hostInfos.find { this.chatId == chatId }.isNotEmpty()
    } else {
        true
    }
}

internal fun leaveGame(
    chatId: Long,
    messageId: Long,
    resetAccount: Account.() -> Unit,
    bot: Bot
) {
    accounts.update(chatId, resetAccount)

    val cons = connections.find { playerId == chatId }
    connections.deleteMany { playerId == chatId }
    bot.deleteMessage(
        ChatId.fromId(chatId),
        messageId
    )
    showMainMenu(chatId, "Возвращаемся в главное меню.", bot, true)

    cons.forEach { con ->
        games.get(con.gameId)?.hostId?.let {
            pendings.save(Pending(ObjectId(), it, con.gameId))
        }
    }
}

fun updateSetup(
    path: String,
    game: Game
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

fun shortLog(town: Town): String {
    return if (town.actions.isNotEmpty()) {
        val set = mutableSetOf<Pair<KClass<out Event>, Int>>()
        val text =
            town.actions.asSequence().filter { it.skippedBy == null }.map { it.events() }.flatten().sortedBy { it.pos }.map {
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

fun fullLog(town: Town): String {
    return if (town.actions.isNotEmpty()) {
        val text = town.actions.filter {
            (it.skippedBy == null || !it.skippedBy!!.master)
                    && it.dependencies.all {
                        it.skippedBy == null || !it.skippedBy!!.master
                    }
        }.mapIndexed { i, it ->
            val action = it.desc()

            val alive = it.actors.mapNotNull { town.playerMap[it] }.filter { it.alive }
            val who = if (alive.isNotEmpty()) {
                alive.joinToString(", ") { desc(it) }
            } else {
                "Действующее лицо не указно"
            }
            val target = it.selection.joinToString { desc(it, " - ") }
            val skipper = it.skippedBy?.let {
                if (it.master)
                    "Ведущий"
                else
                    it.actors
                        .mapNotNull { town.playerMap[it] }
                        .filter { it.alive }
                        .maxByOrNull { it.roleData.priority }
                        ?.roleData?.displayName
                        ?: "Действующее лицо не указано"
            }
            val dep = it.dependencies.lastOrNull()?.let {
                it.desc() + "(" +
                        it.actors
                            .mapNotNull { town.playerMap[it] }
                            .filter { it.alive }
                            .maxByOrNull { it.roleData.priority }
                            ?.roleData?.displayName +
                        ")"
            }
            "Событие ${i + 1}." + (if (it.skippedBy != null) " (ОТМЕНЕНО)" else "") + "\n" +
                    "Кто: $who\n" +
                    "Действие: $action\n" +
                    "Цель: $target\n" +
                    (if (it is InfoAction) "Результат: ${it.text}" else "") +
                    (if (dep != null) "Реакция на: $dep" else "") +
                    (if (skipper != null) "Отменено ролью: $skipper" else "")
        }.joinToString("\n\n")
        text
    } else {
        ""
    }
}

//private fun desc(player: Person, sep: String = ". ") = "${player.pos}$sep${player.name} (${player.role.name})"

fun initAccount(
    userName: String,
    chatId: Long,
    bot: Bot
) {
    if (accounts.get(chatId) == null) {
        accounts.save(Account(ObjectId(), chatId, userName))
    } else {
        accounts.update(chatId) {
            state = AccountState.Init
            menuMessageId = -1L
            connectionId = null
        }
    }
    bot.sendMessage(
        ChatId.fromId(chatId),
        "Пожалуйста, введите свое имя. Это имя смогут видеть ведущие игр, к которым вы присоединяетесь.",
        replyMarkup = ReplyKeyboardRemove(true)
    )
}

fun showMainMenu(
    chatId: Long,
    menuText: String,
    bot: Bot,
    forceUpdate: Boolean = false,
    silent: Boolean = false
) {
    bot.sendMessage(
        ChatId.fromId(chatId),
        menuText,
        disableNotification = silent,
        replyMarkup = mafiaKeyboard(chatId)
    )
    showGames(chatId, -1L, bot, forceUpdate)
}

fun showRoles(
    chatId: Long,
    messageId: Long,
    bot: Bot,
    game: Game
) {
    val players = connections.find { gameId == game.id }
    val pairs = pairings.find { gameId == game.id }
    val gameSetups = setups.find { gameId == game.id }
    editMessageInline(
        bot,
        chatId,
        messageId,
        {
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
            button(resetRolesCommand, game.id, messageId)
            row {
                button(menuLobbyCommand, messageId)
                button(previewCommand, game.id, messageId)
            }
        }
    )
}

fun showPreview(
    bot: Bot,
    chatId: Long,
    messageId: Long,
    game: Game
) {
    val players = connections.find { gameId == game.id }
    val pairs = pairings.find { gameId == game.id }.associateBy { it.connectionId }
    editMessageInline(
        bot,
        chatId,
        messageId,
        {
            val hideRolesMode = getHideRolesMode(game)
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
            button(
                toggleHideRolesModePreviewCommand named
                        if (hideRolesMode) "👓 Показывать роли" else "🕶️ Скрывать роли",
                messageId
            )
            button(previewCommand named "🔄 Перераздать", chatId, messageId)
            row {
                button(menuRolesCommand named "◀️ Меню ролей", messageId)
                button(gameCommand, game.id, messageId)
            }
        }
    )
}

fun withAccount(chatId: Long, func: (Account) -> Unit) {
    accounts.get(chatId)?.let {
        func(it)
    }
}

fun showGames(
    chatId: Long,
    messageId: Long,
    bot: Bot,
    forceUpdate: Boolean = false
) {
    val msgId = if (messageId == -1L || forceUpdate) {
        sendMessage(bot, chatId, "Доступные игры (нажмите на игру чтобы присоединиться):")
    } else {
        messageId
    }
    accounts.update(chatId) { menuMessageId = msgId }
    editMessageInline(
        bot,
        chatId,
        msgId,
        { msgId ->
            games.find { actual }.forEach {
                accounts.get(it.hostId)?.let { host ->
                    row {
                        button(
                            joinCommand named (if (it.state in inGameStates) "🎮" else "👥") + host.fullName(),
                            it.id, msgId
                        )
                    }
                }
            }
            row { button(updateCommand, msgId) }
        }
    )
}

fun mafiaKeyboard(chatId: Long, definition: FooterContext.() -> Unit = {}) = footerKeyboard {
    accounts.get(chatId)?.let {
        when (it.state) {
            AccountState.Menu -> {
                if (isKnownHost(chatId)) {
                    button(startGameCommand)
                }
            }

            AccountState.Host -> {
                button(restartGameCommand)
                button(stopGameCommand)
            }

            AccountState.Lobby -> {
                button(leaveGameCommand)
            }

            else -> {}
        }
    }
    definition()
    if (isAdmin(chatId)) {
        button(adminPanelCommand)
    }
}