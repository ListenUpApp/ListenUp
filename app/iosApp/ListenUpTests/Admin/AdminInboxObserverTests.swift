import Testing
import Foundation
import Shared
@testable import ListenUp

/// The value-type mapping `AdminInboxObserver` performs at its boundary — the sealed
/// `AdminInboxUiState` → `AdminInboxPhase` projection and the native `InboxBookRowModel`.
/// Mirrors `AdminBackupObserversTests`; the shared `AdminInboxViewModel` logic is covered by
/// `AdminInboxViewModelTest` in sharedLogic. Guards against a UiState case routed to the wrong
/// phase and against a bridged Kotlin `InboxBookItem` leaking past the observer boundary
/// (iosApp rule 8 — map to a native value type before the view).

// MARK: - Phase mapping

@Suite("Admin inbox phase mapping")
struct AdminInboxPhaseTests {
    @Test func loadingMapsToLoading() {
        #expect(AdminInboxObserver.phase(from: AdminInboxUiStateLoading.shared) == .loading)
    }

    @Test func errorCarriesMessage() {
        #expect(AdminInboxObserver.phase(from: AdminInboxUiStateError(message: "inbox boom")) == .error("inbox boom"))
    }

    @Test func readyCarriesBooksSelectionAndFlags() {
        let state = AdminInboxUiStateReady(
            bookIds: ["b1", "b2"],
            books: [inboxItem(id: "b1"), inboxItem(id: "b2")],
            selectedBookIds: ["b1"],
            isReleasing: true,
            lastReleasedCount: 3,
            error: "partial"
        )
        guard case .ready(let model) = AdminInboxObserver.phase(from: state) else {
            Issue.record("expected .ready")
            return
        }
        #expect(model.books.count == 2)
        #expect(model.bookCount == 2)
        #expect(model.selectedBookIds == ["b1"])
        #expect(model.isReleasing == true)
        #expect(model.lastReleasedCount == 3)
        #expect(model.error == "partial")
        #expect(model.hasBooks == true)
        #expect(model.hasSelection == true)
        #expect(model.selectedCount == 1)
        #expect(model.allSelected == false)
    }

    @Test func emptyReadyHasNoBooksOrSelection() {
        let state = AdminInboxUiStateReady(
            bookIds: [],
            books: [],
            selectedBookIds: [],
            isReleasing: false,
            lastReleasedCount: nil,
            error: nil
        )
        guard case .ready(let model) = AdminInboxObserver.phase(from: state) else {
            Issue.record("expected .ready")
            return
        }
        #expect(model.books.isEmpty)
        #expect(model.bookCount == 0)
        #expect(model.hasBooks == false)
        #expect(model.hasSelection == false)
        #expect(model.selectedCount == 0)
        #expect(model.allSelected == false)
        #expect(model.lastReleasedCount == nil)
        #expect(model.error == nil)
    }

    @Test func allSelectedWhenSelectionCoversEveryBook() {
        let state = AdminInboxUiStateReady(
            bookIds: ["b1", "b2"],
            books: [inboxItem(id: "b1"), inboxItem(id: "b2")],
            selectedBookIds: ["b1", "b2"],
            isReleasing: false,
            lastReleasedCount: nil,
            error: nil
        )
        guard case .ready(let model) = AdminInboxObserver.phase(from: state) else {
            Issue.record("expected .ready")
            return
        }
        #expect(model.allSelected == true)
    }
}

// MARK: - Row mapping

@Suite("Admin inbox row mapping")
struct AdminInboxRowModelTests {
    @Test func mapsEveryField() {
        let item = InboxBookItem(
            id: "b7",
            title: "The Way of Kings",
            author: "Brandon Sanderson",
            coverPath: "/covers/b7.jpg",
            durationMs: 3_600_000,
            coverHash: "abc123"
        )
        let model = InboxBookRowModel(from: item)

        #expect(model.id == "b7")
        #expect(model.title == "The Way of Kings")
        #expect(model.author == "Brandon Sanderson")
        #expect(model.coverPath == "/covers/b7.jpg")
        #expect(model.coverHash == "abc123")
        #expect(model.durationMs == 3_600_000)
    }

    @Test func nilOptionalsSurvive() {
        let model = InboxBookRowModel(from: inboxItem(id: "b1", author: nil, coverPath: nil, coverHash: nil))

        #expect(model.author == nil)
        #expect(model.coverPath == nil)
        #expect(model.coverHash == nil)
    }

    @Test func formattedDurationPassesThroughForZeroAndOverAnHour() {
        // Passthrough assertion (not a hard-coded string) so the test pins the wiring, not the
        // formatter's locale-sensitive output — mirrors the backup suite's `sizeFormatted` check.
        let zero = InboxBookRowModel(from: inboxItem(id: "z", durationMs: 0))
        #expect(zero.formattedDuration == DurationFormatting.hoursMinutes(ms: 0))

        let overAnHour = InboxBookRowModel(from: inboxItem(id: "h", durationMs: 3_660_000)) // 1h 01m
        #expect(overAnHour.formattedDuration == DurationFormatting.hoursMinutes(ms: 3_660_000))
    }
}

// MARK: - Fixtures

private func inboxItem(
    id: String,
    author: String? = "Author",
    coverPath: String? = nil,
    coverHash: String? = nil,
    durationMs: Int64 = 60_000
) -> InboxBookItem {
    InboxBookItem(
        id: id,
        title: "Title \(id)",
        author: author,
        coverPath: coverPath,
        durationMs: durationMs,
        coverHash: coverHash
    )
}
