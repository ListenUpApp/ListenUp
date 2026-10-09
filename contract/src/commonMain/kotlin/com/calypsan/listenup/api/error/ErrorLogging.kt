package com.calypsan.listenup.api.error

/**
 * A one-line diagnostic string for logging an [AppError] at the point it is surfaced to the user.
 *
 * Includes the server-issued [AppError.correlationId] so a user's screenshot/report ties directly to
 * the operator's server log line for the same request — the whole point of stamping domain failures
 * with a cid. Format: `[CODE] message (cid=<id>)`; the `(cid=…)` clause is omitted for purely
 * client-local errors that carry none. [AppError.message] is a user-facing constant (no PII), safe to
 * log; per-instance [AppError.debugInfo] stays a separate, lower-level log concern.
 */
public fun AppError.diagnosticLogLine(): String =
    buildString {
        append("[").append(code).append("] ").append(message)
        correlationId?.let { append(" (cid=").append(it).append(")") }
    }

/** How many characters of a correlation id a person is shown: enough to find the server log line, short to read out. */
private const val SERVER_REFERENCE_LENGTH = 8

/**
 * The short reference shown with a genuine server fault — the head of its correlation id, which the server logged
 * with the stack trace — or null for every other error. Only [InternalError] gets one: it is the error whose cause
 * lives solely in the server's log, so it is the one a person needs a way to point at.
 */
public val AppError.serverReference: String?
    get() = (this as? InternalError)?.correlationId?.takeIf { it.isNotBlank() }?.take(SERVER_REFERENCE_LENGTH)
