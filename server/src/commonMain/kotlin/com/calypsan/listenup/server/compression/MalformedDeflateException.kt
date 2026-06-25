package com.calypsan.listenup.server.compression

/** Thrown when a DEFLATE stream is truncated, malformed, or violates RFC 1951. */
class MalformedDeflateException(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)
