import SwiftUI
@preconcurrency import Shared

/// Observes `SettingsViewModel`, flattening the shared `SettingsUiState` into flat
/// `@Observable` properties the SwiftUI settings screen binds to, and forwarding the
/// VM's setters/actions. Thin over `FlowBridge`, mirroring `TagDetailObserver`.
///
/// `SettingsUiState` is a plain Kotlin `data class` (not a sealed hierarchy), so its
/// fields bridge directly — no `onEnum` flatten is needed. The observer exists to give
/// SwiftUI a `@MainActor`-isolated, `@Observable` surface and to host the small pure
/// formatting helpers (`skipLabel`, `sleepTimerLabel`, `themeLabel`) that the view
/// renders and the tests pin.
@Observable
@MainActor
final class SettingsObserver {
    // MARK: - Flattened state (synced — server storage)

    private(set) var defaultPlaybackSpeed: Float = 1.0
    private(set) var defaultSkipForwardSec: Int = 30
    private(set) var defaultSkipBackwardSec: Int = 10
    private(set) var defaultSleepTimerMin: Int?
    private(set) var shakeToResetSleepTimer: Bool = false

    // MARK: - Flattened state (local — device storage)

    private(set) var themeMode: ThemeMode = .system
    private(set) var dynamicColorsEnabled: Bool = true
    private(set) var autoRewindEnabled: Bool = true
    private(set) var wifiOnlyDownloads: Bool = true
    private(set) var autoRemoveFinished: Bool = false
    private(set) var hapticFeedbackEnabled: Bool = true
    private(set) var spatialPlayback: Bool = true
    private(set) var ignoreTitleArticles: Bool = true
    private(set) var hideSingleBookSeries: Bool = true

    // MARK: - Server info (read-only)

    private(set) var serverUrl: String?
    private(set) var serverVersion: String?

    private let viewModel: SettingsViewModel
    private let bridge = FlowBridge()

    init(viewModel: SettingsViewModel) {
        self.viewModel = viewModel
        bridge.bind(viewModel.state) { [weak self] in self?.apply($0) }
    }

    deinit {
        // Held in SwiftUI `@State` on a `@MainActor` view, so dealloc is main-thread.
        MainActor.assumeIsolated { bridge.cancelAll() }
    }

    func stopObserving() { bridge.cancelAll() }

    // MARK: - State mapping

    private func apply(_ state: SettingsUiState) {
        defaultPlaybackSpeed = state.defaultPlaybackSpeed
        defaultSkipForwardSec = Int(state.defaultSkipForwardSec)
        defaultSkipBackwardSec = Int(state.defaultSkipBackwardSec)
        defaultSleepTimerMin = state.defaultSleepTimerMin.map { Int(truncating: $0) }
        shakeToResetSleepTimer = state.shakeToResetSleepTimer
        themeMode = state.themeMode
        dynamicColorsEnabled = state.dynamicColorsEnabled
        autoRewindEnabled = state.autoRewindEnabled
        wifiOnlyDownloads = state.wifiOnlyDownloads
        autoRemoveFinished = state.autoRemoveFinished
        hapticFeedbackEnabled = state.hapticFeedbackEnabled
        spatialPlayback = state.spatialPlayback
        ignoreTitleArticles = state.ignoreTitleArticles
        hideSingleBookSeries = state.hideSingleBookSeries
        serverUrl = state.serverUrl
        serverVersion = state.serverVersion
    }

    // MARK: - Actions (forward to the shared VM)

    func setThemeMode(_ mode: ThemeMode) { viewModel.setThemeMode(mode: mode) }
    func setDynamicColorsEnabled(_ enabled: Bool) { viewModel.setDynamicColorsEnabled(enabled: enabled) }
    func setDefaultPlaybackSpeed(_ speed: Float) { viewModel.setDefaultPlaybackSpeed(speed: speed) }
    func setDefaultSkipForwardSec(_ seconds: Int) { viewModel.setDefaultSkipForwardSec(seconds: Int32(seconds)) }
    func setDefaultSkipBackwardSec(_ seconds: Int) { viewModel.setDefaultSkipBackwardSec(seconds: Int32(seconds)) }
    func setAutoRewindEnabled(_ enabled: Bool) { viewModel.setAutoRewindEnabled(enabled: enabled) }
    func setSpatialPlayback(_ enabled: Bool) { viewModel.setSpatialPlayback(enabled: enabled) }

    func setDefaultSleepTimerMin(_ minutes: Int?) {
        viewModel.setDefaultSleepTimerMin(minutes: minutes.map { KotlinInt(int: Int32($0)) })
    }

    func setShakeToResetSleepTimer(_ enabled: Bool) { viewModel.setShakeToResetSleepTimer(enabled: enabled) }
    func setIgnoreTitleArticles(_ ignore: Bool) { viewModel.setIgnoreTitleArticles(ignore: ignore) }
    func setHideSingleBookSeries(_ hide: Bool) { viewModel.setHideSingleBookSeries(hide: hide) }
    func setWifiOnlyDownloads(_ enabled: Bool) { viewModel.setWifiOnlyDownloads(enabled: enabled) }
    func setAutoRemoveFinished(_ enabled: Bool) { viewModel.setAutoRemoveFinished(enabled: enabled) }
    func setHapticFeedbackEnabled(_ enabled: Bool) { viewModel.setHapticFeedbackEnabled(enabled: enabled) }
    func signOut() { viewModel.signOut() }
}

// MARK: - Pure formatting helpers

/// Pure value→label mappings for the settings rows. Kept free of the observer so the
/// branches are unit-testable rather than buried in the view (the SKIE-bridged
/// `SettingsUiState` itself can't be constructed from Swift, so these helpers are the
/// meaningful pure seam).
enum SettingsFormat {
    /// "30s" / "1m" / "1m 30s" for a skip interval expressed in seconds.
    static func skipLabel(seconds: Int) -> String {
        if seconds < 60 { return "\(seconds)s" }
        let minutes = seconds / 60
        let remainder = seconds % 60
        return remainder == 0 ? "\(minutes)m" : "\(minutes)m \(remainder)s"
    }

    /// "1.0×" for a playback-speed multiplier, fixed to one decimal place.
    static func speedLabel(_ speed: Float) -> String {
        String(format: "%.1f×", speed)
    }

    /// "Off" when nil, otherwise "N min", for the default sleep-timer row.
    static func sleepTimerLabel(minutes: Int?, offLabel: String) -> String {
        guard let minutes else { return offLabel }
        return "\(minutes) min"
    }

    /// The localized-string key for a theme mode, used by the picker.
    static func themeKey(_ mode: ThemeMode) -> String {
        switch mode {
        case .light: "settings.theme_light"
        case .dark: "settings.theme_dark"
        default: "settings.theme_automatic"
        }
    }
}
