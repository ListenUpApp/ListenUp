package com.calypsan.listenup.server.io

/**
 * Reads the process environment variable [name], or null when it is unset. The native seam for
 * `System.getenv` — JVM reads the process environment, Kotlin/Native reads it via `platform.posix.getenv`.
 */
internal expect fun readEnv(name: String): String?

/**
 * The current user's home directory (JVM `user.home` system property / native `$HOME`), or an empty
 * string when it cannot be determined. The base for the default `~/ListenUp` data home.
 */
internal expect fun userHomeDir(): String
