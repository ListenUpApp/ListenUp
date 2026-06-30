package com.calypsan.listenup.server.di

import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig

/**
 * Builds the dedicated metadata [HttpClient] for outbound third-party API calls (Audible / iTunes /
 * image downloads), applying [configure] on top of the per-platform HTTP engine.
 *
 * The engine is a native seam because outbound HTTPS differs by target:
 *  - **JVM** uses the CIO engine.
 *  - **Kotlin/Native (Linux)** uses the Curl engine (libcurl). Ktor's CIO engine does not support
 *    outbound TLS on Kotlin/Native, so every HTTPS request to Audible/iTunes threw and was mapped to
 *    `ExternalUnavailable`. The Curl engine delegates TLS to libcurl, which the native runtime image
 *    ships alongside a CA trust store.
 */
internal expect fun metadataHttpClient(configure: HttpClientConfig<*>.() -> Unit): HttpClient
