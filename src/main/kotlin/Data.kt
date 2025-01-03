package org.example

import kotlinx.serialization.Serializable
import org.bson.codecs.pojo.annotations.BsonId
import org.bson.types.ObjectId
import java.util.*

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
    val createdAt: Date = Date(),
    var playedAt: Date? = null,
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
    var notified = false
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
    val displayName: String,
    val desc: String,
    val scripted: Boolean,
    val defaultTeam: String,
    val name: String = "",
    val priority: Int = 1,
    val coverName: String = "",
) {
    var index: Int = -1

    fun teamName(cover: Boolean = false): String {
        if (cover && coverName.isNotBlank()) {
            return coverName
        }
        return displayName
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
) {
    val connection: Connection?
        get() = connections.get(connectionId)

    val role: Role?
        get() = roles.get(roleId)
}

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

data class UserId(
    @BsonId val id: ObjectId,
    val chatId: Long
)

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

data class AdTarget(
    val id: ObjectId,
    val game: Game,
    val connections: List<Connection>,
    val messages: List<Long>
)

data class MessageLink(
    val chatId: Long,
    val messageId: Long
)

data class GameMessage(
    val id: ObjectId,
    val gameId: ObjectId,
    var list: List<MessageLink>
)