import Foundation
import ImageIO
import UIKit

/// Writes a downsized book-cover thumbnail into a shared container so the widget
/// extension — a separate process — can display it. The fallback order is real
/// cover → BlurHash-rendered image → nothing.
struct CoverThumbnailWriter {

    /// The shared App Group identifier.
    static let appGroupID = "group.com.calypsan.listenup.client"

    /// Sub-directory inside the container holding Live Activity thumbnails.
    private static let directoryName = "LiveActivityCovers"

    /// Maximum pixel dimension of the written thumbnail.
    /// The thumbnail is a fixed-size cache artifact; pixel size is what matters,
    /// not points. UIScreen.main is also unavailable in non-@MainActor context
    /// under Swift 6, so we use a concrete pixel value directly (plan-sanctioned).
    private static let maxPixelSize: CGFloat = 240

    private let containerURL: URL

    /// Production initializer — resolves the App Group container.
    /// Returns `nil` if the App Group is not available (entitlement missing).
    init?() {
        guard let container = FileManager.default
            .containerURL(forSecurityApplicationGroupIdentifier: Self.appGroupID) else {
            return nil
        }
        self.init(containerURL: container)
    }

    /// Testable initializer — `containerURL` stands in for the App Group container.
    init(containerURL: URL) {
        self.containerURL = containerURL
    }

    /// The on-disk location of a book's thumbnail (whether or not it exists).
    func thumbnailURL(for bookId: String) -> URL {
        coversDirectory().appendingPathComponent("\(bookId).jpg")
    }

    /// Produces and writes the best available thumbnail. Returns the written URL,
    /// or `nil` when neither a cover nor a BlurHash yields an image (any stale
    /// thumbnail for `bookId` is removed in that case).
    @discardableResult
    func write(bookId: String, coverPath: String?, blurHash: String?) -> URL? {
        guard let image = thumbnail(coverPath: coverPath) ?? blurHashImage(blurHash) else {
            remove(bookId: bookId)
            return nil
        }
        let destination = thumbnailURL(for: bookId)
        guard let data = image.jpegData(compressionQuality: 0.8) else {
            remove(bookId: bookId)
            return nil
        }
        try? FileManager.default.createDirectory(
            at: coversDirectory(), withIntermediateDirectories: true
        )
        guard (try? data.write(to: destination, options: .atomic)) != nil else { return nil }
        return destination
    }

    /// Removes a book's thumbnail. Call when the Live Activity ends.
    func remove(bookId: String) {
        try? FileManager.default.removeItem(at: thumbnailURL(for: bookId))
    }

    // MARK: - Private

    private func coversDirectory() -> URL {
        containerURL.appendingPathComponent(Self.directoryName, isDirectory: true)
    }

    /// Downsizes the cover at `coverPath` using ImageIO — never loads the full image.
    private func thumbnail(coverPath: String?) -> UIImage? {
        guard let coverPath, FileManager.default.fileExists(atPath: coverPath) else { return nil }
        let url = URL(fileURLWithPath: coverPath) as CFURL
        guard let source = CGImageSourceCreateWithURL(url, nil) else { return nil }
        let options: [CFString: Any] = [
            kCGImageSourceCreateThumbnailFromImageAlways: true,
            kCGImageSourceCreateThumbnailWithTransform: true,
            kCGImageSourceThumbnailMaxPixelSize: Self.maxPixelSize,
        ]
        guard let cgImage = CGImageSourceCreateThumbnailAtIndex(source, 0, options as CFDictionary) else {
            return nil
        }
        return UIImage(cgImage: cgImage)
    }

    /// Renders the BlurHash into a small image using the app's existing decoder.
    private func blurHashImage(_ blurHash: String?) -> UIImage? {
        guard let blurHash else { return nil }
        return UIImage(blurHash: blurHash, size: CGSize(width: 32, height: 32))
    }
}
