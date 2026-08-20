package com.calypsan.listenup.server.transcode

import com.calypsan.listenup.server.process.MISSING_BINARY_EXIT_CODE
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf

/** The first two lines real ffmpeg puts on **stderr** when it runs. */
private val REAL_BANNER =
    """
    ffmpeg version n8.1.2 Copyright (c) 2000-2026 the FFmpeg developers
      built with gcc 16 (GCC)
    """.trimIndent()

/** What ffmpeg says on stderr when the requested encoder isn't in the build. */
private const val NO_ENCODER = "Error opening output files: Encoder not found"

/** ffmpeg's exit code when it cannot open the output — i.e. the encoder is missing. */
private const val FFMPEG_ENCODER_MISSING_EXIT = 8

class TranscoderProvisionerTest :
    FunSpec({

        fun provisioner(
            candidates: List<String>,
            exitCodes: Map<String, Int>,
            stderr: String = REAL_BANNER,
            record: (List<String>) -> Unit = {},
        ) = TranscoderProvisioner(
            candidatePaths = { candidates },
            runProcess = { command, onStderr ->
                record(command)
                stderr.lineSequence().forEach(onStderr)
                exitCodes[command.first()] ?: MISSING_BINARY_EXIT_CODE
            },
        )

        test("takes the first candidate that runs") {
            val status = provisioner(listOf("/a/ffmpeg", "/b/ffmpeg"), mapOf("/b/ffmpeg" to 0)).probe()

            status.shouldBeInstanceOf<TranscoderStatus.Available>().path shouldBe "/b/ffmpeg"
        }

        test("reports the version it found") {
            val status = provisioner(listOf("/a/ffmpeg"), mapOf("/a/ffmpeg" to 0)).probe()

            status.shouldBeInstanceOf<TranscoderStatus.Available>().version shouldContain "8.1.2"
        }

        // ⛔ Never-Stranded: absence degrades to direct-play with an honest marker. It is not an error.
        test("no working binary is Unavailable, not a failure") {
            val status = provisioner(listOf("/a/ffmpeg"), emptyMap()).probe()

            status.shouldBeInstanceOf<TranscoderStatus.Unavailable>().reason shouldContain "no working ffmpeg"
        }

        // A build without the AAC encoder would fail at the first segment instead of at boot, which is
        // the difference between an admin seeing a startup line and a listener seeing a stall.
        test("a binary that runs but cannot encode AAC is Unavailable") {
            val status =
                provisioner(
                    listOf("/a/ffmpeg"),
                    mapOf("/a/ffmpeg" to FFMPEG_ENCODER_MISSING_EXIT),
                    stderr = NO_ENCODER,
                ).probe()

            status.shouldBeInstanceOf<TranscoderStatus.Unavailable>().reason shouldContain "AAC"
        }

        // Measured: `/bin/true` ignores these arguments and exits 0, so exit status alone would
        // report a mis-set $LISTENUP_FFMPEG as a working transcoder — and it would fail on every
        // segment instead of at boot.
        test("something that exits 0 without being ffmpeg is Unavailable") {
            val status =
                provisioner(
                    listOf("/bin/true"),
                    mapOf("/bin/true" to 0),
                    stderr = "",
                ).probe()

            status.shouldBeInstanceOf<TranscoderStatus.Unavailable>().reason shouldContain
                "did not identify itself as ffmpeg"
        }

        // ⛔ The regression pin for why this probe is shaped the way it is. `ffmpeg -version` cannot
        // answer "can you encode AAC" — a build with a working native encoder prints no "aac" anywhere
        // in it, because the encoder is not a configure flag (measured on ffmpeg 8.1.2). Worse,
        // `-version` and `-encoders` both write to STDOUT, which ProcessRunner discards by design. So
        // the probe asks for a real AAC encode and reads the exit code, and every byte it depends on
        // arrives on stderr.
        test("the probe asks ffmpeg for a real AAC encode rather than trusting -version") {
            var command = emptyList<String>()
            provisioner(listOf("/a/ffmpeg"), mapOf("/a/ffmpeg" to 0), record = { command = it }).probe()

            command.first() shouldBe "/a/ffmpeg"
            command.joinToString(" ") shouldContain "-c:a aac"
            command.contains("-version") shouldBe false
            command.contains("-encoders") shouldBe false
        }
    })
