import AppIntents
import Shared

/// An audiobook as exposed to App Intents / Siri / Apple Intelligence. Conforms to
/// Apple's `.books.audiobook` assistant schema so the system understands it
/// semantically — Siri can surface and act on our books in the `books` domain, and
/// the Spotlight semantic index can reason about them — rather than treating the
/// entity as an opaque app-specific value.
///
/// Resolution is delegated to `BookEntityQuery`, which queries the offline-first
/// `BookRepository`, so spoken lookups ("Play Dungeon Crawler Carl in ListenUp")
/// work without a connection.
///
/// The schema's properties (`title`, `seriesTitle`, `author`, `genre`,
/// `purchaseDate`, `url`) are all optional. We populate what a `BookListItem`
/// carries — title, comma-joined authors, and the first series name; `genre`,
/// `purchaseDate`, and `url` are detail-only / unmodeled here (ListenUp is
/// self-hosted; there is no canonical public URL for a book) and stay `nil`.
@AppEntity(schema: .books.audiobook)
struct BookEntity {
    let id: String

    @Property(title: "Title")
    var title: String?

    @Property(title: "Series")
    var seriesTitle: String?

    @Property(title: "Author")
    var author: String?

    @Property(title: "Genre")
    var genre: String?

    @Property(title: "Purchase Date")
    var purchaseDate: Date?

    @Property(title: "URL")
    var url: URL?

    var displayRepresentation: DisplayRepresentation {
        DisplayRepresentation(title: "\(title ?? "Audiobook")", subtitle: "\(author ?? "")")
    }

    static let defaultQuery = BookEntityQuery()

    init(
        id: String,
        title: String?,
        seriesTitle: String? = nil,
        author: String?,
        genre: String? = nil,
        purchaseDate: Date? = nil,
        url: URL? = nil
    ) {
        self.id = id
        self.title = title
        self.seriesTitle = seriesTitle
        self.author = author
        self.genre = genre
        self.purchaseDate = purchaseDate
        self.url = url
    }
}

extension BookEntity {
    /// Pure projection of a shared `BookListItem` onto the schema entity. The Kotlin
    /// model exposes `idString` (`BookId.value`) so Swift reads a plain `String`;
    /// `authorNames` is the comma-joined
    /// author display shared with every other list surface; `seriesName` is the
    /// first series this book belongs to (the schema's `seriesTitle`).
    static func from(_ item: BookListItem) -> BookEntity {
        BookEntity(
            id: item.idString,
            title: item.title,
            seriesTitle: item.seriesName,
            author: item.authorNames
        )
    }
}
