import Foundation

/// Every sentence a bulk shelf or collection action speaks, as pure functions over counts.
///
/// Mirrors `BookSelectionScaffold.kt`'s `booksAddedToShelfMessage` / `booksAddedToCollectionMessage`
/// sentence for sentence, so the two platforms describe the same outcome the same way.
enum SelectionFormatting {
    /// Zero is named, not counted. The count is what the shelf actually **gained** rather than the
    /// size of the selection, so zero is the honest answer when every selected book was already
    /// there — and "0 books added" reads as a failure rather than as a no-op.
    static func addedToShelf(count: Int) -> String {
        switch count {
        case 0: String(localized: "selection.added_to_shelf_none")
        case 1: String(localized: "selection.added_to_shelf_one")
        default: String(format: String(localized: "selection.added_to_shelf_plural"), count)
        }
    }

    /// As `addedToShelf(count:)`, for collections.
    static func addedToCollection(count: Int) -> String {
        switch count {
        case 0: String(localized: "selection.added_to_collection_none")
        case 1: String(localized: "selection.added_to_collection_one")
        default: String(format: String(localized: "selection.added_to_collection_plural"), count)
        }
    }

    /// A shelf or collection that did not exist a moment ago. Named rather than counted-into,
    /// because the user just invented it and the name is what they will look for next. One function
    /// for both: the sentence never says which kind of thing it made.
    static func createdWithBooks(name: String, count: Int) -> String {
        count == 1
            ? String(format: String(localized: "selection.created_with_books_one"), name)
            : String(format: String(localized: "selection.created_with_books_plural"), name, count)
    }
}
