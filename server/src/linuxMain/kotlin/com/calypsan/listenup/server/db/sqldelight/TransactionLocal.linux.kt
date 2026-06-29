package com.calypsan.listenup.server.db.sqldelight

import kotlin.native.concurrent.ThreadLocal

@ThreadLocal
private var nativeTransactionLocal: Any? = null

internal actual fun currentTransactionLocal(): Any? = nativeTransactionLocal

internal actual fun setTransactionLocal(value: Any?) {
    nativeTransactionLocal = value
}
