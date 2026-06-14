import SwiftUI

/// A cover card in the "New for You" rail: a rounded cover with the author below and
/// a calmer caption. Tapping navigates to the book's detail. Width is width-driven by
/// the caller (wider on iPad) so the rail reads as a real layout at every size.
struct DiscoverCoverCard: View {
    let book: DiscoverBook
    let width: CGFloat

    var body: some View {
        NavigationLink(value: BookDestination(id: book.id)) {
            VStack(alignment: .leading, spacing: 8) {
                BookCoverImage(bookId: book.id, coverPath: book.coverPath, blurHash: book.blurHash)
                    .frame(width: width, height: width)
                    .clipShape(RoundedRectangle(cornerRadius: 16))
                    .shadow(color: .black.opacity(0.12), radius: 6, x: 0, y: 3)

                if let author = book.author, !author.isEmpty {
                    Text(author)
                        .font(.subheadline.weight(.medium))
                        .foregroundStyle(.primary)
                        .lineLimit(1)
                } else {
                    Text(book.title)
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
        .accessibilityLabel(book.author.map { "\(book.title), \($0)" } ?? book.title)
    }
}
