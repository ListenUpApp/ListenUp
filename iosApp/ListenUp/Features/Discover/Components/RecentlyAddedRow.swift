import SwiftUI

/// A row in the "Recently Added" list: a small cover, title + author, and a trailing
/// relative "added" time ("2d ago"). Tapping navigates to the book's detail.
struct RecentlyAddedRow: View {
    let book: RecentlyAddedBook

    private static let relativeFormatter: RelativeDateTimeFormatter = {
        let formatter = RelativeDateTimeFormatter()
        formatter.unitsStyle = .abbreviated
        return formatter
    }()

    private var addedLabel: String {
        Self.relativeFormatter.localizedString(for: book.addedAt, relativeTo: Date())
    }

    var body: some View {
        NavigationLink(value: BookDestination(id: book.id)) {
            HStack(spacing: 13) {
                BookCoverImage(bookId: book.id, coverPath: book.coverPath, blurHash: book.blurHash)
                    .frame(width: 52, height: 52)
                    .clipShape(RoundedRectangle(cornerRadius: 11))
                    .shadow(color: .black.opacity(0.1), radius: 3, x: 0, y: 1)

                VStack(alignment: .leading, spacing: 2) {
                    Text(book.title)
                        .font(.body.weight(.medium))
                        .foregroundStyle(.primary)
                        .lineLimit(1)

                    if let author = book.author, !author.isEmpty {
                        Text(author)
                            .font(.footnote)
                            .foregroundStyle(.secondary)
                            .lineLimit(1)
                    }
                }

                Spacer(minLength: 8)

                Text(addedLabel)
                    .font(.caption)
                    .foregroundStyle(Color.luLabel3)
            }
            .padding(.vertical, 8)
            .contentShape(Rectangle())
        }
        .buttonStyle(.pressScaleRow)
        .accessibilityElement(children: .combine)
        .accessibilityLabel(book.author.map { "\(book.title), \($0)" } ?? book.title)
    }
}
