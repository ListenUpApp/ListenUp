package com.calypsan.listenup.server.di

import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.engine.curl.Curl

/**
 * Kotlin/Native (Linux) metadata client: the Curl engine (libcurl). Ktor's CIO engine does not support
 * outbound TLS on Kotlin/Native, so HTTPS calls to Audible/iTunes failed and were mapped to
 * `ExternalUnavailable`. Curl delegates TLS to libcurl, which the native runtime image ships alongside a
 * CA trust store. Both linuxX64 and linuxArm64 share this actual (the `ktor-client-curl` artifact and
 * libcurl are available on both arches).
 */
internal actual fun metadataHttpClient(configure: HttpClientConfig<*>.() -> Unit): HttpClient =
    HttpClient(Curl, configure)
