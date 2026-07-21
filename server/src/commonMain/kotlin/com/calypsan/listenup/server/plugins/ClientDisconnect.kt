package com.calypsan.listenup.server.plugins

/**
 * Whether [throwable] (or anything in its cause chain) is a client closing the connection
 * mid-response — a seek/skip/pause/background during audio streaming, an SSE subscriber navigating
 * away, or any aborted download — rather than a real server fault.
 *
 * The single source of truth shared by the audio/REST error handler ([installAppErrorStatusPages])
 * and the remaining SSE surfaces: a disconnect is logged at DEBUG, never dressed up as a 500. Platform actuals
 * match their engine's closed-socket families; a genuine server-side fault has none of those types
 * and is deliberately NOT matched, so it surfaces as a real error.
 */
internal expect fun isClientDisconnect(throwable: Throwable): Boolean
