package com.calypsan.listenup.server.rpcguard

import com.calypsan.listenup.api.error.withCorrelationId
import com.calypsan.listenup.api.result.AppResult
import io.github.oshai.kotlinlogging.KLogger

/**
 * Post-processes a guarded service call's returned [AppResult] so a RETURNED domain failure carries
 * the request's correlation id — closing the gap where only the escaped-exception path stamped one.
 *
 * A service that returns `AppResult.Failure(AuthError.SessionExpired())` carries `correlationId = null`;
 * without this the "operator's log line links to the user's error" contract was vacuous on the primary
 * (RPC) transport. On a [AppResult.Failure] this:
 *  - logs a concise DEBUG line keyed by [cid] carrying only the stable [com.calypsan.listenup.api.error.AppError.code]
 *    (never `message`/`debugInfo`, which can hold per-instance/PII detail — the call already runs inside
 *    the guard's MDC scope, so any richer service-level log line also carries the cid), and
 *  - stamps [cid] onto the error when its `correlationId` is still null (a server-issued id wins if present).
 *
 * Success passes through untouched. Called from the generated `<Service>Guarded` suspend methods —
 * `public` (like the generated `guard(...)` dispatcher) so those per-target-generated classes can
 * reference it across the compilation boundary. It is a function, so it does not enter the type-only
 * Swift-export surface baseline.
 */
public fun <T> AppResult<T>.stampAndLogFailure(
    cid: String,
    log: KLogger,
    service: String,
    method: String,
): AppResult<T> {
    if (this !is AppResult.Failure) return this
    log.debug { "Domain failure in $service.$method: code=${error.code} [cid=$cid]" }
    return if (error.correlationId == null) AppResult.Failure(error.withCorrelationId(cid)) else this
}
