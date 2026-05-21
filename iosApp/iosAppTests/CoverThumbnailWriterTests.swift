import Testing
import UIKit
@testable import listenup

struct CoverThumbnailWriterTests {

    /// A temp directory standing in for the App Group container.
    private func makeTempDir() -> URL {
        let dir = FileManager.default.temporaryDirectory
            .appendingPathComponent(UUID().uuidString, isDirectory: true)
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        return dir
    }

    /// Writes a solid-colour JPEG to disk and returns its path.
    private func writeSourceImage(in dir: URL) -> String {
        let image = UIGraphicsImageRenderer(size: CGSize(width: 600, height: 600)).image { ctx in
            UIColor.orange.setFill()
            ctx.fill(CGRect(x: 0, y: 0, width: 600, height: 600))
        }
        let url = dir.appendingPathComponent("source.jpg")
        try? image.jpegData(compressionQuality: 0.9)?.write(to: url)
        return url.path
    }

    @Test func writesADownsizedThumbnailFromTheRealCover() throws {
        let container = makeTempDir()
        let sourcePath = writeSourceImage(in: container)
        let writer = CoverThumbnailWriter(containerURL: container)

        let url = writer.write(bookId: "book-1", coverPath: sourcePath, blurHash: nil)

        let written = try #require(url)
        let image = try #require(UIImage(contentsOfFile: written.path))
        #expect(image.size.width <= 240)
        #expect(image.size.height <= 240)
    }

    @Test func fallsBackToBlurHashWhenNoCoverPath() throws {
        let container = makeTempDir()
        let writer = CoverThumbnailWriter(containerURL: container)

        let url = writer.write(bookId: "book-2", coverPath: nil, blurHash: "LEHV6nWB2yk8pyo0adR*.7kCMdnj")

        let written = try #require(url)
        #expect(FileManager.default.fileExists(atPath: written.path))
    }

    @Test func returnsNilAndRemovesStaleFileWhenNothingIsAvailable() throws {
        let container = makeTempDir()
        let writer = CoverThumbnailWriter(containerURL: container)
        // Seed a stale thumbnail.
        _ = writer.write(bookId: "book-3", coverPath: nil, blurHash: "LEHV6nWB2yk8pyo0adR*.7kCMdnj")

        let url = writer.write(bookId: "book-3", coverPath: nil, blurHash: nil)

        #expect(url == nil)
        #expect(!FileManager.default.fileExists(atPath: writer.thumbnailURL(for: "book-3").path))
    }

    @Test func removeDeletesTheThumbnail() throws {
        let container = makeTempDir()
        let writer = CoverThumbnailWriter(containerURL: container)
        _ = writer.write(bookId: "book-4", coverPath: nil, blurHash: "LEHV6nWB2yk8pyo0adR*.7kCMdnj")

        writer.remove(bookId: "book-4")

        #expect(!FileManager.default.fileExists(atPath: writer.thumbnailURL(for: "book-4").path))
    }
}
