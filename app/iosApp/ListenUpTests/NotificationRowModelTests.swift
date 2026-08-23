import Foundation
import Testing
@preconcurrency import Shared
@testable import ListenUp

/// Pins `NotificationRowModel`'s boundary mapping from constructed `AppNotification`s (the
/// `DeepLinkRouterTests` pattern — bridged Kotlin values built directly, no Koin): per-case copy
/// selection including the approved/denied split, the nil-event generic fallback, the resolved
/// tap target, and the `isUnread` projection. Timestamp *formatting* is deliberately untested
/// (locale-dependent); the row only carries the raw epoch-ms.
@Suite("NotificationRowModel")
struct NotificationRowModelTests {

    private func notification(
        event: (any NotificationEvent)?,
        type: String = "test_type",
        readAt: Int64? = nil
    ) -> AppNotification {
        AppNotification(id: "n1", type: type, event: event, createdAt: 1_700_000_000_000, readAt: readAt)
    }

    @Test func campfireInviteSelectsCampfireCopyAndStaysPut() {
        let row = NotificationRowModel(from: notification(
            event: NotificationEventCampfireInvite(campfireId: "c1", bookId: "b1", inviterUserId: "u1"),
            type: "campfire_invite"
        ))

        #expect(row.title == String(localized: "notifications.campfire_invite_title"))
        #expect(row.body == String(localized: "notifications.campfire_invite_body"))
        #expect(row.systemImage == "flame")
        #expect(row.target == .none)   // #1065 — no campfire surface yet
    }

    @Test func approvedDecisionSelectsApprovedCopy() {
        let row = NotificationRowModel(from: notification(
            event: NotificationEventRegistrationDecision(userId: "u1", approved: true),
            type: "registration_decision"
        ))

        #expect(row.title == String(localized: "notifications.registration_decision_approved_title"))
        #expect(row.body == String(localized: "notifications.registration_decision_approved_body"))
        #expect(row.systemImage == "person.crop.circle.badge.checkmark")
        #expect(row.target == .none)
    }

    @Test func deniedDecisionSelectsDeniedCopy() {
        let row = NotificationRowModel(from: notification(
            event: NotificationEventRegistrationDecision(userId: "u1", approved: false),
            type: "registration_decision"
        ))

        #expect(row.title == String(localized: "notifications.registration_decision_denied_title"))
        #expect(row.body == String(localized: "notifications.registration_decision_denied_body"))
        #expect(row.systemImage == "person.crop.circle.badge.xmark")
        #expect(row.target == .none)
    }

    @Test func registrationApprovalRoutesToAdminApprovals() {
        let row = NotificationRowModel(from: notification(
            event: NotificationEventRegistrationApproval(userId: "u1"),
            type: "registration_approval"
        ))

        #expect(row.title == String(localized: "notifications.registration_approval_title"))
        #expect(row.body == String(localized: "notifications.registration_approval_body"))
        #expect(row.systemImage == "person.badge.clock")
        // Resolved through `event.target` (Kotlin) → PushTapRouter's one switch.
        #expect(row.target == .adminApprovals)
    }

    @Test func nilEventRendersGenericallyAndStaysPut() {
        let row = NotificationRowModel(from: notification(event: nil, type: "some_future_type"))

        #expect(row.title == String(localized: "notifications.unknown_title"))
        #expect(row.body == String(localized: "notifications.unknown_subtitle"))
        #expect(row.systemImage == "bell")
        #expect(row.target == .none)
    }

    @Test func unreadTracksNilReadAt() {
        let unread = NotificationRowModel(from: notification(
            event: NotificationEventRegistrationApproval(userId: "u1"),
            readAt: nil
        ))
        let read = NotificationRowModel(from: notification(
            event: NotificationEventRegistrationApproval(userId: "u1"),
            readAt: 1_700_000_000_500
        ))

        #expect(unread.isUnread)
        #expect(!read.isUnread)
        #expect(unread.id == "n1")
        #expect(unread.createdAtMs == 1_700_000_000_000)
    }
}
