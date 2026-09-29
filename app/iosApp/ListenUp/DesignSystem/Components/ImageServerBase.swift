import Foundation
@preconcurrency import Shared

/// The connected server's base URL, mirrored on the main actor so image requests can be built in the
/// same render that shows them.
///
/// It follows `ServerConfig.activeUrl`, the shared layer's published copy of the authoritative
/// active URL (seeded at launch by `initializeLocalPreferences`, then republished on every change,
/// including a switch between the local and remote address). Each change is also handed to
/// `AuthenticatingDataLoader`, which attaches the access token only to requests for this host.
@Observable
@MainActor
final class ImageServerBase {
    static let shared = ImageServerBase()

    /// The base URL, or nil before the shared layer has published one (or with no server set).
    private(set) var url: String?

    @ObservationIgnored private let bridge = FlowBridge()
    @ObservationIgnored private var isObserving = false

    /// Starts following the active server URL. Idempotent.
    func startObserving(_ serverConfig: ServerConfig) {
        guard !isObserving else { return }
        isObserving = true
        bridge.bind(serverConfig.activeUrl) { [weak self] activeUrl in
            self?.update(activeUrl?.raw)
        }
    }

    func update(_ raw: String?) {
        let newValue = raw.flatMap { $0.isEmpty ? nil : $0 }
        guard newValue != url else { return }
        url = newValue
        AuthenticatingDataLoader.shared.trust(base: newValue.flatMap(URL.init(string:)))
    }
}
