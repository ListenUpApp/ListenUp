import SwiftUI

/// Fixed-width book card for a contributor's role carousel: cover (with an optional
/// progress overlay), title, author, and duration.
struct WrittenCard: View {
    let book: BookRow
    let progress: Float?

    private let width: CGFloat = 150

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            BookCoverImage(book: book)
                .frame(width: width, height: width)
                .clipShape(RoundedRectangle(cornerRadius: 14, style: .continuous))
                .coverHoverEffect(cornerRadius: 14)
                .accessibilityHidden(true)
                .overlay(alignment: .bottom) {
                    if let progress, progress > 0 {
                        ProgressBar(progress: progress, style: .overlay)
                            .frame(height: 4)
                            .clipShape(RoundedRectangle(cornerRadius: 14, style: .continuous))
                    }
                }
            Text(book.title)
                .font(.subheadline.weight(.semibold))
                .foregroundStyle(.primary)
                .lineLimit(1)
                .padding(.top, 9)
            Text(book.authorNames)
                .font(.footnote)
                .foregroundStyle(.secondary)
                .lineLimit(1)
            Text(DurationFormatting.hoursMinutes(ms: book.duration))
                .font(.caption)
                .foregroundStyle(.tertiary)
                .padding(.top, 1)
        }
        .frame(width: width)
    }
}
