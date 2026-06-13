import AppIntents
@preconcurrency import Shared

/// An audiobook as exposed to App Intents / Siri / Shortcuts. Carries only the
/// fields the system needs to disambiguate and display a spoken/typed match
/// ("Play Dungeon Crawler Carl in ListenUp"). Resolution is delegated to
/// `BookEntityQuery`, which queries the offline-first `BookRepository`.
struct BookEntity: AppEntity, Identifiable {
    let id: String
    let title: String
    let author: String

    static let typeDisplayRepresentation = TypeDisplayRepresentation(name: "Audiobook")

    var displayRepresentation: DisplayRepresentation {
        DisplayRepresentation(title: "\(title)", subtitle: "\(author)")
    }

    static let defaultQuery = BookEntityQuery()
}

extension BookEntity {
    /// Pure projection of a shared `BookListItem` onto the App Intents entity.
    /// The SKIE boundary unboxes `BookId` to `idString`; `authorNames` is the
    /// comma-joined author display shared with every other list surface.
    static func from(_ item: BookListItem) -> BookEntity {
        BookEntity(id: item.idString, title: item.title, author: item.authorNames)
    }
}
