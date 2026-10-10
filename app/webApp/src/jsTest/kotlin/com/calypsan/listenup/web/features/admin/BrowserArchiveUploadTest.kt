package com.calypsan.listenup.web.features.admin

import com.calypsan.listenup.client.diagnostics.probeArchiveUpload
import com.calypsan.listenup.web.createSqliteWorker
import io.kotest.assertions.assertSoftly
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldStartWith
import kotlin.random.Random

/**
 * The end-to-end archive upload proof: a picked Audiobookshelf backup reaches a real server from
 * the browser, streamed by the browser itself rather than copied through Ktor's JS engine — the
 * same path a backup restore takes.
 *
 * Requires a booted server (`webAuthKotest`); the server-free lane skips it by config, exactly as
 * `BlobUploadTest` does. The archive puts its database entry after a few megabytes of padding, so
 * the server only accepts it when every byte arrived.
 *
 * Named to run after `AuthArcTest` — the browser lane runs specs in class-name order — because
 * that spec must be the first to reach the fresh server. The probe waits for setup rather than
 * doing it, so a reordering fails here, by name, instead of breaking `AuthArcTest`.
 */
class BrowserArchiveUploadTest :
    FunSpec({
        val serverBooted = js("window.__LU_SERVER_URL").unsafeCast<String?>() != null

        test("a picked archive uploads to a real server through the browser's own transport")
            .config(enabled = serverBooted) {
                val probe =
                    probeArchiveUpload(
                        worker = createSqliteWorker(),
                        dbName = "archive-upload-probe-${Random.nextInt(0, Int.MAX_VALUE)}",
                        email = "probe-admin@example.invalid",
                        password = "probe-admin-password-1",
                        paddingBytes = PADDING_BYTES,
                    )

                // The whole probe in the clue: the harness forwards no browser console.
                withClue("probe = $probe") {
                    assertSoftly {
                        probe.failure shouldBe null
                        probe.boundApi shouldBe "BrowserArchiveUploadApi"
                        probe.importId.shouldNotBeNull() shouldStartWith "abs-"
                        probe.cleanedUp shouldBe true
                    }
                }
            }
    })

/** 4 MiB — several upload progress events' worth, small enough for every CI runner. */
private const val PADDING_BYTES = 4 * 1024 * 1024
