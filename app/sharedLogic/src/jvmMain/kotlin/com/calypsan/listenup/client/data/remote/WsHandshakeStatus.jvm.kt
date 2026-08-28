package com.calypsan.listenup.client.data.remote

/** Ktor's engine here upgrades over a real HTTP response, so a rejected handshake names its status. */
internal actual val handshakeStatusIsVisible: Boolean = true
