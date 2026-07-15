import SwiftUI

/// Displays a book cover with title and author below.
///
/// Features:
/// - Authenticated cover loading via `BookCoverImage` (local file → signed server URL →
///   gradient placeholder), so covers appear on the library grid even before the book is
///   downloaded — the local file is not yet present there.
/// - Soft shadow on the cover image
/// - Title and author (single line, truncated)
/// - Optional progress bar at bottom of cover
struct BookCoverCard: View {
    let book: BookRow
    let progress: Float?
    /// Whether the grid is in multi-select mode (shows a selection circle on every cover).
    let isSelecting: Bool
    /// Whether this book is currently selected (filled vs. empty circle).
    let isSelected: Bool

    init(book: BookRow, progress: Float? = nil, isSelecting: Bool = false, isSelected: Bool = false) {
        self.book = book
        self.progress = progress
        self.isSelecting = isSelecting
        self.isSelected = isSelected
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
            // Square cover — local file, signed server URL, or gradient placeholder,
            // all resolved by BookCoverImage.
            BookCoverImage(book: book)
                .aspectRatio(1, contentMode: .fit)
                .clipShape(RoundedRectangle(cornerRadius: 8))
                .shadow(color: .black.opacity(0.15), radius: 8, x: 0, y: 4)
                .overlay(alignment: .topTrailing) {
                    if book.hasDocuments {
                        Image(systemName: "book.closed.fill")
                            .font(.system(size: 11, weight: .semibold))
                            .foregroundStyle(Color.listenUpOrange)
                            .padding(6)
                            .background(.ultraThinMaterial, in: RoundedRectangle(cornerRadius: 8, style: .continuous))
                            .padding(6)
                            .accessibilityLabel(String(localized: "library.has_documents_badge"))
                    }
                }
                // Selection circle — top-leading so it never clashes with the top-trailing
                // documents badge. Shown only while the grid is in multi-select mode.
                .overlay(alignment: .topLeading) {
                    if isSelecting {
                        Image(systemName: isSelected ? "checkmark.circle.fill" : "circle")
                            .font(.system(size: 22))
                            .symbolRenderingMode(.palette)
                            .foregroundStyle(.white, isSelected ? Color.listenUpOrange : Color.black.opacity(0.35))
                            .padding(6)
                            .accessibilityLabel(Text(isSelected
                                ? String(localized: "common.selected")
                                : String(localized: "common.not_selected")))
                    }
                }
                // Time remaining for in-progress books — a small capsule above the progress bar.
                .overlay(alignment: .bottomLeading) {
                    if let progress, progress > 0, progress < 1, book.duration > 0 {
                        Text(timeLeftLabel(progress: progress))
                            .font(.caption2.weight(.semibold))
                            .foregroundStyle(.white)
                            .padding(.horizontal, 6)
                            .padding(.vertical, 3)
                            .background(.black.opacity(0.55), in: Capsule())
                            .padding(.horizontal, 6)
                            .padding(.bottom, 8)
                    }
                }

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

    /// "{Xh Ym} left" — remaining time derived from the book's total duration and listen progress.
    private func timeLeftLabel(progress: Float) -> String {
        let remaining = Int64(Double(book.duration) * Double(1 - progress))
        return String(format: String(localized: "book.time_left"), DurationFormatting.hoursMinutes(ms: remaining))
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
