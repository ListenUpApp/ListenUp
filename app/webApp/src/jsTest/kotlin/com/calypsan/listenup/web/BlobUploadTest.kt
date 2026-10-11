package com.calypsan.listenup.web

import com.calypsan.listenup.client.diagnostics.probeBlobUpload
import io.kotest.assertions.assertSoftly
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlin.random.Random

/**
 * THE end-to-end upload proof: a picked file reaches a real server from the browser, streamed by the
 * browser itself rather than copied through Ktor's JS engine.
 *
 * Requires a booted server (`webAuthKotest`); the server-free lane skips it by config, exactly as
 * [LibrarySyncTest] does. A few megabytes rather than an audiobook: the size that broke Ktor is a
 * browser allocation limit, and a spec cannot ask CI for gigabytes — what this proves is that the
 * server accepts the request the browser transport actually sends.
 */
class BlobUploadTest :
    FunSpec({
        val serverBooted = js("window.__LU_SERVER_URL").unsafeCast<String?>() != null

        test("a picked file uploads to a real server through the browser's own transport")
            .config(enabled = serverBooted) {
                val probe =
                    probeBlobUpload(
                        worker = createSqliteWorker(),
                        dbName = "blob-upload-probe-${Random.nextInt(0, Int.MAX_VALUE)}",
                        email = "probe-admin@example.invalid",
                        password = "probe-admin-password-1",
                        byteCount = PROBE_BYTES,
                    )

                // The whole probe in the clue: the harness forwards no browser console.
                withClue("probe = $probe") {
                    assertSoftly {
                        probe.failure shouldBe null
                        probe.boundApi shouldBe "BrowserUploadApi"
                        probe.stagedFiles shouldBe 1
                        probe.stagedBytes shouldBe PROBE_BYTES.toLong()
                        // Progress counts the multipart body, so it ends at or past the file itself.
                        (probe.lastReportedBytes >= PROBE_BYTES) shouldBe true
                    }
                }
            }
    })

/** 4 MiB — several upload progress events' worth, small enough for every CI runner. */
private const val PROBE_BYTES = 4 * 1024 * 1024
