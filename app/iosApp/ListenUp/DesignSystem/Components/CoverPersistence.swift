import Foundation
@preconcurrency import Shared

/// Asks the shared image repository to persist a cover for offline use — once per cover version per
/// launch.
///
/// Every cover cell used to call `ensureBookCoverCached` each time it appeared, and each call launched
/// a coroutine, checked the disk, wrote a database row and logged a line, all for a cover that was
/// almost always already on disk (2026-09-29 iOS audit, performance). The first request for a
/// `bookId` and `coverHash` pair goes through; repeats are dropped here. A new hash (a re-scraped
/// cover) is a new pair, so it's persisted again. A request that fails is retried on the next launch,
/// and the sync engine persists covers on its own too.
@MainActor
final class CoverPersistence {
    static let shared = CoverPersistence { bookId in
        KoinHelper.shared.ensureBookCoverCached(bookId: bookId)
    }

    private var requested: Set<String> = []
    private let persist: (String) -> Void

    init(persist: @escaping (String) -> Void) {
        self.persist = persist
    }

    /// Persists the cover if `CoverImageRequest.shouldPersist` says it needs it and this version
    /// hasn't been asked for already.
    func ensureCached(bookId: String?, coverPath: String?, coverHash: String?) {
        guard CoverImageRequest.shouldPersist(bookId: bookId, coverPath: coverPath, coverHash: coverHash),
              let bookId
        else { return }
        guard requested.insert("\(bookId):\(coverHash ?? "")").inserted else { return }
        persist(bookId)
    }
}
