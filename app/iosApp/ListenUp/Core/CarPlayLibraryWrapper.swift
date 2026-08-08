import Foundation
@preconcurrency import Shared

/// The CarPlay Library tab's slice of `LibraryViewModel`: just the sorted book list, mapped to
/// native `BookRow`s at the boundary (rule 8).
///
/// A separate wrapper rather than the phone's `LibraryObserver` because ownership differs: this
/// one OWNS its ViewModel for the duration of a car session and must close it on disconnect
/// (#1192 — nothing else calls `onCleared` on iOS), while the phone observer borrows a ViewModel
/// whose lifetime the library screen manages. It also deliberately ignores the series/author/
/// narrator surfaces — the car browse is books only.
@Observable
@MainActor
final class CarPlayLibraryWrapper {
    /// Sorted library books, empty until the first loaded state arrives. Loading and error are
    /// collapsed to "no rows yet": the Library tab simply stays empty rather than surfacing a
    /// retry flow a driver shouldn't be reading.
    private(set) var books: [BookRow] = []

    private let viewModel: LibraryViewModel
    private let bridge = FlowBridge()

    init(viewModel: LibraryViewModel = Dependencies.shared.libraryViewModel) {
        self.viewModel = viewModel
        bridge.bind(viewModel.uiState) { [weak self] in self?.apply($0) }
        viewModel.onScreenVisible()
    }

    // Isolated deinit (SE-0371): runs on the main actor so the non-Sendable Kotlin viewModel can
    // be closed — else its stream jobs outlive the car session (#1192).
    isolated deinit {
        bridge.cancelAll()   // cancelAll() is nonisolated-safe; see FlowBridge.
        viewModel.close()
    }

    private func apply(_ state: LibraryUiState) {
        guard case .loaded(let loaded) = onEnum(of: state) else { return }
        books = loaded.books.map { BookRow($0) }
    }
}
