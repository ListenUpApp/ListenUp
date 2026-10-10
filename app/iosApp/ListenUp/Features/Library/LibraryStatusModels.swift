import Shared

/// A Library card's reading state, bridged once per emission from Kotlin's `BookCardStatus`.
enum LibraryCardState: Equatable {
    case notStarted(durationMs: Int64)
    case inProgress(fraction: Float, timeLeftMs: Int64)
    case finished(durationMs: Int64)

    static func from(_ status: BookCardStatus) -> LibraryCardState {
        switch status.sealedType() {
        case .notStarted(let notStarted): .notStarted(durationMs: notStarted.value.durationMs)
        case .inProgress(let inProgress):
            .inProgress(fraction: inProgress.value.fraction, timeLeftMs: inProgress.value.timeLeftMs)
        case .finished(let finished): .finished(durationMs: finished.value.durationMs)
        }
    }

    /// The card's last line (spec §2.6): "40h 31m left", "Finished · 12h 4m", or the length.
    var lastLine: String {
        switch self {
        case .inProgress(_, let timeLeftMs):
            String(format: String(localized: "book.time_left"), DurationFormatting.hoursMinutes(ms: timeLeftMs))
        case .finished(let durationMs):
            String(format: String(localized: "library.card_finished_length"), DurationFormatting.hoursMinutes(ms: durationMs))
        case .notStarted(let durationMs):
            DurationFormatting.hoursMinutes(ms: durationMs)
        }
    }

    var isFinished: Bool {
        if case .finished = self { true } else { false }
    }

    /// How far along a started book is; nil when not started or finished.
    var fraction: Float? {
        if case .inProgress(let fraction, _) = self { fraction } else { nil }
    }
}

/// Whole-library counts per reading state, as Swift `Int`s.
struct LibraryStatusCounts: Equatable {
    let all: Int
    let inProgress: Int
    let notStarted: Int
    let finished: Int

    init(_ counts: BookStatusCounts) {
        all = Int(counts.all)
        inProgress = Int(counts.inProgress)
        notStarted = Int(counts.notStarted)
        finished = Int(counts.finished)
    }

    static let zero = LibraryStatusCounts(BookStatusCounts(all: 0, inProgress: 0, notStarted: 0, finished: 0))

    func count(for filter: BookStatusFilter) -> Int {
        switch filter {
        case .all: all
        case .inProgress: inProgress
        case .notStarted: notStarted
        case .finished: finished
        }
    }
}

/// The filter rows the menu offers. A Swift array — never Kotlin's `List<BookStatusFilter>`, which traps.
enum LibraryStatusOptions {
    /// Computed, not stored: a stored array of a bridged Kotlin enum is not `Sendable`.
    static var filters: [BookStatusFilter] { [.all, .inProgress, .notStarted, .finished] }

    static func label(_ filter: BookStatusFilter) -> String {
        switch filter {
        case .all: String(localized: "library.status_all")
        case .inProgress: String(localized: "library.status_in_progress_ios")
        case .notStarted: String(localized: "library.status_not_started_ios")
        case .finished: String(localized: "library.status_finished")
        }
    }
}
