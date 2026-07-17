import SwiftUI
import Shared

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

    // MARK: - Flattened state (local — device storage)

    private(set) var themeMode: ThemeMode = .system
    private(set) var autoRewindEnabled: Bool = true
    private(set) var wifiOnlyDownloads: Bool = true
    private(set) var hapticFeedbackEnabled: Bool = true
    private(set) var ignoreTitleArticles: Bool = true
    private(set) var hideSingleBookSeries: Bool = true

    // MARK: - Server info (read-only)

    private(set) var serverUrl: String?
    private(set) var serverVersion: String?

    private let viewModel: SettingsViewModel
    private let bridge = FlowBridge()

    /// Tears down native playback. Injected rather than reaching for `Dependencies` so the
    /// observer stays free of the player graph; the ordering it participates in is pinned by
    /// `SignOutSequenceTests`.
    private let stopPlayback: () async -> Void

    init(
        viewModel: SettingsViewModel,
        stopPlayback: @escaping () async -> Void
    ) {
        self.viewModel = viewModel
        self.stopPlayback = stopPlayback
        bridge.bind(viewModel.state) { [weak self] in self?.apply($0) }
    }

    deinit { bridge.cancelAll() }   // cancelAll() is nonisolated-safe; see FlowBridge.

    // MARK: - State mapping

    private func apply(_ state: SettingsUiState) {
        defaultPlaybackSpeed = state.defaultPlaybackSpeed
        defaultSkipForwardSec = Int(state.defaultSkipForwardSec)
        defaultSkipBackwardSec = Int(state.defaultSkipBackwardSec)
        defaultSleepTimerMin = state.defaultSleepTimerMin.map { Int($0) }
        themeMode = state.themeMode
        autoRewindEnabled = state.autoRewindEnabled
        wifiOnlyDownloads = state.wifiOnlyDownloads
        hapticFeedbackEnabled = state.hapticFeedbackEnabled
        ignoreTitleArticles = state.ignoreTitleArticles
        hideSingleBookSeries = state.hideSingleBookSeries
        serverUrl = state.serverUrl
        serverVersion = state.serverVersion
    }

    // MARK: - Actions (forward to the shared VM)

    func setThemeMode(_ mode: ThemeMode) { viewModel.setThemeMode(mode: mode) }
    func setDefaultPlaybackSpeed(_ speed: Float) { viewModel.setDefaultPlaybackSpeed(speed: speed) }
    func setDefaultSkipForwardSec(_ seconds: Int) { viewModel.setDefaultSkipForwardSec(seconds: Int32(seconds)) }
    func setDefaultSkipBackwardSec(_ seconds: Int) { viewModel.setDefaultSkipBackwardSec(seconds: Int32(seconds)) }
    func setAutoRewindEnabled(_ enabled: Bool) { viewModel.setAutoRewindEnabled(enabled: enabled) }

    func setDefaultSleepTimerMin(_ minutes: Int?) {
        viewModel.setDefaultSleepTimerMin(minutes: minutes.map { Int32($0) })
    }

    func setIgnoreTitleArticles(_ ignore: Bool) { viewModel.setIgnoreTitleArticles(ignore: ignore) }
    func setHideSingleBookSeries(_ hide: Bool) { viewModel.setHideSingleBookSeries(hide: hide) }
    func setWifiOnlyDownloads(_ enabled: Bool) { viewModel.setWifiOnlyDownloads(enabled: enabled) }
    func setHapticFeedbackEnabled(_ enabled: Bool) { viewModel.setHapticFeedbackEnabled(enabled: enabled) }
    /// Stops native playback, then runs the shared logout (server revoke + local teardown).
    ///
    /// The shared `LogoutUseCase` clears playback through `PlaybackStateProvider`, which only
    /// Android's `PlaybackManager` implements — iOS drives AVFoundation natively, so the shared
    /// flow can't stop the engine and audio would keep playing after sign-out. See
    /// [SignOutSequence] for why the order matters.
    func signOut() async {
        await SignOutSequence.run(
            stopPlayback: stopPlayback,
            clearSession: { self.viewModel.signOut() }
        )
    }
}

// MARK: - Pure formatting helpers

/// Pure value→label mappings for the settings rows. Kept free of the observer so the
/// branches are unit-testable rather than buried in the view (the Swift Export-bridged
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
