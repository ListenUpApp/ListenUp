package com.calypsan.listenup.web.logging

import kotlinx.browser.window
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/** Where [RecentLogBuffer] keeps its lines between page loads. */
internal interface RecentLogStore {
    /** The value last written, or null when there is none. May throw. */
    fun read(): String?

    /** Replaces the stored value. May throw — storage can be full, blocked or gone. */
    fun write(value: String)
}

/**
 * [RecentLogStore] over `window.localStorage`.
 *
 * ⛔ Every access is guarded. Storage can be disabled outright (the accessor itself throws a
 * SecurityError), full (`setItem` throws QuotaExceededError), or partitioned away in a private
 * window — and logging is the last thing that may fail because of it. A blocked store reads as
 * empty and drops writes; the in-memory buffer carries on regardless.
 */
internal class LocalStorageLogStore(
    private val key: String = STORAGE_KEY,
) : RecentLogStore {
    override fun read(): String? =
        try {
            window.localStorage.getItem(key)
        } catch (_: Throwable) {
            null
        }

    override fun write(value: String) {
        try {
            window.localStorage.setItem(key, value)
        } catch (_: Throwable) {
            // Full or blocked: the lines stay in memory for this page's life, which is the most
            // a browser that will not store them allows.
        }
    }

    private companion object {
        const val STORAGE_KEY = "listenup.recent-logs"
    }
}

/**
 * The web client's log file: the most recent lines, newest last, capped at [maxChars] and kept in a
 * [RecentLogStore] so they survive a reload — which is often the first thing someone does when the
 * app misbehaves, and exactly when the lines explaining it matter.
 *
 * Writes are debounced through [scheduleFlush] rather than made per line: re-serialising up to a
 * megabyte on every log call would make logging the slowest thing on the page. The window that
 * costs is closed by flushing on `pagehide` (see [installRecentLogCapture]).
 *
 * Nothing here throws: a store that fails is the store's problem, never the caller's.
 */
internal class RecentLogBuffer(
    private val store: RecentLogStore,
    private val maxChars: Int = DEFAULT_MAX_CHARS,
    private val scheduleFlush: (task: () -> Unit) -> Unit,
) {
    private val lines = ArrayDeque<String>()
    private var totalChars = 0
    private var flushScheduled = false

    init {
        restore().forEach(::keep)
    }

    /** Keeps [line] as the newest, dropping the oldest lines past the cap, and schedules a flush. */
    fun append(line: String) {
        keep(line)
        if (!flushScheduled) {
            flushScheduled = true
            scheduleFlush(::flush)
        }
    }

    /** Every kept line, oldest first. */
    fun snapshot(): List<String> = lines.toList()

    /** Every kept line as one text, oldest first — what a download contains. */
    fun text(): String = lines.joinToString("\n")

    /** Writes the kept lines to the store now. Safe to call at any time, including from `pagehide`. */
    fun flush() {
        flushScheduled = false
        try {
            store.write(Json.encodeToString(lineListSerializer, lines.toList()))
        } catch (_: Throwable) {
            // Best-effort persistence; the in-memory lines are unaffected.
        }
    }

    private fun keep(line: String) {
        // One pathological line must not evict the whole history, or exceed the cap by itself.
        val bounded = if (line.length > maxChars) line.take(maxChars) else line
        lines.addLast(bounded)
        totalChars += bounded.length
        while (totalChars > maxChars) {
            totalChars -= lines.removeFirst().length
        }
    }

    private fun restore(): List<String> =
        try {
            store.read()?.let { stored -> Json.decodeFromString(lineListSerializer, stored) }.orEmpty()
        } catch (_: Throwable) {
            // Unreadable — blocked storage, or a value from some other build. Start afresh.
            emptyList()
        }

    private companion object {
        /** ~1 MB of text: hours of ordinary logging, well inside every browser's storage quota. */
        const val DEFAULT_MAX_CHARS = 1_000_000

        val lineListSerializer = ListSerializer(String.serializer())
    }
}
