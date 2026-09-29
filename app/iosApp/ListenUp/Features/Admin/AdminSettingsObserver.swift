import Foundation
import Shared

/// Observes `AdminSettingsViewModel` — flattens the sealed `AdminSettingsUiState` into a
/// SwiftUI-native `AdminSettingsPhase` for the SERVER section of the admin screen.
///
/// Server name and remote URL are edit-buffer fields the user mutates locally; `isDirty`
/// gates the Save affordance and `isSaving` shows progress. A failure surfaces the typed
/// `AppError`'s message — iOS maps the typed error to inline state, never the Compose
/// errorBus (iosApp rule 10).
///
/// Thin over `FlowBridge`, mirroring `SettingsObserver`.
@Observable
@MainActor
final class AdminSettingsObserver {
    // MARK: - State

    private(set) var phase: AdminSettingsPhase = .loading

    // MARK: - Dependencies

    private let viewModel: AdminSettingsViewModel
    private let bridge = FlowBridge()

    // MARK: - Init

    init(viewModel: AdminSettingsViewModel) {
        self.viewModel = viewModel
        bridge.bind(viewModel.state) { [weak self] in self?.apply($0) }
    }

    deinit { bridge.cancelAll() }   // cancelAll() is nonisolated-safe; see FlowBridge.

    // MARK: - Actions

    func reload() { viewModel.loadSettings() }
    func setServerName(_ name: String) { viewModel.setServerName(name: name) }
    func setRemoteUrl(_ url: String) { viewModel.setRemoteUrl(url: url) }
    func setHoldNewBooksForReview(_ enabled: Bool) { viewModel.setHoldNewBooksForReview(enabled: enabled) }

    func setPushNotificationsEnabled(_ enabled: Bool) { viewModel.setPushNotificationsEnabled(enabled: enabled) }

    func setRatingSourceEnabled(_ source: ExternalRatingSource, _ enabled: Bool) {
        viewModel.setRatingSourceEnabled(source: source, enabled: enabled)
    }

    func save() { viewModel.saveAll() }
    func clearError() { viewModel.clearError() }

    // MARK: - State mapping

    private func apply(_ state: AdminSettingsUiState) {
        switch state.sealedType() {
        case .loading:
            phase = .loading
        case .ready(let readyType):
            let ready = readyType.value
            phase = .ready(AdminSettingsReadyModel.from(ready))
        case .error(let errorType):
            let error = errorType.value
            phase = .error(message: error.error.message)
        }
    }
}

// MARK: - Phase

/// Flattened server-settings state for a SwiftUI `switch`.
enum AdminSettingsPhase: Equatable {
    case loading
    case ready(AdminSettingsReadyModel)
    case error(message: String)
}

/// The flattened SERVER edit-buffer the screen binds to.
struct AdminSettingsReadyModel: Equatable {
    let serverName: String
    let remoteUrl: String
    let holdNewBooksForReview: Bool
    let pushNotificationsEnabled: Bool
    /// Every outside rating source, with its enabled flag and last-fetch health.
    let ratingSources: [RatingSourceRowModel]
    let isDirty: Bool
    let isSaving: Bool
    /// Transient save/load failure message (nil when none), surfaced as an inline banner.
    let error: String?

    /// Pure mapping from the Swift Export-bridged KMP `Ready` state. `nonisolated` so tests can
    /// exercise it without a live observer or main-actor context.
    nonisolated static func from(_ ready: AdminSettingsUiStateReady) -> AdminSettingsReadyModel {
        AdminSettingsReadyModel(
            serverName: ready.serverName,
            remoteUrl: ready.remoteUrl,
            holdNewBooksForReview: ready.holdNewBooksForReview,
            pushNotificationsEnabled: ready.pushNotificationsEnabled,
            ratingSources: ready.ratingSources.map { RatingSourceRowModel.from($0) },
            isDirty: ready.isDirty,
            isSaving: ready.isSaving,
            error: ready.error?.message
        )
    }
}

/// One outside rating source in the admin Rating Sources list: its display name (via
/// `ExternalRatingSource.displayName`), a switch bound to `enabled`, and a health line, mirroring
/// Android's `RatingSourceRow`. The health line reads, first match wins: why the source cannot run
/// at all, then until when it has paused itself, then its last error, then when it last fetched,
/// then that it never has. Hardcover adds a second line naming whose account it fetches with.
/// An unavailable source's switch stays operable: switching it off is still meaningful.
struct RatingSourceRowModel: Equatable, Identifiable {
    let source: ExternalRatingSource
    let enabled: Bool
    let lastFetchedAtMs: Int64?
    let lastError: String?
    /// Set while the source has paused itself after repeated failures (epoch millis).
    let pausedUntilMs: Int64?
    /// Why the source cannot run at all, or nil when it can.
    let unavailable: RatingSourceUnavailable?
    /// The account a connection-backed source (Hardcover) fetches with.
    let connectionUsername: String?

    var id: ExternalRatingSource { source }

    nonisolated static func from(_ status: RatingSourceStatus) -> RatingSourceRowModel {
        RatingSourceRowModel(
            source: status.source,
            enabled: status.enabled,
            lastFetchedAtMs: status.lastFetchedAt,
            lastError: status.lastError,
            pausedUntilMs: status.pausedUntil,
            unavailable: status.unavailable,
            connectionUsername: status.connectionUsername
        )
    }

    /// The row's whole subtitle: the health line, then Hardcover's connection line beneath it.
    nonisolated func subtitle(now: Date = Date()) -> String {
        [healthLine(now: now), connectionLine].compactMap { $0 }.joined(separator: "\n")
    }

    /// "Using simonhull's Hardcover account" — Hardcover only, and only once someone has connected.
    nonisolated var connectionLine: String? {
        guard source == .hardcover, let connectionUsername else { return nil }
        return String(format: String(localized: "admin.rating_source_using_connection"), connectionUsername)
    }

    /// The health line, in the priority the type documents. A pause names a future date, so it
    /// reads as an absolute long date ("October 6, 2026"), not a relative phrase. `now`, `timeZone`
    /// and `locale` are injectable so tests get deterministic text.
    nonisolated func healthLine(
        now: Date = Date(),
        timeZone: TimeZone = .current,
        locale: Locale = .current
    ) -> String {
        if let unavailable {
            return Self.unavailableLine(unavailable)
        }
        if let pausedUntilMs {
            var style = Date.FormatStyle(date: .long, time: .omitted, locale: locale)
            style.timeZone = timeZone
            let until = Date(timeIntervalSince1970: Double(pausedUntilMs) / 1000).formatted(style)
            if let lastError {
                return String(format: String(localized: "admin.rating_source_paused"), until, lastError)
            }
            return String(format: String(localized: "admin.rating_source_paused_plain"), until)
        }
        if let lastError {
            return String(format: String(localized: "admin.rating_source_error"), lastError)
        }
        if let lastFetchedAtMs {
            let fetched = Date(timeIntervalSince1970: Double(lastFetchedAtMs) / 1000)
            let relative = RelativeDateTimeFormatter()
            relative.unitsStyle = .full
            let phrase = relative.localizedString(for: fetched, relativeTo: now)
            return String(format: String(localized: "admin.rating_source_last_fetched"), phrase)
        }
        return String(localized: "admin.rating_source_never_fetched")
    }

    private nonisolated static func unavailableLine(_ reason: RatingSourceUnavailable) -> String {
        switch reason {
        case .notConfigured: return String(localized: "admin.rating_source_not_configured")
        case .noConnection: return String(localized: "admin.rating_source_no_connection")
        case .unknown: return String(localized: "admin.rating_source_unavailable")
        }
    }
}
