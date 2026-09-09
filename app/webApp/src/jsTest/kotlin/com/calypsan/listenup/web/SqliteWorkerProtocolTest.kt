package com.calypsan.listenup.web

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import org.w3c.dom.Worker
import org.w3c.dom.events.Event
import kotlin.coroutines.resume
import kotlin.js.Json
import kotlin.js.json
import kotlin.random.Random

/**
 * The SQLite worker's wire protocol, spoken directly rather than through the driver.
 *
 * `WebWorkerSQLiteDriver`'s `closeStatement` / `closeDatabase` are fire-and-forget — they hold no
 * pending entry for the reply, so neither a success nor an error answer to a `close` is observable
 * from Kotlin through the driver. The only way to prove the worker actually closes what it opened
 * is to post the messages ourselves and read what comes back.
 */
class SqliteWorkerProtocolTest :
    FunSpec({

        test("the first database opened is id 0") {
            // Not a corner case: ids start at 0, so the FIRST database — the app's only one — is
            // exactly the value a truthiness check on `databaseId` would skip.
            val worker = createSqliteWorker()

            try {
                val opened = worker.request(1, openCommand())

                errorOf(opened) shouldBe null
                opened.data.databaseId.unsafeCast<Int>() shouldBe 0
            } finally {
                worker.terminate()
            }
        }

        test("closing database 0 is acknowledged") {
            val worker = createSqliteWorker()

            try {
                worker.request(1, openCommand())

                val closed = worker.request(2, json("cmd" to "close", "databaseId" to 0, "statementId" to null))

                errorOf(closed) shouldBe null
            } finally {
                worker.terminate()
            }
        }

        test("closing statement 0 finalizes it") {
            val worker = createSqliteWorker()

            try {
                worker.request(1, openCommand())
                val prepared = worker.request(2, json("cmd" to "prepare", "databaseId" to 0, "sql" to "SELECT 1"))
                prepared.data.statementId.unsafeCast<Int>() shouldBe 0

                val closed = worker.request(3, json("cmd" to "close", "statementId" to 0, "databaseId" to null))
                errorOf(closed) shouldBe null

                // The statement is gone, so stepping it must be rejected — the proof that `close`
                // finalized rather than silently skipped.
                val stepped =
                    worker.request(4, json("cmd" to "step", "statementId" to 0, "bindings" to emptyArray<Any?>()))
                errorOf(stepped).orEmpty() shouldContain "Invalid statement ID"
            } finally {
                worker.terminate()
            }
        }

        test("an unknown command is reported as an error") {
            // The control: if this passes, the request/response rig and the transport work, so a
            // failure in the close tests is the handler rather than the harness.
            val worker = createSqliteWorker()

            try {
                val answered = worker.request(1, json("cmd" to "nope"))

                errorOf(answered).orEmpty() shouldContain "unknown command"
            } finally {
                worker.terminate()
            }
        }
    })

/** An `open` command against a database name no other run can collide with — OPFS outlives the suite. */
private fun openCommand(): Json = json("cmd" to "open", "fileName" to "listenup-protocol-${Random.nextInt(0, Int.MAX_VALUE)}.db")

/** The reply's `error` string, or null when the worker answered successfully. */
private fun errorOf(reply: dynamic): String? {
    val error = reply.error
    return if (error == null || jsTypeOf(error) == "undefined") null else error.toString()
}

/**
 * Posts one protocol message and resolves with the worker's reply for that id.
 *
 * Cancellable on purpose: a handler that answers nothing is exactly the defect under test, and an
 * uncancellable suspension would hang the whole browser run instead of failing this one spec.
 */
private suspend fun Worker.request(
    id: Int,
    data: Json,
): dynamic =
    withTimeout(WORKER_TIMEOUT_MS) {
        suspendCancellableCoroutine { continuation ->
            var listener: ((Event) -> Unit)? = null
            listener = { event ->
                val reply = event.asDynamic().data
                if (reply.id.unsafeCast<Int>() == id) {
                    removeEventListener("message", listener)
                    continuation.resume(reply)
                }
            }
            addEventListener("message", listener)
            continuation.invokeOnCancellation { removeEventListener("message", listener) }
            postMessage(json("id" to id, "data" to data))
        }
    }

/** Generous: the worker compiles and boots wasm SQLite on its first message. */
private const val WORKER_TIMEOUT_MS = 10_000L
