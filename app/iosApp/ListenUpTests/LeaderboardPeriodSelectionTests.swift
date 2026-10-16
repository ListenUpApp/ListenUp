import Foundation
import Testing
import Shared
import UIKit
@testable import ListenUp

/// The leaderboard's periods say the one wording every platform uses — Week, Month, Year, All Time
/// (title case, per the HIG) — the same four, in the same order, as Android and web. The visible
/// word is the accessible name, so VoiceOver hears exactly what is shown.
@Suite("Leaderboard periods")
struct LeaderboardPeriodSelectionTests {

    @Test func offersFourPeriodsInOrder() {
        #expect(LeaderboardPeriodOption.allCases == [.week, .month, .year, .allTime])
    }

    @Test func eachPeriodSelectsItsSharedWindow() {
        #expect(LeaderboardPeriodOption.week.kmpPeriod is LeaderboardPeriodWeek)
        #expect(LeaderboardPeriodOption.month.kmpPeriod is LeaderboardPeriodMonth)
        #expect(LeaderboardPeriodOption.year.kmpPeriod is LeaderboardPeriodYear)
        #expect(LeaderboardPeriodOption.allTime.kmpPeriod is LeaderboardPeriodAllTime)
    }

    @Test func labelsAreTheOneWordingInTitleCase() {
        let titles = LeaderboardPeriodOption.allCases.map { String(localized: $0.titleKey) }
        #expect(titles == ["Week", "Month", "Year", "All Time"])
    }

    /// The segmented control spans the leaderboard column. On the narrowest supported phone
    /// (iPhone SE, 375pt, minus Discover's 20pt compact inset each side, minus the card's 16pt
    /// padding each side) all four segments must fit at the default text size without truncating.
    @Test @MainActor func fourSegmentsFitOnTheNarrowestPhone() {
        let titles = LeaderboardPeriodOption.allCases.map { String(localized: $0.titleKey) }
        let control = UISegmentedControl(items: titles)
        let needed = control.intrinsicContentSize.width
        #expect(needed <= 375 - 2 * 20 - 2 * 16, "needs \(needed)pt")
    }
}
