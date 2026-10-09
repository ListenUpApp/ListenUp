package com.calypsan.listenup.server.plugins

/** The health probe's path: polled every few seconds, so its requests are left out of the access log. */
private const val HEALTH_PATH = "/healthz"

/**
 * Whether a request to [path] is left out of the access log. Health probes arrive every few seconds from Docker and
 * monitors; logged, they were most of the log and buried every request a person made.
 */
internal fun isQuietRequestPath(path: String): Boolean = path == HEALTH_PATH
