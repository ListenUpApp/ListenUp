package com.calypsan.listenup.client.data.remote

import io.ktor.client.HttpClientConfig

/**
 * Configures every Ktor `HttpClient` with canonical response-validation behaviour.
 * Sets `expectSuccess = true` so non-2xx responses raise `ResponseException` instead
 * of leaking error bodies through the success-decoder path.
 *
 * Typed error mapping happens at the API method boundary — each `apiCall { ... }`
 * wraps its request in `suspendRunCatching`, catches the Ktor exception, and routes
 * it through [com.calypsan.listenup.client.core.error.ErrorMapper] to produce an
 * `AppResult.Failure(typedError)`.
 *
 * (Ktor clients must enable `expectSuccess = true`.)
 */
internal fun HttpClientConfig<*>.installListenUpErrorHandling() {
    expectSuccess = true
}
