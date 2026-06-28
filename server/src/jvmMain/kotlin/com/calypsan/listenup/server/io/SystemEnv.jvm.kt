package com.calypsan.listenup.server.io

internal actual fun readEnv(name: String): String? = System.getenv(name)

internal actual fun userHomeDir(): String = System.getProperty("user.home").orEmpty()
