import Foundation
import Shared

/// A native, value-typed projection of the shared `ContributorCandidate` for the merge picker.
/// Snapshotting at the observer boundary keeps re-bridged Kotlin objects out of SwiftUI's diff
/// (see `ContributorRow` for the same rationale).
struct MergeCandidate: Identifiable, Equatable, Hashable {
    let id: String
    let name: String

    init(id: String, name: String) {
        self.id = id
        self.name = name
    }

    /// Snapshot a Kotlin `ContributorCandidate` into native values. Reads each bridged property once.
    init(_ candidate: ContributorCandidate) {
        self.id = candidate.id.value
        self.name = candidate.displayName
    }
}
