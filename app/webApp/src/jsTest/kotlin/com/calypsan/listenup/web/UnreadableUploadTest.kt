package com.calypsan.listenup.web

import com.calypsan.listenup.client.diagnostics.probeUnreadableUpload
import com.calypsan.listenup.core.FileSource
import io.kotest.assertions.assertSoftly
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.khronos.webgl.Int8Array
import org.w3c.files.File
import kotlin.random.Random

private const val SLICE = 64 * 1024
private const val FILE_BYTES = 300 * 1024

/** Counts how many times the upload opens the file, so a wrongly retried file is visible. */
private class CountingSource(
    private val inner: FileSource,
) : FileSource by inner {
    var opens = 0
        private set

    override fun openChannel(): ByteReadChannel {
        opens++
        return inner.openChannel()
    }
}

/**
 * A picked file that cannot be read part-way through, uploaded through the real client stack to a
 * real server.
 *
 * What this pins: the user is told their FILE could not be read, once, rather than that the network
 * is down — three times, with back-off. A failed request body surfaces in the browser as a closed
 * channel (an `IOException`) with the real reason buried in its cause chain, and the client used to
 * read only the outer type.
 *
 * Needs a booted server, so it is gated exactly like [AuthArcTest].
 */
class UnreadableUploadTest :
    FunSpec({
        val serverBooted = js("window.__LU_SERVER_URL").unsafeCast<String?>() != null

        test("an upload whose file stops being readable fails as a file error and is not retried")
            .config(enabled = serverBooted) {
                val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
                try {
                    var slicesRead = 0
                    val bytes = ByteArray(FILE_BYTES) { (it % 251).toByte() }
                    val file = File(arrayOf(bytes.unsafeCast<Int8Array>()).unsafeCast<Array<Any>>(), "book.m4b")
                    val source =
                        CountingSource(
                            BrowserFileSource(file, scope, sliceBytes = SLICE, readSlice = { f, start, end ->
                                slicesRead++
                                // The third slice of every open cannot be read.
                                if (slicesRead % 3 == 0) null else readFileSlice(f, start, end)
                            }),
                        )

                    val probe =
                        probeUnreadableUpload(
                            worker = createSqliteWorker(),
                            dbName = "unreadable-upload-probe-${Random.nextInt(0, Int.MAX_VALUE)}",
                            email = "probe-admin@example.invalid",
                            password = "probe-admin-password-1",
                            source = source,
                        )

                    // The harness forwards only TeamCity messages from the console, so the whole probe
                    // goes in the clue, as AuthArcTest does.
                    withClue("probe = $probe, opens = ${source.opens}") {
                        assertSoftly {
                            probe.failure shouldBe null
                            probe.reachedAuthenticated shouldBe true
                            probe.errorCode shouldBe "UPLOAD_FILE_UNREADABLE"
                            probe.isRetryable shouldBe false
                            source.opens shouldBe 1
                        }
                    }
                } finally {
                    scope.cancel()
                }
            }
    })
