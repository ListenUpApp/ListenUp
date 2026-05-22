import SwiftUI
@preconcurrency import Shared
import UIKit

/// Displays a book cover with title and author below.
///
/// Features:
/// - Local image loading from cached cover path
/// - BlurHash placeholder while loading or on failure
/// - Soft shadow on the cover image
/// - Title and author (single line, truncated)
/// - Optional progress bar at bottom of cover
struct BookCoverCard: View {
    let book: BookListItem
    let progress: Float?

    /// Cached UIImage loaded from local file path.
    private let cachedImage: UIImage?

    init(book: BookListItem, progress: Float? = nil) {
        self.book = book
        self.progress = progress

        // Load image from local cache path
        if let coverPath = book.coverPath {
            self.cachedImage = UIImage(contentsOfFile: coverPath)
        } else {
            self.cachedImage = nil
        }
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            coverImage
            bookInfo
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }

    // MARK: - Cover Image

    private var coverImage: some View {
        ZStack(alignment: .bottom) {
            // Cover with BlurHash placeholder (square covers)
            coverContent
                .aspectRatio(1, contentMode: .fit)
                .clipShape(RoundedRectangle(cornerRadius: 8))
                .shadow(color: .black.opacity(0.15), radius: 8, x: 0, y: 4)

            // Progress bar overlay
            if let progress, progress > 0 {
                progressOverlay(progress: progress)
            }
        }
    }

    @ViewBuilder
    private var coverContent: some View {
        if let uiImage = cachedImage {
            // Display locally cached cover image
            Image(uiImage: uiImage)
                .resizable()
                .aspectRatio(contentMode: .fill)
        } else {
            // Show BlurHash placeholder if no cached image
            blurHashPlaceholder
        }
    }

    @ViewBuilder
    private var blurHashPlaceholder: some View {
        if book.coverBlurHash != nil {
            BlurHashView(blurHash: book.coverBlurHash)
        } else {
            // Fallback: gradient with book icon
            ZStack {
                LinearGradient(
                    colors: [Color.gray.opacity(0.3), Color.gray.opacity(0.2)],
                    startPoint: .topLeading,
                    endPoint: .bottomTrailing
                )
                Image(systemName: "book.closed.fill")
                    .font(.system(size: 32))
                    .foregroundStyle(.secondary)
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
