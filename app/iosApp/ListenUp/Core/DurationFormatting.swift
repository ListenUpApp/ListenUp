import Foundation

/// One home for millisecond→string duration formatting, replacing the hand-rolled copies
/// scattered across rows, observers, and the player. Output is intentionally identical to the
/// prior inline implementations (this was a dedup, not a behavior change). Negatives clamp to
/// zero — durations are never negative.
enum DurationFormatting {
    /// `"15h 54m"` / `"54m"` — hours dropped when zero, sub-minute rounds down to `"0m"`.
    static func hoursMinutes(ms: Int64) -> String {
        let totalMinutes = max(0, ms / 60_000)
        let hours = totalMinutes / 60
        let minutes = totalMinutes % 60
        return hours > 0 ? "\(hours)h \(minutes)m" : "\(minutes)m"
    }

    /// `"1:05:09"` / `"5:09"` — clock form for scrubber/now-playing labels. Hours dropped when zero.
    static func clock(ms: Int64) -> String {
        let totalSeconds = max(0, ms / 1000)
        let hours = totalSeconds / 3600
        let minutes = (totalSeconds % 3600) / 60
        let seconds = totalSeconds % 60
        return hours > 0
            ? String(format: "%d:%02d:%02d", hours, minutes, seconds)
            : String(format: "%d:%02d", minutes, seconds)
    }

    /// A human, VoiceOver-friendly duration like "2 hr, 5 min", or the localized
    /// "no listening" placeholder for zero. Foundation-localized — unlike the
    /// deterministic formatters above.
    static func accessibleHoursMinutes(seconds: Int) -> String {
        guard seconds > 0 else { return String(localized: "home.no_listening") }
        return Duration.seconds(seconds).formatted(
            .units(allowed: [.hours, .minutes], width: .wide)
        )
    }

    /// "45 hr, 30 min" in the reader's locale — the length Home's hero and Book Detail print.
    static func abbreviated(ms: Int64) -> String {
        Duration.milliseconds(max(0, ms)).formatted(.units(allowed: [.hours, .minutes], width: .abbreviated))
    }

    /// "40 hr, 31 min left" — Book Detail's and Home's time left (spec §6.2).
    static func timeLeft(ms: Int64) -> String {
        String(format: String(localized: "book.time_left"), abbreviated(ms: ms))
    }
}
