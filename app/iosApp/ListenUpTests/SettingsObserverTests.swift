import Shared
import Testing
@testable import ListenUp

/// Coverage for the settings screen: the value→label formatting, and the shared
/// `SettingsUiState` → flat `SettingsObserver` mapping.
@Suite("SettingsFormat")
struct SettingsObserverTests {
    @Test func skipUnderOneMinuteIsSeconds() {
        #expect(SettingsFormat.skipLabel(seconds: 5) == "5s")
        #expect(SettingsFormat.skipLabel(seconds: 30) == "30s")
    }

    @Test func skipWholeMinutesDropTheSeconds() {
        #expect(SettingsFormat.skipLabel(seconds: 60) == "1m")
        #expect(SettingsFormat.skipLabel(seconds: 120) == "2m")
    }

    @Test func skipMixedMinutesAndSeconds() {
        #expect(SettingsFormat.skipLabel(seconds: 90) == "1m 30s")
    }

    @Test func speedIsFixedToOneDecimal() {
        #expect(SettingsFormat.speedLabel(1.0) == "1.0×")
        #expect(SettingsFormat.speedLabel(1.5) == "1.5×")
    }

    @Test func sleepTimerNilUsesOffLabel() {
        #expect(SettingsFormat.sleepTimerLabel(minutes: nil, offLabel: "Off") == "Off")
    }

    @Test func sleepTimerMinutesAreLabeled() {
        #expect(SettingsFormat.sleepTimerLabel(minutes: 30, offLabel: "Off") == "30 min")
    }

    @Test func themeKeyMapsEachMode() {
        #expect(SettingsFormat.themeKey(.system) == "settings.theme_automatic")
        #expect(SettingsFormat.themeKey(.light) == "settings.theme_light")
        #expect(SettingsFormat.themeKey(.dark) == "settings.theme_dark")
    }

    // MARK: - State mapping

    @MainActor private func makeObserver() -> SettingsObserver {
        SettingsObserver(viewModel: Dependencies.shared.createSettingsViewModel(), stopPlayback: {})
    }

    /// Every field the observer reads gets a value no other field shares, so a swapped pair shows.
    private var distinctiveState: SettingsUiState {
        SettingsUiState(
            isLoading: false,
            isSyncing: false,
            syncError: nil,
            defaultPlaybackSpeed: 1.75,
            defaultVolumeBoostDb: 4.5,
            defaultSkipForwardSec: 45,
            defaultSkipBackwardSec: 15,
            defaultSleepTimerMin: 20,
            themeMode: .dark,
            dynamicColorsEnabled: true,
            autoRewindEnabled: false,
            wifiOnlyDownloads: false,
            hapticFeedbackEnabled: false,
            ignoreTitleArticles: false,
            hideSingleBookSeries: false,
            serverUrl: "https://books.example",
            serverVersion: "0.9.8",
            appVersion: "0.9.9"
        )
    }

    /// The five switches, with only the one at `index` on.
    private func stateWithOnlyFlag(_ index: Int) -> SettingsUiState {
        SettingsUiState(
            isLoading: false,
            isSyncing: false,
            syncError: nil,
            defaultPlaybackSpeed: 0.8,
            defaultVolumeBoostDb: 0,
            defaultSkipForwardSec: 60,
            defaultSkipBackwardSec: 5,
            defaultSleepTimerMin: nil,
            themeMode: .light,
            dynamicColorsEnabled: false,
            autoRewindEnabled: index == 0,
            wifiOnlyDownloads: index == 1,
            hapticFeedbackEnabled: index == 2,
            ignoreTitleArticles: index == 3,
            hideSingleBookSeries: index == 4,
            serverUrl: nil,
            serverVersion: nil,
            appVersion: "1.0.0"
        )
    }

    @MainActor private func flags(of observer: SettingsObserver) -> [Bool] {
        [
            observer.autoRewindEnabled,
            observer.wifiOnlyDownloads,
            observer.hapticFeedbackEnabled,
            observer.ignoreTitleArticles,
            observer.hideSingleBookSeries
        ]
    }

    @MainActor @Test func applyMapsEveryField() {
        let observer = makeObserver()
        observer.apply(distinctiveState)

        #expect(observer.defaultPlaybackSpeed == 1.75)
        #expect(observer.defaultVolumeBoostDb == 4.5)
        #expect(observer.defaultSkipForwardSec == 45)
        #expect(observer.defaultSkipBackwardSec == 15)
        #expect(observer.defaultSleepTimerMin == 20)
        #expect(SettingsFormat.themeKey(observer.themeMode) == "settings.theme_dark")
        #expect(flags(of: observer) == [false, false, false, false, false])
        #expect(observer.serverUrl == "https://books.example")
        #expect(observer.serverVersion == "0.9.8")
        #expect(observer.appVersion == "0.9.9")
    }

    /// One switch on at a time: the five are told apart, not just copied as a block.
    @MainActor @Test func applyKeepsEachSwitchInItsOwnField() {
        let observer = makeObserver()
        for index in 0..<5 {
            observer.apply(stateWithOnlyFlag(index))
            #expect(flags(of: observer) == (0..<5).map { $0 == index })
        }
    }

    @MainActor @Test func aSecondStateReplacesEveryField() {
        let observer = makeObserver()
        observer.apply(distinctiveState)
        observer.apply(stateWithOnlyFlag(1))

        #expect(observer.defaultPlaybackSpeed == 0.8)
        #expect(observer.defaultVolumeBoostDb == 0)
        #expect(observer.defaultSkipForwardSec == 60)
        #expect(observer.defaultSkipBackwardSec == 5)
        #expect(observer.defaultSleepTimerMin == nil)
        #expect(SettingsFormat.themeKey(observer.themeMode) == "settings.theme_light")
        #expect(flags(of: observer) == [false, true, false, false, false])
        #expect(observer.serverUrl == nil)
        #expect(observer.serverVersion == nil)
        #expect(observer.appVersion == "1.0.0")
    }
}
