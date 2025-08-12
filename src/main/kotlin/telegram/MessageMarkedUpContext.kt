package org.example.telegram

data class MessageMarkedUpContext(
    val msgId: Long
) {

    fun callback(action: (Long) -> Unit) {
        if (msgId > 0) {
            action.invoke(msgId)
        }
    }

}
