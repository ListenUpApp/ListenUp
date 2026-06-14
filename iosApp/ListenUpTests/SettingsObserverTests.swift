import Testing
@testable import ListenUp

/// Pure-seam coverage for the settings screen.
///
/// The shared `SettingsUiState` → flat `SettingsObserver` mapping can't be exercised
/// here: SKIE bridges `SettingsUiState` as a type whose instances aren't constructible
/// from Swift, so behavioural verification of `apply(_:)` lands at the green-build pass
/// (the app target proves the field mapping compiles). What *is* pure and constructible
/// is the value→label formatting, so those seams are pinned here.
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
}
