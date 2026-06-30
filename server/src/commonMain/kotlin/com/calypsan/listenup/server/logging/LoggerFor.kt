package com.calypsan.listenup.server.logging

import io.github.oshai.kotlinlogging.KLogger
import io.github.oshai.kotlinlogging.KotlinLogging

/**
 * Creates a [KLogger] named after [T]'s fully-qualified class name, resolved at compile time.
 *
 * Unlike `KotlinLogging.logger {}` — which derives the name from the enclosing symbol at runtime —
 * this reads the reified type's name from compile-time type info (on Kotlin/Native, RTTI in
 * `.rodata`). That survives `-s` symbol stripping on the native release binary, so release logs keep
 * their per-subsystem tag instead of collapsing to `[UnknownLogger]` (issue #949).
 *
 * The fully-qualified name also keeps the JVM per-package level overrides
 * (`LISTENUP_LOG_LEVEL_<pkg>`, matched by `startsWith`) working; the formatters shorten it to the
 * simple class name for display.
 */
internal inline fun <reified T : Any> loggerFor(): KLogger =
    KotlinLogging.logger(T::class.qualifiedName ?: T::class.simpleName ?: "UnknownLogger")
