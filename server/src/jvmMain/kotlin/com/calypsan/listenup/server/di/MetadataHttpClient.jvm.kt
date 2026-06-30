package com.calypsan.listenup.server.di

import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.engine.cio.CIO

/** JVM metadata client: the CIO engine, whose TLS is fully supported on the JVM. */
internal actual fun metadataHttpClient(configure: HttpClientConfig<*>.() -> Unit): HttpClient =
    HttpClient(CIO, configure)
