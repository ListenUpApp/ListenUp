import SwiftUI
import Shared

/// Render phase for the inbox, flattened from `NotificationsUiState`.
enum NotificationsPhase {
    case loading
    case empty
    case ready([NotificationRowModel])
}

/// One inbox row, native. Copy selection happens HERE (onEnum over the event), never in ForEach.
struct NotificationRowModel: Identifiable, Equatable {
    let id: String
    let title: String
    let body: String
    let createdAtMs: Int64
    let isUnread: Bool
    let systemImage: String
    let target: NotificationTapOutcome   // resolved once, at the boundary

    init(from notification: AppNotification) {
        self.id = notification.id
        self.createdAtMs = notification.createdAt
        self.isUnread = notification.isUnread
        if let event = notification.event {
            switch onEnum(of: event) {
            case .campfireInvite:
                title = String(localized: "notifications.campfire_invite_title")
                body = String(localized: "notifications.campfire_invite_body")
                systemImage = "flame"
            case .registrationDecision(let decision):
                title = String(localized: decision.approved
                    ? "notifications.registration_decision_approved_title"
                    : "notifications.registration_decision_denied_title")
                body = String(localized: decision.approved
                    ? "notifications.registration_decision_approved_body"
                    : "notifications.registration_decision_denied_body")
                systemImage = decision.approved
                    ? "person.crop.circle.badge.checkmark"
                    : "person.crop.circle.badge.xmark"
            case .registrationApproval:
                title = String(localized: "notifications.registration_approval_title")
                body = String(localized: "notifications.registration_approval_body")
                systemImage = "person.badge.clock"
            case .unknown:
                title = String(localized: "notifications.unknown_title")
                body = String(localized: "notifications.unknown_subtitle")
                systemImage = "bell"
            }
            target = PushTapRouter.outcome(for: event.target)
        } else {
            // A type this build does not know — render generically, never drop (domain contract).
            title = String(localized: "notifications.unknown_title")
            body = String(localized: "notifications.unknown_subtitle")
            systemImage = "bell"
            target = .none
        }
    }
}

/// Observes `NotificationsViewModel`, flattening `NotificationsUiState` into a native
/// `NotificationsPhase` whose rows are value-typed `NotificationRowModel`s — the bridged
/// `AppNotification`s never reach a `ForEach`. Mirrors `DevicesObserver`.
@Observable
@MainActor
final class NotificationsObserver {
    private(set) var phase: NotificationsPhase = .loading

    private let viewModel: NotificationsViewModel
    private let bridge = FlowBridge()

    init(viewModel: NotificationsViewModel) {
        self.viewModel = viewModel
        bridge.bind(viewModel.uiState) { [weak self] in self?.apply($0) }
    }

    deinit { bridge.cancelAll() }   // cancelAll() is nonisolated-safe; see FlowBridge.

    // MARK: - Actions

    /// Marks the row read; the visual state follows from Room, so there is nothing to roll back.
    func markRead(_ id: String) { viewModel.markRead(notificationId: id) }

    // MARK: - State mapping

    private func apply(_ state: NotificationsUiState) {
        switch onEnum(of: state) {
        case .loading:
            phase = .loading
        case .empty:
            phase = .empty
        case .data(let data):
            phase = .ready(data.notifications.map { NotificationRowModel(from: $0) })
        case .unknown:
            Log.error("Unexpected NotificationsUiState case")
            phase = .empty
        }
    }
}
