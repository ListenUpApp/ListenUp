import Foundation
@preconcurrency import Shared

/// The finish-date arithmetic behind `markFinished` — pure, calendar-injected, and split out of
/// `BookDetailObserver.swift` (the `PlayerCoordinator+Chapters` precedent) to keep that file inside
/// the 550-line cap.
extension BookDetailObserver {
    /// Pure: the days the sheet opens on — the recorded start day (today if unknown), and today —
    /// as start-of-day `Date`s in [calendar]. Mirrors `FinishDates.initial` in sharedLogic.
    nonisolated static func finishDaysOpened(
        startedAtMs: Int64?,
        now: Int64,
        calendar: Calendar
    ) -> (started: Date, finished: Date) {
        (
            started: calendar.startOfDay(for: date(ms: startedAtMs ?? now)),
            finished: calendar.startOfDay(for: date(ms: now))
        )
    }

    /// Pure: why these days can't be saved, or nil — the shared `FinishDatesProblem`, by the rule of
    /// `FinishDates.problem` (whose `LocalDate`s don't cross Swift Export usefully).
    nonisolated static func finishDatesProblem(
        started: Date,
        finished: Date,
        now: Int64,
        calendar: Calendar
    ) -> FinishDatesProblem? {
        let today = calendar.startOfDay(for: date(ms: now))
        let startDay = calendar.startOfDay(for: started)
        let finishDay = calendar.startOfDay(for: finished)
        if startDay > today || finishDay > today { return .InTheFuture }
        if finishDay < startDay { return .FinishedBeforeStarted }
        return nil
    }

    /// Pure: the epoch milliseconds for the chosen days. An unchanged start day is nil — only a
    /// picked one is sent; a changed day is the start of that day in [calendar]; an unchanged finish
    /// is now; the finish never precedes the start (picked, or the instant the sheet opened with).
    /// Mirrors `FinishDates.toTimestamps`.
    nonisolated static func markCompleteTimestamps(
        started: Date,
        finished: Date,
        startedAtMs: Int64?,
        now: Int64,
        calendar: Calendar
    ) -> (start: Int64?, finish: Int64) {
        let openedStart = startedAtMs ?? now
        let pickedStart = calendar.isDate(started, inSameDayAs: date(ms: openedStart))
            ? nil
            : ms(calendar.startOfDay(for: started))
        let finish = calendar.isDate(finished, inSameDayAs: date(ms: now))
            ? now
            : ms(calendar.startOfDay(for: finished))
        return (start: pickedStart, finish: max(finish, pickedStart ?? openedStart))
    }

    private nonisolated static func date(ms: Int64) -> Date { Date(timeIntervalSince1970: Double(ms) / 1000) }
    private nonisolated static func ms(_ date: Date) -> Int64 { Int64((date.timeIntervalSince1970 * 1000).rounded()) }
}
