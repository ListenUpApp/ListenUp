import SwiftUI
@preconcurrency import Shared

/// A user shelf flattened for the bulk shelf-picker sheet.
struct SelectionShelfRow: Identifiable, Equatable {
    let id: String
    let name: String
}

/// A collection flattened for the bulk collection-picker sheet (admin-only).
struct SelectionCollectionRow: Identifiable, Equatable {
    let id: String
    let name: String
}

/// Observes `BookMultiSelectViewModel` — the single shared source of truth for multi-select
/// across any books-bearing screen — and projects its `Flow`s to flat native `@Observable`
/// state (rule 8: never feed bridged Kotlin objects into a `ForEach`). Thin over `FlowBridge`,
/// mirroring `BookDetailObserver`.
///
/// Selection state lives in the Kotlin VM; this observer only flattens it for SwiftUI and
/// forwards the user's actions. Successful bulk adds dismiss the matching picker and clear the
/// selection inside the VM (which flips `selectionMode` back to `None`); failures surface on the
/// global `ErrorBus` like everywhere else, never here.
@Observable
@MainActor
final class BookSelectionObserver {
    // MARK: - Flattened state

    private(set) var isSelecting = false
    private(set) var selectedBookIds: Set<String> = []
    private(set) var isAdmin = false
    private(set) var myShelves: [SelectionShelfRow] = []
    private(set) var allCollections: [SelectionCollectionRow] = []
    private(set) var isAddingToShelf = false
    private(set) var isAddingToCollection = false

    // MARK: - Sheet visibility (View-owned, reset on a success event)

    var showShelfPicker = false
    var showCollectionPicker = false

    // MARK: - Dependencies

    private let viewModel: BookMultiSelectViewModel
    private let bridge = FlowBridge()

    init(viewModel: BookMultiSelectViewModel) {
        self.viewModel = viewModel
        bridge.bind(viewModel.selectionMode) { [weak self] mode in
            self?.applySelectionMode(mode)
        }
        bridge.bind(viewModel.isAdmin) { [weak self] value in
            self?.isAdmin = value
        }
        bridge.bind(viewModel.myShelves) { [weak self] shelves in
            self?.myShelves = shelves.map { SelectionShelfRow(id: $0.idString, name: $0.name) }
        }
        bridge.bind(viewModel.collections) { [weak self] collections in
            self?.allCollections = collections.map { SelectionCollectionRow(id: $0.id, name: $0.name) }
        }
        bridge.bind(viewModel.isAddingToShelf) { [weak self] value in
            self?.isAddingToShelf = value
        }
        bridge.bind(viewModel.isAddingToCollection) { [weak self] value in
            self?.isAddingToCollection = value
        }
        // One-shot success events: dismiss whichever picker is open (the VM already cleared the
        // selection, which flips `selectionMode` to `None`). Failures never reach here.
        bridge.bind(viewModel.events) { [weak self] _ in
            self?.showShelfPicker = false
            self?.showCollectionPicker = false
        }
    }

    deinit { bridge.cancelAll() }   // cancelAll() is nonisolated-safe; see FlowBridge.

    // MARK: - State mapping

    /// Flatten the sealed `SelectionMode` into `isSelecting` + the native `Set<String>`.
    private func applySelectionMode(_ mode: SelectionMode) {
        switch onEnum(of: mode) {
        case .none:
            isSelecting = false
            selectedBookIds = []
        case .active(let active):
            isSelecting = true
            // The Kotlin Set<String> arrives as a bridged Kotlin set, not a Swift Set;
            // map through String(describing:) to a Swift-native Set (DevicesObserver precedent).
            selectedBookIds = Set(active.selectedIds.map { String(describing: $0) })
        case .unknown:
            Log.error("Unexpected SelectionMode case")
            isSelecting = false
            selectedBookIds = []
        }
    }

    // MARK: - Selection actions

    /// Enter selection mode seeded with one book — the long-press entry point on a cover.
    func enter(_ bookId: String) { viewModel.enterSelectionMode(initialBookId: bookId) }
    func toggle(_ bookId: String) { viewModel.toggleSelection(bookId: bookId) }
    func exit() { viewModel.exitSelectionMode() }
    func isSelected(_ bookId: String) -> Bool { selectedBookIds.contains(bookId) }

    // MARK: - Bulk add actions

    func addToShelf(shelfId: String) { viewModel.addSelectedToShelf(shelfId: shelfId) }
    func createShelfAndAdd(name: String) { viewModel.createShelfAndAddBooks(name: name) }
    func addToCollection(collectionId: String) { viewModel.addSelectedToCollection(collectionId: collectionId) }
    func createCollectionAndAdd(name: String) { viewModel.createCollectionAndAddBooks(name: name) }
}
