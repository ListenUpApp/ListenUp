import Foundation
import Shared

/// Native projection of a contributor's per-role section ("Author", "Narrator", …).
///
/// Mapped once at the observer boundary (`ContributorDetailObserver`) so the role carousels
/// diff cheap Swift value types instead of re-bridging the Kotlin `RoleSection` and its nested
/// `[BookListItem]` on every SwiftUI diff/layout pass — the same re-bridging hazard `BookRow`
/// and `SeriesRow` were introduced to kill.
struct RoleSectionRow: Identifiable, Equatable, Hashable {
    let role: String
    let displayName: String
    let books: [BookRow]
    /// True when the role has more books than fit in the preview carousel — the detail screen
    /// surfaces a "See all" link to the full `ContributorBooksView`. Mirrors `RoleSection.showViewAll`.
    let showViewAll: Bool

    var id: String { role }

    init(role: String, displayName: String, books: [BookRow], showViewAll: Bool = false) {
        self.role = role
        self.displayName = displayName
        self.books = books
        self.showViewAll = showViewAll
    }

    /// Snapshot a Kotlin `RoleSection` (and its preview books) into native values.
    init(_ section: RoleSection) {
        self.role = section.role
        self.displayName = section.displayName
        self.books = section.previewBooks.map { BookRow($0) }
        self.showViewAll = section.showViewAll
    }
}
