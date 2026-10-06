package com.calypsan.listenup.server.metadata

/**
 * A `Retry-After` header as whole seconds, or null when there is none or it isn't a plain non-negative
 * number. The HTTP-date form reads as unknown — none of our catalogues sends it, and the caller has its own
 * default.
 */
internal fun retryAfterSeconds(header: String?): Long? = header?.trim()?.toLongOrNull()?.takeIf { it >= 0 }
