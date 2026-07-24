import Foundation
import Shared

/// Observes `CreateInviteViewModel` — flattens the command-driven `CreateInviteUiState.Ready`
/// status into a SwiftUI-native `CreateInvitePhase` the sheet binds to. The VM has no async
/// initial load, so it enters `Ready` immediately; the `Loading`/`Error` arms exist only for
/// hierarchy symmetry and map to `.idle` here.
///
/// The error-type → UI mapping (which field to highlight, which inline banner to show) lives
/// in pure statics so the sheet never re-derives the Kotlin classification.
///
/// Thin over `FlowBridge`, mirroring `LeaderboardObserver`.
@Observable
@MainActor
final class CreateInviteObserver {
    // MARK: - State

    private(set) var phase: CreateInvitePhase = .idle

    // MARK: - Dependencies

    private let viewModel: CreateInviteViewModel
    private let bridge = FlowBridge()

    // MARK: - Init

    init(viewModel: CreateInviteViewModel) {
        self.viewModel = viewModel
        bridge.bind(viewModel.state) { [weak self] in self?.apply($0) }
    }

    deinit { bridge.cancelAll() }   // cancelAll() is nonisolated-safe; see FlowBridge.

    // MARK: - Actions

    func createInvite(email: String, role: InviteRole, expiresInDays: Int) {
        viewModel.createInvite(
            email: email,
            role: role.wireValue,
            expiresInDays: Int32(expiresInDays)
        )
    }

    func clearError() { viewModel.clearError() }
    func reset() { viewModel.reset() }

    // MARK: - State mapping

    private func apply(_ state: CreateInviteUiState) {
        guard let ready = state as? CreateInviteUiStateReady else {
            // Loading / Error arms are unused by this VM — treat as idle.
            phase = .idle
            return
        }
        phase = Self.phase(from: ready.status)
    }

    /// Pure: project a `CreateInviteStatus` onto the sheet's phase. `nonisolated` so tests can
    /// exercise it off the main actor.
    nonisolated static func phase(from status: CreateInviteStatus) -> CreateInvitePhase {
        switch onEnum(of: status) {
        case .idle:
            return .idle
        case .submitting:
            return .submitting
        case .success(let success):
            return .success(CreatedInviteModel(from: success.invite))
        case .error(let error):
            return .failure(InviteFailure(from: error.type))
        case .unknown:
            Log.error("Unexpected CreateInviteStatus case")
            return .failure(.server(detail: nil))
        }
    }
}

// MARK: - Phase

/// Flattened create-invite state for a SwiftUI `switch`.
enum CreateInvitePhase: Equatable {
    case idle
    case submitting
    case success(CreatedInviteModel)
    case failure(InviteFailure)

    var isSubmitting: Bool { self == .submitting }

    /// The created invite, when in the success state.
    var createdInvite: CreatedInviteModel? {
        if case .success(let invite) = self { return invite }
        return nil
    }

    /// The field to highlight, if the failure is a client-side validation error.
    var validationField: InviteField? {
        if case .failure(let failure) = self, case .validation(let field) = failure {
            return field
        }
        return nil
    }

    /// A banner message for a non-field failure (email-in-use / network / server), else nil.
    var bannerMessage: String? {
        guard case .failure(let failure) = self else { return nil }
        switch failure {
        case .validation:
            return nil
        case .emailInUse:
            return String(localized: "admin.email_already_in_use")
        case .network(let detail):
            return detail ?? String(localized: "error.transport_network_unavailable")
        case .server(let detail):
            return detail ?? String(localized: "common.something_went_wrong")
        }
    }
}

// MARK: - Access level

/// The two access levels the invite form offers, mapped to the wire role strings the VM expects.
enum InviteRole: CaseIterable, Identifiable {
    case member
    case admin

    var id: Self { self }

    var wireValue: String {
        switch self {
        case .member: "member"
        case .admin: "admin"
        }
    }
}

// MARK: - Validation field

/// Which form field a validation failure highlights. A native mirror of the shared
/// `CreateInviteField` (which doesn't bridge as a clean Swift enum for view code).
enum InviteField: Equatable {
    case email

    init(from field: CreateInviteField) {
        switch field {
        case .email: self = .email
        }
    }
}

// MARK: - Failure model

/// Flattened create-invite failure. Mirrors the shared `CreateInviteErrorType` so the sheet
/// can branch on it without touching Swift Export-bridged sealed types.
enum InviteFailure: Equatable {
    case validation(InviteField)
    case emailInUse
    case network(detail: String?)
    case server(detail: String?)

    init(from type: CreateInviteErrorType) {
        switch onEnum(of: type) {
        case .validationError(let validation):
            self = .validation(InviteField(from: validation.field))
        case .emailInUse:
            self = .emailInUse
        case .networkError(let network):
            self = .network(detail: network.detail)
        case .serverError(let server):
            self = .server(detail: server.detail)
        case .unknown:
            Log.error("Unexpected CreateInviteErrorType case")
            self = .server(detail: nil)
        }
    }
}

// MARK: - Created invite model

/// The server-issued invite shown on success: recipient + the shareable URL.
struct CreatedInviteModel: Equatable {
    let name: String
    let url: String

    init(from invite: InviteInfo) {
        self.name = invite.name
        self.url = invite.url
    }

    init(name: String, url: String) {
        self.name = name
        self.url = url
    }
}
