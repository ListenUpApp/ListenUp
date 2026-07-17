import Testing
@testable import ListenUp

/// Pure-seam coverage for the Storage screen.
///
/// The shared `StorageUiState` → `StorageObserver` mapping isn't constructible from Swift, so the
/// `apply(_:)` field mapping lands at the green-build pass. The byte formatting is pure and pinned
/// here (the `.file` count style is locale-shaped, so we assert only the deterministic clamp/zero
/// behavior rather than an exact localized string).
@Suite("StorageFormat")
struct StorageObserverTests {
    @Test func negativeSizesClampToZero() {
        #expect(StorageFormat.byteSize(-1) == StorageFormat.byteSize(0))
    }

    @Test func zeroIsNonEmpty() {
        #expect(!StorageFormat.byteSize(0).isEmpty)
    }

    @Test func pendingDeletionIdentityDistinguishesCases() {
        let single = StoragePendingDeletion.single(title: "Mistborn", sizeBytes: 100)
        let all = StoragePendingDeletion.all(count: 3, totalBytes: 500)
        #expect(single.id != all.id)
    }
}
