import Foundation
import Shared

/// A native, value-typed projection of `ContributorWithBookCount` for the Library Contributors tab.
///
/// **Why (performance — see `BookRow`).** `PersonRow` re-read 5+ bridged properties of the Kotlin
/// `ContributorWithBookCount` on every diff; over a whole-library list with an alphabet scrubber that
/// re-bridging hangs the main thread. Snapshot once at the observer boundary. `imagePath` is the
/// contributor's content-addressed photo path — `PersonRow` passes it to
/// `ContributorAvatar(streamsContributorPhoto: true)` so a sync-driven photo change busts the cache.
struct ContributorRow: Identifiable, Equatable, Hashable {
    let id: String
    let name: String
    let bookCount: Int
    let imagePath: String?

    init(id: String, name: String, bookCount: Int, imagePath: String?) {
        self.id = id
        self.name = name
        self.bookCount = bookCount
        self.imagePath = imagePath
    }

    /// Snapshot a Kotlin `ContributorWithBookCount` into native values. Reads each bridged property once.
    init(_ contributor: ContributorWithBookCount) {
        self.id = contributor.contributor.idString
        self.name = contributor.contributor.name
        self.bookCount = Int(contributor.bookCount)
        self.imagePath = contributor.contributor.imagePath
    }
}
