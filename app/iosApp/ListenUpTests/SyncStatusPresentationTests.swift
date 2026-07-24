import Testing
@testable import ListenUp

/// Pure-seam coverage for the shell sync indicator.
///
/// `SyncIndicatorViewModel`'s bridged `SyncIndicatorUiState` isn't constructible from Swift, so the
/// observer's `apply(_:)` field mapping lands at the green-build pass. What *is* pure and
/// constructible is the indicator-decision precedence, so that seam is pinned here.
@Suite("SyncStatusPresentation")
struct SyncStatusPresentationTests {
    @Test func quietEmptyOutboxIsHidden() {
        let result = SyncStatusPresentation.from(isSyncing: false, pendingCount: 0, hasErrors: false)
        #expect(result.isVisible == false)
        #expect(result.icon == nil)
        #expect(result.badgeCount == nil)
    }

    @Test func pendingBacklogShowsCountBadge() {
        let result = SyncStatusPresentation.from(isSyncing: false, pendingCount: 3, hasErrors: false)
        #expect(result.isVisible)
        #expect(result.icon == .pending)
        #expect(result.badgeCount == 3)
    }

    @Test func syncingShowsSpinnerNoBadge() {
        let result = SyncStatusPresentation.from(isSyncing: true, pendingCount: 5, hasErrors: false)
        #expect(result.icon == .syncing)
        #expect(result.badgeCount == nil)
    }

    @Test func errorsDominateSyncingAndPending() {
        let result = SyncStatusPresentation.from(isSyncing: true, pendingCount: 5, hasErrors: true)
        #expect(result.icon == .error)
        #expect(result.isVisible)
    }
}
