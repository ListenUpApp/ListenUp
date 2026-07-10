import Foundation
@preconcurrency import Shared

/// Native reachability state, mapped from the shared `Reachability` at the observer boundary so
/// views never touch a bridged Kotlin object.
enum ServerConnection {
    /// The server connection is live.
    case reachable
    /// The server connection is down.
    case unreachable
    /// Not yet determined (e.g. connecting at startup) — treat optimistically.
    case unknown
}

/// App-wide `@Observable` over the shared `ServerReachability` firehose signal. Drives offline
/// indicators + Retry across screens off a single source of truth (the same signal Book Detail's
/// shared availability uses), so the whole app agrees on whether the server is reachable.
@Observable
@MainActor
final class ServerReachabilityObserver {
    /// Latest reachability, projected to a native enum. Starts `.unknown` (optimistic) until the
    /// firehose reports.
    private(set) var connection: ServerConnection = .unknown

    /// The server is confirmed unreachable — drives offline banners/indicators. `.unknown` is
    /// treated optimistically (not "unreachable"), matching the shared derivation.
    var isUnreachable: Bool { connection == .unreachable }

    private let reachability: ServerReachability
    private let bridge = FlowBridge()

    init(reachability: ServerReachability) {
        self.reachability = reachability
        bridge.bind(reachability.state) { [weak self] value in
            self?.connection = Self.map(value)
        }
    }

    convenience init(deps: Dependencies) {
        self.init(reachability: deps.serverReachability)
    }

    /// Force a reachability recheck (re-opens the SSE firehose). Backs the "Retry" affordance.
    /// The `Task` stays MainActor-isolated so the non-`Sendable` `ServerReachability` is never sent
    /// across an isolation boundary (it lives on this MainActor observer).
    func retry() {
        Task { @MainActor in try? await reachability.retry() }
    }

    private static func map(_ value: Reachability) -> ServerConnection {
        switch onEnum(of: value) {
        case .reachable: return .reachable
        case .unreachable: return .unreachable
        // `.unknown` plus the export's synthetic future-case catch-all → treat optimistically.
        default: return .unknown
        }
    }

    deinit { bridge.cancelAll() }
}
