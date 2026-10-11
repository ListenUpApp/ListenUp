package com.calypsan.listenup.web

import com.calypsan.listenup.client.diagnostics.probeUnreadableUpload
import com.calypsan.listenup.core.FileSource
import com.calypsan.listenup.core.FileUnreadableException
import io.kotest.assertions.assertSoftly
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.writeFully
import io.ktor.utils.io.writer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlin.random.Random

private const val SLICE = 64 * 1024
private const val FILE_BYTES = 300 * 1024

/**
 * A file whose read fails part-way through every time it is opened: two slices arrive, then the
 * channel closes with a [FileUnreadableException]. Counts its opens, so a wrongly retried file is
 * visible.
 */
private class UnreadablePartWaySource(
    private val scope: CoroutineScope,
) : FileSource {
    var opens = 0
        private set

    override val filename: String = "book.m4b"

    override val size: Long = FILE_BYTES.toLong()

    override fun openChannel(): ByteReadChannel {
        opens++
        return scope
            .writer {
                repeat(2) {
                    channel.writeFully(ByteArray(SLICE) { (it % 251).toByte() })
                    channel.flush()
                }
                throw FileUnreadableException("Could not read $filename")
            }.channel
    }
}

/**
 * A file that cannot be read part-way through, uploaded through the real client stack to a real
 * server.
 *
 * What this pins: the user is told their FILE could not be read, once, rather than that the network
 * is down — three times, with back-off. A failed request body surfaces in the browser as a closed
 * channel (an `IOException`) with the real reason buried in its cause chain, and the client used to
 * read only the outer type.
 *
 * The source here is a channel-backed one, so it takes the upload API's Ktor path. A picked browser
 * file goes through `XhrUploadTransport` instead, whose own unreadable-file verdict is pinned in
 * `XhrUploadTransportTest` — a real picked file cannot be made unreadable from inside a page.
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
                    val source = UnreadablePartWaySource(scope)

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
