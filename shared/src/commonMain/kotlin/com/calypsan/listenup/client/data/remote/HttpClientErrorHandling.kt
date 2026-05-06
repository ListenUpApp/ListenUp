package com.calypsan.listenup.client.data.remote

import com.calypsan.listenup.client.core.error.AppException
import com.calypsan.listenup.client.core.error.ErrorMapper
import io.ktor.client.HttpClientConfig
import io.ktor.client.plugins.HttpResponseValidator

/**
 * Installs the canonical response-validation + error-mapping chain on an
 * [HttpClientConfig]. Must be applied to every [io.ktor.client.HttpClient] constructed
 * in this codebase — see Finding 01 D6 / rubric rule "Ktor clients must enable
 * `expectSuccess = true` and install `HttpResponseValidator`."
 *
 * Effect:
 * 1. `expectSuccess = true` — Ktor throws `ResponseException` on every non-2xx status
 *    instead of letting the body decoder see error HTML.
 * 2. `HttpResponseValidator { handleResponseExceptionWithRequest { ... } }` — every
 *    exception from the request pipeline is routed through [ErrorMapper] and re-thrown
 *    as [AppException] carrying the already-typed [com.calypsan.listenup.client.core.error.AppError].
 *
 * Call sites catch [AppException] once; the `error` property carries the categorised
 * [com.calypsan.listenup.client.core.error.AppError] with no further mapping needed.
 */
fun HttpClientConfig<*>.installListenUpErrorHandling() {
    expectSuccess = true
    HttpResponseValidator {
        handleResponseExceptionWithRequest { cause, _ ->
            if (cause is AppException) throw cause
            throw AppException(error = ErrorMapper.map(cause), cause = cause)
        }
    }
}
