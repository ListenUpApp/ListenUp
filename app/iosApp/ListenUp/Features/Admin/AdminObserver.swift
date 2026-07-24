import Foundation
import Shared

/// Observes `AdminViewModel` — flattens the sealed `AdminUiState` into a SwiftUI-native
/// `AdminPhase` (`loading` / `ready`) and surfaces the transient mutation error
/// and per-row in-flight ids as flat state the `AdminView` binds to.
///
/// The VM subscribes to SSE for real-time pending-user updates; this observer simply renders
/// whatever snapshot it emits. Actions forward straight through. Item models
/// (`AdminUserRowModel`, `AdminInviteRowModel`) and the role-label mapping live in pure,
/// testable initializers / statics so the row reskin never re-derives Kotlin semantics.
///
/// Thin over `FlowBridge`, mirroring `LeaderboardObserver`.
@Observable
@MainActor
final class AdminObserver {
    // MARK: - State

    private(set) var phase: AdminPhase = .loading

    // MARK: - Dependencies

    private let viewModel: AdminViewModel
    private let bridge = FlowBridge()

    // MARK: - Init

    init(viewModel: AdminViewModel) {
        self.viewModel = viewModel
        bridge.bind(viewModel.state) { [weak self] in self?.apply($0) }
    }

    deinit { bridge.cancelAll() }   // cancelAll() is nonisolated-safe; see FlowBridge.

    // MARK: - Actions

    func reload() { viewModel.loadData() }
    func deleteUser(id: String) { viewModel.deleteUser(userId: id) }
    func revokeInvite(id: String) { viewModel.revokeInvite(inviteId: id) }
    func approveUser(id: String) { viewModel.approveUser(userId: id) }
    func denyUser(id: String) { viewModel.denyUser(userId: id) }
    func setRegistrationPolicy(_ policy: RegistrationPolicy) { viewModel.setRegistrationPolicy(policy: policy) }
    func clearError() { viewModel.clearError() }

    // MARK: - State mapping

    private func apply(_ state: AdminUiState) {
        switch onEnum(of: state) {
        case .loading:
            phase = .loading
        case .ready(let ready):
            phase = .ready(AdminReadyModel(from: ready))
        case .unknown:
            Log.error("Unexpected AdminUiState case")
            phase = .loading
        }
    }
}

// MARK: - Phase

/// Flattened admin state for a SwiftUI `switch`.
enum AdminPhase {
    case loading
    case ready(AdminReadyModel)
}

// MARK: - Ready model

/// The fully-flattened `Ready` snapshot the screen renders. Carries pre-mapped row models
/// plus the per-action in-flight ids and the transient mutation error.
struct AdminReadyModel {
    let registrationPolicy: RegistrationPolicy
    let isTogglingRegistrationPolicy: Bool
    let users: [AdminUserRowModel]
    let pendingUsers: [AdminUserRowModel]
    let pendingInvites: [AdminInviteRowModel]
    let deletingUserId: String?
    let revokingInviteId: String?
    let approvingUserId: String?
    let denyingUserId: String?
    let error: String?

    init(from ready: AdminUiStateReady) {
        self.registrationPolicy = ready.registrationPolicy
        self.isTogglingRegistrationPolicy = ready.isTogglingRegistrationPolicy
        self.users = ready.users.map(AdminUserRowModel.init(from:))
        self.pendingUsers = ready.pendingUsers.map(AdminUserRowModel.init(from:))
        self.pendingInvites = ready.pendingInvites.map(AdminInviteRowModel.init(from:))
        self.deletingUserId = ready.deletingUserId
        self.revokingInviteId = ready.revokingInviteId
        self.approvingUserId = ready.approvingUserId
        self.denyingUserId = ready.denyingUserId
        self.error = ready.error
    }
}

// MARK: - Row models

/// One user (active or pending). `roleLabel` / `isRootBadge` drive the role badge; `isProtected`
/// gates the delete affordance (root users can't be deleted).
struct AdminUserRowModel: Identifiable, Equatable {
    let id: String
    let name: String
    let email: String
    let roleLabel: String
    let isRootBadge: Bool
    let isProtected: Bool

    init(from user: AdminUserInfo) {
        self.id = user.id
        self.name = user.displayableName
        self.email = user.email
        self.roleLabel = AdminRoleFormat.label(isRoot: user.isRoot, role: user.role)
        self.isRootBadge = AdminRoleFormat.isRootBadge(isRoot: user.isRoot, role: user.role)
        self.isProtected = user.isProtected
    }

    init(
        id: String,
        name: String,
        email: String,
        roleLabel: String,
        isRootBadge: Bool,
        isProtected: Bool
    ) {
        self.id = id
        self.name = name
        self.email = email
        self.roleLabel = roleLabel
        self.isRootBadge = isRootBadge
        self.isProtected = isProtected
    }
}

/// One pending invite. `roleLabel` capitalizes the wire role; `url` is copied to the clipboard.
struct AdminInviteRowModel: Identifiable, Equatable {
    let id: String
    let name: String
    let email: String
    let roleLabel: String
    let url: String
    let expiresAt: Date?

    init(from invite: InviteInfo) {
        self.id = invite.id
        self.name = invite.name
        self.email = invite.email
        self.roleLabel = AdminRoleFormat.capitalized(invite.role)
        self.url = invite.url
        self.expiresAt = ISO8601DateParser.date(from: invite.expiresAt)
    }

    init(id: String, name: String, email: String, roleLabel: String, url: String, expiresAt: Date?) {
        self.id = id
        self.name = name
        self.email = email
        self.roleLabel = roleLabel
        self.url = url
        self.expiresAt = expiresAt
    }
}

// MARK: - Role formatting (pure, unit-tested)

/// Pure role-label mapping shared by the row models and the tests. Mirrors the Android
/// `UserRow` semantics: a root user reads "Root" with a tinted shield badge; an "admin" role
/// also gets the tinted badge; everything else is a neutral capitalized role, defaulting to
/// "Member" when the wire role is blank.
enum AdminRoleFormat {
    /// The badge text for a user with the given root flag and wire role.
    static func label(isRoot: Bool, role: String) -> String {
        if isRoot { return "Root" }
        let trimmed = role.trimmingCharacters(in: .whitespaces)
        return trimmed.isEmpty ? String(localized: "common.member") : capitalized(trimmed)
    }

    /// Whether the badge should read as elevated (tinted shield): root users and admins.
    static func isRootBadge(isRoot: Bool, role: String) -> Bool {
        isRoot || role.lowercased() == "admin"
    }

    /// Upper-cases the first character of a role string (e.g. "member" → "Member").
    static func capitalized(_ role: String) -> String {
        guard let first = role.first else { return role }
        return first.uppercased() + role.dropFirst()
    }
}

// MARK: - ISO date parsing

/// Parses the server's ISO-8601 timestamps (the admin domain carries dates as ISO strings).
/// Tolerant of a fractional-seconds suffix, which the plain internet-date style rejects.
/// Uses value-type `Date.ISO8601FormatStyle` parse styles — no shared mutable formatter.
enum ISO8601DateParser {
    static func date(from string: String) -> Date? {
        (try? Date.ISO8601FormatStyle(includingFractionalSeconds: true).parse(string))
            ?? (try? Date.ISO8601FormatStyle().parse(string))
    }
}
