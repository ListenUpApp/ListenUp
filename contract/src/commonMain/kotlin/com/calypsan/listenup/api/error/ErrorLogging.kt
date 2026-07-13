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
