import Foundation
import Testing
@testable import ListenUp

/// The words Home's cards and hero say (spec §2.3, §2.5, §8.2), as pure functions.
@Suite("Home text")
struct HomeTextTests {
    @Test func aCardIsOneLabelWithAuthorPercentAndTimeLeft() {
        let label = HomeCardText.label(
            title: "Babel", author: "R. F. Kuang", narrators: nil, percent: 11, remainingMs: 67_200_000
        )
        #expect(label == "Babel, by R. F. Kuang, 11 percent listened, 18 hr, 40 min left")
    }

    @Test func aWideCardAddsWhoReadsIt() {
        let label = HomeCardText.label(
            title: "Babel", author: "R. F. Kuang", narrators: "Chris Lew Kum Hoi", percent: 11, remainingMs: 67_200_000
        )
        #expect(label.contains("Read by Chris Lew Kum Hoi"))
    }

    @Test func aBookWithNoAuthorDoesNotSayByNobody() {
        let label = HomeCardText.label(title: "Babel", author: "", narrators: nil, percent: 11, remainingMs: 67_200_000)
        #expect(!label.contains("by ,"))
        #expect(label.hasPrefix("Babel, 11 percent"))
    }

    @Test func theEyebrowSaysWhenYouLastListened() {
        let now = Date(timeIntervalSince1970: 1_760_000_000)
        let eyebrow = ResumeHeroText.eyebrow(lastPlayedAt: now.addingTimeInterval(-7_200), now: now)
        #expect(eyebrow.hasPrefix("Up Next · "))
        #expect(eyebrow.contains("2 hr"))
    }

    @Test func theChapterLineIsBookDetails() {
        #expect(ResumeHeroText.chapter(number: 6, title: "Bridge Four") == "Ch. 6 · Bridge Four")
        #expect(ResumeHeroText.chapter(number: nil, title: nil) == nil)
    }

    @Test func percentOfLength() {
        #expect(ResumeHeroText.progressOfLength(percent: 11, totalMs: 163_800_000) == "11% of 45 hr, 30 min")
    }

    @Test func theStreakChipAndTheBestStreakReadInTheCatalogsWords() {
        #expect(HomeStatsText.streakChip(days: 5) == "5-day streak")
        #expect(HomeStatsText.bestStreak(days: 12) == "Best streak 12 days")
        #expect(HomeStatsText.bestStreak(days: 1) == "Best streak 1 day")
    }

    @Test func theChartSummaryReadsOldestToToday() {
        let days = [DayBar(dayOffset: 0, seconds: 4_800), DayBar(dayOffset: 1, seconds: 0)]
            + (2...6).map { DayBar(dayOffset: $0, seconds: 600) }
        let summary = HomeStatsText.chartSummary(days: days)
        #expect(summary.hasPrefix("Listening per day, oldest to today: "))
        #expect(summary.hasSuffix("today 1 hour, 20 minutes"))
    }
}
