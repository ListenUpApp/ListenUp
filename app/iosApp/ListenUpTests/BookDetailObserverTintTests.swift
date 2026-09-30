import Foundation
import SwiftUI
import Testing
@preconcurrency import Shared
@testable import ListenUp

/// "Mark as finished" asks for the days — the same defaults, rule and conversion as Android and web
/// (`FinishDates` in sharedLogic). Pinned in a fixed UTC−7 calendar because the failure it guards is
/// a timezone one: a chosen day must become the start of that day where the reader is.
@Suite("BookDetailObserver finish dates")
struct BookDetailObserverTintTests {
    private static let pacific: Calendar = {
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = TimeZone(secondsFromGMT: -7 * 3600)!
        return calendar
    }()

    /// 2026-09-30T03:00Z — still the evening of the 29th in UTC−7.
    private static let now = ms("2026-09-30T03:00:00Z")
    /// 2026-09-02T18:00Z — 11:00 on the 2nd in UTC−7.
    private static let startedAt = ms("2026-09-02T18:00:00Z")

    private static func ms(_ iso: String) -> Int64 {
        Int64(ISO8601DateFormatter().date(from: iso)!.timeIntervalSince1970 * 1000)
    }

    private static func day(_ year: Int, _ month: Int, _ day: Int) -> Date {
        pacific.date(from: DateComponents(year: year, month: month, day: day))!
    }

    @Test("a book never started opens on today for both, in the reader's zone")
    func opensOnTodayWhenNeverStarted() {
        let days = BookDetailObserver.finishDaysOpened(startedAtMs: nil, now: Self.now, calendar: Self.pacific)
        #expect(days.started == Self.day(2026, 9, 29))
        #expect(days.finished == Self.day(2026, 9, 29))
    }

    @Test("a book already started opens on the day it was started")
    func opensOnRecordedStartDay() {
        let days = BookDetailObserver.finishDaysOpened(
            startedAtMs: Self.startedAt, now: Self.now, calendar: Self.pacific
        )
        #expect(days.started == Self.day(2026, 9, 2))
        #expect(days.finished == Self.day(2026, 9, 29))
    }

    @Test("untouched days send exactly what the one-tap finish always sent")
    func untouchedDaysKeepTheirInstants() {
        let opened = BookDetailObserver.finishDaysOpened(
            startedAtMs: Self.startedAt, now: Self.now, calendar: Self.pacific
        )
        let ts = BookDetailObserver.markCompleteTimestamps(
            started: opened.started, finished: opened.finished,
            startedAtMs: Self.startedAt, now: Self.now, calendar: Self.pacific
        )
        #expect(ts.start == Self.startedAt)
        #expect(ts.finish == Self.now)

        let fresh = BookDetailObserver.finishDaysOpened(startedAtMs: nil, now: Self.now, calendar: Self.pacific)
        let freshTs = BookDetailObserver.markCompleteTimestamps(
            started: fresh.started, finished: fresh.finished,
            startedAtMs: nil, now: Self.now, calendar: Self.pacific
        )
        #expect(freshTs.start == Self.now)
        #expect(freshTs.finish == Self.now)
    }

    @Test("chosen days are the start of those days in the reader's zone, not UTC")
    func chosenDaysAreLocalStartOfDay() {
        let ts = BookDetailObserver.markCompleteTimestamps(
            started: Self.day(2019, 3, 1), finished: Self.day(2019, 4, 12),
            startedAtMs: Self.startedAt, now: Self.now, calendar: Self.pacific
        )
        #expect(ts.start == Self.ms("2019-03-01T07:00:00Z"))
        #expect(ts.finish == Self.ms("2019-04-12T07:00:00Z"))
    }

    @Test("finishing on the day it was started never records the finish before the start")
    func finishNeverPrecedesStart() {
        let ts = BookDetailObserver.markCompleteTimestamps(
            started: Self.day(2026, 9, 2), finished: Self.day(2026, 9, 2),
            startedAtMs: Self.startedAt, now: Self.now, calendar: Self.pacific
        )
        #expect(ts.start == Self.startedAt)
        #expect(ts.finish == Self.startedAt)
    }

    @Test("finished before started is refused")
    func finishedBeforeStartedIsRefused() {
        #expect(
            BookDetailObserver.finishDatesProblem(
                started: Self.day(2026, 9, 10), finished: Self.day(2026, 9, 9),
                now: Self.now, calendar: Self.pacific
            ) == .FinishedBeforeStarted
        )
    }

    @Test("a day in the future is refused")
    func futureIsRefused() {
        #expect(
            BookDetailObserver.finishDatesProblem(
                started: Self.day(2026, 9, 29), finished: Self.day(2026, 9, 30),
                now: Self.now, calendar: Self.pacific
            ) == .InTheFuture
        )
    }

    @Test("started and finished today, or years ago, is fine")
    func validDaysPass() {
        #expect(
            BookDetailObserver.finishDatesProblem(
                started: Self.day(2026, 9, 29), finished: Self.day(2026, 9, 29),
                now: Self.now, calendar: Self.pacific
            ) == nil
        )
        #expect(
            BookDetailObserver.finishDatesProblem(
                started: Self.day(2019, 3, 1), finished: Self.day(2019, 4, 12),
                now: Self.now, calendar: Self.pacific
            ) == nil
        )
    }
}
