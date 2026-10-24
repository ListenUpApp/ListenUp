import Foundation
@preconcurrency import Shared

/// What the Sleep sheet shows (player spec §5.5.2, PT1–PT8): choosing a timer, a running one, or the fade. A pure value
/// built from the snapshot, the progress and the clock, so every face is testable without SwiftUI.
///
/// Times follow open question 2's default: a countdown and "Pauses at" are wall time (the shared `SleepCountdowns`),
/// while "After Bridge Four · in 8 min" is the chapter's content time, like every other "left" (S10).
struct SleepSheetModel: Equatable {
    enum Mode: Equatable {
        /// `endOfChapter` is End of Chapter's line ("After Bridge Four · in 8 min"), nil for a chapterless book.
        case choosing(endOfChapter: String?)
        /// `meter` is the share still to run, 0...1. `chapterLine` names the chapter an end-of-chapter timer ends with.
        case running(countdown: String, pausesAt: String, meter: Double, canExtend: Bool, chapterLine: String?)
        case fading
    }

    let mode: Mode

    static func make(
        snapshot: PlayerSnapshot,
        progress: PlayerProgressRow,
        now: Date,
        locale: Locale = .autoupdatingCurrent,
        timeZone: TimeZone = .autoupdatingCurrent
    ) -> SleepSheetModel {
        if snapshot.isSleepFading { return SleepSheetModel(mode: .fading) }
        guard let countdownMs = snapshot.sleepCountdownMs(chapterRemainingMs: progress.chapterRemainingMs) else {
            let line = snapshot.offersEndOfChapter
                ? String(
                    format: String(localized: "player.sheet_end_of_chapter_after_ios"),
                    chapterName(snapshot),
                    MiniPlayerAccessoryModel.shortDuration(ms: progress.chapterRemainingMs))
                : nil
            return SleepSheetModel(mode: .choosing(endOfChapter: line))
        }
        var clock = Date.FormatStyle(date: .omitted, time: .shortened)
        clock.locale = locale
        clock.timeZone = timeZone
        let pausesAt = now.addingTimeInterval(TimeInterval(countdownMs) / 1000).formatted(clock)
        let chapterLine = snapshot.sleepTimerIsEndOfChapter
            ? String(
                format: String(localized: "player.sheet_until_end_of"),
                chapterName(snapshot), MiniPlayerAccessoryModel.shortDuration(ms: countdownMs))
            : nil
        return SleepSheetModel(mode: .running(
            countdown: DurationFormatting.clock(ms: countdownMs),
            pausesAt: String(format: String(localized: "player.sheet_pauses_at"), pausesAt),
            meter: meter(snapshot, progress),
            canExtend: snapshot.sleepTimerCanExtend,
            chapterLine: chapterLine
        ))
    }

    /// The chapter by the book's own title, or by its own number ("Chapter 8") when it has none, as Android and the
    /// chapter rows name it.
    private static func chapterName(_ snapshot: PlayerSnapshot) -> String {
        if let title = snapshot.chapterTitle, !title.isEmpty { return title }
        return String(format: String(localized: "player.chapter_number"), Int32(snapshot.chapterNumber))
    }

    /// The share of the timer still to run: the shared `SleepCountdowns.meterFraction`, so the ring agrees with
    /// Android's bar and the web's.
    private static func meter(_ snapshot: PlayerSnapshot, _ progress: PlayerProgressRow) -> Double {
        let mode: any SleepTimerMode = snapshot.sleepTimerIsEndOfChapter
            ? SleepTimerModeEndOfChapter.shared
            : SleepTimerModeDuration(minutes: Int32(snapshot.sleepTimerTotalMs / 60_000))
        let timer = SleepTimerStateActive(
            mode: mode, remainingMs: snapshot.sleepTimerRemainingMs, totalMs: snapshot.sleepTimerTotalMs,
            startedAt: snapshot.sleepTimerStartedAtMs)
        let shared = PlayerProgress(
            chapterPositionMs: progress.chapterPositionMs, chapterDurationMs: progress.chapterDurationMs,
            bookPositionMs: progress.bookPositionMs, bookDurationMs: progress.bookDurationMs)
        return Double(SleepCountdowns.shared.meterFraction(timer: timer, progress: shared))
    }
}
