import Foundation
@preconcurrency import Shared

/// What the Sleep sheet shows: choosing a timer, a running one, or the fade. A pure value
/// built from the snapshot, the progress and the clock, so every face is testable without SwiftUI.
///
/// A countdown and "Pauses at" are wall time (the shared `SleepCountdowns`),
/// while "After Bridge Four · in 8 min" is the chapter's content time, like every other "left".
struct SleepSheetModel: Equatable {
    enum Mode: Equatable {
        /// `endOfChapter` is End of Chapter's line ("After Bridge Four · in 8 min"), nil for a chapterless book.
        case choosing(endOfChapter: String?)
        /// `meter` is the share still to run, 0...1. `chapterLine` names the chapter an end-of-chapter timer ends with.
        case running(countdown: String, pausesAt: String, meter: Double, canExtend: Bool, chapterLine: String?)
        case fading
    }

    let mode: Mode
    /// The user's default sleep timer in minutes, nil for Off.
    var defaultMinutes: Int?

    /// Where the custom time starts: the default (clamped), or the shared 20 with none.
    var startingCustomMinutes: Int {
        Int(SleepTimerChoices.shared.startingCustomMinutes(defaultMinutes: defaultMinutes.map { Int32($0) }))
    }

    /// Whether [minutes] is the user's default, so its row says so.
    func isDefault(_ minutes: Int) -> Bool { defaultMinutes == minutes }

    static func make(
        snapshot: PlayerSnapshot,
        sleepRemainingMs: Int64,
        progress: PlayerProgressRow,
        now: Date,
        locale: Locale = .autoupdatingCurrent,
        timeZone: TimeZone = .autoupdatingCurrent
    ) -> SleepSheetModel {
        let defaultMinutes = snapshot.defaultSleepMinutes
        if snapshot.isSleepFading { return SleepSheetModel(mode: .fading, defaultMinutes: defaultMinutes) }
        guard let countdownMs = snapshot.sleepCountdownMs(
            chapterRemainingMs: progress.chapterRemainingMs, sleepRemainingMs: sleepRemainingMs
        ) else {
            let line = snapshot.offersEndOfChapter
                ? String(localized: .playerSheetEndOfChapterAfterIos(chapterName(snapshot), MiniPlayerAccessoryModel.shortDuration(ms: progress.chapterRemainingMs)))
                : nil
            return SleepSheetModel(mode: .choosing(endOfChapter: line), defaultMinutes: defaultMinutes)
        }
        var clock = Date.FormatStyle(date: .omitted, time: .shortened)
        clock.locale = locale
        clock.timeZone = timeZone
        let pausesAt = now.addingTimeInterval(TimeInterval(countdownMs) / 1000).formatted(clock)
        let chapterLine = snapshot.sleepTimerIsEndOfChapter
            ? String(localized: .playerSheetUntilEndOf(chapterName(snapshot), MiniPlayerAccessoryModel.shortDuration(ms: countdownMs)))
            : nil
        return SleepSheetModel(mode: .running(
            countdown: DurationFormatting.clock(ms: countdownMs),
            pausesAt: String(localized: .playerSheetPausesAt(pausesAt)),
            meter: meter(snapshot, sleepRemainingMs: sleepRemainingMs, progress),
            canExtend: snapshot.sleepTimerCanExtend,
            chapterLine: chapterLine
        ), defaultMinutes: defaultMinutes)
    }

    /// The chapter by the book's own title, or by its own number ("Chapter 8") when it has none, as Android and the
    /// chapter rows name it.
    private static func chapterName(_ snapshot: PlayerSnapshot) -> String {
        if let title = snapshot.chapterTitle, !title.isEmpty { return title }
        return String(format: String(localized: "player.chapter_number"), Int32(snapshot.chapterNumber))
    }

    /// The share of the timer still to run: the shared `SleepCountdowns.meterFraction`, so the ring agrees with
    /// Android's bar and the web's.
    private static func meter(
        _ snapshot: PlayerSnapshot, sleepRemainingMs: Int64, _ progress: PlayerProgressRow) -> Double {
        let mode: any SleepTimerMode = snapshot.sleepTimerIsEndOfChapter
            ? SleepTimerModeEndOfChapter.shared
            : SleepTimerModeDuration(minutes: Int32(snapshot.sleepTimerTotalMs / 60_000))
        let timer = SleepTimerStateActive(
            mode: mode, remainingMs: sleepRemainingMs, totalMs: snapshot.sleepTimerTotalMs,
            startedAt: snapshot.sleepTimerStartedAtMs)
        let shared = PlayerProgress(
            chapterPositionMs: progress.chapterPositionMs, chapterDurationMs: progress.chapterDurationMs,
            bookPositionMs: progress.bookPositionMs, bookDurationMs: progress.bookDurationMs)
        return Double(SleepCountdowns.shared.meterFraction(timer: timer, progress: shared))
    }
}
