import Nuke
import Foundation
import Testing
@testable import ListenUp

/// Pins the local-file cache-key policy that prevents the "cover never changes" bug (RC-1):
/// a `bookId`-scoped key is only content-safe when it folds in the content hash, because
/// during a book switch `bookId` advances while `coverPath` can still point at the previous
/// book's file. Without a hash the key must fall back to the file path, never `bookId` alone.
@Suite("CoverImageRequest cache keying")
struct CoverImageRequestTests {
    @Test func localKeyWithoutHashUsesPathNotBookId() {
        let key = CoverImageRequest.localFileCacheKey(
            bookId: "book-B", coverPath: "/covers/a.jpg", coverHash: nil
        )
        #expect(key == "/covers/a.jpg")
        #expect(key != "book-B:cover")
    }

    @Test func localKeyWithEmptyHashUsesPath() {
        let key = CoverImageRequest.localFileCacheKey(
            bookId: "book-B", coverPath: "/covers/a.jpg", coverHash: ""
        )
        #expect(key == "/covers/a.jpg")
    }

    @Test func localKeyWithHashFoldsBookIdAndHash() {
        let key = CoverImageRequest.localFileCacheKey(
            bookId: "book-B", coverPath: "/covers/a.jpg", coverHash: "abc123"
        )
        #expect(key == "book-B:abc123")
    }

    @Test func localKeyWithHashButNoBookIdFoldsPathAndHash() {
        let key = CoverImageRequest.localFileCacheKey(
            bookId: nil, coverPath: "/covers/a.jpg", coverHash: "abc123"
        )
        #expect(key == "/covers/a.jpg:abc123")
    }

    /// End-to-end through `CoverImageRequest.book`: the player call sites pass no `coverHash`,
    /// so the resulting request must be path-keyed — never stamped with the bookId, which is
    /// what poisoned the shared cache with the previous book's bytes.
    @Test @MainActor func bookRequestWithoutHashIsPathKeyed() async {
        let request = await CoverImageRequest.book(
            bookId: "book-B", coverPath: "/covers/a.jpg", coverHash: nil, targetPixels: 100
        )
        #expect(request?.userInfo[.imageIdKey] as? String == "/covers/a.jpg")
    }

    @Test @MainActor func bookRequestWithHashIsHashKeyed() async {
        let request = await CoverImageRequest.book(
            bookId: "book-B", coverPath: "/covers/a.jpg", coverHash: "abc123", targetPixels: 100
        )
        #expect(request?.userInfo[.imageIdKey] as? String == "book-B:abc123")
    }
}
