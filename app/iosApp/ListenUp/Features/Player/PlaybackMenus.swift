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

/// The player's speed control: a pill that opens a menu of `PlaybackRates`, checked at the
/// current speed.
struct PlaybackSpeedMenu<Label: View>: View {
    let observer: PlayerObserver
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
    let observer: PlayerObserver
    @ViewBuilder let label: () -> Label

    private var isEndOfChapter: Bool { observer.sleepTimerIsEndOfChapter }

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
            // A separate section, so the actions that change a running timer read apart from the
            // picker that replaces it — HIG, Menus: "Consider grouping logically related items."
            if SleepTimerOption.canExtend(isActive: observer.sleepTimerActive, isEndOfChapter: isEndOfChapter) {
                Section(String(localized: "player.add_more_time")) {
                    ForEach(SleepTimerOption.extensionMinutes, id: \.self) { minutes in
                        Button(String(format: String(localized: "player.extend_minutes"), minutes)) {
                            observer.extendSleepTimer(minutes: minutes)
                        }
                    }
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
