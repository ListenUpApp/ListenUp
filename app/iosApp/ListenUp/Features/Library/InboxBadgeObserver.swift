import SwiftUI
import Shared

/// Observes `InboxBadgeViewModel`: how many books wait in the admin inbox. It derives from the same
/// Room held set as the inbox page and the library's exclusion, so the badge, the entry and the inbox
/// can never disagree. Zero for anyone who is not an admin.
@Observable
@MainActor
final class InboxBadgeObserver {
    private(set) var heldCount: Int = 0
    /// Whether this viewer is an admin: the iPad sidebar's Server › Inbox entry shows for an admin even with nothing held.
    private(set) var isAdmin = false

    private let viewModel: InboxBadgeViewModel
    private let bridge = FlowBridge()

    init(viewModel: InboxBadgeViewModel) {
        self.viewModel = viewModel
        bridge.own(viewModel)
        bridge.bind(viewModel.heldCount) { [weak self] in self?.heldCount = Int($0) }
        bridge.bind(viewModel.isAdmin) { [weak self] in self?.isAdmin = $0 }
    }
}
