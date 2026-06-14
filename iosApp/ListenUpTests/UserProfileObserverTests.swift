import Testing
@testable import ListenUp

/// Pure-seam coverage for the user profile screen.
///
/// The sealed-state → `@Observable` flatten in `UserProfileObserver.apply` can't be
/// exercised here: SKIE bridges `UserProfileUiState` as a sealed *protocol* whose
/// cases aren't constructible from Swift, so the `onEnum` mapping is proven at the
/// green-build pass. What *is* pure and constructible is the stat-strip listen-time
/// formatter, so that seam is pinned here.
@Suite("ProfileStatFormat")
struct UserProfileObserverTests {
    @Test func zeroListenTimeShowsZeroHours() {
        #expect(ProfileStatFormat.listened(totalMs: 0) == "0h")
    }

    @Test func subHourShowsWholeMinutes() {
        // 12m 34s → "12m"
        #expect(ProfileStatFormat.listened(totalMs: (12 * 60 + 34) * 1_000) == "12m")
    }

    @Test func wholeHourShowsHours() {
        #expect(ProfileStatFormat.listened(totalMs: 60 * 60 * 1_000) == "1h")
    }

    @Test func hoursTruncateTowardsWholeHours() {
        // 47h 59m → "47h" (the strip is compact; minutes are dropped once past an hour)
        let ms = Int64((47 * 60 + 59) * 60) * 1_000
        #expect(ProfileStatFormat.listened(totalMs: ms) == "47h")
    }
}
