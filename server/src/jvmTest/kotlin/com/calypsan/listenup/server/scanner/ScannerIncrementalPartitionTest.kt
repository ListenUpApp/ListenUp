@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.calypsan.listenup.server.scanner

import com.calypsan.listenup.api.contractJson
import com.calypsan.listenup.api.dto.scanner.ChangeEventDto
import com.calypsan.listenup.api.dto.scanner.ScanResult
import com.calypsan.listenup.api.event.ScanEvent
import com.calypsan.listenup.server.embeddedmeta.AudioFormatDetector
import com.calypsan.listenup.server.embeddedmeta.EmbeddedMetadataParser
import com.calypsan.listenup.server.scanner.metadata.AbsMetadataReader
import com.calypsan.listenup.server.testing.testLibrary
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.runTest
import kotlinx.io.files.Path

/**
 * Data-integrity regression coverage for finding A2: a multi-folder incremental scan mass-tombstones
 * books in OTHER folders.
 *
 * `Scanner.partitionBooksUnder` split the previous snapshot (which spans EVERY folder, each
 * `rootRelPath` relative to its OWN folder) by string prefix alone. When the incremental's bookRoot
 * equals its folder root, the prefix is empty and the predicate matched every book in the whole
 * library — so every OTHER folder's book became "affected", went unmatched against the single-folder
 * walk, and the Differ emitted `Removed` for it. The persister then tombstoned those books for real.
 *
 * The fix additionally filters on the book's stamped `folderRootPath`, so a book in a different folder
 * is never dragged into another subtree's diff.
 */
class ScannerIncrementalPartitionTest :
    FunSpec({

        fun noOpParser(): EmbeddedMetadataParser = EmbeddedMetadataParser(detector = AudioFormatDetector(), parsers = emptyList())

        test("an incremental scan under folder A's root does not mark folder B's books as Removed (empty-prefix case)") {
            runTest {
                audioLibrary("listenup-a2-folder-a-").use { folderA ->
                    audioLibrary("listenup-a2-folder-b-").use { folderB ->
                        folderA.book("Author A/Book A") { tracks(count = 1) }
                        folderB.book("Author B/Book B") { tracks(count = 1) }

                        val bus = MutableSharedFlow<ScanResult>(replay = 1)
                        val scanner =
                            Scanner(
                                library =
                                    testLibrary(
                                        folders = listOf(folderA.root.toString(), folderB.root.toString()),
                                    ),
                                metadataReader = AbsMetadataReader(contractJson),
                                embeddedMetadataParser = noOpParser(),
                                eventBus = MutableSharedFlow<ScanEvent>(replay = 64, extraBufferCapacity = 64),
                                scanResultBus = bus,
                                correlationIdFactory = { "a2-corr" },
                            )

                        // Full scan populates lastResult with BOTH folders' books, each stamped with its
                        // own folderRootPath.
                        scanner.runFullScan()

                        // Incremental over folder A's ROOT — bookRoot == folderRoot, so the buggy
                        // prefix-only partition treated every book (including folder B's) as affected.
                        scanner.runIncremental(Path(folderA.root.toString()))

                        val incremental = bus.replayCache.last()
                        val removed =
                            incremental.changes
                                .filterIsInstance<ChangeEventDto.Removed>()
                                .map { it.rootRelPath }

                        // Folder B's book must NOT be tombstoned by a folder-A incremental.
                        removed shouldBe emptyList()
                    }
                }
            }
        }

        test("folder B's book survives in the patched snapshot after a folder-A incremental") {
            runTest {
                audioLibrary("listenup-a2-keep-a-").use { folderA ->
                    audioLibrary("listenup-a2-keep-b-").use { folderB ->
                        folderA.book("Author A/Book A") { tracks(count = 1) }
                        folderB.book("Author B/Book B") { tracks(count = 1) }

                        val bus = MutableSharedFlow<ScanResult>(replay = 2)
                        val scanner =
                            Scanner(
                                library =
                                    testLibrary(
                                        folders = listOf(folderA.root.toString(), folderB.root.toString()),
                                    ),
                                metadataReader = AbsMetadataReader(contractJson),
                                embeddedMetadataParser = noOpParser(),
                                eventBus = MutableSharedFlow<ScanEvent>(replay = 64, extraBufferCapacity = 64),
                                scanResultBus = bus,
                                correlationIdFactory = { "a2-keep-corr" },
                            )

                        scanner.runFullScan()
                        scanner.runIncremental(Path(folderA.root.toString()))

                        // The incremental result carries no Removed changes at all …
                        bus.replayCache
                            .last()
                            .changes
                            .filterIsInstance<ChangeEventDto.Removed>()
                            .shouldBeEmpty()
                    }
                }
            }
        }
    })
