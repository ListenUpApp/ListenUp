import Foundation

/// The three display states for series-list progress, derived purely from counts.
enum SeriesProgressState: Equatable {
    case complete
    case notStarted
    case partial(finished: Int, total: Int)

    init(finishedCount: Int, totalCount: Int) {
        switch true {
        case totalCount > 0 && finishedCount >= totalCount: self = .complete
        case finishedCount == 0: self = .notStarted
        default: self = .partial(finished: finishedCount, total: totalCount)
        }
    }

    /// Finished fraction for the linear bar.
    var fraction: Double {
        if case let .partial(finished, total) = self, total > 0 {
            return Double(finished) / Double(total)
        }
        return self == .complete ? 1 : 0
    }
}
