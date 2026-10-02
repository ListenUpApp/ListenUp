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
/// `lastSyncedAt` is nil before the first sync, either way; `sync` is the sync line. `booksToMatch`
/// are the books ListenUp couldn't match, in the server's order; `isMatchListKnown` is false until
/// the server has answered, so only a known empty list says every book is matched. `shareMode` is when
/// ListenUp updates Hardcover: the server's, or the one just chosen while `isSavingShareMode`. `history`
/// is the earlier-books offer.
struct HardcoverConnectedModel: Equatable {
    let username: String
    let since: Date
    let isDisconnecting: Bool
    var lastSyncedAt: Date?
    var sync: HardcoverSyncLine = .idle
    var booksToMatch: [HardcoverBookToMatchRow] = []
    var isMatchListKnown = false
    var shareMode: HardcoverShareMode = .asIListen
    var isSavingShareMode = false
    var history: HardcoverHistoryModel = .none
}

/// One line of "What ListenUp shares", native, for a `ForEach` (iosApp rule 8). A quiet line says what
/// is NOT shared, in the secondary style.
struct HardcoverSharedLine: Equatable, Identifiable {
    let systemImage: String
    let text: String
    var isQuiet = false
    var id: String { text }
}

/// The Connected screen's sync line, each sentence already resolved.
enum HardcoverSyncLine: Equatable {
    /// Nothing in flight: when it last synced, and Sync Now.
    case idle
    /// A sync is running: Sync Now waits.
    case syncing
    /// The Sync Now just pressed didn't finish. The line stays idle — Sync Now is the retry — with
    /// this brief notice beneath it.
    case syncNowFailed(String)
    /// A push or a pull is stuck past its retries: this sentence, with Try Again, in place of the line.
    case stalled(String)
}

/// One book that needs a match, native, for a `ForEach` (iosApp rule 8).
struct HardcoverBookToMatchRow: Equatable, Identifiable {
    let id: String
    let title: String
    let authorNames: String
    let coverPath: String?
    let coverHash: String?
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

    /// Sync Now, or Try Again beside a stuck sync. The ViewModel ignores a press while one is in flight.
    func syncNow() { viewModel.syncNow() }

    /// Update Hardcover: As I listen, or Only when I finish. The ViewModel shows the choice at once and
    /// puts the server's back, with an alert, if the server refuses it.
    func setShareMode(_ mode: HardcoverShareMode) { viewModel.setShareMode(mode: mode) }

    /// Send the books finished before connecting — from the section or the quiet row. The ViewModel shows
    /// Sending at once, and puts the offer back, with an alert, if the server refuses.
    func sendHistory() { viewModel.sendHistory() }

    /// Not Now on the offer, or Done on what the send came to.
    func dismissHistory() { viewModel.dismissHistory() }

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
                    isDisconnecting: connected.isDisconnecting,
                    lastSyncedAt: connected.lastSyncedAt.map { Date(timeIntervalSince1970: Double($0) / 1_000) },
                    sync: syncLine(from: connected.sync),
                    booksToMatch: connected.booksToMatch.map {
                        HardcoverBookToMatchRow(
                            id: $0.bookId,
                            title: $0.title,
                            authorNames: $0.authorNames,
                            coverPath: $0.coverPath,
                            coverHash: $0.coverHash
                        )
                    },
                    isMatchListKnown: connected.isMatchListKnown,
                    shareMode: connected.shareMode,
                    isSavingShareMode: connected.isSavingShareMode,
                    history: HardcoverHistoryModel.from(connected.history)
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

    nonisolated static func syncLine(from status: HardcoverSyncStatus) -> HardcoverSyncLine {
        switch status.sealedType() {
        case .idle: .idle
        case .syncing: .syncing
        case .problem(let problemType): syncLine(for: problemType.value.problem)
        }
    }

    // Deliberately no `default`: a new problem must fail to compile here rather than borrow another's
    // words. A failed Sync Now is a passing notice; a stuck push or pull is a standing problem.
    nonisolated static func syncLine(for problem: HardcoverSyncProblem) -> HardcoverSyncLine {
        switch problem {
        case .syncNowFailed: .syncNowFailed(String(localized: "hardcover.sync_now_failed_notice"))
        case .pushStalled: .stalled(String(localized: "hardcover.problem_push_stalled"))
        case .pullStalled: .stalled(String(localized: "hardcover.problem_pull_stalled"))
        }
    }

    /// Each mode's name, for the Update Hardcover menu, in title style: HIG, Menus — "To be consistent
    /// with platform experiences, use title-style capitalization." Deliberately no `default`: a new mode
    /// must fail to compile here rather than borrow a name.
    nonisolated static func shareModeLabel(_ mode: HardcoverShareMode) -> String {
        switch mode {
        case .asIListen: String(localized: "hardcover.share_mode_as_i_listen").titleStyled
        case .finishedOnly: String(localized: "hardcover.share_mode_finished_only").titleStyled
        }
    }

    /// What the chosen mode sends to Hardcover, row by row; Only when I finish ends with a quiet line
    /// saying nothing is shared while listening.
    nonisolated static func sharedLines(for mode: HardcoverShareMode) -> [HardcoverSharedLine] {
        switch mode {
        case .asIListen:
            [
                HardcoverSharedLine(systemImage: "book", text: String(localized: "hardcover.shared_started_row")),
                HardcoverSharedLine(
                    systemImage: "headphones",
                    text: String(localized: "hardcover.shared_progress_row")
                ),
                HardcoverSharedLine(systemImage: "checkmark", text: String(localized: "hardcover.shared_finished_row"))
            ]
        case .finishedOnly:
            [
                HardcoverSharedLine(
                    systemImage: "checkmark",
                    text: String(localized: "hardcover.shared_finished_with_dates_row")
                ),
                HardcoverSharedLine(
                    systemImage: "headphones",
                    text: String(localized: "hardcover.shared_nothing_while_listening"),
                    isQuiet: true
                )
            ]
        }
    }

    /// "Last synced 5 minutes ago", "Last synced just now", or "Not synced yet". The relative phrase is
    /// the system's own, so it reads naturally in every language the app ships.
    nonisolated static func lastSyncedText(_ date: Date?, now: Date) -> String {
        guard let date else { return String(localized: "hardcover.never_synced") }
        if now.timeIntervalSince(date) < 60 { return String(localized: "hardcover.last_synced_just_now") }
        let relative = RelativeDateTimeFormatter()
        relative.unitsStyle = .full
        return String(
            format: String(localized: "hardcover.last_synced"),
            relative.localizedString(for: date, relativeTo: now)
        )
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
