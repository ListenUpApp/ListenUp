import SwiftUI
import Shared

/// A native, value-typed projection of `DownloadedBookSummary` for SwiftUI lists — snapshotted
/// once at the observer boundary so the list diffs cheap Swift values instead of re-bridging the
/// Kotlin object on every pass.
struct DownloadedBookRow: Identifiable, Equatable {
    let id: String
    let title: String
    let authorNames: String
    let sizeBytes: Int64
    let fileCount: Int

    init(id: String, title: String, authorNames: String, sizeBytes: Int64, fileCount: Int) {
        self.id = id
        self.title = title
        self.authorNames = authorNames
        self.sizeBytes = sizeBytes
        self.fileCount = fileCount
    }

    init(_ summary: DownloadedBookSummary) {
        self.id = summary.bookId
        self.title = summary.title
        self.authorNames = summary.authorNames
        self.sizeBytes = summary.sizeBytes
        self.fileCount = Int(summary.fileCount)
    }
}

/// The confirmation the Storage screen is asking the user to approve, flattened from the shared
/// sealed `DeleteConfirmation` into a native value the SwiftUI alert binds to.
enum StoragePendingDeletion: Equatable, Identifiable {
    case single(title: String, sizeBytes: Int64)
    case all(count: Int, totalBytes: Int64)

    var id: String {
        switch self {
        case .single(let title, _): "single-\(title)"
        case .all: "all"
        }
    }
}

/// Observes `StorageViewModel`, flattening `StorageUiState` into flat `@Observable` properties the
/// SwiftUI Storage screen binds to, and forwarding the VM's actions. Thin over `FlowBridge`.
///
/// `StorageUiState` is a plain Kotlin `data class`, so its scalar fields bridge directly; the sealed
/// `deleteConfirmation` and the bridged `downloadedBooks` are flattened to native values here. The
/// raw Kotlin summaries are kept privately (off the diff path) for the id→object lookup the VM's
/// `confirmDeleteBook(book:)` needs.
@Observable
@MainActor
final class StorageObserver {
    private(set) var isLoading: Bool = true
    private(set) var totalStorageUsed: Int64 = 0
    private(set) var availableStorage: Int64 = 0
    private(set) var books: [DownloadedBookRow] = []
    private(set) var isDeleting: Bool = false
    private(set) var pendingDeletion: StoragePendingDeletion?
    /// Set to a book's title when a delete was refused because that book is currently playing (B9).
    /// The screen surfaces a "stop playback first" alert; `dismissDeleteBlocked()` clears it.
    private(set) var blockedDeletionTitle: String?

    /// Raw Kotlin summaries kept off the SwiftUI diff path, for the id→object lookup the VM needs.
    private var rawBooks: [String: DownloadedBookSummary] = [:]

    private let viewModel: StorageViewModel
    private let bridge = FlowBridge()

    init(viewModel: StorageViewModel) {
        self.viewModel = viewModel
        bridge.bind(viewModel.state) { [weak self] in self?.apply($0) }
    }

    deinit { bridge.cancelAll() }   // cancelAll() is nonisolated-safe; see FlowBridge.

    // MARK: - Actions

    /// Ask to delete a single downloaded book, by id (native rows never carry the bridged object).
    func confirmDeleteBook(id: String) {
        guard let raw = rawBooks[id] else { return }
        viewModel.confirmDeleteBook(book: raw)
    }

    func confirmClearAll() {
        viewModel.confirmClearAll()
    }

    func cancelDelete() {
        viewModel.cancelDelete()
    }

    func executeDelete() {
        viewModel.executeDelete()
    }

    func dismissDeleteBlocked() {
        viewModel.dismissDeleteBlocked()
    }

    // MARK: - State mapping

    private func apply(_ state: StorageUiState) {
        isLoading = state.isLoading
        totalStorageUsed = state.totalStorageUsed
        availableStorage = state.availableStorage
        rawBooks = Dictionary(uniqueKeysWithValues: state.downloadedBooks.map { ($0.bookId, $0) })
        books = state.downloadedBooks.map { DownloadedBookRow($0) }
        isDeleting = state.isDeleting
        blockedDeletionTitle = state.blockedDeletionTitle
        pendingDeletion = mapConfirmation(state.deleteConfirmation, total: state.totalStorageUsed)
    }

    private func mapConfirmation(_ confirmation: DeleteConfirmation?, total: Int64) -> StoragePendingDeletion? {
        guard let confirmation else { return nil }
        switch onEnum(of: confirmation) {
        case .singleBook(let single):
            return .single(title: single.book.title, sizeBytes: single.book.sizeBytes)
        case .allDownloads:
            return .all(count: books.count, totalBytes: total)
        case .unknown:
            Log.error("Unexpected DeleteConfirmation case")
            return nil
        }
    }
}
