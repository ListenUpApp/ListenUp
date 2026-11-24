import ListenupContract
import SwiftUI
import Shared

/// Observes `SettingsViewModel`, flattening the shared `SettingsUiState` into flat
/// `@Observable` properties the SwiftUI settings screen binds to, and forwarding the
/// VM's setters/actions. Thin over `FlowBridge`, mirroring `FacetBooksObserver`.
///
/// `SettingsUiState` is a plain Kotlin `data class` (not a sealed hierarchy), so its
/// fields bridge directly — no `sealedType()` flatten is needed. The observer exists to give
/// SwiftUI a `@MainActor`-isolated, `@Observable` surface and to host the small pure
/// formatting helpers (`skipLabel`, `sleepTimerLabel`, `themeLabel`) that the view
/// renders and the tests pin.
@Observable
final class SettingsObserver {
    // MARK: - Flattened state (synced — server storage)

    private(set) var defaultPlaybackSpeed: Float = 1.0
    private(set) var defaultVolumeBoostDb: Float = 0
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
    private(set) var appVersion: String = ""

    // MARK: - Hardcover row

    /// The Account › Hardcover row. Nil hides it — before the server answers, and for good on a
    /// server with no Hardcover app.
    private(set) var hardcoverRow: HardcoverRowValue?

    // MARK: - Curation

    /// Whether Settings › Library offers Categories: members with Curate library, and admins.
    private(set) var canCurateLibrary = false

    // MARK: - Send a test notification

    /// The Send a test notification row's outcome. Held until the next tap, so the user can compare "sent" with what
    /// their notifications actually did.
    private(set) var testNotification: TestNotificationStatus = .idle

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
        bridge.own(viewModel)
        self.stopPlayback = stopPlayback
        bridge.bind(viewModel.state) { [weak self] in self?.apply($0) }
        bridge.bind(viewModel.hardcoverRow) { [weak self] in self?.hardcoverRow = HardcoverRowValue(from: $0) }
        bridge.bind(viewModel.canCurateLibrary) { [weak self] value in self?.canCurateLibrary = value }
        bridge.bind(viewModel.events) { [weak self] in self?.apply($0) }
    }

    // MARK: - State mapping

    func apply(_ state: SettingsUiState) {
        defaultPlaybackSpeed = state.defaultPlaybackSpeed
        defaultVolumeBoostDb = state.defaultVolumeBoostDb
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
        appVersion = state.appVersion
    }

    /// A test send's answer. VoiceOver hears it, since the row's subtitle changing is otherwise silent.
    func apply(_ event: SettingsEvent) {
        testNotification = TestNotificationStatus(event)
        VoiceOverAnnouncement.post(testNotification.detail)
    }

    // MARK: - Actions (forward to the shared VM)

    func setThemeMode(_ mode: ThemeMode) { viewModel.setThemeMode(mode: mode) }
    func setDefaultPlaybackSpeed(_ speed: Float) { viewModel.setDefaultPlaybackSpeed(speed: speed) }
    func setDefaultVolumeBoostDb(_ db: Float) { viewModel.setDefaultVolumeBoostDb(boostDb: db) }
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

    /// Asks the server to push a test notification to this device. A second tap while one is in flight does nothing.
    func sendTestNotification() {
        guard !testNotification.isSending else { return }
        testNotification = .sending
        viewModel.sendTestNotification()
    }
    /// Stops native playback, then runs the shared logout (server revoke + local teardown).
    ///
    /// The shared `LogoutUseCase` clears playback through `PlaybackStateProvider` too, but on iOS
    /// that only queues a Stop for the engine; awaiting the native teardown first is what keeps a
    /// stream from outliving the session. See [SignOutSequence] for why the order matters.
    func signOut() async {
        await SignOutSequence.run(
            stopPlayback: stopPlayback,
            clearSession: { self.viewModel.signOut() }
        )
    }
}

/// Where a Send a test notification stands, as a value the row renders and the tests pin.
///
/// "Sent" means the server accepted the push, not that it arrived: whether a notification appears is the relay's,
/// the OS's and the permission's business, and the gap between the two is what the user is checking. A refusal
/// carries the typed error's message; the shared error alert reports it too.
enum TestNotificationStatus: Equatable {
    case idle, sending, sent
    case failed(message: String)

    init(_ event: SettingsEvent) {
        switch event.sealedType() {
        case .testNotificationSent: self = .sent
        case .testNotificationFailed(let failed): self = .failed(message: failed.value.error.message)
        }
    }

    /// A send is in flight: the row holds still until its answer.
    var isSending: Bool { self == .sending }

    /// The row's subtitle: what the button does, or what the last send came to.
    var detail: String {
        switch self {
        case .idle, .sending: String(localized: "settings.test_notification_subtitle")
        case .sent: String(localized: "settings.test_notification_sent")
        case .failed(let message): message
        }
    }
}

// MARK: - Pure formatting helpers

/// Pure value→label mappings for the settings rows. Kept free of the observer so the
/// branches are unit-testable rather than buried in the view.
enum SettingsFormat {
    /// "30s" / "1m" / "1m 30s" for a skip interval expressed in seconds.
    static func skipLabel(seconds: Int) -> String {
        if seconds < 60 { return "\(seconds)s" }
        let minutes = seconds / 60
        let remainder = seconds % 60
        return remainder == 0 ? "\(minutes)m" : "\(minutes)m \(remainder)s"
    }

    /// "Off" when nil, otherwise "N min", for the default sleep-timer row.
    static func sleepTimerLabel(minutes: Int?, offLabel: String) -> String {
        guard let minutes else { return offLabel }
        return "\(minutes) min"
    }

    /// What the default-timer picker offers: nil for Off, then the shared ladder, with [current] added in order when it
    /// is something else, so a stored value is a checked row (shared `SleepTimerChoices`).
    static func sleepTimerChoices(including current: Int?) -> [Int?] {
        SleepTimerChoices.shared.defaultChoicesIncluding(current: current.map { Int32($0) }).map { $0.map { Int($0) } }
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
