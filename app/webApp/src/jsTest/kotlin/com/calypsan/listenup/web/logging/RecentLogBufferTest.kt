package com.calypsan.listenup.web.logging

import io.github.oshai.kotlinlogging.Appender
import io.github.oshai.kotlinlogging.KLoggingEvent
import io.github.oshai.kotlinlogging.Level
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldStartWith
import kotlinx.browser.window
import kotlinx.datetime.TimeZone
import kotlin.time.Instant

/** A store that keeps one value in memory and counts the writes it receives. */
private class MemoryLogStore(
    var value: String? = null,
) : RecentLogStore {
    var writes = 0

    override fun read(): String? = value

    override fun write(value: String) {
        writes++
        this.value = value
    }
}

/** Storage the browser has blocked: every access throws, as a disabled-storage SecurityError does. */
private class BlockedLogStore : RecentLogStore {
    override fun read(): String? = error("storage blocked")

    override fun write(value: String): Unit = error("storage blocked")
}

/** Stands in for `window.setTimeout`: holds scheduled flushes until the spec runs them. */
private class ManualScheduler {
    private val pending = mutableListOf<() -> Unit>()

    val scheduled: Int get() = pending.size

    fun schedule(task: () -> Unit) {
        pending += task
    }

    fun runPending() {
        val tasks = pending.toList()
        pending.clear()
        tasks.forEach { it() }
    }
}

/**
 * The web client's recent-log buffer — what Settings → Download logs hands over.
 *
 * A browser has no log file, so the last ~1 MB of lines lives in `localStorage`: it has to survive
 * a reload (the reload is often the user's first reaction to the bug), stay bounded, and never let a
 * blocked or full storage take logging down with it.
 */
class RecentLogBufferTest :
    FunSpec({
        fun buffer(
            store: RecentLogStore = MemoryLogStore(),
            maxChars: Int = 1_000,
            scheduler: ManualScheduler = ManualScheduler(),
        ) = RecentLogBuffer(store = store, maxChars = maxChars, scheduleFlush = scheduler::schedule)

        test("lines come back in the order they were logged") {
            val logs = buffer()

            listOf("first", "second", "third").forEach(logs::append)

            logs.snapshot() shouldBe listOf("first", "second", "third")
            logs.text() shouldBe "first\nsecond\nthird"
        }

        test("past the cap the oldest lines are dropped, never the newest") {
            val logs = buffer(maxChars = 30)

            (0..9).forEach { logs.append("line-$it---") } // nine chars each: three fit in thirty

            logs.snapshot() shouldBe (7..9).map { "line-$it---" }
        }

        test("a single line larger than the whole cap is cut down rather than evicting everything") {
            val logs = buffer(maxChars = 20)

            logs.append("x".repeat(100))

            logs.snapshot() shouldHaveSize 1
            logs.snapshot().single().length shouldBe 20
        }

        test("storage is written once per burst, not once per line") {
            val store = MemoryLogStore()
            val scheduler = ManualScheduler()
            val logs = buffer(store = store, scheduler = scheduler)

            repeat(50) { logs.append("line $it") }

            scheduler.scheduled shouldBe 1
            store.writes shouldBe 0

            scheduler.runPending()
            store.writes shouldBe 1

            logs.append("after the flush")
            scheduler.scheduled shouldBe 1
        }

        test("the lines survive the page being re-created over the same storage") {
            val store = MemoryLogStore()
            val scheduler = ManualScheduler()
            val before = buffer(store = store, scheduler = scheduler)
            listOf("before reload 1", "before reload 2").forEach(before::append)
            scheduler.runPending()

            val after = buffer(store = store)

            after.snapshot() shouldBe listOf("before reload 1", "before reload 2")
        }

        test("restored lines still respect the cap") {
            val store = MemoryLogStore()
            val scheduler = ManualScheduler()
            val wide = buffer(store = store, maxChars = 1_000, scheduler = scheduler)
            (0..9).forEach { wide.append("line-$it---") }
            scheduler.runPending()

            val narrow = buffer(store = store, maxChars = 30)

            narrow.snapshot() shouldBe (7..9).map { "line-$it---" }
        }

        test("blocked storage never breaks logging") {
            val scheduler = ManualScheduler()
            val logs = buffer(store = BlockedLogStore(), scheduler = scheduler)

            logs.append("still logged")
            scheduler.runPending()
            logs.flush()

            logs.snapshot() shouldBe listOf("still logged")
        }

        test("an unreadable stored value starts an empty buffer instead of failing") {
            val logs = buffer(store = MemoryLogStore(value = "{not json"))

            logs.snapshot().shouldBeEmpty()
        }

        test("the localStorage store round-trips through the real browser storage") {
            val key = "listenup.test.recent-logs"
            try {
                val store = LocalStorageLogStore(key)
                store.write("[\"hello\"]")

                LocalStorageLogStore(key).read() shouldBe "[\"hello\"]"
            } finally {
                window.localStorage.removeItem(key)
            }
        }

        test("the appender prints to the console as before and keeps the line with its stack trace") {
            val printed = mutableListOf<KLoggingEvent>()
            val console =
                object : Appender {
                    override fun log(loggingEvent: KLoggingEvent) {
                        printed += loggingEvent
                    }
                }
            val logs = buffer()
            val failure = IllegalStateException("socket closed")
            val event =
                KLoggingEvent(
                    level = Level.ERROR,
                    marker = null,
                    loggerName = "com.calypsan.listenup.Sync",
                    message = "sync failed",
                    cause = failure,
                )

            RecentLogAppender(console = console, buffer = logs).log(event)

            printed shouldBe listOf(event)
            val line = logs.snapshot().single()
            line shouldContain "ERROR com.calypsan.listenup.Sync - sync failed"
            line shouldContain "IllegalStateException"
            line shouldContain "socket closed"
        }

        test("the console formatter appends the stack trace a plain formatter drops") {
            val failure = IllegalStateException("socket closed")
            val event =
                KLoggingEvent(level = Level.ERROR, marker = null, loggerName = "x", message = "failed", cause = failure)

            val formatted = StackTraceFormatter.formatMessage(event)

            formatted shouldContain "failed"
            formatted shouldContain failure.stackTraceToString().lines().first()
        }

        test("the download is named for the moment it was taken") {
            val name = recentLogsFilename(Instant.parse("2026-10-09T16:32:05Z"), TimeZone.UTC)

            name shouldBe "listenup-logs-2026-10-09T16-32-05.log"
            name shouldStartWith "listenup-logs-"
        }
    })
