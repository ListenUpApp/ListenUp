import SwiftUI
import Shared

/// Observes `InboxBadgeViewModel`: how many books wait in the admin inbox, and the newest few for
/// the Library entry's cover fan. Both derive from the same Room held set as the inbox page and the
/// library's exclusion, so the badge, the entry and the inbox can never disagree. Zero (and empty)
/// for anyone who is not an admin.
@Observable
@MainActor
final class InboxBadgeObserver {
    private(set) var heldCount: Int = 0
    private(set) var previewBookIds: [String] = []

    private let viewModel: InboxBadgeViewModel
    private let bridge = FlowBridge()

    init(viewModel: InboxBadgeViewModel) {
        self.viewModel = viewModel
        bridge.bind(viewModel.heldCount) { [weak self] in self?.heldCount = Int($0) }
        bridge.bind(viewModel.previewBookIds) { [weak self] in self?.previewBookIds = $0 }
    }

    deinit { bridge.cancelAll() }   // cancelAll() is nonisolated-safe; see FlowBridge.
}
