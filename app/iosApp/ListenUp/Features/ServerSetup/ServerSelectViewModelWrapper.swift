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
    /// The action that can fix the current failure, when there is one.
    private(set) var recovery: ConnectRecovery?

    /// True while Local Network access is known to be off (Bonjour refused the browse, or a
    /// selected server was blocked by it).
    var isLocalNetworkDenied: Bool { recovery == .openSettings }

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
        // A rescan is a fresh attempt: drop the last failure so it can't outlive the new browse.
        if error != nil { dismissError() }
        viewModel.onEvent(event: ServerSelectUiEventRefreshClicked.shared)
    }

    /// Called whenever the app returns to the foreground. If Local Network access blocked the last
    /// browse or activation, tell the shared ViewModel access may be back; it restarts discovery
    /// and re-runs whatever was blocked. A still-denied retry fails the same way again.
    func retryAfterLocalNetworkGrant() {
        guard isLocalNetworkDenied else { return }
        viewModel.onEvent(event: ServerSelectUiEventLocalNetworkPermissionGranted.shared)
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
        switch state.sealedType() {
        case .discovering:
            isDiscovering = true; isConnecting = false; selectedServerId = nil; error = nil; recovery = nil
        case .ready:
            isDiscovering = false; isConnecting = false; selectedServerId = nil; error = nil; recovery = nil
        case .connecting(let sType):
            let s = sType.value
            isDiscovering = false; isConnecting = true; selectedServerId = s.selectedServerId
            error = nil; recovery = nil
        case .error(let sType):
            let s = sType.value
            isDiscovering = false; isConnecting = false
            selectedServerId = s.selectedServerId; error = s.error.message
            recovery = ServerConnectViewModelWrapper.recovery(for: s.error)
        }
    }

    private func applyNavigation(_ event: ServerSelectViewModel.NavigationEvent) {
        switch event.sealedType() {
        case .serverActivated:
            onServerActivated?()
        case .goToManualEntry:
            onManualEntryRequested?()
        }
    }

    /// Parse `host` and `port` from a URL like `http://192.168.1.100:8080`.
    private static func parseHostPort(_ urlString: String?) -> (String, Int) {
        guard let urlString, let url = URL(string: urlString) else { return ("unknown", 0) }
        return (url.host ?? "unknown", url.port ?? 8080)
    }
}
