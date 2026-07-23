import NukeUI
import SwiftUI
import Shared

/// Reusable book cover image component with authenticated loading and a gradient placeholder.
///
/// Display priority:
/// 1. Downloaded local cover file (`coverPath`), when present — the offline fast path.
/// 2. The authenticated server URL `{activeUrl}/api/v1/covers/{bookId}`, when a `bookId` is known.
/// 3. A gradient placeholder with a book icon, while loading or when no source resolves.
///
/// Loading, downsampling, and memory + disk caching are owned by Nuke (`LazyImage`). The
/// request is built off the main work by `CoverImageRequest`, which keys the cache on the
/// cover URL so covers survive token refresh.
///
/// Usage:
/// ```swift
/// BookCoverImage(book: book)
///     .frame(width: 100, height: 100)
///     .clipShape(RoundedRectangle(cornerRadius: 8))
/// ```
struct BookCoverImage: View {
    let bookId: String?
    let coverPath: String?
    /// Content hash of the current cover, folded into the image cache key so a new cover at the same
    /// stable path/URL busts the stale entry (mirrors Android's `"$bookId:$coverHash"` Coil key).
    /// Nil when unknown — the cache then keys on the path/URL alone, as before.
    let coverHash: String?
    /// VoiceOver label. When nil the cover is treated as decorative and hidden from VoiceOver
    /// (an unlabeled cover is noise — meaningful covers pass a label via `CoverAccessibility.label`).
    var accessibilityLabel: String?

    @Environment(\.displayScale) private var displayScale
    @State private var request: ImageRequest?
    @State private var targetMaxPixels: CGFloat = 0

    /// Convenience initializer from a BookListItem
    init(book: BookListItem) {
        self.bookId = book.idString
        self.coverPath = book.coverPath
        self.coverHash = book.coverHash
        self.accessibilityLabel = CoverAccessibility.label(title: book.title, author: book.authorNames)
    }

    /// Convenience initializer from a native `BookRow` (the Library grid's value type).
    init(book: BookRow) {
        self.bookId = book.id
        self.coverPath = book.coverPath
        self.coverHash = book.coverHash
        self.accessibilityLabel = CoverAccessibility.label(title: book.title, author: book.authorNames)
    }

    /// Convenience initializer from a BookDetail
    init(book: BookDetail) {
        self.bookId = book.idString
        self.coverPath = book.coverPath
        self.coverHash = book.coverHash
        self.accessibilityLabel = CoverAccessibility.label(title: book.title, author: book.authorNames)
    }

    /// Direct initializer with a book id, so the authenticated server URL can resolve.
    init(
        bookId: String?,
        coverPath: String?,
        coverHash: String? = nil,
        accessibilityLabel: String? = nil
    ) {
        self.bookId = bookId
        self.coverPath = coverPath
        self.coverHash = coverHash
        self.accessibilityLabel = accessibilityLabel
    }

    /// Local-only initializer (no `bookId`): renders the downloaded file or a placeholder.
    init(coverPath: String?, coverHash: String? = nil, accessibilityLabel: String? = nil) {
        self.bookId = nil
        self.coverPath = coverPath
        self.coverHash = coverHash
        self.accessibilityLabel = accessibilityLabel
    }

    var body: some View {
        LazyImage(request: request) { state in
            ZStack {
                // Layer 1: Placeholder (always behind)
                gradientPlaceholder

                // Layer 2: Loaded image (fades in on top)
                if let image = state.image {
                    image
                        .resizable()
                        .aspectRatio(contentMode: .fill)
                        .transition(.opacity)
                }
            }
            .animation(.easeIn(duration: 0.2), value: state.image != nil)
        }
        .onCompletion { result in
            switch result {
            case .success(let response):
                ImageTrace.log(
                    "coverlayer done id=\(ImageTrace.tail(bookId, 6)) " +
                        "ok cache=\(response.cacheType.map { String(describing: $0) } ?? "network")"
                )
            case .failure(let error):
                ImageTrace.log("coverlayer done id=\(ImageTrace.tail(bookId, 6)) FAIL \(error)")
            }
        }
        .onGeometryChange(for: CGSize.self) { proxy in proxy.size } action: { size in
            let px = (max(size.width, size.height) * displayScale).rounded()
            if px > 0, px != targetMaxPixels {
                targetMaxPixels = px
            }
        }
        .task(id: TaskKey(bookId: bookId, coverPath: coverPath, coverHash: coverHash, targetPixels: targetMaxPixels)) {
            guard targetMaxPixels > 0 else { return }
            request = await CoverImageRequest.book(
                bookId: bookId,
                coverPath: coverPath,
                coverHash: coverHash,
                targetPixels: targetMaxPixels
            )
        }
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(accessibilityLabel ?? "")
        .accessibilityHidden(accessibilityLabel == nil)
        .accessibilityAddTraits(.isImage)
    }

    /// Identity for the request-building task: any change re-resolves the cover source.
    private struct TaskKey: Equatable {
        let bookId: String?
        let coverPath: String?
        let coverHash: String?
        let targetPixels: CGFloat
    }

    private var gradientPlaceholder: some View {
        ZStack {
            LinearGradient(
                colors: [Color.gray.opacity(0.3), Color.gray.opacity(0.2)],
                startPoint: .topLeading,
                endPoint: .bottomTrailing
            )
            Image(systemName: "book.closed.fill")
                .font(.system(size: 24))
                .foregroundStyle(.secondary)
        }
    }
}

// MARK: - Preview

#Preview("Placeholder") {
    BookCoverImage(coverPath: nil)
        .frame(width: 100, height: 100)
        .clipShape(RoundedRectangle(cornerRadius: 8))
}
