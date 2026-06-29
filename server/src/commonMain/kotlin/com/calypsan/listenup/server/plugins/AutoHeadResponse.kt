package com.calypsan.listenup.server.plugins

import io.ktor.server.application.Application

/**
 * Installs automatic `HEAD` handling for every `GET` route. JVM-only — `ktor-server-auto-head-response`
 * has no Kotlin/Native artifact, so the native actual is a no-op (HEAD requests fall through to the
 * normal handler, which is acceptable for the self-hosted clients we serve).
 */
expect fun Application.installAutoHeadResponse()
