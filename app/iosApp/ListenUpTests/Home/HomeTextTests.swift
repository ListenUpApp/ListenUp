import Foundation
import Testing
@testable import ListenUp

/// The words Home's cards and stats say (spec §2.5, §2.6, §8.2), as pure functions.
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

    @Test func aShelfSaysHowManyBooksAndHowLong() {
        #expect(HomeShelfText.meta(bookCount: 6, totalSeconds: 266_400) == "6 books · 74 hr")
        #expect(HomeShelfText.meta(bookCount: 1, totalSeconds: 0) == "1 book")
        #expect(HomeShelfText.meta(bookCount: 3, totalSeconds: 0) == "3 books")
    }

    // MARK: - Every state

    private func date(_ year: Int, _ month: Int, _ day: Int) -> Date {
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = TimeZone(identifier: "UTC")!
        return calendar.date(from: DateComponents(year: year, month: month, day: day, hour: 12))!
    }

    @Test func theEyebrowCountsCalendarDays() {
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = TimeZone(identifier: "UTC")!
        let now = Date(timeIntervalSince1970: 1_760_097_600) // 10 Oct 2025, 12:00 UTC
        func eyebrow(hoursAgo: Double) -> String {
            let finishedAt = now.addingTimeInterval(-hoursAgo * 3_600)
            return FinishedCardText.eyebrow(finishedAt: finishedAt, now: now, calendar: calendar)
        }
        #expect(eyebrow(hoursAgo: 1) == "Finished Today")
        #expect(eyebrow(hoursAgo: 24) == "Finished Yesterday")
        #expect(eyebrow(hoursAgo: 48) == "Finished 2 Days Ago")
    }

    @Test func theMetaLineHasLengthAndRange() {
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = TimeZone(identifier: "UTC")!
        let locale = Locale(identifier: "en_US")
        func meta(startedAt: Date?) -> String {
            FinishedCardText.meta(
                durationMs: 163_800_000,
                startedAt: startedAt,
                finishedAt: date(2025, 10, 10),
                locale: locale,
                calendar: calendar
            )
        }
        #expect(meta(startedAt: date(2025, 9, 6)) == "45 hr, 30 min · Sep 6 – Oct 10")
        #expect(meta(startedAt: nil) == "45 hr, 30 min")
    }

    @Test func theFinishersLine() {
        #expect(FinishedCardText.alsoFinished(names: ["Maya"], more: 0) == "Maya finished it too")
        #expect(FinishedCardText.alsoFinished(names: ["Maya", "Tom"], more: 0) == "Maya and Tom finished it too")
        #expect(FinishedCardText.alsoFinished(names: ["Maya"], more: 2) == "Maya and 2 more finished it too")
        #expect(
            FinishedCardText.alsoFinished(names: ["Maya", "Tom"], more: 3) == "Maya, Tom and 3 more finished it too"
        )
        #expect(FinishedCardText.alsoFinished(names: [], more: 3) == nil)
        #expect(FinishedCardText.alsoFinished(names: [" "], more: 3) == nil)
    }

    @Test func seriesLineReadsBookNumberAndLength() {
        #expect(
            QuietSlotText.seriesLine(series: "Stormlight Archive", sequence: "2", durationMs: 173_700_000)
                == "Stormlight Archive, Book 2 · 48 hr, 15 min"
        )
        #expect(
            QuietSlotText.seriesLine(series: "Stormlight Archive", sequence: nil, durationMs: 173_700_000)
                == "Stormlight Archive · 48 hr, 15 min"
        )
    }

    @Test func theStripCountsOnlyWhenTheScanKnowsBoth() {
        #expect(FirstSyncText.title(count: nil) == "Building your library")
        #expect(
            FirstSyncText.title(count: ArrivalCount(done: 412, total: 1280))
                == "Building your library · 412 of 1,280 books"
        )
    }

    @Test func aBlockedCardSaysItNeedsAConnection() {
        let label = HomeCardText.label(
            title: "Babel", author: "R. F. Kuang", narrators: nil, percent: 11, remainingMs: 67_200_000,
            needsConnection: true
        )
        #expect(label == "Babel, by R. F. Kuang, 11 percent listened, not downloaded, needs a connection")
        #expect(HomeCardText.caption(remainingMs: 67_200_000, needsConnection: true) == "Needs a connection")
        #expect(HomeCardText.caption(remainingMs: 67_200_000, needsConnection: false) == "18 hr, 40 min left")
    }
}
