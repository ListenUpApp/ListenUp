package com.calypsan.listenup.server.io

import java.net.InetAddress

internal actual fun readEnv(name: String): String? = System.getenv(name)

internal actual fun userHomeDir(): String = System.getProperty("user.home").orEmpty()

internal actual fun hostname(): String = runCatching { InetAddress.getLocalHost().hostName }.getOrDefault("")
