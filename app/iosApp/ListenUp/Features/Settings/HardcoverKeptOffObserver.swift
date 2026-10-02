import SwiftUI
import Shared

/// The kept-off list's phase, native (iosApp rule 8): the view never reaches across the bridge.
enum KeptOffPhase: Equatable {
    case loading
    /// The server couldn't say which books are kept off: nothing is claimed.
    case unavailable
    case books([KeptOffBookRow])
}

/// One book kept off Hardcover, native, for a `ForEach` (iosApp rule 8).
struct KeptOffBookRow: Equatable, Identifiable {
    let id: String
    let title: String
    let authorNames: String
    let coverPath: String?
    let coverHash: String?
}

/// What a one-shot `KeptOffBooksEvent` does on iOS. There is no toast, so Sync Again is the row leaving plus a
/// VoiceOver announcement; `close` when no kept-off book is left, so the list pops.
enum KeptOffEffect: Equatable {
    case announce(String, close: Bool)
    case alert(String)
}

/// Observes `KeptOffBooksViewModel` (#1541): flattens its state into a native `KeptOffPhase`, announces each
/// Sync Again, closes the list after the last, and turns a refusal into an alert (iosApp rule 10).
@Observable
@MainActor
final class HardcoverKeptOffObserver {
    private(set) var phase: KeptOffPhase = .loading
    /// The error to show, from a refused Sync Again. Cleared when the alert is dismissed.
    var alert: MessageAlert?
    /// True once the last kept-off book has synced again: the view pops itself.
    private(set) var shouldClose = false

    private let viewModel: KeptOffBooksViewModel
    private let bridge = FlowBridge()

    init(viewModel: KeptOffBooksViewModel) {
        self.viewModel = viewModel
        bridge.bind(viewModel.uiState) { [weak self] in self?.phase = Self.phase(from: $0) }
        bridge.bind(viewModel.events) { [weak self] in self?.apply(Self.effect(of: $0)) }
    }

    deinit { bridge.cancelAll() }   // cancelAll() is nonisolated-safe; see FlowBridge.

    /// Sync Again: the row leaves at once; the ViewModel puts it back, with an alert, if the server refuses.
    func syncAgain(_ bookId: String) { viewModel.syncAgain(bookId: bookId) }

    private func apply(_ effect: KeptOffEffect) {
        switch effect {
        case .announce(let words, let close):
            AccessibilityNotification.Announcement(words).post()
            if close { shouldClose = true }
        case .alert(let message):
            alert = MessageAlert(message: message)
        }
    }

    // MARK: - Pure mappings (unit-tested)

    nonisolated static func phase(from state: KeptOffBooksUiState) -> KeptOffPhase {
        switch state.sealedType() {
        case .loading:
            return .loading
        case .unavailable:
            return .unavailable
        case .loaded(let loadedType):
            return .books(loadedType.value.books.map {
                KeptOffBookRow(
                    id: $0.bookId,
                    title: $0.title,
                    authorNames: $0.authorNames,
                    coverPath: $0.coverPath,
                    coverHash: $0.coverHash
                )
            })
        }
    }

    nonisolated static func effect(of event: KeptOffBooksEvent) -> KeptOffEffect {
        switch event.sealedType() {
        case .syncingAgain(let syncingType):
            let syncing = syncingType.value
            return .announce(
                String(format: String(localized: "hardcover.syncing_again"), syncing.title),
                close: syncing.wasLast
            )
        case .showError(let showErrorType):
            return .alert(showErrorType.value.error.message)
        }
    }
}
