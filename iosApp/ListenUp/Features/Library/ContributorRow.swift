import Foundation
@preconcurrency import Shared

/// A native, value-typed projection of `ContributorWithBookCount` for the Library Contributors tab.
///
/// **Why (performance — see `BookRow`).** `PersonRow` re-read 5+ bridged properties of the Kotlin
/// `ContributorWithBookCount` on every diff; over a whole-library list with an alphabet scrubber that
/// re-bridging hangs the main thread. Snapshot once at the observer boundary. The avatar still streams
/// the server photo — `PersonRow` passes `id` to `ContributorAvatar(streamsContributorPhoto: true)`,
/// which resolves the durable path from the id, so `imagePath` isn't needed here.
struct ContributorRow: Identifiable, Equatable, Hashable {
    let id: String
    let name: String
    let bookCount: Int
    let imageBlurHash: String?

    init(id: String, name: String, bookCount: Int, imageBlurHash: String?) {
        self.id = id
        self.name = name
        self.bookCount = bookCount
        self.imageBlurHash = imageBlurHash
    }

    /// Snapshot a Kotlin `ContributorWithBookCount` into native values. Reads each bridged property once.
    init(_ contributor: ContributorWithBookCount) {
        self.id = contributor.contributor.idString
        self.name = contributor.contributor.name
        self.bookCount = Int(contributor.bookCount)
        self.imageBlurHash = contributor.contributor.imageBlurHash
    }
}
