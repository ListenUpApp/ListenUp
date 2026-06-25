import SwiftUI
@preconcurrency import Shared

/// Observes the KMP auth-state flow and exposes it as SwiftUI-native state.
/// The single source of truth for authentication state in the iOS app — placed at
/// the app root, it drives top-level navigation.
@Observable
@MainActor
final class AuthStateObserver {

    // MARK: - State

    private(set) var state: AuthStateKind = .initializing
    private(set) var openRegistration: Bool = false
    private(set) var pendingApprovalUserId: String = ""
    private(set) var pendingApprovalEmail: String = ""

    // MARK: - Dependencies

    private let authSession: any AuthSession
    private let bridge = FlowBridge()

    // MARK: - Init

    init(authSession: any AuthSession = KoinHelper.shared.getAuthSession()) {
        self.authSession = authSession
        Task {
            do {
                try await authSession.initializeAuthState()
            } catch is CancellationError {
            } catch {
                // Don't silently swallow: a failed init leaves auth state stuck at
                // `.initializing` (launch screen). Log it so the stall is diagnosable.
                Log.error("Failed to initialize auth state", error: error)
            }
        }
        bridge.bind(authSession.authState) { [weak self] authState in
            self?.apply(authState)
        }
    }

    /// Stop observing. Call on teardown.
    func stopObserving() {
        bridge.cancelAll()
    }

    // MARK: - Mapping

    private func apply(_ authState: AuthState) {
        switch onEnum(of: authState) {
        case .initializing:
            state = .initializing
        case .needsServerUrl:
            state = .needsServerUrl
        case .checkingServer:
            state = .checkingServer
        case .needsSetup:
            state = .needsSetup
        case .needsLogin(let login):
            state = .needsLogin
            openRegistration = login.openRegistration
        case .pendingApproval(let pending):
            state = .pendingApproval
            pendingApprovalUserId = pending.userId as? String ?? ""
            pendingApprovalEmail = pending.email
        case .authenticated:
            state = .authenticated
        }
    }
}

/// Flattened auth state for SwiftUI `switch` — associated data lives on the observer.
enum AuthStateKind: Equatable {
    case initializing
    case needsServerUrl
    case checkingServer
    case needsSetup
    case needsLogin
    case pendingApproval
    case authenticated
}
