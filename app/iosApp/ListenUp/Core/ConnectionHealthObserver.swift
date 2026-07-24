import SwiftUI
@preconcurrency import Shared

/// Observes the shared `ConnectionHealthViewModel` and exposes its state as SwiftUI-native state.
/// Drives the connection-health banner (Update-available) while the shell is mounted — the iOS
/// counterpart to the Compose `ConnectionHealthBanner`. `AuthState.sessionLapsed` keeps its own
/// dedicated banner (`SessionLapsedBanner`, via `AuthStateObserver`); this covers the one state
/// that occurs *while authenticated*: a meaningful client/server version skew. There is deliberately
/// no ambient "offline" state here — ListenUp is offline-first, so connectivity is surfaced only at
/// point of need (book detail, player), never as a shell-wide banner.
///
/// Mirrors `AuthStateObserver`: bind the KMP flow once, map to a native value enum at the boundary
/// so SwiftUI never diffs a bridged Kotlin object.
@Observable
@MainActor
final class ConnectionHealthObserver {

    // MARK: - State

    private(set) var kind: ConnectionHealthKind = .hidden

    // MARK: - Dependencies

    private let viewModel: ConnectionHealthViewModel
    private let bridge = FlowBridge()

    // MARK: - Init

    init(viewModel: ConnectionHealthViewModel = KoinHelper.shared.getConnectionHealthViewModel()) {
        self.viewModel = viewModel
        bridge.bind(viewModel.state) { [weak self] ui in
            self?.apply(ui)
        }
    }

    deinit { bridge.cancelAll() }   // cancelAll() is nonisolated-safe; see FlowBridge.

    // MARK: - Actions

    /// Dismiss the current Update-available hint — persisted per (client, server) pair.
    func dismiss() { viewModel.dismiss() }

    // MARK: - Mapping

    private func apply(_ ui: ConnectionHealthUi) {
        switch onEnum(of: ui) {
        case .hidden:
            kind = .hidden
        case .sessionExpired:
            kind = .sessionExpired
        case .outdated(let outdated):
            kind = .outdated(clientVersion: outdated.clientVersion, serverVersion: outdated.serverVersion)
        case .unknown:
            Log.error("Unexpected ConnectionHealthUi case")
            kind = .hidden
        }
    }
}

/// Flattened connection-health state for SwiftUI `switch` — a native value type off the Kotlin diff path.
enum ConnectionHealthKind: Equatable {
    case hidden
    /// Session credentials dead — surfaced by `SessionLapsedBanner` via `AuthStateObserver`, not here.
    case sessionExpired
    /// A meaningful client/server version skew. Non-blocking "Update available" hint.
    case outdated(clientVersion: String, serverVersion: String)
}
