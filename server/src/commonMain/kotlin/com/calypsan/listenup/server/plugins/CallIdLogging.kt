package com.calypsan.listenup.server.plugins

import io.ktor.server.application.Application

/**
 * Installs per-request access logging (method, path, status, duration) keyed to the correlation id
 * from [installCallId]. The JVM actual uses Ktor's `CallLogging` plugin and binds the id into the
 * SLF4J MDC; `ktor-server-call-logging` has no native artifact, so the native actual reproduces the
 * essentials with a pipeline interceptor and inlines the id (native has no MDC).
 */
expect fun Application.installCallLogging()
