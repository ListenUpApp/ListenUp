import SwiftUI
@preconcurrency import Shared
import UIKit

/// In-memory image cache shared across all BookCoverImage instances.
/// Uses NSCache for automatic memory pressure eviction.
private final class CoverImageCache {
    nonisolated(unsafe) static let shared = CoverImageCache()
    private let cache = NSCache<NSString, UIImage>()

    init() {
        cache.countLimit = 200
    }

    func image(forKey key: String) -> UIImage? {
        cache.object(forKey: key as NSString)
    }

    func setImage(_ image: UIImage, forKey key: String) {
        cache.setObject(image, forKey: key as NSString)
    }
}

/// Reusable book cover image component with async loading and BlurHash placeholders.
///
/// Display priority:
/// 1. Cached in-memory image (instant)
/// 2. BlurHash placeholder while loading from disk
/// 3. Gradient placeholder with book icon (no blurHash available)
///
/// Images are loaded off the main thread and cached in memory via NSCache
/// (auto-evicts under memory pressure).
///
/// Usage:
/// ```swift
/// BookCoverImage(coverPath: book.coverPath, blurHash: book.coverBlurHash)
///     .frame(width: 100, height: 100)
///     .clipShape(RoundedRectangle(cornerRadius: 8))
/// ```
struct BookCoverImage: View {
    let coverPath: String?
    let blurHash: String?

    @Environment(\.displayScale) private var displayScale
    @State private var loadedImage: UIImage?
    @State private var loadTask: Task<Void, Never>?
    @State private var targetMaxPixels: Int = 0

    /// Convenience initializer from a BookListItem
    init(book: BookListItem) {
        self.coverPath = book.coverPath
        self.blurHash = book.coverBlurHash
    }

    /// Convenience initializer from a BookDetail
    init(book: BookDetail) {
        self.coverPath = book.coverPath
        self.blurHash = book.coverBlurHash
    }

    /// Direct initializer for custom sources
    init(coverPath: String?, blurHash: String? = nil) {
        self.coverPath = coverPath
        self.blurHash = blurHash
    }

    var body: some View {
        ZStack {
            // Layer 1: Placeholder (always behind)
            if blurHash != nil {
                BlurHashView(blurHash: blurHash)
            } else {
                gradientPlaceholder
            }

            // Layer 2: Loaded image (fades in on top)
            if let loadedImage {
                Image(uiImage: loadedImage)
                    .resizable()
                    .aspectRatio(contentMode: .fill)
                    .transition(.opacity)
            }
        }
        .animation(.easeIn(duration: 0.2), value: loadedImage != nil)
        .onGeometryChange(for: CGSize.self) { proxy in proxy.size } action: { size in
            let px = Int((max(size.width, size.height) * displayScale).rounded())
            if px > 0, px != targetMaxPixels {
                targetMaxPixels = px
                loadImage()
            }
        }
        .onDisappear { loadTask?.cancel() }
        .onChange(of: coverPath) {
            loadedImage = nil
            loadImage()
        }
    }

    private func loadImage() {
        guard let path = coverPath, targetMaxPixels > 0 else { return }

        let cacheKey = "\(path)@\(targetMaxPixels)"
        // Check cache first (instant, no async needed)
        if let cached = CoverImageCache.shared.image(forKey: cacheKey) {
            loadedImage = cached
            return
        }

        // Decode downsampled (to the displayed pixel size) on a background thread.
        loadTask?.cancel()
        let maxPixels = targetMaxPixels
        loadTask = Task.detached(priority: .utility) {
            guard let image = ImageDownsampler.downsampledImage(atPath: path, maxPixelSize: maxPixels) else { return }
            CoverImageCache.shared.setImage(image, forKey: cacheKey)
            await MainActor.run {
                guard !Task.isCancelled else { return }
                loadedImage = image
            }
        }
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

#Preview("With Cover") {
    BookCoverImage(coverPath: nil, blurHash: "LEHV6nWB2yk8pyo0adR*.7kCMdnj")
        .frame(width: 100, height: 100)
        .clipShape(RoundedRectangle(cornerRadius: 8))
}

#Preview("Placeholder") {
    BookCoverImage(coverPath: nil, blurHash: nil)
        .frame(width: 100, height: 100)
        .clipShape(RoundedRectangle(cornerRadius: 8))
}
