@file:OptIn(ExperimentalForeignApi::class)

package com.calypsan.listenup.core

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.toKString
import platform.posix.getenv

// Server (Linux): mirror the Apple actual — reads LISTENUP_DEBUG env var;
// only the literal string "true" enables debug mode; defaults to false.
actual val isDebugBuild: Boolean =
    getenv("LISTENUP_DEBUG")?.toKString() == "true"
