import Foundation
import Testing
@preconcurrency import Shared
@testable import ListenUp

/// The sync readout's wording over the shared `SyncReadout` rule, and what a failed refresh does to the indicator.
@MainActor
@Suite("Sync readout words")
struct SyncReadoutWordsTests {
    @Test func eachReadoutIsWordedFromTheCatalog() {
        #expect(SyncReadoutWords.words(SyncReadoutSyncing.shared) == String(localized: "shell.syncing"))
        #expect(SyncReadoutWords.words(SyncReadoutCouldNotSync.shared) == "Couldn't sync")
        #expect(SyncReadoutWords.words(SyncReadoutNotSyncedYet.shared) == "Not synced yet")
        #expect(SyncReadoutWords.words(SyncReadoutSyncedJustNow.shared) == "Synced just now")
        #expect(SyncReadoutWords.words(SyncReadoutSyncedMinutesAgo(count: 2)) == "Synced 2 min ago")
        #expect(SyncReadoutWords.words(SyncReadoutSyncedHoursAgo(count: 3)) == "Synced 3 h ago")
        #expect(SyncReadoutWords.words(SyncReadoutSyncedDaysAgo(count: 4)) == "Synced 4 d ago")
    }

    /// An empty outbox hides the indicator; a refresh that failed must not hide with it.
    @Test func aFailedRefreshKeepsTheIndicatorVisibleWithTheErrorGlyph() {
        let quiet = SyncStatusPresentation.from(isSyncing: false, pendingCount: 0, hasErrors: false)
        #expect(!quiet.isVisible)

        let failed = SyncStatusPresentation.from(
            isSyncing: false, pendingCount: 0, hasErrors: false, refreshFailed: true
        )
        #expect(failed.isVisible)
        #expect(failed.icon == .error)
    }
}
