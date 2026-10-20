package com.calypsan.listenup.client.presentation.library

import com.calypsan.listenup.client.domain.model.ScanProgressState
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class LibraryStatusStripTest :
    FunSpec({
        val scan =
            ScanProgressState(
                phase = "analyzing",
                current = 412,
                total = 1280,
                added = 3,
                updated = 0,
                removed = 0,
                books = 412,
                booksTotal = 1280,
            )

        test("a scan with progress outranks the library arriving (spec L27/L28: scan wins)") {
            libraryStatusStrip(isServerScanning = true, scanProgress = scan, isLibraryArriving = true, booksSoFar = 412) shouldBe
                LibraryStatusStrip.Scanning(scan)
        }

        test("a scan flag without progress says nothing about the scan; the arrival still shows") {
            libraryStatusStrip(isServerScanning = true, scanProgress = null, isLibraryArriving = true, booksSoFar = 40) shouldBe
                LibraryStatusStrip.Arriving(booksSoFar = 40)
        }

        test("stale progress after a scan ended is not shown (Android's banner gate)") {
            libraryStatusStrip(isServerScanning = false, scanProgress = scan, isLibraryArriving = false, booksSoFar = 900) shouldBe
                LibraryStatusStrip.Hidden
        }

        test("an arriving library carries what Room holds so far") {
            libraryStatusStrip(isServerScanning = false, scanProgress = null, isLibraryArriving = true, booksSoFar = 0) shouldBe
                LibraryStatusStrip.Arriving(booksSoFar = 0)
        }

        test("a settled library hides the slot") {
            libraryStatusStrip(isServerScanning = false, scanProgress = null, isLibraryArriving = false, booksSoFar = 248) shouldBe
                LibraryStatusStrip.Hidden
        }
    })
