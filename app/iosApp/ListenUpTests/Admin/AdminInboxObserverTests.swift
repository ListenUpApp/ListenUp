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
            lastUnreleasedCount: 0,
            error: "partial",
            scanIssues: []
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
            lastUnreleasedCount: 0,
            error: nil,
            scanIssues: []
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
            lastUnreleasedCount: 0,
            error: nil,
            scanIssues: []
        )
        guard case .ready(let model) = AdminInboxObserver.phase(from: state) else {
            Issue.record("expected .ready")
            return
        }
        #expect(model.allSelected == true)
    }
}

// MARK: - Release confirmation

@Suite("Admin inbox release confirmation")
struct AdminInboxReleaseConfirmationTests {
    private func model(released: Int32?, unreleased: Int32) -> AdminInboxReadyModel {
        AdminInboxReadyModel(from: AdminInboxUiStateReady(
            bookIds: [],
            books: [],
            selectedBookIds: [],
            isReleasing: false,
            lastReleasedCount: released,
            lastUnreleasedCount: unreleased,
            error: nil,
            scanIssues: []
        ))
    }

    @Test func nothingToConfirmBeforeARelease() {
        #expect(model(released: nil, unreleased: 0).releaseConfirmation == nil)
    }

    @Test func aFullReleaseCountsInTheRightNumber() {
        #expect(model(released: 1, unreleased: 0).releaseConfirmation == "Released 1 book")
        #expect(model(released: 4, unreleased: 0).releaseConfirmation == "Released 4 books")
    }

    @Test func aPartialReleaseSaysHowManyCouldNotBeReleased() {
        let confirmation = model(released: 2, unreleased: 1).releaseConfirmation
        #expect(confirmation == "Released 2 of 3 books. 1 couldn't be released.")
    }

    // The error bus stays quiet on a partial release, so the screen itself must show it to sighted
    // users — the books that stayed held are otherwise indistinguishable from untouched ones.
    @Test func aPartialReleaseIsShownOnScreenWithAWarning() {
        let partial = model(released: 2, unreleased: 1)
        #expect(partial.partialReleaseNotice == "Released 2 of 3 books. 1 couldn't be released.")
        #expect(partial.releaseHaptic == .warning)
    }

    @Test func aFullReleaseNeedsNoNoticeAndFeelsLikeSuccess() {
        let full = model(released: 3, unreleased: 0)
        #expect(full.partialReleaseNotice == nil)
        #expect(full.releaseHaptic == .commit)
        #expect(model(released: nil, unreleased: 0).partialReleaseNotice == nil)
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

// MARK: - Scan issues

/// The gap these cover: the observer dropped `scanIssues` entirely, and the view asked `hasBooks`
/// where the shared ViewModel means `isEmpty` — so an inbox holding nothing but failed imports told
/// the admin it was empty. A scan issue is the only place a failed import is visible at all.
@Suite("Admin inbox scan issues")
struct AdminInboxScanIssueTests {
    @Test func issuesWithNoBooksIsNotAnEmptyInbox() {
        // The bug, exactly: zero held books plus issues must NOT read as empty.
        let state = AdminInboxUiStateReady(
            bookIds: [],
            books: [],
            selectedBookIds: [],
            isReleasing: false,
            lastReleasedCount: nil,
            lastUnreleasedCount: 0,
            error: nil,
            scanIssues: [scanIssue(id: "i1"), scanIssue(id: "i2")]
        )
        guard case .ready(let model) = AdminInboxObserver.phase(from: state) else {
            Issue.record("expected .ready")
            return
        }
        #expect(model.hasBooks == false)
        #expect(model.hasIssues == true)
        #expect(model.isEmpty == false, "issues with no books is a populated inbox")
        #expect(model.scanIssues.map(\.id) == ["i1", "i2"])
    }

    @Test func emptyOnlyWhenBothHalvesAreEmpty() {
        let state = AdminInboxUiStateReady(
            bookIds: [],
            books: [],
            selectedBookIds: [],
            isReleasing: false,
            lastReleasedCount: nil,
            lastUnreleasedCount: 0,
            error: nil,
            scanIssues: []
        )
        guard case .ready(let model) = AdminInboxObserver.phase(from: state) else {
            Issue.record("expected .ready")
            return
        }
        #expect(model.isEmpty == true)
        #expect(model.hasIssues == false)
    }

    @Test func everyReasonGetsItsOwnHeadlineAndFix() {
        // A switch collapsing to one shared string would still render five rows, so counting rows
        // proves nothing — distinctness is the property that matters.
        let reasons: [ScanIssueReason] = [
            .noRecognizedAudio, .fileUnreadable, .metadataParseFailed, .titleInferenceFailed, .unknown
        ]
        let models = reasons.map { ScanIssueRowModel(from: scanIssue(id: "i", reason: $0)) }

        #expect(Set(models.map(\.headline)).count == reasons.count, "each reason needs its own headline")
        #expect(Set(models.map(\.fix)).count == reasons.count, "a notice the user cannot act on is just an apology")
        #expect(models.allSatisfy { !$0.headline.isEmpty && !$0.fix.isEmpty })
        // A missing catalog key resolves to the key itself — that would pass the distinctness check
        // above while shipping "admin.inbox_issue_no_audio" to the user.
        #expect(models.allSatisfy { !$0.headline.hasPrefix("admin.inbox_") && !$0.fix.hasPrefix("admin.inbox_") })
    }

    @Test func rowModelCarriesFolderAndDetail() {
        let issue = scanIssue(id: "i9", rootRelPath: "Sanderson/Mistborn", detail: "no readable tracks")
        let model = ScanIssueRowModel(from: issue)

        #expect(model.id == "i9")
        #expect(model.rootRelPath == "Sanderson/Mistborn")
        #expect(model.detail == "no readable tracks")
    }

    @Test func nilDetailSurvives() {
        #expect(ScanIssueRowModel(from: scanIssue(id: "i1", detail: nil)).detail == nil)
    }
}

private func scanIssue(
    id: String,
    rootRelPath: String = "Some/Folder",
    reason: ScanIssueReason = .noRecognizedAudio,
    detail: String? = nil
) -> ScanIssue {
    ScanIssue(
        id: id,
        rootRelPath: rootRelPath,
        reason: reason,
        detail: detail,
        firstSeenAt: 0,
        lastSeenAt: 0
    )
}
