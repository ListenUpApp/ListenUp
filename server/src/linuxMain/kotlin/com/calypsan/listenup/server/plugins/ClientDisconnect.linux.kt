package com.calypsan.listenup.server.plugins

import io.ktor.utils.io.ClosedByteChannelException

/**
 * Native actual. Matches Ktor's multiplatform closed-channel type ([ClosedByteChannelException]).
 * A genuine native I/O fault has no such type and is deliberately NOT matched — it stays a real 500
 * (conservative: never swallow a real fault as a "client left"). The match set broadens as the
 * native CIO engine's error surfaces are characterised.
 */
internal actual fun isClientDisconnect(throwable: Throwable): Boolean {
    val seen = HashSet<Throwable>()
    var current: Throwable? = throwable
    while (current != null && seen.add(current)) {
        if (current is ClosedByteChannelException) return true
        current = current.cause
    }
    return false
}
