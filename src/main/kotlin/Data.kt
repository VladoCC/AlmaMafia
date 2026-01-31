package org.example

import kotlinx.serialization.Serializable
import org.bson.codecs.pojo.annotations.BsonId
import org.bson.types.ObjectId
import org.example.game.RoleDistribution
import org.example.game.WakeStatus
import org.example.lua.Choice
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.*
import java.time.Duration

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
    val scriptOwnership: List<GameScript> get() = gameScripts.find(chatId) { ownerId == it }
    val scripts: List<ScriptLink> get() = scriptAccess.find(chatId) { chatId == it }
    val scriptSelection: ScriptLink? get() = scriptSelections.find(chatId) { chatId == it }.firstOrNull()
    val scriptStatList: List<ScriptStat> get() = scriptStats.find(chatId) { playerId == it }
    val teamStatList: List<TeamHistory> get() = teamHistories.find(chatId) { playerId == it  }
}

typealias GameId = ObjectId
data class Game(
    @BsonId val id: GameId,
    var hostId: Long,
    var scriptId: GameScriptId,
    var state: GameState = GameState.CONNECT,
    val createdAt: Date = Date(),
    var playedAt: Date? = null,
    val creatorId: Long = hostId,
    var actual: Boolean = true
) {
    val host: Account? get() = accounts.get(hostId)
    val creator: Account? get() = accounts.get(creatorId)
    val mode: GameMode? get() = modes.get(id)
    val rehost: Rehost? get() = rehosts.get(id)
    val reassignment: Reassignment? get() = reassignments.get(id)
    val nightHostMessage: NightHostMessage? get() = nightHostMessages.get(hostId)
    val messages: List<MessageLink> get() = messageLinks.find(id) { gameId == it }
    val pairingList: List<Pairing> get() = pairings.find(id) { gameId == it }
    val connectionList: List<Connection> get() = connections.find(id) { gameId == it}
    val shares: List<GameShare> get() = gameShares.find(id) { gameId == it }
    val nightPlayerMessageList: List<NightPlayerMessage> get() = nightPlayerMessages.find(id) { gameId == it }
    val script: GameScript? get() = gameScripts.get(scriptId)
    val setupList: List<Setup> get() = setups.find(id) { gameId == it }
    val roleList: List<Role> get() = roles.find(id) { gameId == it }
    val kickList: List<Kick> get() = kicks.find(id) { gameId == it }

    fun name() = (if (state == GameState.GAME) "🎮" else "👥") + (accounts.get(hostId)?.fullName()
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
    fun name() = (if (bot) "🤖 " else "") + name + if (handle.isNotBlank()) " ($handle)" else ""

    val game: Game? get() = games.get(gameId)
    val player: Account? get() = if (bot) null else accounts.get(playerId)
    val share: GameShare? get() = gameShares.get(id)
    val pairing: Pairing? get() = pairings.find(id) { this.connectionId == it }.firstOrNull()
    val nightPlayerMessage: NightPlayerMessage? get() = nightPlayerMessages.find(id) { this.playerId == it }.firstOrNull()
    val autoNightActor: AutoNightActor? get() = autoNightActors.find(id) { this.connectionId == it }.firstOrNull()
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
    val defaultTeam: String,
    val defaultType: String,
    val name: String = "",
    val priority: Int = 1,
    val coverName: String = ""
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
    var roleId: RoleId
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
    var hideRolesMode: Boolean = false,
    var autoNight: AutoNightSettings? = AutoNightSettings()
) {
    val host: Account? get() = accounts.get(hostId)

    data class AutoNightSettings(
        var enabled: Boolean = false,
        var actionSingleLimitSec: Int = 15,
        var actionTeamLimitSec: Int = 30
    ) {
        val actionSingleLimit: Duration
            get() = Duration.ofSeconds(actionSingleLimitSec.toLong())
        val actionTeamLimit: Duration
            get() = Duration.ofSeconds(actionTeamLimitSec.toLong())
    }
}

// todo fix naming
data class Person(
    val pos: Int,
    val name: String,
    val roleData: Role,
    var team: String,
    var types: List<String>,
    var connectionId: ConnectionId,
    var alive: Boolean = true,
    var protected: Boolean = false,
    var fallCount: Int = 0
) {
    fun isAlive() = alive
    fun getRole() = roleData.name
    fun getRoleName() = roleData.displayName

    fun desc(sep: String = ". ", icons: Boolean = true, roles: Boolean = true) =
        "${pos}$sep" +
                (if (!icons) "" else if (protected) "⛑️" else if (alive) "" else "☠️") +
                (if (!icons) "" else if (fallCount > 0) numbers[fallCount % numbers.size] else "") +
                (if (connections.get(connectionId)?.bot == true) "🤖" else "") +
                " ${name}" +
                if (roles) " (${roleData.displayName})" else ""
}

typealias TypeId = ObjectId
data class Type(
    @BsonId val id: TypeId,
    val gameId: GameId,
    val name: String,
    val displayName: String,
    val choice: Int = 0,
    val passive: Boolean = false,
    val order: Int = 0
) {
    val game: Game? get() = games.get(gameId)
}

data class Wake(
    val id: Int,
    val type: Type,
    val players: List<Person>,
    var choices: List<Choice> = emptyList(),
    val selections: MutableList<Int> = mutableListOf(),
    var status: WakeStatus = WakeStatus.none(),
) {
    fun actor() = players.firstOrNull { it.alive }?: players.firstOrNull()
    fun alivePlayers() = players.filter { it.alive && connections.get(it.connectionId)?.bot == false }
    fun filled() = type.choice <= selections.size
    fun updateStatus() {
        if (status == WakeStatus.none() && (filled() || players.all { !it.alive })) {
            status = WakeStatus.woke("Все игроки мертвы")
        }
    }
}

@Serializable
data class GameSet(
    val name: String,
    val type: List<TypeData>,
    val roles: List<RoleData>,
    val teamDisplayNames: Map<String, String>
)

@Serializable
data class TypeData(
    val name: String,
    val choice: Int = 1,
    val displayName: String = "",
    val passive: Boolean = false
)

@Serializable
data class RoleData(
    val displayName: String,
    val desc: String,
    val defaultTeam: String,
    val name: String,
    val defaultType: String = name,
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
    var canReassign: Boolean = false,
    var showDistribution: Boolean = false
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

data class Reassignment(
    val gameId: GameId,
    val connectionId: ConnectionId,
) {
    val game: Game? get() = games.get(gameId)
    val connection: Connection? get() = connections.get(connectionId)
}

data class GameShare (
    val connectionId: ConnectionId,
    val gameId: GameId
) {
    val connection: Connection? get() = connections.get(connectionId)
    val game: Game? get() = games.get(gameId)
}

data class AutoNightInput(
    val chatId: Long,
    val type: AutoNightInputType,
    val settingsMessageId: Long,
    val gameMessageId: Long
)

data class NightPlayerMessage(
    val chatId: Long,
    val messageId: Long,
    val playerId: ConnectionId,
    val gameId: GameId
) {
    val connection: Connection? get() = connections.get(playerId)
    val game: Game? get() = games.get(gameId)
    val account: Account? get() = accounts.get(chatId)
}

data class NightHostMessage(
    val chatId: Long,
    val messageId: Long,
    val gameId: GameId
) {
    val game: Game? get() = games.get(gameId)
}

typealias AutoNightActionId = ObjectId
data class AutoNightAction(
    val id: AutoNightActionId,
    val gameId: GameId,
    val wakeId: Int
) {
    val game: Game? get() = games.get(gameId)
    val wake: Wake? get() = towns[gameId]?.night[wakeId]
    val actorLinks: List<ActorActionLink> get() = actorActionLinks.find(id) { actionId == it }
}

typealias AutoNightActorId = ObjectId
data class AutoNightActor(
    val id: AutoNightActorId,
    val connectionId: ConnectionId,
) {
    val connection: Connection? get() = connections.get(connectionId)
    val actionLinks: List<ActorActionLink> get() = actorActionLinks.find(id) { actorId == it }
}

typealias ActorActionLinkId = ObjectId
data class ActorActionLink(
    val id: ActorActionLinkId,
    val actorId: AutoNightActorId,
    val actionId: AutoNightActionId,
    var leader: Boolean = false
) {
    val actor: AutoNightActor? get() = autoNightActors.get(actorId)
    val action: AutoNightAction? get() = autoNightActions.get(actionId)
    val selections: List<AutoNightSelection> get() = autoNightSelections.find(id) { linkId == it }
}

typealias AutoNightSelectionId = ObjectId
data class AutoNightSelection(
    val id: AutoNightSelectionId,
    val actionId: AutoNightActionId,
    val linkId: ActorActionLinkId,
    val selection: Int
) {
    val action: AutoNightAction? get() = autoNightActions.get(actionId)
    val link: ActorActionLink? get() = actorActionLinks.get(linkId)
}

data class NightMessageUpdate(
    val actionId: AutoNightActionId,
    val gameId: GameId
) {
    val action: AutoNightAction? get() = autoNightActions.get(actionId)
}

typealias TeamNameId = ObjectId
data class TeamName(
    val id: TeamNameId,
    val gameId: GameId,
    val team: String,
    val name: String
)

typealias GameScriptId = ObjectId
data class GameScript(
    val id: GameScriptId,
    val ownerId: Long,
    val name: String,
    val jsonPath: String,
    val roleDistribution: RoleDistribution = RoleDistribution.RANDOM
) {
    val owner: Account? get() = accounts.get(ownerId)
    val path: String get() = "${Config().path}/scripts/$ownerId/$id"
    fun displayName(): String = "$name${owner?.let { " от ${it.fullName()}" } ?: ""}"
}

typealias ScriptLinkId = ObjectId
data class ScriptLink(
    val id: ScriptLinkId,
    val scriptId: GameScriptId,
    val chatId: Long
) {
    val script: GameScript? get() = gameScripts.get(scriptId)
    val account: Account? get() = accounts.get(chatId)
}

data class MigrationRecord(
    val name: String = "",
    val appliedAt: Date = Date()
)

typealias WinSelectionId = ObjectId
data class WinSelection(
    val id: WinSelectionId,
    val gameId: GameId,
    val team: String
) {
    val game: Game? get() = games.get(gameId)
}

typealias ScriptStatId = ObjectId
data class ScriptStat(
    val id: ScriptStatId,
    val scriptId: GameScriptId,
    val playerId: Long,
    val gamesPlayed: Int = 0,
    val wins: Int = 0,
    val roleStats: Map<String, Int> = emptyMap(),
    val teamStats: Map<String, Int> = emptyMap()
) {
    val script: GameScript? get() = gameScripts.get(scriptId)
    val player: Account? get() = accounts.get(playerId)
}

typealias TeamHistoryId = ObjectId
data class TeamHistory(
    val id: TeamHistoryId,
    val scriptId: GameScriptId,
    val playerId: Long,
    val team: String,
    val gameId: GameId = ObjectId(),
    val playerCount: Int = 10,
    val teamSize: Int = 1,
    val date: Date = Date()
) {
    val script: GameScript? get() = gameScripts.get(scriptId)
    val player: Account? get() = accounts.get(playerId)
}