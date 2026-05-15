@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.calypsan.listenup.server.scanner

import com.calypsan.listenup.api.contractJson
import com.calypsan.listenup.api.dto.scanner.ScanResult
import com.calypsan.listenup.api.dto.scanner.ScanResultSummary
import com.calypsan.listenup.api.error.ScanError
import com.calypsan.listenup.api.event.ScanEvent
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.server.scanner.metadata.AbsMetadataReader
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest

class ScannerServiceImplTest :
    FunSpec({

        test("scanFull returns Success with a summary built from the latest result") {
            runTest {
                audioLibrary {
                    book("Author/Title") { tracks(count = 2) }
                }.use { fixture ->
                    val service = newService(fixture, scope = this)
                    val result = service.scanFull()
                    val success = result.shouldBeInstanceOf<AppResult.Success<ScanResultSummary>>()
                    success.data.totalBooks shouldBe 1
                    success.data.added shouldBe 1
                    success.data.errors shouldBe 0
                }
            }
        }

        test("lastScanResult returns LibraryPathNotConfigured before any scan has run") {
            runTest {
                audioLibrary {}.use { fixture ->
                    val service = newService(fixture, scope = this)
                    val result = service.lastScanResult()
                    val failure = result.shouldBeInstanceOf<AppResult.Failure>()
                    failure.error.shouldBeInstanceOf<ScanError.LibraryPathNotConfigured>()
                }
            }
        }

        test("lastScanResult returns Success after a scan completes") {
            runTest {
                audioLibrary {
                    book("Author/Title") { tracks(count = 1) }
                }.use { fixture ->
                    val service = newService(fixture, scope = this)
                    service.scanFull()

                    val result = service.lastScanResult()
                    val success = result.shouldBeInstanceOf<AppResult.Success<ScanResult>>()
                    success.data.books.size shouldBe 1
                }
            }
        }
    })

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
private fun newService(
    fixture: AudioLibraryFixture,
    scope: TestScope,
): ScannerServiceImpl {
    val eventBus = MutableSharedFlow<ScanEvent>(replay = 0, extraBufferCapacity = 64)
    val scanner =
        Scanner(
            rootPath = fixture.root,
            metadataReader = AbsMetadataReader(contractJson),
            embeddedMetadataParser =
                com.calypsan.listenup.server.embeddedmeta.EmbeddedMetadataParser(
                    detector =
                        com.calypsan.listenup.server.embeddedmeta
                            .AudioFormatDetector(),
                    parsers = emptyList(),
                ),
            eventBus = eventBus,
            scanResultBus = MutableSharedFlow(replay = 1),
        )
    val coordinator =
        ScanCoordinator(
            runFullScan = { scanner.runFullScan() },
            runIncremental = { scanner.runIncremental(it) },
            scope = scope.backgroundScope,
        )
    return ScannerServiceImpl(scanner, coordinator, eventBus)
}
