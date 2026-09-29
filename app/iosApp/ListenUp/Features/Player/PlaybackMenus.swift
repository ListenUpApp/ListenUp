import SwiftUI

// Speed and sleep are system menus holding a picker, the way Podcasts offers them — HIG, Menus:
// a menu's picker shows the current choice with a checkmark, and one tap both chooses and closes.
// They replace the chip sheets, which asked for a sheet round-trip to pick one value.

/// One entry in the sleep-timer menu.
enum SleepTimerOption: Hashable {
    case off
    case minutes(Int)
    case endOfChapter

    /// The menu's entries, in order.
    static let menuOptions: [SleepTimerOption] = [
        .off, .minutes(15), .minutes(30), .minutes(45), .minutes(60), .minutes(120), .endOfChapter
    ]

    /// The checked entry for the current timer. A running countdown checks nothing: it no longer
    /// equals the duration it was armed with, so the menu's header shows the time left instead.
    static func selection(isActive: Bool, isEndOfChapter: Bool) -> SleepTimerOption? {
        guard isActive else { return .off }
        return isEndOfChapter ? .endOfChapter : nil
    }

    var title: String {
        switch self {
        case .off: String(localized: "player.sleep_off")
        case .minutes(let minutes): SleepTimerOption.formatDuration(minutes)
        case .endOfChapter: String(localized: "player.end_of_chapter")
        }
    }

    /// "15 min", "1 hour", "2 hours" — a duration in minutes as the menu names it.
    static func formatDuration(_ minutes: Int) -> String {
        if minutes < 60 { return "\(minutes) min" }
        if minutes == 60 { return "1 hour" }
        return "\(minutes / 60) hours"
    }
}

/// The player's speed control: a pill that opens a menu of `PlaybackRates`, checked at the
/// current speed.
struct PlaybackSpeedMenu<Label: View>: View {
    let observer: PlayerCoordinator
    @ViewBuilder let label: () -> Label

    var body: some View {
        Menu {
            Picker(String(localized: "player.playback_speed"), selection: speed) {
                ForEach(PlaybackRates.options(including: observer.playbackSpeed), id: \.self) { rate in
                    Text(PlaybackRates.format(rate)).tag(rate)
                }
            }
        } label: {
            label()
        }
        .accessibilityLabel(String(localized: "player.speed"))
        .accessibilityValue(PlaybackRates.format(observer.playbackSpeed))
    }

    private var speed: Binding<Float> {
        Binding(get: { observer.playbackSpeed }, set: { observer.setSpeed($0) })
    }
}

/// The player's sleep-timer control: a menu of durations and "End of chapter", headed by the time
/// left while a timer runs.
struct SleepTimerMenu<Label: View>: View {
    let observer: PlayerCoordinator
    @ViewBuilder let label: () -> Label

    private var isEndOfChapter: Bool { observer.sleepTimerMode == "endOfChapter" }

    var body: some View {
        Menu {
            Section {
                Picker(String(localized: "player.sleep_timer"), selection: selection) {
                    ForEach(SleepTimerOption.menuOptions, id: \.self) { option in
                        Text(option.title).tag(Optional(option))
                    }
                }
                .pickerStyle(.inline)
            } header: {
                if observer.sleepTimerActive {
                    Text(observer.sleepTimerLabel)
                }
            }
        } label: {
            label()
        }
        .accessibilityLabel(String(localized: "player.sleep_timer"))
        // The time left (or "End of chapter") while a timer runs, "Off" otherwise — the moon
        // glyph's fill alone is state by colour and shape only.
        .accessibilityValue(
            observer.sleepTimerActive ? observer.sleepTimerLabel : String(localized: "player.sleep_off")
        )
    }

    private var selection: Binding<SleepTimerOption?> {
        Binding(
            get: { SleepTimerOption.selection(isActive: observer.sleepTimerActive, isEndOfChapter: isEndOfChapter) },
            set: { option in
                switch option {
                case .off: observer.cancelSleepTimer()
                case .minutes(let minutes): observer.setSleepTimer(minutes: minutes)
                case .endOfChapter: observer.setSleepTimerEndOfChapter()
                case nil: break
                }
            }
        )
    }
}
