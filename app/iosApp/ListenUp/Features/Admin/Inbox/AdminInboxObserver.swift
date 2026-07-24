import Foundation
import Shared

/// Observes `AdminInboxViewModel` — flattens the sealed `AdminInboxUiState` into a
/// SwiftUI-native `AdminInboxPhase` and surfaces selection state, release overlay, and
/// transient error/result as flat properties the view binds to.
///
/// SSE inbox updates flow through the shared VM's `state` — no extra wiring needed here.
@Observable
@MainActor
final class AdminInboxObserver {
    // MARK: - State

    private(set) var phase: AdminInboxPhase = .loading

    // MARK: - Dependencies

    private let viewModel: AdminInboxViewModel
    private let bridge = FlowBridge()

    // MARK: - Init

    init(viewModel: AdminInboxViewModel) {
        self.viewModel = viewModel
        bridge.bind(viewModel.state) { [weak self] in self?.apply($0) }
    }

    deinit { bridge.cancelAll() }   // cancelAll() is nonisolated-safe; see FlowBridge.

    // MARK: - Actions

    func reload() { viewModel.loadInboxBooks() }
    func toggleBookSelection(bookId: String) { viewModel.toggleBookSelection(bookId: bookId) }
    func selectAll() { viewModel.selectAll() }
    func clearSelection() { viewModel.clearSelection() }
    func releaseSelected() { viewModel.releaseSelected() }
    func clearError() { viewModel.clearError() }
    func clearReleaseResult() { viewModel.clearReleaseResult() }

    // MARK: - State mapping

    private func apply(_ state: AdminInboxUiState) {
        phase = Self.phase(from: state)
    }

    /// Pure: project the sealed `AdminInboxUiState` onto the flattened phase.
    /// `nonisolated` so tests can exercise it off the main actor (mirrors the backup observers).
    nonisolated static func phase(from state: AdminInboxUiState) -> AdminInboxPhase {
        switch onEnum(of: state) {
        case .loading:
            return .loading
        case .ready(let ready):
            return .ready(AdminInboxReadyModel(from: ready))
        case .error(let error):
            return .error(error.message)
        case .unknown:
            Log.error("Unexpected AdminInboxUiState case")
            return .error(String(localized: "common.something_went_wrong"))
        }
    }
}

// MARK: - Phase

enum AdminInboxPhase: Equatable {
    case loading
    case ready(AdminInboxReadyModel)
    case error(String)
}

// MARK: - Ready model

struct AdminInboxReadyModel: Equatable {
    let books: [InboxBookRowModel]
    let selectedBookIds: Set<String>
    let isReleasing: Bool
    let lastReleasedCount: Int?
    let error: String?
    let bookCount: Int
    let hasBooks: Bool
    let hasSelection: Bool
    let selectedCount: Int
    let allSelected: Bool

    init(from ready: AdminInboxUiStateReady) {
        self.books = ready.books.map(InboxBookRowModel.init(from:))
        self.selectedBookIds = Set(ready.selectedBookIds)
        self.isReleasing = ready.isReleasing
        self.lastReleasedCount = ready.lastReleasedCount.map { Int($0) }
        self.error = ready.error
        self.bookCount = Int(ready.bookIds.count)
        self.hasBooks = ready.hasBooks
        self.hasSelection = ready.hasSelection
        self.selectedCount = Int(ready.selectedCount)
        self.allSelected = ready.allSelected
    }
}

// MARK: - Row model

struct InboxBookRowModel: Identifiable, Equatable {
    let id: String
    let title: String
    let author: String?
    let coverPath: String?
    let coverHash: String?
    let durationMs: Int64

    var formattedDuration: String {
        DurationFormatting.hoursMinutes(ms: durationMs)
    }

    init(from item: InboxBookItem) {
        self.id = item.id
        self.title = item.title
        self.author = item.author
        self.coverPath = item.coverPath
        self.coverHash = item.coverHash
        self.durationMs = item.durationMs
    }
}
