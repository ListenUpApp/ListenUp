import Foundation
@preconcurrency import Shared

/// The CarPlay Home tab's list: every book in progress, from `HomeRepository` (C3). Home's own state no longer carries
/// this list (it leaves the loaded book out, spec F1), and a car must offer the loaded book too — Android Auto reads
/// the same repository. Owns its subscription for the car session; the bridge cancels it on release.
@Observable
@MainActor
final class CarPlayHomeWrapper {
    private(set) var items: [ContinueItem] = []
    /// Set when the list could not be read, so the car says so rather than showing an empty list (honest over silent).
    private(set) var failureMessage: String?

    private let bridge = FlowBridge()

    init(repository: HomeRepository = Dependencies.shared.homeRepository) {
        bridge.bind(
            repository.observeContinueListening(limit: 10),
            onError: { [weak self] _ in self?.failureMessage = String(localized: "home.couldnt_load") }
        ) { [weak self] list in
            self?.failureMessage = nil
            self?.items = Self.items(from: list)
        }
    }

    static func items(from list: [ContinueListeningItem]) -> [ContinueItem] {
        list.map(ContinueItem.init(from:))
    }
}
