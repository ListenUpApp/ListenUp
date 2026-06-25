import SwiftUI

/// A carousel card for "What Others Are Listening To": a person's cover with a small
/// avatar + name beneath it. Tapping navigates to the book's detail. Width is width-driven
/// by the caller (wider on iPad) so the rail reads as a real layout at every size.
struct CurrentlyListeningCard: View {
    let row: CurrentlyListeningRow
    let width: CGFloat

    var body: some View {
        NavigationLink(value: BookDestination(id: row.bookId)) {
            VStack(alignment: .leading, spacing: 8) {
                BookCoverImage(bookId: row.bookId, coverPath: row.coverPath, blurHash: row.blurHash)
                    .frame(width: width, height: width)
                    .clipShape(RoundedRectangle(cornerRadius: 16))
                    .shadow(color: .black.opacity(0.12), radius: 6, x: 0, y: 3)

                HStack(spacing: 8) {
                    InitialsAvatar(initials: row.initials, size: 26, tint: Color(hex: row.avatarColor))

                    Text(row.displayName)
                        .font(.subheadline.weight(.medium))
                        .foregroundStyle(.primary)
                        .lineLimit(1)
                }
            }
            .frame(width: width, alignment: .leading)
            .contentShape(Rectangle())
        }
        .buttonStyle(.pressScaleCard)
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
