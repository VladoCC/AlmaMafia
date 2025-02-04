package org.example

import org.bson.types.ObjectId
import org.example.db.Collection
import org.example.telegram.ParametrizedProcessor

internal fun createHostRequest(chatId: Long) {
    if (checks.get(CheckOption.HOST_REQUEST)) {
        hostRequests.save(UserId(ObjectId(), chatId))
    }
}

internal fun Collection<String, Check>.get(option: CheckOption) =
    (get(option.key)?.state ?: false)

internal fun isAdmin(chatId: Long) = chatId == Config().author || admins.get(chatId) != null

internal fun ParametrizedProcessor.HandlerContext.createAdminContext(desc: Long, state: AdminState) {
    accounts.update(chatId) {
        this.state = AccountState.Admin
    }
    if (adminContexts.get(chatId) == null) {
        adminContexts.save(
            AdminContext(
                ObjectId(),
                chatId,
                state,
                long(0),
                long(1),
                desc
            )
        )
    } else {
        adminContexts.update(chatId) {
            this.state = state
            editId = long(0)
            messageId = long(1)
            descId = desc
        }
    }
}
