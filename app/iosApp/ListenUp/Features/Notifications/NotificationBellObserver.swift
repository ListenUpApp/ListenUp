import SwiftUI
import Shared

/// Observes `NotificationBellViewModel`'s unread count for the toolbar bell. The count derives
/// from the same Room table the inbox list reads, so bell and list can never disagree.
@Observable
@MainActor
final class NotificationBellObserver {
    private(set) var unreadCount: Int = 0

    private let viewModel: NotificationBellViewModel
    private let bridge = FlowBridge()

    init(viewModel: NotificationBellViewModel) {
        self.viewModel = viewModel
        bridge.bind(viewModel.unreadCount) { [weak self] in self?.unreadCount = Int($0) }
    }

    deinit { bridge.cancelAll() }   // cancelAll() is nonisolated-safe; see FlowBridge.
}
