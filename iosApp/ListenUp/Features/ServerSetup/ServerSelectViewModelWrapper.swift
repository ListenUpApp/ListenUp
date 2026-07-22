import Foundation
import Shared

/// A discovered server, flattened for SwiftUI display.
struct DiscoveredServerItem: Identifiable, Equatable {
    let id: String
    let name: String
    let host: String
    let port: Int
    let version: String
    let isOnline: Bool

    var hostPort: String { "\(host):\(port)" }
}

/// Observes `ServerSelectViewModel` — the sealed `ServerSelectUiState` plus a
/// navigation-events flow — flattening both into SwiftUI-native state. Thin over `FlowBridge`.
@Observable
@MainActor
final class ServerSelectViewModelWrapper {
    private(set) var servers: [DiscoveredServerItem] = []
    private(set) var isDiscovering: Bool = true
    private(set) var selectedServerId: String?
    private(set) var isConnecting: Bool = false
    private(set) var error: String?

    /// Navigation callbacks — set by the view.
    var onServerActivated: (() -> Void)?
    var onManualEntryRequested: (() -> Void)?

    private let viewModel: ServerSelectViewModel
    private let bridge = FlowBridge()
    /// Kept so `selectServer` can hand the matching KMP value back to `onEvent`.
    private var kotlinServers: [ServerWithStatus] = []

    init(viewModel: ServerSelectViewModel) {
        self.viewModel = viewModel
        bridge.bind(viewModel.state) { [weak self] in self?.apply($0) }
        bridge.bind(viewModel.navigationEvents) { [weak self] in self?.applyNavigation($0) }
    }

    // Isolated deinit (SE-0371): runs hopped onto the main actor, so the non-Sendable Kotlin
    // viewModel can be closed here. No ViewModelStore on iOS calls onCleared, so this wrapper must
    // (#1192) — else the VM's mDNS discovery + stream jobs orphan and run forever.
    isolated deinit {
        bridge.cancelAll()   // cancelAll() is nonisolated-safe; see FlowBridge.
        viewModel.close()
    }

    // MARK: - Actions

    func selectServer(_ server: DiscoveredServerItem) {
        guard let match = kotlinServers.first(where: { $0.server.id == server.id }) else { return }
        viewModel.onEvent(event: ServerSelectUiEventServerSelected(server: match))
    }

    func refresh() {
        viewModel.onEvent(event: ServerSelectUiEventRefreshClicked.shared)
    }

    func dismissError() {
        viewModel.onEvent(event: ServerSelectUiEventErrorDismissed.shared)
    }

    func requestManualEntry() {
        viewModel.onEvent(event: ServerSelectUiEventManualEntryClicked.shared)
    }

    /// Kick off mDNS discovery. The shared `ServerSelectViewModel` does not auto-start
    /// discovery — it waits for the UI to signal local-network access (mirroring the
    /// Android `RequestLocalNetworkPermission` flow). iOS has no pre-flight permission
    /// API, so starting the Bonjour browse is what surfaces the system's "find devices on
    /// your local network" prompt; we therefore fire the granted event on appear.
    func startDiscovery() {
        viewModel.onEvent(event: ServerSelectUiEventLocalNetworkPermissionGranted.shared)
    }

    // MARK: - State mapping

    private func apply(_ state: ServerSelectUiState) {
        kotlinServers = Array(state.servers)
        servers = kotlinServers.map { item in
            let (host, port) = Self.parseHostPort(item.server.localUrl)
            return DiscoveredServerItem(
                id: item.server.id,
                name: item.server.name,
                host: host,
                port: port,
                version: item.server.serverVersion,
                isOnline: item.isOnline
            )
        }
        switch onEnum(of: state) {
        case .discovering:
            isDiscovering = true; isConnecting = false; selectedServerId = nil; error = nil
        case .ready:
            isDiscovering = false; isConnecting = false; selectedServerId = nil; error = nil
        case .connecting(let s):
            isDiscovering = false; isConnecting = true; selectedServerId = s.selectedServerId; error = nil
        case .error(let s):
            isDiscovering = false; isConnecting = false
            selectedServerId = s.selectedServerId; error = s.message
        case .unknown:
            Log.error("Unexpected ServerSelectUiState case")
            isDiscovering = false; isConnecting = false
            error = String(localized: "common.something_went_wrong")
        }
    }

    private func applyNavigation(_ event: ServerSelectViewModel.NavigationEvent) {
        switch onEnum(of: event) {
        case .serverActivated:
            onServerActivated?()
        case .goToManualEntry:
            onManualEntryRequested?()
        case .unknown:
            Log.error("Unexpected ServerSelectViewModel.NavigationEvent case")
        }
    }

    /// Parse `host` and `port` from a URL like `http://192.168.1.100:8080`.
    private static func parseHostPort(_ urlString: String?) -> (String, Int) {
        guard let urlString, let url = URL(string: urlString) else { return ("unknown", 0) }
        return (url.host ?? "unknown", url.port ?? 8080)
    }
}
