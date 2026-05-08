package com.calypsan.listenup.server.embeddedmeta.live

import com.calypsan.listenup.server.embeddedmeta.AudioFormatDetector
import com.calypsan.listenup.server.embeddedmeta.EmbeddedMetadataParser
import com.calypsan.listenup.server.embeddedmeta.format.mp3.Mp3Parser
import com.calypsan.listenup.server.embeddedmeta.format.mp4.Mp4Parser
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import java.nio.file.Path

/**
 * Env-gated live corpus validation harness.
 *
 * The Definition of Done for an embeddedmeta parser (spec §10.4) is "no
 * crashes against a real corpus." Synthetic-fixture tests prove the
 * parser handles formats *we know about*; only a live corpus exposes the
 * weird tags real audiobooks carry.
 *
 * **Skipped in CI** — the env var [LIVE_CORPUS_ENV] is unset on shared
 * infrastructure. Run locally:
 *
 * ```
 * export LISTENUP_EMBEDDEDMETA_LIVE_DIR=/path/to/your/corpus
 * ./gradlew :server:test --tests "com.calypsan.listenup.server.embeddedmeta.live.LiveCorpusValidationTest"
 * ```
 *
 * Look at `report.formatLine()` in the failure clue if anything trips —
 * it summarises the per-format counts plus the first ten crashes / typed
 * errors so a fix can be scoped without re-running the harness.
 */
class LiveCorpusValidationTest :
    FunSpec({
        val liveDir = System.getenv(LIVE_CORPUS_ENV)

        test("parses every MP3/MP4 audio file under \$$LIVE_CORPUS_ENV without crashing")
            .config(enabled = liveDir != null) {
                val parser =
                    EmbeddedMetadataParser(
                        detector = AudioFormatDetector(),
                        parsers = listOf(Mp3Parser(), Mp4Parser()),
                    )
                val report = LiveCorpusValidator(parser).validate(Path.of(liveDir!!))
                withClue(report.formatLine()) {
                    report.crashed.shouldBeEmpty()
                    report.totalFiles shouldBeGreaterThan 0
                    report.byFormat.values.all { it.parsed > 0 } shouldBe true
                }
            }
    })

private const val LIVE_CORPUS_ENV = "LISTENUP_EMBEDDEDMETA_LIVE_DIR"
