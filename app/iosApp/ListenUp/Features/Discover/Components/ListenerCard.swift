import SwiftUI

/// One person in Listening Now: the book (art with LIVE when they are on it now, then its title) as one
/// link to the book, and under it — a sibling, never nested — the person as a link to their profile
/// (spec X12, X16). `width` nil fills the grid cell it is given.
///
/// When `selection` is supplied and selecting, the book shows a selection circle and a tap toggles it
/// instead of navigating; a long-press opens the book's context menu. `nil` = navigation only.
struct ListenerCard: View {
    let row: CurrentlyListeningRow
    let width: CGFloat?
    var selection: BookSelectionObserver?
    var now = Date()

    @Environment(\.restrictedBooks) private var restrictedBooks

    private var marker: String {
        row.isLive
            ? String(localized: "discover.listening_now")
            : DiscoverCopy.lastActive(row.lastActiveAt, relativeTo: now)
    }

    var body: some View {
        VStack(alignment: .leading, spacing: Spacing.xs) {
            book
            person
        }
        .frame(width: width, alignment: .leading)
    }

    private var book: some View {
        SelectableBookCard(bookId: row.bookId, selection: selection) {
            VStack(alignment: .leading, spacing: Spacing.xs) {
                BookCoverImage(bookId: row.bookId, coverPath: row.coverPath, coverHash: row.coverHash)
                    .aspectRatio(1, contentMode: .fit)
                    .clipShape(RoundedRectangle(cornerRadius: Radius.l))
                    .overlay(alignment: .topTrailing) {
                        if row.isLive { LiveBadge().padding(Spacing.xs) }
                    }
                    .restrictedMarker(bookId: row.bookId, isSelecting: selection?.isSelecting ?? false)
                    .coverHoverEffect(cornerRadius: Radius.l)
                    .shadow(color: .black.opacity(0.12), radius: 6, x: 0, y: 3)
                    .bookSelectionCircle(bookId: row.bookId, selection: selection)

                Text(row.title)
                    .font(.subheadline)
                    .foregroundStyle(.primary)
                    .lineLimit(1)
            }
            .contentShape(Rectangle())
        }
        .accessibilityElement(children: .combine)
        .accessibilityLabel(
            RestrictedMarker.label(
                DiscoverCopy.listenerLabel(book: row.title, name: row.displayName, isLive: row.isLive, when: marker),
                isRestricted: restrictedBooks?.isRestricted(row.bookId) == true
            )
        )
    }

    private var person: some View {
        NavigationLink(value: ProfileDestination(userId: row.userId)) {
            HStack(spacing: Spacing.xs) {
                UserAvatarView(userId: row.userId, fallbackName: row.displayName, size: 30)
                    .accessibilityHidden(true)
                VStack(alignment: .leading, spacing: 0) {
                    Text(row.displayName)
                        .font(.footnote.weight(.semibold))
                        .foregroundStyle(.primary)
                        .lineLimit(1)
                    Text(marker)
                        .font(.caption)
                        .fontWeight(row.isLive ? .semibold : .regular)
                        .foregroundStyle(row.isLive ? Color.luTint : Color.secondary)
                        .lineLimit(1)
                }
            }
            .frame(maxWidth: .infinity, minHeight: 44, alignment: .leading)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityElement(children: .combine)
    }
}
