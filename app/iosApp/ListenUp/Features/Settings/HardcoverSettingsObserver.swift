import SwiftUI
import Shared

/// Render phase for the Hardcover screen, flattened from `HardcoverSettingsUiState`. Every value is
/// native, and every sentence is already resolved, so the view never reaches across the bridge.
enum HardcoverPhase: Equatable {
    case loading
    case notOffered
    /// Not connected. `failureMessage` says why the last attempt ended, when one just did.
    case notConnected(failureMessage: String?, isStarting: Bool)
    case linking(HardcoverLinkingModel)
    case connected(HardcoverConnectedModel)
    case broken(HardcoverBrokenModel)
}

/// Waiting for the user to approve `userCode` at `address` (the verification page without its
/// scheme, for typing on another device). `pageURL` is the pre-filled approval page.
struct HardcoverLinkingModel: Equatable {
    let userCode: String
    let address: String
    let pageURL: URL?
}

/// Connected as `username` since `since`. `isDisconnecting` while Disconnect is in flight.
struct HardcoverConnectedModel: Equatable {
    let username: String
    let since: Date
    let isDisconnecting: Bool
}

/// Needs a reconnect, for the reason `reasonMessage` explains. `username` is who it was connected
/// as, when the server still knows. `isStarting` while Reconnect is in flight.
struct HardcoverBrokenModel: Equatable {
    let reasonMessage: String
    let username: String?
    let isStarting: Bool
}

/// What a one-shot `HardcoverSettingsEvent` does on iOS.
enum HardcoverEffect: Equatable {
    /// Open the approval page in the browser.
    case open(URL)
    /// Show this message in an alert.
    case alert(String)
}

/// Observes `HardcoverSettingsViewModel`: flattens its state into a native `HardcoverPhase`, opens
/// the approval page when the ViewModel asks, and turns its errors into an alert (iosApp rule 10).
///
/// The server owns the connection and does the waiting, so nothing here moves the screen by
/// itself: Connect and Disconnect change the server's state, and the stream carries it back.
@Observable
@MainActor
final class HardcoverSettingsObserver {
    private(set) var phase: HardcoverPhase = .loading
    /// The error to show, from a failed Connect or Disconnect. Cleared when the alert is dismissed.
    var alert: MessageAlert?

    private let viewModel: HardcoverSettingsViewModel
    private let openURL: @MainActor (URL) -> Void
    private let bridge = FlowBridge()

    /// `openURL` is the view's `OpenURLAction`, captured when the observer is built, so the
    /// ViewModel's "open the approval page" lands in the same place a tapped link would.
    init(viewModel: HardcoverSettingsViewModel, openURL: @escaping @MainActor (URL) -> Void) {
        self.viewModel = viewModel
        self.openURL = openURL
        bridge.bind(viewModel.uiState) { [weak self] in self?.phase = Self.phase(from: $0) }
        bridge.bind(viewModel.events) { [weak self] in self?.apply(Self.effect(of: $0)) }
    }

    deinit { bridge.cancelAll() }   // cancelAll() is nonisolated-safe; see FlowBridge.

    // MARK: - Actions

    /// Connect, or reconnect from Broken. The ViewModel opens the approval page when it's ready.
    func connect() { viewModel.connect() }

    /// Disconnect, or cancel a pending sign-in. Callers confirm first, except for Cancel.
    func disconnect() { viewModel.disconnect() }

    // MARK: - Event routing

    private func apply(_ effect: HardcoverEffect?) {
        switch effect {
        case .open(let url): openURL(url)
        case .alert(let message): alert = MessageAlert(message: message)
        case nil: break
        }
    }

    // MARK: - Pure mappings (unit-tested)

    /// Projects the shared UI state onto the screen's phase. `nonisolated` so tests can run it off
    /// the main actor.
    nonisolated static func phase(from state: HardcoverSettingsUiState) -> HardcoverPhase {
        switch state.sealedType() {
        case .loading:
            return .loading
        case .notOffered:
            return .notOffered
        case .notConnected(let notConnectedType):
            let notConnected = notConnectedType.value
            return .notConnected(
                failureMessage: notConnected.lastFailure.map(failureMessage(for:)),
                isStarting: notConnected.isStarting
            )
        case .linking(let linkingType):
            let linking = linkingType.value
            return .linking(
                HardcoverLinkingModel(
                    userCode: linking.userCode,
                    address: withoutScheme(linking.verificationUri),
                    pageURL: URL(string: linking.verificationUriComplete)
                )
            )
        case .connected(let connectedType):
            let connected = connectedType.value
            return .connected(
                HardcoverConnectedModel(
                    username: connected.username,
                    since: Date(timeIntervalSince1970: Double(connected.since) / 1_000),
                    isDisconnecting: connected.isDisconnecting
                )
            )
        case .broken(let brokenType):
            let broken = brokenType.value
            return .broken(
                HardcoverBrokenModel(
                    reasonMessage: reasonMessage(for: broken.reason),
                    username: broken.username,
                    isStarting: broken.isStarting
                )
            )
        }
    }

    /// What an event does. Nil only for an approval page the server sent as an unparseable URL.
    nonisolated static func effect(of event: HardcoverSettingsEvent) -> HardcoverEffect? {
        switch event.sealedType() {
        case .openVerificationPage(let openType):
            return URL(string: openType.value.url).map(HardcoverEffect.open)
        case .showError(let showErrorType):
            return .alert(showErrorType.value.error.message)
        }
    }

    /// The sentence VoiceOver announces when an approval lands while the user waits on this screen.
    /// Nil for every other change: opening the screen already connected isn't news, and neither is
    /// a busy flag flipping.
    nonisolated static func announcement(from old: HardcoverPhase, to new: HardcoverPhase) -> String? {
        guard case .linking = old, case .connected(let connected) = new else { return nil }
        return String(format: String(localized: "hardcover.row_subtitle_connected"), connected.username)
    }

    // Deliberately no `default` branches: a new reason must fail to compile here rather than
    // borrow another's explanation.
    nonisolated private static func failureMessage(for failure: HardcoverLinkFailure) -> String {
        switch failure {
        case .denied: String(localized: "hardcover.failure_denied")
        case .expired: String(localized: "hardcover.failure_expired")
        case .unreachable: String(localized: "hardcover.failure_unreachable")
        }
    }

    nonisolated private static func reasonMessage(for reason: HardcoverBrokenReason) -> String {
        switch reason {
        case .revoked: String(localized: "hardcover.broken_revoked")
        case .cannotDecrypt: String(localized: "hardcover.broken_cannot_decrypt")
        case .missingScope: String(localized: "hardcover.broken_missing_scope")
        }
    }

    /// "https://hardcover.app/link" → "hardcover.app/link": what a person types on another device.
    nonisolated private static func withoutScheme(_ uri: String) -> String {
        guard let range = uri.range(of: "://") else { return uri }
        return String(uri[range.upperBound...])
    }
}

// MARK: - Settings row

/// The Settings › Account › Hardcover row, native. `nil` hides the row: before the server's first
/// answer, and for good on a server with no Hardcover app.
enum HardcoverRowValue: Equatable {
    case notConnected
    case connected(username: String)
    case connecting
    case needsAttention

    init?(from state: HardcoverRowState?) {
        guard let state else { return nil }
        switch state.sealedType() {
        case .notConnected: self = .notConnected
        case .connected(let connectedType): self = .connected(username: connectedType.value.username)
        case .connecting: self = .connecting
        case .needsAttention: self = .needsAttention
        }
    }

    /// The row's trailing value: who you're connected as, or what the connection needs. Blank when
    /// not connected — the row's title is invitation enough.
    var trailingText: String? {
        switch self {
        case .notConnected: nil
        case .connected(let username): username
        case .connecting: String(localized: "hardcover.row_value_connecting")
        case .needsAttention: String(localized: "hardcover.reconnect")
        }
    }
}
