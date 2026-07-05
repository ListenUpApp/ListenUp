import SwiftUI

/// A carousel card for "What Others Are Listening To": a person's cover with a small
/// avatar + name beneath it. Tapping navigates to the book's detail. Width is width-driven
/// by the caller (wider on iPad) so the rail reads as a real layout at every size.
///
/// When `selection` is supplied and selecting, the cover shows a selection circle and a tap toggles
/// selection of that person's book instead of navigating; a long-press is the secondary entry.
/// `nil` = navigation only.
struct CurrentlyListeningCard: View {
    let row: CurrentlyListeningRow
    let width: CGFloat
    var selection: BookSelectionObserver?

    var body: some View {
        SelectableBookCard(bookId: row.bookId, selection: selection) {
            VStack(alignment: .leading, spacing: 8) {
                BookCoverImage(bookId: row.bookId, coverPath: row.coverPath, blurHash: row.blurHash)
                    .frame(width: width, height: width)
                    .clipShape(RoundedRectangle(cornerRadius: 16))
                    .shadow(color: .black.opacity(0.12), radius: 6, x: 0, y: 3)
                    .bookSelectionCircle(bookId: row.bookId, selection: selection)

                HStack(spacing: 8) {
                    UserAvatarView(userId: row.userId, fallbackName: row.displayName, size: 26)

                    Text(row.displayName)
                        .font(.subheadline.weight(.medium))
                        .foregroundStyle(.primary)
                        .lineLimit(1)
                }
            }
            .frame(width: width, alignment: .leading)
            .contentShape(Rectangle())
        }
        .accessibilityElement(children: .combine)
        .accessibilityLabel(
            String(
                format: String(localized: "discover.x_is_listening_to_y"),
                row.displayName,
                row.title
            )
        )
    }
}
