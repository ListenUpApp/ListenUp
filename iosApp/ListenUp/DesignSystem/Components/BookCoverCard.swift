import SwiftUI
@preconcurrency import Shared

/// Displays a book cover with title and author below.
///
/// Features:
/// - Authenticated cover loading via `BookCoverImage` (local file → signed server URL →
///   BlurHash placeholder), so covers appear on the library grid even before the book is
///   downloaded — the local file is not yet present there.
/// - Soft shadow on the cover image
/// - Title and author (single line, truncated)
/// - Optional progress bar at bottom of cover
struct BookCoverCard: View {
    let book: BookListItem
    let progress: Float?

    init(book: BookListItem, progress: Float? = nil) {
        self.book = book
        self.progress = progress
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            coverImage
            bookInfo
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(CoverAccessibility.label(title: book.title, author: book.authorNames) ?? book.title)
    }

    // MARK: - Cover Image

    private var coverImage: some View {
        ZStack(alignment: .bottom) {
            // Square cover — local file, signed server URL, or BlurHash/gradient placeholder,
            // all resolved by BookCoverImage.
            BookCoverImage(book: book)
                .aspectRatio(1, contentMode: .fit)
                .clipShape(RoundedRectangle(cornerRadius: 8))
                .shadow(color: .black.opacity(0.15), radius: 8, x: 0, y: 4)

            // Progress bar overlay
            if let progress, progress > 0 {
                progressOverlay(progress: progress)
            }
        }
    }

    private func progressOverlay(progress: Float) -> some View {
        GeometryReader { geo in
            VStack {
                Spacer()
                ZStack(alignment: .leading) {
                    // Background track
                    Rectangle()
                        .fill(Color.black.opacity(0.3))
                        .frame(height: 4)

                    // Progress fill
                    Rectangle()
                        .fill(Color.listenUpOrange)
                        .frame(width: geo.size.width * CGFloat(progress), height: 4)
                }
            }
        }
        .clipShape(RoundedRectangle(cornerRadius: 8))
    }

    // MARK: - Book Info

    private var bookInfo: some View {
        VStack(alignment: .leading, spacing: 2) {
            Text(book.title)
                .font(.subheadline.weight(.medium))
                .lineLimit(1)
                .truncationMode(.tail)
                .foregroundStyle(.primary)

            Text(book.authorNames)
                .font(.caption)
                .lineLimit(1)
                .truncationMode(.tail)
                .foregroundStyle(.secondary)
        }
    }
}

// MARK: - Preview

#Preview("With Progress") {
    ScrollView {
        LazyVGrid(columns: [GridItem(.adaptive(minimum: 150))], spacing: 16) {
            // Can't create real BookListItem in preview, use placeholders
            ForEach(0 ..< 6, id: \.self) { _ in
                BookCoverCardPreview()
            }
        }
        .padding()
    }
}

/// Preview helper since we can't easily create Kotlin BookListItem objects
private struct BookCoverCardPreview: View {
    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            ZStack(alignment: .bottom) {
                RoundedRectangle(cornerRadius: 8)
                    .fill(Color.gray.opacity(0.3))
                    .aspectRatio(2 / 3, contentMode: .fit)
                    .shadow(color: .black.opacity(0.15), radius: 8, x: 0, y: 4)

                // Sample progress
                GeometryReader { geo in
                    VStack {
                        Spacer()
                        Rectangle()
                            .fill(Color.listenUpOrange)
                            .frame(width: geo.size.width * 0.6, height: 4)
                            .frame(maxWidth: .infinity, alignment: .leading)
                    }
                }
                .clipShape(RoundedRectangle(cornerRadius: 8))
            }

            VStack(alignment: .leading, spacing: 2) {
                Text("The Name of the Wind")
                    .font(.subheadline.weight(.medium))
                    .lineLimit(1)

                Text("Patrick Rothfuss")
                    .font(.caption)
                    .lineLimit(1)
                    .foregroundStyle(.secondary)
            }
        }
    }
}
