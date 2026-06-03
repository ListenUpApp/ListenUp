package com.calypsan.listenup.client.design.util

import java.io.File

internal actual fun fileLastModifiedMillis(path: String): Long? = File(path).takeIf { it.exists() }?.lastModified()
