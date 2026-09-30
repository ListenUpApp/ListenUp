import SwiftUI

extension FocusedValues {
    /// The focused window's player, for the Playback menu. Published only by a window showing the
    /// tab shell, so a signed-out window's menu bar offers no playback at all.
    @Entry var playerCoordinator: PlayerCoordinator?
    /// The focused window's tab shell, for the View menu's tab items and ⌘F.
    @Entry var mainShell: MainShellModel?
}

/// The iPad menu bar (and the ⌘-hold shortcut overlay): a Playback menu, and the tabs in the View
/// menu.
///
/// HIG, The menu bar: "Provide app-specific menus for custom commands … Putting commands in the
/// menu bar makes them easier for people to find, lets you assign keyboard shortcuts to them, and
/// makes them more accessible to people using Full Keyboard Access" — and "For apps with tab-style
/// navigation, consider adding each tab as a menu item in the View menu … assigning key bindings".
/// HIG, Keyboards: custom shortcuts only for the most frequent commands, and Command-F keeps its
/// standard meaning, find.
struct ListenUpCommands: Commands {
    @FocusedValue(\.playerCoordinator) private var player
    @FocusedValue(\.mainShell) private var shell

    var body: some Commands {
        CommandGroup(before: .sidebar) {
            tabCommands
                .disabled(shell == nil)
        }
        CommandMenu(String(localized: "common.playback")) {
            playbackCommands
        }
    }

    // MARK: - View menu

    @ViewBuilder
    private var tabCommands: some View {
        Button(String(localized: "common.home")) { shell?.select(.home) }
            .keyboardShortcut("1", modifiers: .command)
        Button(String(localized: "common.library")) { shell?.select(.library) }
            .keyboardShortcut("2", modifiers: .command)
        Button(String(localized: "common.discover")) { shell?.select(.discover) }
            .keyboardShortcut("3", modifiers: .command)
        Button(String(localized: "common.search")) { shell?.focusSearch() }
            .keyboardShortcut("f", modifiers: .command)
        Divider()
    }

    // MARK: - Playback menu

    private var state: PlaybackCommandState {
        guard let player else { return .unavailable }
        return PlaybackCommandState.from(
            phase: player.phase,
            chapterIndex: player.chapterIndex,
            totalChapters: player.totalChapters,
            sleepTimerActive: player.sleepTimerActive,
            sleepTimerIsEndOfChapter: player.sleepTimerMode == "endOfChapter"
        )
    }

    @ViewBuilder
    private var playbackCommands: some View {
        let state = state
        // Space, as in Music and Podcasts. A focused text field keeps it: key commands do not take
        // priority over text input.
        Button(state.showsPause ? String(localized: "player.pause") : String(localized: "player.play")) {
            player?.togglePlayback()
        }
            .keyboardShortcut(.space, modifiers: [])
            .disabled(!state.canTogglePlayback)
        Divider()
        Button(String(format: String(localized: "player.skip_backward"), "\(player?.skipBackwardSec ?? 0)")) {
            player?.skipBackward()
        }
            .keyboardShortcut(.leftArrow, modifiers: .command)
            .disabled(!state.canSeek)
        Button(String(format: String(localized: "player.skip_forward"), "\(player?.skipForwardSec ?? 0)")) {
            player?.skipForward()
        }
            .keyboardShortcut(.rightArrow, modifiers: .command)
            .disabled(!state.canSeek)
        Divider()
        Button(String(localized: "player.previous_chapter")) {
            if let player { player.selectChapter(index: player.chapterIndex - 1) }
        }
        .keyboardShortcut(.leftArrow, modifiers: [.option, .command])
        .disabled(!state.hasPreviousChapter)
        Button(String(localized: "player.next_chapter")) {
            if let player { player.selectChapter(index: player.chapterIndex + 1) }
        }
        .keyboardShortcut(.rightArrow, modifiers: [.option, .command])
        .disabled(!state.hasNextChapter)
        Divider()
        sleepTimerMenu
            .disabled(!state.canSeek)
        addSleepTimeMenu
            .disabled(!state.canExtendSleepTimer)
    }

    private var sleepTimerMenu: some View {
        Picker(String(localized: "player.sleep_timer"), selection: sleepSelection) {
            ForEach(SleepTimerOption.menuOptions, id: \.self) { option in
                Text(option.title).tag(Optional(option))
            }
        }
    }

    /// The player sleep menu's "Add more time" ladder, as a submenu beside the picker. It stays
    /// listed and dims when there is no countdown to grow — HIG, The menu bar: "If a menu bar item
    /// isn't actionable, disable the action instead of hiding it from the menu." No key bindings:
    /// HIG, Keyboards keeps custom shortcuts for the most frequent commands.
    private var addSleepTimeMenu: some View {
        Menu(String(localized: "player.add_more_time")) {
            ForEach(SleepTimerOption.extensionMinutes, id: \.self) { minutes in
                Button(String(format: String(localized: "player.extend_minutes"), minutes)) {
                    player?.extendSleepTimer(minutes: minutes)
                }
            }
        }
    }

    private var sleepSelection: Binding<SleepTimerOption?> {
        Binding(
            get: {
                guard let player else { return nil }
                return SleepTimerOption.selection(
                    isActive: player.sleepTimerActive,
                    isEndOfChapter: player.sleepTimerMode == "endOfChapter"
                )
            },
            set: { option in
                switch option {
                case .off: player?.cancelSleepTimer()
                case .minutes(let minutes): player?.setSleepTimer(minutes: minutes)
                case .endOfChapter: player?.setSleepTimerEndOfChapter()
                case nil: break
                }
            }
        )
    }
}
