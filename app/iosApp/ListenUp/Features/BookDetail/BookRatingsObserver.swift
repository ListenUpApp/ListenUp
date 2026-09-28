import Foundation
import SwiftUI
import Shared

/// Observes `BookRatingsViewModel`: flattens the sealed `BookRatingsUiState` into a native
/// `BookRatingsPhase` for the rating block on Book Detail, and forwards rate and clear back to the
/// VM. Everything the VM reads comes from Room, so the block works offline and updates the moment
/// a rating syncs in from another device.
///
/// Kotlin values are projected to Swift value types at this boundary, so the view never holds a
/// bridged object. Thin over `FlowBridge`; the mapping is a pure, testable static.
@Observable
@MainActor
final class BookRatingsObserver {
    // MARK: - State

    private(set) var phase: BookRatingsPhase = .loading

    // MARK: - Dependencies

    private let viewModel: BookRatingsViewModel
    private let bridge = FlowBridge()

    // MARK: - Init

    init(viewModel: BookRatingsViewModel) {
        self.viewModel = viewModel
        bridge.bind(viewModel.state) { [weak self] in self?.phase = BookRatingsObserver.phase(from: $0) }
    }

    deinit { bridge.cancelAll() }   // cancelAll() is nonisolated-safe; see FlowBridge.

    // MARK: - Actions

    /// Rate the book `halfStars` (2...10) with an optional note; the VM trims a blank note away.
    func rate(halfStars: Int, note: String) {
        viewModel.rate(halfStars: Int32(halfStars), note: note)
    }

    /// Remove my rating.
    func clear() { viewModel.clear() }

    // MARK: - State mapping

    /// Pure: project the shared state onto native values. `nonisolated` so tests can call it off
    /// the main actor.
    nonisolated static func phase(from state: BookRatingsUiState) -> BookRatingsPhase {
        switch state.sealedType() {
        case .loading:
            return .loading
        case .ready(let readyType):
            let ready = readyType.value
            return .ready(BookRatingsSnapshot(
                listeners: ready.listeners.map {
                    ListenersAverage(averageHalfStars: $0.averageHalfStars, count: Int($0.count))
                },
                mine: ready.mine.map { MyRating(halfStars: Int($0.halfStars), note: $0.note) }
            ))
        }
    }
}

// MARK: - Phase

/// The rating block's state. `loading` renders nothing, so the block never flashes "Rate" at
/// someone who already has.
enum BookRatingsPhase: Equatable {
    case loading
    case ready(BookRatingsSnapshot)
}

/// What the rating block shows once the ratings are read.
struct BookRatingsSnapshot: Equatable {
    /// Your listeners' average, or nil when nobody has rated the book.
    let listeners: ListenersAverage?
    /// The signed-in listener's rating, or nil when they haven't rated it.
    let mine: MyRating?
}

/// Your listeners' average on the 2...10 half-star scale, over `count` ratings.
struct ListenersAverage: Equatable {
    let averageHalfStars: Double
    let count: Int
}

/// The signed-in listener's own rating.
struct MyRating: Equatable {
    let halfStars: Int
    let note: String?
}
