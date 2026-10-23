import SwiftUI
@preconcurrency import Shared

/// One sleep-timer choice, for the surfaces that offer the timer as a menu: the iPad's menu bar (`ShellCommands`) and
/// the system's playback commands (`PlaybackCommandState`). Now Playing offers it as a sheet (`SleepSheet`). The ladder
/// is the shared one, so every surface lists the same durations.
enum SleepTimerOption: Hashable {
    case off
    case minutes(Int)
    case endOfChapter

    /// The durations every sleep surface offers, in order: the shared ladder (`SleepTimerChoices`).
    static var durationMinutes: [Int] { SleepTimerChoices.shared.DURATION_MINUTES.map { Int($0) } }

    /// The menu's entries, in order.
    static var menuOptions: [SleepTimerOption] { [.off] + durationMinutes.map { .minutes($0) } + [.endOfChapter] }

    /// The checked entry for the current timer. A running countdown checks nothing: it no longer
    /// equals the duration it was armed with, so the menu's header shows the time left instead.
    static func selection(isActive: Bool, isEndOfChapter: Bool) -> SleepTimerOption? {
        guard isActive else { return .off }
        return isEndOfChapter ? .endOfChapter : nil
    }

    /// The "Add more time" ladder — the shared 5/10/15 Android's sheet and web's picker offer.
    static var extensionMinutes: [Int] { SleepTimerChoices.shared.EXTEND_MINUTES.map { Int($0) } }

    /// Whether the timer has a countdown to add to. An end-of-chapter timer has none (the shared
    /// timer would ignore the request), so the menu offers extending only to a running countdown.
    static func canExtend(isActive: Bool, isEndOfChapter: Bool) -> Bool {
        isActive && !isEndOfChapter
    }

    var title: String {
        switch self {
        case .off: String(localized: "player.sleep_off")
        case .minutes(let minutes): SleepTimerOption.formatDuration(minutes)
        case .endOfChapter: String(localized: "player.end_of_chapter")
        }
    }

    /// "15 minutes", "1 hour", "2 hours" — a duration in minutes as the menu names it, in the
    /// reader's language and with its plural rules (Foundation's duration formatter owns both).
    static func formatDuration(_ minutes: Int, locale: Locale = .autoupdatingCurrent) -> String {
        Duration.seconds(minutes * 60)
            .formatted(.units(allowed: [.hours, .minutes], width: .wide).locale(locale))
    }
}
