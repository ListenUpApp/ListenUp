import Foundation
import Shared

/// A server-side directory, flattened for SwiftUI display.
struct DirectoryItem: Identifiable, Equatable {
    var id: String { path }
    let name: String
    let path: String
    let itemCount: Int
    let hasChildren: Bool
    let isSelected: Bool

    /// Flatten a KMP ``DirectoryEntry`` against the current selection set.
    init(from entry: DirectoryEntry, selectedPaths: Set<String>) {
        name = entry.name
        path = entry.path
        itemCount = Int(entry.itemCount)
        hasChildren = entry.hasChildren
        isSelected = selectedPaths.contains(entry.path)
    }
}

/// Observes ``LibrarySetupViewModel`` — the `LibrarySetupUiState` and its one-shot
/// `navActions` flow — flattening both into SwiftUI-native state. Thin over `FlowBridge`.
/// Errors are mapped once here into ``errorMessage`` (no `errorBus`).
@Observable
@MainActor
final class LibrarySetupViewModelWrapper {
    // Status check
    private(set) var isCheckingStatus: Bool = true
    private(set) var needsSetup: Bool = false

    // Folder browser
    private(set) var currentPath: String = "/"
    private(set) var parentPath: String?
    private(set) var isRoot: Bool = true
    private(set) var isLoadingDirectories: Bool = false
    private(set) var directories: [DirectoryItem] = []

    /// The full selection set, spanning every directory the user has visited — not just
    /// the rows currently on screen. The visible `directories` only carry a per-row
    /// `isSelected` flag for rendering; this is the authoritative gate for "can create".
    private(set) var selectedPaths: Set<String> = []

    /// True once at least one folder anywhere in the tree is selected.
    var hasSelection: Bool { !selectedPaths.isEmpty }

    /// How many folders are selected across the whole tree.
    var selectionCount: Int { selectedPaths.count }

    // Library creation
    private(set) var isCreatingLibrary: Bool = false

    // Error (mapped once from state.error)
    private(set) var errorMessage: String?

    /// Navigation callbacks — set by the coordinator.
    var onFinished: (() -> Void)?

    /// The shared VM. Optional because the bridge-free test initializer (`init()`) leaves
    /// it nil — production always wires it. Action methods `guard` on it so a stray call on
    /// a test-initialized wrapper is a safe no-op, not a nil-unwrap crash. The state-mapping
    /// path (`apply`) doesn't touch it, which is what tests exercise.
    private let viewModel: LibrarySetupViewModel?
    private let bridge = FlowBridge()

    init(viewModel: LibrarySetupViewModel) {
        self.viewModel = viewModel
        bridge.bind(viewModel.state) { [weak self] in self?.apply($0) }
        bridge.bind(viewModel.navActions) { [weak self] in self?.applyNav($0) }
    }

    /// Bridge-free initializer for wrapper-level unit tests. Skips flow binding so the
    /// stateful mapping (`apply`) can be driven directly without a live KMP ViewModel.
    init() {
        self.viewModel = nil
    }

    // Isolated deinit (SE-0371): runs hopped onto the main actor, so the non-Sendable Kotlin
    // viewModel can be closed here. No ViewModelStore on iOS calls onCleared, so this wrapper must
    // (#1192). Optional because the bridge-free test initializer leaves it nil.
    isolated deinit {
        bridge.cancelAll()   // cancelAll() is nonisolated-safe; see FlowBridge.
        viewModel?.close()
    }

    // MARK: - Actions

    func checkStatus() {
        guard let viewModel else { return }
        viewModel.checkLibraryStatus()
    }

    func open(_ path: String) {
        guard let viewModel else { return }
        viewModel.loadDirectory(path: path)
    }

    func up() {
        guard let viewModel else { return }
        viewModel.navigateUp()
    }

    func toggle(_ path: String) {
        guard let viewModel else { return }
        viewModel.togglePath(path: path)
    }

    /// Add the current directory to the selection (mirrors the wizard's
    /// "use this folder" affordance). `selectPath` is additive in the shared VM.
    func selectCurrent() {
        guard let viewModel else { return }
        viewModel.selectPath(path: currentPath)
    }

    func clearSelection() {
        guard let viewModel else { return }
        viewModel.clearSelection()
    }

    func completeSetup() {
        guard let viewModel else { return }
        viewModel.completeSetup()
    }

    func dismissError() {
        guard let viewModel else { return }
        viewModel.clearError()
    }

    // MARK: - State mapping

    /// Internal (not private) so wrapper-level tests can drive the stateful selection
    /// logic directly without a live KMP ViewModel.
    func apply(_ state: LibrarySetupUiState) {
        isCheckingStatus = state.isCheckingStatus
        needsSetup = state.needsSetup
        currentPath = state.currentPath
        parentPath = state.parentPath
        isRoot = state.isRoot
        isLoadingDirectories = state.isLoadingDirectories
        isCreatingLibrary = state.isCreatingLibrary
        errorMessage = state.error

        let selected = state.selectedPaths
        selectedPaths = selected
        directories = state.directories.map {
            DirectoryItem(from: $0, selectedPaths: selected)
        }
    }

    private func applyNav(_ action: LibrarySetupNavAction) {
        switch onEnum(of: action) {
        case .finished:
            onFinished?()
        case .unknown:
            Log.error("Unexpected LibrarySetupNavAction case")
        }
    }
}
