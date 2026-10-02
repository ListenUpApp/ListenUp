import Foundation
import Testing
import Shared
@testable import ListenUp

// The Connected phase's new halves: the sync line (when it last synced, a sync running, a failed Sync
// Now, a stuck push or pull) and the books that need a match, each mapped once at the observer
// boundary into native values.

@Suite("Hardcover sync mapping")
struct HardcoverSyncMappingTests {
    private func connected(
        lastSyncedAt: Int64? = nil,
        sync: HardcoverSyncStatus = HardcoverSyncStatusIdle.shared,
        books: [HardcoverBookToMatch] = [],
        isMatchListKnown: Bool = true
    ) -> HardcoverSettingsUiStateConnected {
        HardcoverSettingsUiStateConnected(
            username: "simon",
            since: 1_790_424_000_000,
            isDisconnecting: false,
            lastSyncedAt: lastSyncedAt,
            sync: sync,
            booksToMatch: books,
            isMatchListKnown: isMatchListKnown,
            shareMode: .asIListen,
            isSavingShareMode: false,
            history: HardcoverHistoryNone.shared,
            keptOffBookCount: 0
        )
    }

    private func model(_ state: HardcoverSettingsUiStateConnected) -> HardcoverConnectedModel? {
        guard case .connected(let model) = HardcoverSettingsObserver.phase(from: state) else {
            Issue.record("expected .connected")
            return nil
        }
        return model
    }

    @Test func idleCarriesTheLastSyncTime() {
        let model = model(connected(lastSyncedAt: 1_790_424_060_000))
        #expect(model?.lastSyncedAt == Date(timeIntervalSince1970: 1_790_424_060))
        #expect(model?.sync == .idle)
    }

    @Test func neverSyncedHasNoTime() {
        #expect(model(connected())?.lastSyncedAt == nil)
    }

    @Test func syncingMapsToSyncing() {
        #expect(model(connected(sync: HardcoverSyncStatusSyncing.shared))?.sync == .syncing)
    }

    @Test func aFailedSyncNowIsANoticeNotAStandingProblem() {
        let line = HardcoverSettingsObserver.syncLine(from: HardcoverSyncStatusProblem(problem: .syncNowFailed))
        #expect(line == .syncNowFailed(String(localized: "hardcover.sync_now_failed_notice")))
    }

    @Test func aStuckPushOrPullIsAStandingProblemInItsOwnWords() {
        #expect(HardcoverSettingsObserver.syncLine(from: HardcoverSyncStatusProblem(problem: .pushStalled))
            == .stalled(String(localized: "hardcover.problem_push_stalled")))
        #expect(HardcoverSettingsObserver.syncLine(from: HardcoverSyncStatusProblem(problem: .pullStalled))
            == .stalled(String(localized: "hardcover.problem_pull_stalled")))
    }

    @Test func booksToMatchKeepTheServersOrder() {
        let books = [
            HardcoverBookToMatch(bookId: "b2", title: "Second", authorNames: "B", coverPath: nil, coverHash: nil),
            HardcoverBookToMatch(bookId: "b1", title: "First", authorNames: "A", coverPath: "/c.jpg", coverHash: "h")
        ]
        let model = model(connected(books: books))
        #expect(model?.booksToMatch.map(\.id) == ["b2", "b1"])
        #expect(model?.booksToMatch.last
            == HardcoverBookToMatchRow(id: "b1", title: "First", authorNames: "A", coverPath: "/c.jpg", coverHash: "h"))
    }

    @Test func onlyAKnownEmptyListCountsAsAllMatched() {
        #expect(model(connected(isMatchListKnown: true))?.isMatchListKnown == true)
        #expect(model(connected(isMatchListKnown: false))?.isMatchListKnown == false)
    }

    @Test func theLastSyncLineReadsNaturally() {
        let now = Date(timeIntervalSince1970: 1_790_424_600)
        #expect(HardcoverSettingsObserver.lastSyncedText(nil, now: now) == String(localized: "hardcover.never_synced"))
        #expect(HardcoverSettingsObserver.lastSyncedText(now.addingTimeInterval(-20), now: now)
            == String(localized: "hardcover.last_synced_just_now"))
        let fiveMinutes = HardcoverSettingsObserver.lastSyncedText(now.addingTimeInterval(-300), now: now)
        #expect(fiveMinutes.contains("5"))
        #expect(fiveMinutes != String(localized: "hardcover.last_synced_just_now"))
    }
}
