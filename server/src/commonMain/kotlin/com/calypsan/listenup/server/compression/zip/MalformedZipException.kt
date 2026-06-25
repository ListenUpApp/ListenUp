package com.calypsan.listenup.server.compression.zip

/** Thrown when a ZIP archive is malformed, truncated, or uses an unsupported feature. */
public class MalformedZipException(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)
