package com.calypsan.listenup.client.data.remote

/**
 * The DOM `WebSocket` reports a rejected upgrade as a bare `error` event — no status, no message,
 * no body — so there is nothing for Ktor's JS engine to surface and nothing here to read.
 */
internal actual val handshakeStatusIsVisible: Boolean = false
