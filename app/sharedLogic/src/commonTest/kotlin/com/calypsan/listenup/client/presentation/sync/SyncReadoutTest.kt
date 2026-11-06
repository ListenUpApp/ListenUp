package com.calypsan.listenup.client.presentation.sync

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

private const val NOW = 10_000_000_000L
private const val MINUTE = 60_000L

class SyncReadoutTest :
    FunSpec({
        fun readout(
            lastSyncedAtMs: Long? = null,
            isSyncing: Boolean = false,
            refreshFailed: Boolean = false,
        ): SyncReadout =
            syncReadoutOf(
                SyncIndicatorUiState(isSyncing = isSyncing, lastSyncedAtMs = lastSyncedAtMs, refreshFailed = refreshFailed),
                NOW,
            )

        test("nothing synced yet") {
            readout() shouldBe SyncReadout.NotSyncedYet
        }

        test("under a minute is just now") {
            readout(lastSyncedAtMs = NOW - 59_999L) shouldBe SyncReadout.SyncedJustNow
        }

        test("a clock behind the last sync reads as just now, not a negative span") {
            readout(lastSyncedAtMs = NOW + 5 * MINUTE) shouldBe SyncReadout.SyncedJustNow
        }

        test("a minute to under an hour reads in minutes") {
            readout(lastSyncedAtMs = NOW - MINUTE) shouldBe SyncReadout.SyncedMinutesAgo(1)
            readout(lastSyncedAtMs = NOW - 59 * MINUTE) shouldBe SyncReadout.SyncedMinutesAgo(59)
        }

        test("an hour to under a day reads in hours") {
            readout(lastSyncedAtMs = NOW - 60 * MINUTE) shouldBe SyncReadout.SyncedHoursAgo(1)
            readout(lastSyncedAtMs = NOW - 185 * MINUTE) shouldBe SyncReadout.SyncedHoursAgo(3)
            readout(lastSyncedAtMs = NOW - 1_439 * MINUTE) shouldBe SyncReadout.SyncedHoursAgo(23)
        }

        test("a day or more reads in days") {
            readout(lastSyncedAtMs = NOW - 1_440 * MINUTE) shouldBe SyncReadout.SyncedDaysAgo(1)
            readout(lastSyncedAtMs = NOW - 3 * 1_440 * MINUTE) shouldBe SyncReadout.SyncedDaysAgo(3)
        }

        test("a running sync says so over the last good one") {
            readout(lastSyncedAtMs = NOW, isSyncing = true) shouldBe SyncReadout.Syncing
        }

        test("a failed refresh is said until the next one, over a recent good sync") {
            readout(lastSyncedAtMs = NOW - 20_000L, refreshFailed = true) shouldBe SyncReadout.CouldNotSync
        }
    })
