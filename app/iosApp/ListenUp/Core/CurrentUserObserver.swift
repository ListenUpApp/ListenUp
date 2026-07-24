import SwiftUI
import Shared

/// Observes the current user from KMP `UserRepository`, exposing it as SwiftUI state.
@Observable
@MainActor
final class CurrentUserObserver {

    // MARK: - State

    private(set) var user: User?

    // MARK: - Dependencies

    private let bridge = FlowBridge()

    // MARK: - Init

    init(userRepository: UserRepository = KoinHelper.shared.getUserRepository()) {
        bridge.bind(userRepository.observeCurrentUser()) { [weak self] user in
            self?.user = user
        }
    }

    deinit { bridge.cancelAll() }   // cancelAll() is nonisolated-safe; see FlowBridge.
}
