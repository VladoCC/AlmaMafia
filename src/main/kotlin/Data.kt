package org.example

import kotlinx.serialization.Serializable
import org.bson.codecs.pojo.annotations.BsonId
import org.bson.types.ObjectId
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.*

data class Account(
    @BsonId val id: ObjectId,
    val chatId: Long,
    var userName: String,
    var name: String = "",
    var state: AccountState = AccountState.Init,
    var menuMessageId: Long = -1L,
    var connectionId: ConnectionId? = null
) {
    fun fullName() = name + if (userName.isNotBlank()) " (@$userName)" else ""

    val connection: Connection? get() = connectionId?.let { connections.get(it) }
    val pending: Pending? get() = pendings.get(chatId)
    val hostInfo: HostInfo? get() = hostInfos.get(chatId)
    val adminContext: AdminContext? get() = adminContexts.get(chatId)
    val timer: Timer? get() = timers.get(chatId)
    val adPopup: AdPopup? get() = adPopups.get(chatId)
    val settings: HostSettings? get() = hostSettings.get(chatId)
}

typealias GameId = ObjectId
data class Game(
    @BsonId val id: GameId,
    var hostId: Long,
    var state: GameState = GameState.Connect,
    val createdAt: Date = Date(),
    var playedAt: Date? = null,
    val creatorId: Long = hostId,
    var actual: Boolean = true
) {
    val host: Account? get() = accounts.get(hostId)
    val creator: Account? get() = accounts.get(creatorId)
    val mode: GameMode? get() = modes.get(id)
    val rehost: Rehost? get() = rehosts.get(id)
    val messages: List<MessageLink> get() = messageLinks.find(id) { gameId == it }
    val pairingList: List<Pairing> get() = pairings.find(id) { gameId == it }
    val connectionList: List<Connection> get() = connections.find(id) { gameId == it}

    fun name() = (if (state == GameState.Game) "🎮" else "👥") + (accounts.get(hostId)?.fullName()
        ?: "") + " (" + dateFormat.format(
        ZonedDateTime.ofInstant(
            (playedAt ?: createdAt).toInstant(),
            ZoneId.systemDefault()
        )
    ) + ") - ${id.let { gameId -> connections.find { this.gameId == gameId }.size }} игроков"
}

typealias ConnectionId = ObjectId
data class Connection(
    @BsonId val id: ConnectionId,
    val gameId: GameId,
    val playerId: Long,
    var name: String = "",
    var handle: String = "",
    var bot: Boolean = false,
    var pos: Int = Int.MAX_VALUE,
) {
    val createdAt: Date = Date()
    var notified = false
    fun name() = name + if (handle.isNotBlank()) " ($handle)" else ""

    val game: Game? get() = games.get(gameId)
    val player: Account? get() = if (bot) null else accounts.get(playerId)
}

data class Pending(
    @BsonId val id: ObjectId,
    val host: Long,
    val gameId: GameId,
    val date: Date = Date()
) {
    val game: Game? get() = games.get(gameId)
}


typealias RoleId = ObjectId
data class Role(
    @BsonId val id: RoleId,
    val gameId: GameId,
    val displayName: String,
    val desc: String,
    val scripted: Boolean,
    val defaultTeam: String,
    val name: String = "",
    val priority: Int = 1,
    val coverName: String = "",
) {
    var index: Int = -1
    val game: Game? get() = games.get(gameId)

    fun teamName(cover: Boolean = false): String {
        if (cover && coverName.isNotBlank()) {
            return coverName
        }
        return displayName
    }
}

typealias SetupId = ObjectId
data class Setup(
    @BsonId val id: SetupId,
    var roleId: RoleId,
    var gameId: GameId,
    val index: Int,
    var count: Int = 0
) {
    val role: Role? get() = roles.get(roleId)
    val game: Game? get() = games.get(gameId)
}

typealias PairingId = ObjectId
data class Pairing(
    @BsonId val id: PairingId,
    val gameId: GameId,
    val connectionId: ConnectionId,
    val roleId: RoleId
) {
    val game: Game? get() = games.get(gameId)
    val connection: Connection? get() = connections.get(connectionId)
    val role: Role? get() = roles.get(roleId)
}

typealias TypeOrderId = ObjectId
data class TypeOrder(
    @BsonId val id: TypeOrderId,
    val gameId: GameId,
    val type: String,
    val pos: Int
) {
    val game: Game? get() = games.get(gameId)
}

typealias TimedMessageId = ObjectId
data class TimedMessage(
    @BsonId val id: TimedMessageId,
    val chatId: Long,
    val messageId: Long,
    val date: Date
)

typealias MessageId = ObjectId
data class Message(
    @BsonId val id: MessageId,
    val text: String
)

typealias CheckId = ObjectId
data class Check(
    @BsonId val id: CheckId,
    val name: String,
    var state: Boolean
)

typealias KickId = ObjectId
data class Kick(
    @BsonId val id: KickId,
    val gameId: GameId,
    val player: Long
) {
    val game: Game? get() = games.get(gameId)
}

typealias GameModeId = ObjectId
data class GameMode(
    @BsonId val id: GameModeId,
    val gameId: GameId,
    var mode: Mode,
) {
    val game: Game? get() = games.get(gameId)
}

typealias HostSettingsId = ObjectId
data class HostSettings(
    val id: HostSettingsId,
    val hostId: Long,
    var dayView: DayView = DayView.ALL,
    var fallMode: Boolean = false,
    var detailedView: Boolean = false,
    var doubleColumnNight: Boolean = true,
    var confirmNightSelection: Boolean = false,
    var timer: Boolean = false,
    var hideDayPlayers: Boolean = false,
    var playersHidden: Boolean = false,
) {
    val host: Account? get() = accounts.get(hostId)
}

// todo fix naming
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
    fun getRoleName() = roleData.displayName
}

typealias TypeId = ObjectId
data class Type(
    @BsonId val id: TypeId,
    val gameId: GameId,
    val name: String,
    val choice: Int
) {
    val game: Game? get() = games.get(gameId)
}

data class Wake(
    val type: Type,
    val players: List<Person>
) {
    fun actor() = players.firstOrNull { it.alive }?: players.firstOrNull()
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
    val displayName: String,
    val desc: String,
    val scripted: Boolean,
    val defaultTeam: String,
    val name: String,
    val priority: Int = 1,
    val coverName: String = ""
)

typealias HostInfoId = ObjectId
data class HostInfo(
    @BsonId val id: HostInfoId,
    val chatId: Long,
    var timeLimit: Boolean = false,
    var until: Date = Date(),
    var gameLimit: Boolean = false,
    var left: Int = -1,
    var canShare: Boolean = true,
    var revealRolesMode: Boolean = true
) {
    val account: Account? get() = accounts.get(chatId)
}

data class UserId(
    @BsonId val id: ObjectId,
    val chatId: Long
) {
    val account: Account? get() = accounts.get(chatId)
}

typealias AdminMenuId = ObjectId
data class AdminContext(
    @BsonId val id: AdminMenuId,
    val chatId: Long,
    var state: AdminState = AdminState.NONE,
    var editId: Long = -1,
    var messageId: Long = -1,
    var descId: Long = -1
) {
    val account: Account? get() = accounts.get(chatId)
}

typealias TimerId = ObjectId
data class Timer(
    @BsonId val id: TimerId,
    val chatId: Long,
    val gameId: GameId,
    val messageId: Long,
    var timestamp: Long,
    var time: Long,
    var active: Boolean = true,
    var updated: Boolean = true,
    var creationTime: Long = System.currentTimeMillis()
) {
    val account: Account? get() = accounts.get(chatId)
    val game: Game? get() = games.get(gameId)
}

typealias GameSummaryId = ObjectId
data class GameSummary(
    val id: GameSummaryId,
    val game: Game,
    val connections: List<Connection>,
    val playedAt: Date = Date()
) {
    fun name() = (accounts.get(game.hostId)?.fullName()
        ?: "") + " (" + dateFormat.format(
        ZonedDateTime.ofInstant(
            (game.playedAt ?: game.createdAt).toInstant(),
            ZoneId.systemDefault()
        )
    ) + ") - ${connections.size} игроков"
}

typealias AdPopupId = ObjectId
data class AdPopup(
    val id: ObjectId,
    val chatId: Long,
    val messageId: Long
)

typealias AdTargetId = ObjectId
data class AdTarget(
    val id: AdTargetId,
    val game: Game,
    val connections: List<Connection>,
    val messages: List<Long>
)

typealias MessageLinkId = ObjectId
data class MessageLink(
    val id: MessageLinkId,
    val gameId: GameId,
    val chatId: Long,
    val messageId: Long,
    var type: LinkType = LinkType.NONE
) {
    val game: Game? = games.get(gameId)
}

typealias RehostId = ObjectId
data class Rehost(
    val id: RehostId,
    val gameId: GameId,
    val hostId: Long,
    val messageId: Long
) {
    val game: Game? = games.get(gameId)
    val host: Account? = accounts.get(hostId)
}

typealias NameChangeId = ObjectId
data class NameChange(
    val id: NameChangeId,
    val chatId: Long
)

typealias GameUpdateId = ObjectId
data class GameUpdate(
    val id: GameUpdateId,
    val gameId: GameId
)