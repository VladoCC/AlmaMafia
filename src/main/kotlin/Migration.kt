package org.example

import com.mongodb.kotlin.client.coroutine.MongoCollection
import org.bson.codecs.pojo.annotations.BsonId
import org.bson.types.ObjectId

suspend fun <T: Any, K: Any> migrate(from: MongoCollection<T>, to: Collection<T, K>) {
    from.find().collect {
        to.save(it)
    }
}

suspend fun <T1: Any, T2: Any, K: Any> migrate(from: MongoCollection<T1>, to: Collection<T2, K>, migration: (T1) -> T2) {
    from.find().collect {
        to.save(migration(it))
    }
}

data class AccountOld(
    @BsonId val id: ObjectId,
    val chatId: Long,
    var userName: String,
    var name: String = "",
    var state: AccountState = AccountState.Init,
    var menuMessageId: Long = -1L,
    var hostMessageId: Long = -1L,
    var setupMessageId: Long = -1L,
    var dayMessageId: Long = -1L,
    var connectionId: String = ""
) {
    fun toAccount() = Account(id, chatId, userName, name, state, menuMessageId, hostMessageId, setupMessageId, dayMessageId)
}