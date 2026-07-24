import Foundation
import SwiftUI
import Shared

/// Observes `BookReadersViewModel` — flattens the sealed `BookReadersUiState` into a
/// SwiftUI-native `BookReadersPhase` for the Book Detail "Readers" section.
///
/// The shared `Reader` model carries both states at once: `currentProgressPct` (non-null ⇒
/// reading now) and `finishes` (dated completions, newest-first). This observer projects each
/// Kotlin `Reader` to a native `BookReaderRow` value type **at the observer boundary** so the
/// section's `ForEach` never re-bridges Kotlin objects across the SKIE seam (iOS rule 8).
///
/// Thin over `FlowBridge`; the `Data` mapping lives in a pure, testable helper.
@Observable
@MainActor
final class BookReadersObserver {
    // MARK: - State

    private(set) var phase: BookReadersPhase = .loading

    // MARK: - Dependencies

    private let viewModel: BookReadersViewModel
    private let bridge = FlowBridge()

    // MARK: - Init

    init(viewModel: BookReadersViewModel) {
        self.viewModel = viewModel
        bridge.bind(viewModel.uiState) { [weak self] in self?.apply($0) }
    }

    deinit { bridge.cancelAll() }   // cancelAll() is nonisolated-safe; see FlowBridge.

    // MARK: - State mapping

    private func apply(_ state: BookReadersUiState) {
        switch onEnum(of: state) {
        case .loading:
            phase = .loading
        case .noReaders:
            phase = .empty
        case .data(let data):
            phase = .data(Self.rows(from: data.readers))
        case .error(let error):
            phase = .error(isRetryable: error.isRetryable)
        case .unknown:
            Log.error("Unexpected BookReadersUiState case")
            phase = .error(isRetryable: false)
        }
    }

    /// Pure: project the shared `BookReaders` to display rows. `nonisolated` because it touches
    /// no actor state — callable from tests off the main actor.
    nonisolated static func rows(from readers: BookReaders) -> [BookReaderRow] {
        readers.readers.map { BookReaderRow(from: $0) }
    }
}

// MARK: - Phase

/// Flattened readers state for a SwiftUI `switch`. `empty` hides the section entirely.
enum BookReadersPhase: Equatable {
    case loading
    case empty
    case data([BookReaderRow])
    case error(isRetryable: Bool)
}

// MARK: - Row model

/// One reader of this book, projected from the Kotlin `Reader` at the observer boundary.
///
/// `isReading` is `progressPercent != nil` — the shared model encodes "reading now" as a
/// non-null `currentProgressPct`. When not reading, `lastFinished` carries the most recent dated
/// completion (newest-first in the source list) for the "Finished {date}" label.
struct BookReaderRow: Identifiable, Equatable {
    let id: String
    let displayName: String
    let initials: String
    let isYou: Bool
    /// 0...100 when reading now; nil otherwise.
    let progressPercent: Int?
    /// Most recent completion, when finished and not currently reading; nil otherwise.
    let lastFinished: Date?

    var isReading: Bool { progressPercent != nil }

    init(from reader: Reader) {
        self.id = reader.userId
        self.displayName = reader.displayName
        self.initials = Self.initials(from: reader.displayName)
        self.isYou = reader.isYou
        self.progressPercent = reader.currentProgressPct.map { Int($0) }
        // `finishes` is newest-first; the first entry is the most recent completion (epoch ms).
        self.lastFinished = reader.finishes.first.map {
            Date(timeIntervalSince1970: Double($0) / 1000)
        }
    }

    init(
        id: String,
        displayName: String,
        initials: String,
        isYou: Bool,
        progressPercent: Int?,
        lastFinished: Date?
    ) {
        self.id = id
        self.displayName = displayName
        self.initials = initials
        self.isYou = isYou
        self.progressPercent = progressPercent
        self.lastFinished = lastFinished
    }

    /// Up to two uppercase initials from the first and last words of a display name.
    static func initials(from name: String) -> String {
        let words = name.split(separator: " ")
        guard let first = words.first?.first else { return "" }
        if words.count > 1, let last = words.last?.first {
            return "\(first)\(last)".uppercased()
        }
        return String(first).uppercased()
    }
}
