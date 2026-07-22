import Foundation
import Shared

/// Observes `HomeViewModel` — flattens the sealed `HomeUiState` into a SwiftUI-native
/// `HomePhase`, and surfaces transient `snackbarMessages` as a clearable string.
/// Thin over `FlowBridge`; all mapping logic lives in pure, testable initializers.
@Observable
@MainActor
final class HomeViewModelWrapper {
    // MARK: - State

    private(set) var phase: HomePhase = .loading

    /// Latest transient message from the VM's snackbar channel. The view reads it,
    /// shows it, and calls `clearSnackbar()` once consumed.
    private(set) var snackbar: String?

    // MARK: - Dependencies

    private let viewModel: HomeViewModel
    private let bridge = FlowBridge()

    // MARK: - Init

    init(viewModel: HomeViewModel = Dependencies.shared.makeHomeViewModel()) {
        self.viewModel = viewModel
        bridge.bind(viewModel.state) { [weak self] in self?.apply($0) }
        bridge.bind(viewModel.snackbarMessages) { [weak self] in self?.snackbar = $0 }
    }

    // Isolated deinit (SE-0371): runs hopped onto the main actor, so the non-Sendable Kotlin
    // viewModel can be closed here. No ViewModelStore on iOS calls onCleared, so this wrapper must
    // (#1192) — else the VM's stream/poll jobs orphan and run forever.
    isolated deinit {
        bridge.cancelAll()   // cancelAll() is nonisolated-safe; see FlowBridge.
        viewModel.close()
    }

    // MARK: - Actions

    func refresh() {
        viewModel.refresh()
    }

    /// Clear the transient snackbar once the view has shown it.
    func clearSnackbar() {
        snackbar = nil
    }

    // MARK: - State mapping

    private func apply(_ state: HomeUiState) {
        switch onEnum(of: state) {
        case .loading:
            phase = .loading
        case .ready(let ready):
            phase = .ready(HomeReady(from: ready))
        case .error(let error):
            phase = .error(error.message)
        case .unknown:
            Log.error("Unexpected HomeUiState case")
            phase = .error(String(localized: "common.something_went_wrong"))
        }
    }
}

// MARK: - Phase

/// Flattened Home state for a SwiftUI `switch` — the screen has three distinct surfaces.
enum HomePhase: Equatable {
    case loading
    case ready(HomeReady)
    case error(String)
}

// MARK: - Ready content

/// Everything the Home screen renders when `HomeUiState.Ready` is active.
struct HomeReady: Equatable {
    /// The bare time-of-day greeting ("Good evening") — *not* combined with the name. The KMP
    /// `Ready.greeting` getter appends the name; the header renders the name separately as a hero,
    /// so we map the bare `timeGreeting` here to avoid showing the name twice.
    let timeGreeting: String
    let userName: String
    let continueItems: [ContinueItem]
    let shelves: [ShelfItem]
    let isSyncing: Bool

    init(from ready: HomeUiStateReady) {
        self.timeGreeting = ready.timeGreeting
        self.userName = ready.userName
        self.continueItems = ready.continueListening.map(ContinueItem.init(from:))
        self.shelves = ready.myShelves.map(ShelfItem.init(from:))
        self.isSyncing = ready.isSyncing
    }
}

// MARK: - Continue Listening item

/// One Continue-Listening card. A `Ready` KMP item maps to a fully-populated card;
/// a `Loading` item (sync in-flight) maps to a skeleton card (`isLoading == true`).
struct ContinueItem: Identifiable, Equatable {
    let id: String
    let title: String
    let author: String
    let coverPath: String?
    let progress: Double
    let progressPercent: Int
    let timeLeft: String
    let isLoading: Bool

    init(from item: ContinueListeningItem) {
        switch onEnum(of: item) {
        case .ready(let ready):
            let book = ready.book
            self.init(
                id: book.bookId,
                title: book.title,
                author: book.authorNames,
                coverPath: book.coverPath,
                progress: Double(book.progress),
                progressPercent: Int(book.progressPercent),
                timeLeft: book.timeRemainingFormatted,
                isLoading: false
            )
        case .loading(let loading):
            self.init(
                id: loading.bookId,
                title: "",
                author: "",
                coverPath: nil,
                progress: 0,
                progressPercent: 0,
                timeLeft: "",
                isLoading: true
            )
        case .unknown:
            Log.error("Unexpected ContinueListeningItem case")
            self.init(
                id: "",
                title: "",
                author: "",
                coverPath: nil,
                progress: 0,
                progressPercent: 0,
                timeLeft: "",
                isLoading: true
            )
        }
    }

    init(
        id: String,
        title: String,
        author: String,
        coverPath: String?,
        progress: Double,
        progressPercent: Int,
        timeLeft: String,
        isLoading: Bool
    ) {
        self.id = id
        self.title = title
        self.author = author
        self.coverPath = coverPath
        self.progress = progress
        self.progressPercent = progressPercent
        self.timeLeft = timeLeft
        self.isLoading = isLoading
    }
}

// MARK: - Shelf item

/// One "My Shelves" card.
struct ShelfItem: Identifiable, Equatable {
    let id: String
    let name: String
    let bookCount: Int
    let durationLabel: String
    let coverPaths: [String]

    init(from shelf: Shelf) {
        self.id = shelf.idString
        self.name = shelf.name
        self.bookCount = Int(shelf.bookCount)
        self.durationLabel = shelf.formattedDuration
        self.coverPaths = shelf.coverPaths
    }
}
