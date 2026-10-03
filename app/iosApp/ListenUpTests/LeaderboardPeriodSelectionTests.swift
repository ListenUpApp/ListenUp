import Foundation
import Testing
import Shared
import UIKit
@testable import ListenUp

/// The leaderboard's periods say what they count — trailing windows, so "7 days", "30 days",
/// "12 months" and "All time" — in the same four, in the same order, as Android and web, and a
/// VoiceOver user hears "Last 7 days" rather than a bare number.
@Suite("Leaderboard periods")
struct LeaderboardPeriodSelectionTests {

    @Test func offersFourTrailingPeriodsInOrder() {
        #expect(LeaderboardSelection.allCases == [.sevenDays, .thirtyDays, .twelveMonths, .allTime])
    }

    @Test func eachPeriodSelectsItsSharedWindow() {
        #expect(LeaderboardSelection.sevenDays.kmpPeriod is LeaderboardPeriodWeek)
        #expect(LeaderboardSelection.thirtyDays.kmpPeriod is LeaderboardPeriodMonth)
        #expect(LeaderboardSelection.twelveMonths.kmpPeriod is LeaderboardPeriodYear)
        #expect(LeaderboardSelection.allTime.kmpPeriod is LeaderboardPeriodAllTime)
    }

    @Test func labelsNameTheWindow() {
        let titles = LeaderboardSelection.allCases.map { String(localized: $0.titleKey) }
        #expect(titles == ["7 days", "30 days", "12 months", "All time"])
    }

    @Test func voiceOverHearsTheWholeWindow() {
        let labels = LeaderboardSelection.allCases.map { String(localized: $0.accessibilityKey) }
        #expect(labels == ["Last 7 days", "Last 30 days", "Last 12 months", "All time"])
    }

    /// The segmented control spans the leaderboard column. On the narrowest supported phone
    /// (iPhone SE, 375pt, minus Discover's 20pt compact inset each side) all four segments must
    /// fit at the default text size without truncating.
    @Test @MainActor func fourSegmentsFitOnTheNarrowestPhone() {
        let titles = LeaderboardSelection.allCases.map { String(localized: $0.titleKey) }
        let control = UISegmentedControl(items: titles)
        let needed = control.intrinsicContentSize.width
        #expect(needed <= 375 - 2 * 20, "needs \(needed)pt")
    }
}
