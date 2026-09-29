import Foundation
import Nuke
import Testing
import UIKit
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

    /// The **server-URL** branch keys on `contentHashKey`, which returns `nil` without a hash —
    /// so the request falls back to Nuke's URL-derived key (`/api/v1/covers/{bookId}`, already
    /// unique per book) and NEVER the poisoned `"bookId:cover"` key. This closes the residual
    /// where a switch (coverPath cleared, bookId = B) would flash book A's cached bytes on B.
    @Test func serverBranchWithoutHashIsNotBookIdKeyed() {
        #expect(CoverImageRequest.contentHashKey(identity: "book-B", coverHash: nil) == nil)
        #expect(CoverImageRequest.contentHashKey(identity: "book-B", coverHash: "") == nil)
    }

    @Test func serverBranchWithHashIsContentKeyed() {
        #expect(CoverImageRequest.contentHashKey(identity: "book-B", coverHash: "abc123") == "book-B:abc123")
    }

    /// End-to-end through `CoverImageRequest.book`: the player call sites pass no `coverHash`,
    /// so the resulting request must be path-keyed — never stamped with the bookId, which is
    /// what poisoned the shared cache with the previous book's bytes.
    @Test @MainActor func bookRequestWithoutHashIsPathKeyed() {
        let request = CoverImageRequest.book(
            bookId: "book-B", coverPath: "/covers/a.jpg", coverHash: nil, targetPixels: 100,
            serverBase: "https://x"
        )
        #expect(request?.userInfo[.imageIdKey] as? String == "/covers/a.jpg")
        #expect(request?.url?.isFileURL == true)
    }

    @Test @MainActor func bookRequestWithHashIsHashKeyed() {
        let request = CoverImageRequest.book(
            bookId: "book-B", coverPath: "/covers/a.jpg", coverHash: "abc123", targetPixels: 100,
            serverBase: nil
        )
        #expect(request?.userInfo[.imageIdKey] as? String == "book-B:abc123")
    }

    // MARK: - Synchronous server request (no placeholder flash)

    /// With the server URL mirrored, a versioned cover resolves to the content-addressed server URL
    /// in the same call — no await — keyed on the token-free `"<id>:<hash>"`.
    @Test @MainActor func hashedCoverWithServerBaseBuildsServerRequestSynchronously() {
        let request = CoverImageRequest.book(
            bookId: "book-B", coverPath: "/covers/a.jpg", coverHash: "abc123", targetPixels: 100,
            serverBase: "https://x"
        )
        #expect(request?.url?.absoluteString.hasPrefix("https://x/api/v1/covers/book-B?v=abc123") == true)
        #expect(request?.userInfo[.imageIdKey] as? String == "book-B:abc123")
    }

    /// The token is attached at load time by `AuthenticatingDataLoader`, never baked into the request.
    @Test @MainActor func serverRequestCarriesNoToken() {
        let request = CoverImageRequest.book(
            bookId: "book-B", coverPath: nil, coverHash: "abc123", targetPixels: 100, serverBase: "https://x"
        )
        #expect(request?.urlRequest?.value(forHTTPHeaderField: "Authorization") == nil)
    }

    @Test @MainActor func requestIsResizedForTheTargetPixels() {
        let request = CoverImageRequest.book(
            bookId: "book-B", coverPath: nil, coverHash: "abc123", targetPixels: 240, serverBase: "https://x"
        )
        #expect(request?.processors.count == 1)
    }

    /// After the server load fails, a downloaded cover falls back to its local file.
    @Test @MainActor func serverFailureFallsBackToLocalFile() {
        let request = CoverImageRequest.book(
            bookId: "book-B", coverPath: "/covers/a.jpg", coverHash: "abc123", targetPixels: 100,
            serverBase: "https://x", serverFailed: true
        )
        #expect(request?.url?.isFileURL == true)
        #expect(request?.userInfo[.imageIdKey] as? String == "book-B:abc123")
    }

    @Test @MainActor func unhashedCoverWithoutFileUsesBareServerURL() {
        let request = CoverImageRequest.book(
            bookId: "book-B", coverPath: nil, coverHash: nil, targetPixels: 100, serverBase: "https://x"
        )
        #expect(request?.url?.absoluteString == "https://x/api/v1/covers/book-B")
        #expect(request?.userInfo[.imageIdKey] == nil)
    }

    @Test @MainActor func noSourceYieldsNoRequest() {
        #expect(CoverImageRequest.book(
            bookId: "book-B", coverPath: nil, coverHash: "abc123", targetPixels: 100, serverBase: nil
        ) == nil)
        #expect(CoverImageRequest.book(
            bookId: nil, coverPath: nil, coverHash: nil, targetPixels: 100, serverBase: "https://x"
        ) == nil)
    }

    // MARK: - Fallback and fade decisions

    @Test func requestMissingIsNotALoadFailure() {
        let result: Result<ImageResponse, Error> = .failure(ImagePipeline.Error.imageRequestMissing)
        #expect(!CoverImageRequest.shouldFallBackToLocalFile(after: result, coverPath: "/covers/a.jpg"))
    }

    @Test func realFailureFallsBackOnlyWithAFile() {
        let result: Result<ImageResponse, Error> = .failure(URLError(.notConnectedToInternet))
        #expect(CoverImageRequest.shouldFallBackToLocalFile(after: result, coverPath: "/covers/a.jpg"))
        #expect(!CoverImageRequest.shouldFallBackToLocalFile(after: result, coverPath: nil))
        #expect(!CoverImageRequest.shouldFallBackToLocalFile(after: result, coverPath: ""))
    }

    @Test func onlyAMemoryHitSkipsTheFade() {
        let request = ImageRequest(url: URL(string: "https://x/a.jpg"))
        let container = ImageContainer(image: UIImage())
        let memory = ImageResponse(container: container, request: request, cacheType: .memory)
        let disk = ImageResponse(container: container, request: request, cacheType: .disk)
        let network = ImageResponse(container: container, request: request)
        #expect(CoverImageRequest.isMemoryCacheHit(.success(memory)))
        #expect(!CoverImageRequest.isMemoryCacheHit(.success(disk)))
        #expect(!CoverImageRequest.isMemoryCacheHit(.success(network)))
        #expect(!CoverImageRequest.isMemoryCacheHit(nil))
    }

    // MARK: - Offline persistence

    @Test func persistsWhenVersionedOrWithoutAFile() {
        #expect(CoverImageRequest.shouldPersist(bookId: "b", coverPath: "/c.jpg", coverHash: "h"))
        #expect(CoverImageRequest.shouldPersist(bookId: "b", coverPath: nil, coverHash: nil))
        #expect(!CoverImageRequest.shouldPersist(bookId: "b", coverPath: "/c.jpg", coverHash: nil))
        #expect(!CoverImageRequest.shouldPersist(bookId: nil, coverPath: nil, coverHash: "h"))
    }

    // Content-addressed server URL: the coverHash rides as `?v=` so the URL changes when the cover
    // does, busting URLSession's URL-keyed cache (not just Nuke's key).
    @Test func coverURLWithHashIsVersioned() {
        let url = CoverImageRequest.coverURL(base: "https://x", bookId: "book-B", coverHash: "abc123")
        #expect(url?.absoluteString.hasPrefix("https://x/api/v1/covers/book-B?v=") == true)
    }

    @Test func coverURLWithoutHashIsBare() {
        let url = CoverImageRequest.coverURL(base: "https://x", bookId: "book-B", coverHash: nil)
        #expect(url?.absoluteString == "https://x/api/v1/covers/book-B")
    }

    @Test func coverURLChangesWhenHashChanges() {
        let before = CoverImageRequest.coverURL(base: "https://x", bookId: "book-B", coverHash: "aaa")
        let after = CoverImageRequest.coverURL(base: "https://x", bookId: "book-B", coverHash: "bbb")
        #expect(before != after)
    }

    @Test func coverURLBlankBaseIsNil() {
        #expect(CoverImageRequest.coverURL(base: "", bookId: "book-B", coverHash: "aaa") == nil)
    }
}
