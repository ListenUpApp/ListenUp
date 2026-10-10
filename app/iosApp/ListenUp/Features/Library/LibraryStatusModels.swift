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
    static let filters: [BookStatusFilter] = [.all, .inProgress, .notStarted, .finished]

    static func label(_ filter: BookStatusFilter) -> String {
        switch filter {
        case .all: String(localized: "library.status_all")
        case .inProgress: String(localized: "library.status_in_progress_ios")
        case .notStarted: String(localized: "library.status_not_started_ios")
        case .finished: String(localized: "library.status_finished")
        }
    }
}
