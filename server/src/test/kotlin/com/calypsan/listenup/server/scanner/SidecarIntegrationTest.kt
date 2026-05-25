package com.calypsan.listenup.server.scanner

import com.calypsan.listenup.api.contractJson
import com.calypsan.listenup.api.dto.scanner.ScanResult
import com.calypsan.listenup.api.event.ScanEvent
import com.calypsan.listenup.server.scanner.metadata.AbsMetadataReader
import com.calypsan.listenup.server.scanner.sidecar.DescTxtParser
import com.calypsan.listenup.server.scanner.sidecar.NfoParser
import com.calypsan.listenup.server.scanner.sidecar.OpfParser
import com.calypsan.listenup.server.scanner.sidecar.ReaderTxtParser
import com.calypsan.listenup.server.testing.testLibrary
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.runTest

/**
 * End-to-end integration test: verifies that sidecar parsers are wired into
 * the full scan pipeline (Walker → Grouper → Analyzer → Differ).
 *
 * A synthetic library with `reader.txt` and `desc.txt` sidecars is scanned;
 * the resulting [com.calypsan.listenup.api.dto.scanner.AnalyzedBook] must
 * carry the narrator names and description from the sidecar files.
 */
class SidecarIntegrationTest :
    FunSpec({

        test("full scan enriches AnalyzedBook from reader.txt and desc.txt sidecars") {
            runTest {
                audioLibrary {
                    book("Brandon Sanderson/The Way of Kings") {
                        audio("01 - Track.mp3")
                        text("reader.txt", "Michael Kramer\nKate Reading\n")
                        text("desc.txt", "An epic fantasy about honor and storms.")
                    }
                }.use { fixture ->
                    val scanner = newScannerWithSidecars(fixture)

                    val result = scanner.runFullScan()

                    result.books.size shouldBe 1
                    val analyzed = result.books.single()
                    analyzed.narrators shouldContain "Michael Kramer"
                    analyzed.narrators shouldContain "Kate Reading"
                    analyzed.description shouldBe "An epic fantasy about honor and storms."
                }
            }
        }
    })

private fun newScannerWithSidecars(fixture: AudioLibraryFixture): Scanner {
    val eventBus = MutableSharedFlow<ScanEvent>(replay = 64, extraBufferCapacity = 64)
    return Scanner(
        library = testLibrary(folders = listOf(fixture.root.toString())),
        metadataReader = AbsMetadataReader(contractJson),
        embeddedMetadataParser =
            com.calypsan.listenup.server.embeddedmeta.EmbeddedMetadataParser(
                detector =
                    com.calypsan.listenup.server.embeddedmeta
                        .AudioFormatDetector(),
                parsers = emptyList(),
            ),
        eventBus = eventBus,
        scanResultBus = MutableSharedFlow<ScanResult>(replay = 1),
        sidecarParsers = listOf(NfoParser(), OpfParser(), ReaderTxtParser(), DescTxtParser()),
    )
}
