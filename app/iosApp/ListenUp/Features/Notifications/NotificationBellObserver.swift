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

    // Isolated deinit (SE-0371): runs on the main actor so the non-Sendable Kotlin viewModel can be
    // closed — a fresh factory instance per shell, whose stream jobs would otherwise outlive it (#1192).
    isolated deinit {
        bridge.cancelAll()   // cancelAll() is nonisolated-safe; see FlowBridge.
        viewModel.close()
    }
}
