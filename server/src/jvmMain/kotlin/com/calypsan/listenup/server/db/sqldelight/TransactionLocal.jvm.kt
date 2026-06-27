package com.calypsan.listenup.server.db.sqldelight

private val jvmTransactionLocal = ThreadLocal<Any?>()

internal actual fun currentTransactionLocal(): Any? = jvmTransactionLocal.get()

internal actual fun setTransactionLocal(value: Any?) {
    jvmTransactionLocal.set(value)
}
