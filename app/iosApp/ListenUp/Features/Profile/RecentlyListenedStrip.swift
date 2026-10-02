import SwiftUI
import Shared

/// A book in a profile's "Recently listened" strip — a native value mapped once at the observer
/// boundary, so the strip's `ForEach` never re-reads a bridged Kotlin object.
struct ProfileRecentBookRow: Identifiable, Hashable {
    let id: String
    let title: String
    let coverHash: String?

    init(id: String, title: String, coverHash: String?) {
        self.id = id
        self.title = title
        self.coverHash = coverHash
    }

    init(from book: ProfileRecentBook) {
        self.init(id: book.bookId, title: book.title, coverHash: book.coverHash)
    }

    /// The shared state's books, in the order the shared layer ranked them (newest first).
    static func rows(from books: [ProfileRecentBook]) -> [ProfileRecentBookRow] {
        books.map(ProfileRecentBookRow.init(from:))
    }

    /// Where a tap on this cover goes: the book's detail.
    var destination: BookDestination { BookDestination(id: id) }

    /// What VoiceOver reads for this cover.
    var accessibilityLabel: String { title }
}

/// A profile's "Recently listened" strip: a horizontally scrolling row of covers, newest first, each
/// opening its book. Shown on your own profile and on someone else's; renders nothing when empty.
///
/// HIG, Collections (https://developer.apple.com/design/human-interface-guidelines/collections): a
/// single horizontally scrolling row of like items, each a large, clear tap target. HIG, Accessibility
/// (https://developer.apple.com/design/human-interface-guidelines/accessibility): the heading carries
/// the header trait so the rotor can jump to it, and each cover is one link named for its book. Cover
/// width scales with Dynamic Type so a larger text size keeps titles legible.
struct RecentlyListenedStrip: View {
    let books: [ProfileRecentBookRow]
    let isOwnProfile: Bool

    @ScaledMetric(relativeTo: .subheadline) private var coverWidth: CGFloat = 112

    /// An empty strip is absent, not an empty heading over nothing.
    nonisolated static func isShown(books: [ProfileRecentBookRow]) -> Bool { !books.isEmpty }

    /// "Recently listened" on someone else's profile; addressed to you on your own.
    nonisolated static func title(isOwnProfile: Bool) -> String {
        isOwnProfile
            ? String(localized: "profile.recently_listened_own")
            : String(localized: "profile.recently_listened")
    }

    var body: some View {
        if Self.isShown(books: books) {
            VStack(alignment: .leading, spacing: Spacing.s) {
                SectionRow(title: Self.title(isOwnProfile: isOwnProfile))

                ScrollView(.horizontal, showsIndicators: false) {
                    LazyHStack(alignment: .top, spacing: Spacing.m) {
                        ForEach(books) { book in
                            NavigationLink(value: book.destination) {
                                card(book)
                            }
                            .buttonStyle(.plain)
                            .accessibilityLabel(book.accessibilityLabel)
                        }
                    }
                }
                .scrollClipDisabled()
            }
        }
    }

    private func card(_ book: ProfileRecentBookRow) -> some View {
        VStack(alignment: .leading, spacing: Spacing.xs) {
            BookCoverImage(bookId: book.id, coverPath: nil, coverHash: book.coverHash)
                .aspectRatio(1, contentMode: .fit)
                .clipShape(RoundedRectangle(cornerRadius: Radius.s, style: .continuous))

            Text(book.title)
                .font(.subheadline.weight(.medium))
                .foregroundStyle(.primary)
                .lineLimit(2, reservesSpace: true)
                .multilineTextAlignment(.leading)
        }
        .frame(width: coverWidth)
        .contentShape(Rectangle())
    }
}
