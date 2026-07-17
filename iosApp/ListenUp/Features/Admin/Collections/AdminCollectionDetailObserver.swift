import Foundation
import Shared

/// Observes `AdminCollectionDetailViewModel` — flattens the sealed
/// `AdminCollectionDetailUiState` into a SwiftUI-native `AdminCollectionDetailPhase`
/// with pre-mapped Swift row models.
///
/// Actions (`updateName`, `saveName`, `removeBook`, `shareWithUser`, `revokeShare`,
/// `showAddMemberSheet`, `hideAddMemberSheet`, `loadUsersForSharing`, `clearError`,
/// `clearSaveSuccess`) forward directly to the Kotlin VM.
@Observable
@MainActor
final class AdminCollectionDetailObserver {
    // MARK: - State

    private(set) var phase: AdminCollectionDetailPhase = .loading

    // MARK: - Dependencies

    private let viewModel: AdminCollectionDetailViewModel
    private let bridge = FlowBridge()

    // MARK: - Init

    init(viewModel: AdminCollectionDetailViewModel) {
        self.viewModel = viewModel
        bridge.bind(viewModel.state) { [weak self] in self?.apply($0) }
    }

    deinit { bridge.cancelAll() }   // cancelAll() is nonisolated-safe; see FlowBridge.

    // MARK: - Actions

    func updateName(_ name: String) { viewModel.updateName(name: name) }
    func saveName() { viewModel.saveName() }
    func removeBook(bookId: String) { viewModel.removeBook(bookId: bookId) }
    func shareWithUser(userId: String) { viewModel.shareWithUser(userId: userId) }
    func revokeShare(userId: String) { viewModel.revokeShare(userId: userId) }
    func showAddMemberSheet() { viewModel.showAddMemberSheet() }
    func hideAddMemberSheet() { viewModel.hideAddMemberSheet() }
    func loadUsersForSharing() { viewModel.loadUsersForSharing() }
    func openAddBooks() { viewModel.openAddBooks() }
    func closeAddBooks() { viewModel.closeAddBooks() }
    func onBookQueryChange(_ query: String) { viewModel.onBookQueryChange(query: query) }
    func addBookFromSearch(bookId: String) { viewModel.addBookFromSearch(bookId: bookId) }
    func clearError() { viewModel.clearError() }
    func clearSaveSuccess() { viewModel.clearSaveSuccess() }

    // MARK: - State mapping

    private func apply(_ state: AdminCollectionDetailUiState) {
        switch onEnum(of: state) {
        case .loading:
            phase = .loading
        case .ready(let ready):
            phase = .ready(AdminCollectionDetailReadyModel(from: ready))
        case .error(let err):
            phase = .error(err.message)
        case .unknown:
            Log.error("Unexpected AdminCollectionDetailUiState case")
            phase = .error(String(localized: "common.something_went_wrong"))
        }
    }
}

// MARK: - Phase

/// Flattened collection detail state for a SwiftUI `switch`.
enum AdminCollectionDetailPhase {
    case loading
    case ready(AdminCollectionDetailReadyModel)
    case error(String)
}

// MARK: - Ready model

struct AdminCollectionDetailReadyModel {
    let collectionId: String
    let collectionName: String
    let editedName: String
    let isDirty: Bool
    let isSaving: Bool
    let saveSuccess: Bool
    let books: [CollectionBookRowModel]
    let removingBookId: String?
    let shares: [CollectionShareRowModel]
    let showAddMemberSheet: Bool
    let isSharing: Bool
    let removingShareUserId: String?
    let isLoadingUsers: Bool
    let availableUsers: [AvailableUserModel]
    let showAddBooks: Bool
    let bookQuery: String
    let bookResults: [BookSearchResultModel]
    let isSearchingBooks: Bool
    let error: String?

    init(from ready: AdminCollectionDetailUiStateReady) {
        self.collectionId = ready.collection.id
        self.collectionName = ready.collection.name
        self.editedName = ready.editedName
        self.isDirty = ready.isDirty
        self.isSaving = ready.isSaving
        self.saveSuccess = ready.saveSuccess
        self.books = Array(ready.books).map(CollectionBookRowModel.init(from:))
        self.removingBookId = ready.removingBookId
        self.shares = Array(ready.shares).map(CollectionShareRowModel.init(from:))
        self.showAddMemberSheet = ready.showAddMemberSheet
        self.isSharing = ready.isSharing
        self.removingShareUserId = ready.removingShareUserId
        self.isLoadingUsers = ready.isLoadingUsers
        self.availableUsers = Array(ready.availableUsers).map(AvailableUserModel.init(from:))
        self.showAddBooks = ready.showAddBooks
        self.bookQuery = ready.bookQuery
        self.bookResults = Array(ready.bookResults).map(BookSearchResultModel.init(from:))
        self.isSearchingBooks = ready.isSearchingBooks
        self.error = ready.error
    }
}

// MARK: - Row models

struct CollectionBookRowModel: Identifiable {
    let id: String
    let title: String
    let author: String?
    let coverPath: String?
    let coverHash: String?

    init(from book: CollectionBookItem) {
        self.id = book.id
        self.title = book.title
        self.author = book.author
        self.coverPath = book.coverPath
        self.coverHash = book.coverHash
    }
}

/// A book search hit shown in the add-books sheet. Native mapping of the shared `SearchHit`, off
/// the ForEach diff path (see iOS charter: never feed bridged Kotlin objects into a `ForEach`).
struct BookSearchResultModel: Identifiable {
    let id: String
    let title: String
    let author: String?

    init(from hit: SearchHit) {
        self.id = hit.id
        self.title = hit.name
        self.author = hit.author
    }
}

struct CollectionShareRowModel: Identifiable {
    let id: String
    let userId: String
    let displayName: String
    let permission: String

    init(from share: CollectionShareItem) {
        self.id = share.id
        self.userId = share.userId
        // Resolved in the shared VM from the public_profiles mirror; falls back to the id until synced.
        self.displayName = share.displayName
        self.permission = share.permission
    }
}

struct AvailableUserModel: Identifiable {
    let id: String
    let displayName: String
    let email: String
    let isRoot: Bool

    init(from user: AdminUserInfo) {
        self.id = user.id
        self.displayName = user.displayableName
        self.email = user.email
        self.isRoot = user.isRoot
    }
}
