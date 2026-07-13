@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.calypsan.listenup.server.scanner

import com.calypsan.listenup.api.contractJson
import com.calypsan.listenup.api.dto.scanner.ScanResult
import com.calypsan.listenup.api.error.ScanError
import com.calypsan.listenup.api.event.ScanEvent
import com.calypsan.listenup.server.embeddedmeta.AudioFormatDetector
import com.calypsan.listenup.server.embeddedmeta.EmbeddedMetadataParser
import com.calypsan.listenup.server.scanner.metadata.AbsMetadataReader
import com.calypsan.listenup.server.testing.testLibrary
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.runTest

/**
 * Data-integrity regression coverage for finding A1: an unreachable or unreadable configured folder
 * root (a dropped NAS/SMB mount, a permission change) makes [com.calypsan.listenup.server.scanner.pipeline.Walker]
 * yield an empty walk SILENTLY. If the resulting full scan were treated as authoritative, the
 * tombstone sweep would soft-delete every live book under that folder.
 *
 * The Scanner must detect an unreachable root before walking, surface a warning, skip that folder,
 * and mark the whole full scan NON-AUTHORITATIVE so `BookPersister` skips the sweep. A reachable
 * (even genuinely empty) root stays authoritative — an emptied library is a legitimate sweep.
 */
class ScannerUnreachableRootTest :
    FunSpec({

        fun noOpParser(): EmbeddedMetadataParser = EmbeddedMetadataParser(detector = AudioFormatDetector(), parsers = emptyList())

        test("a full scan over an unreachable folder root skips it, surfaces a warning, and is non-authoritative") {
            runTest {
                audioLibrary("listenup-a1-reachable-").use { reachable ->
                    reachable.book("Author/Book") { tracks(count = 1) }
                    // A configured root that does not exist on disk — the dropped-mount / bad-path case.
                    val missingRoot = reachable.root.resolve("this-root-was-unmounted").toString()

                    val scanner =
                        Scanner(
                            library = testLibrary(folders = listOf(reachable.root.toString(), missingRoot)),
                            metadataReader = AbsMetadataReader(contractJson),
                            embeddedMetadataParser = noOpParser(),
                            eventBus = MutableSharedFlow(replay = 64, extraBufferCapacity = 64),
                            scanResultBus = MutableSharedFlow<ScanResult>(replay = 1),
                            correlationIdFactory = { "a1-corr" },
                        )

                    val result = scanner.runFullScan()

                    // The reachable folder's book is still scanned.
                    result.books.map { it.candidate.rootRelPath } shouldContainExactly listOf("Author/Book")

                    // The unreachable root is surfaced as a typed warning naming that exact path
                    // (honest over silent) — not swallowed.
                    val err = result.errors.filterIsInstance<ScanError.LibraryPathNotFound>().single()
                    err.path shouldBe missingRoot

                    // The full scan is NON-AUTHORITATIVE, so BookPersister must skip the tombstone sweep
                    // (proven separately in BookPersisterTest). This is the data-loss guard.
                    result.fullScanAuthoritative shouldBe false
                }
            }
        }

        test("a full scan over a reachable but empty root stays authoritative (an emptied library is a legitimate sweep)") {
            runTest {
                audioLibrary("listenup-a1-empty-").use { empty ->
                    // No books written — the folder is present and readable but contains nothing.
                    val scanner =
                        Scanner(
                            library = testLibrary(folders = listOf(empty.root.toString())),
                            metadataReader = AbsMetadataReader(contractJson),
                            embeddedMetadataParser = noOpParser(),
                            eventBus = MutableSharedFlow(replay = 64, extraBufferCapacity = 64),
                            scanResultBus = MutableSharedFlow<ScanResult>(replay = 1),
                            correlationIdFactory = { "a1-empty-corr" },
                        )

                    val result = scanner.runFullScan()

                    result.books.shouldBeEmpty()
                    // A reachable root — the sweep is safe; the scan stays authoritative.
                    result.fullScanAuthoritative shouldBe true
                }
            }
        }
    })
