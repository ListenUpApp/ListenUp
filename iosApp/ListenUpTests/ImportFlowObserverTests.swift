import Testing
import Shared
@testable import ListenUp

/// Pure-mapping coverage for the ABS-import observers' seams: the progress arithmetic the rings
/// render, the user-review resolution classification, the hub stage classification, and the
/// summary-row tally mapping. No SKIE objects — these exercise the flattened Swift models.
struct ImportFlowObserverTests {
    // MARK: - Progress maths

    @Test func fractionIsNilBeforeTotalsArrive() {
        #expect(ImportProgressMath.fraction(done: 0, total: 0) == nil)
        #expect(ImportProgressMath.fraction(done: 5, total: 0) == nil)
    }

    @Test func fractionDividesDoneByTotal() {
        #expect(ImportProgressMath.fraction(done: 343, total: 2751) == 343.0 / 2751.0)
        #expect(ImportProgressMath.fraction(done: 1, total: 2) == 0.5)
    }

    @Test func fractionClampsServerOvercountToOne() {
        #expect(ImportProgressMath.fraction(done: 12, total: 10) == 1.0)
    }

    @Test func counterLabelGroupsThousands() {
        #expect(ImportProgressMath.counterLabel(done: 343, total: 2751) == "343 / 2,751")
    }

    @Test func counterLabelIsNilWithoutTotals() {
        #expect(ImportProgressMath.counterLabel(done: 5, total: 0) == nil)
    }

    @Test func progressModelDerivesPercent() {
        let model = ImportProgressModel(done: 46, total: 100, currentItem: nil, usersMatched: 0, booksMatched: 0)
        #expect(model.percent == 46)
        #expect(model.fraction == 0.46)
    }

    @Test func indeterminateProgressHasNoPercent() {
        let model = ImportProgressModel(done: 0, total: 0, currentItem: nil, sessionsWritten: 0)
        #expect(model.percent == nil)
        #expect(model.fraction == nil)
        #expect(model.counterLabel == nil)
    }

    // MARK: - User resolution classification

    @Test func assignedSnapshotResolvesToTargetDisplayName() {
        let snapshot = ImportReviewUserSnapshot(
            absUserId: "abs1", username: "simon", email: "s@x.com",
            suggestedUserId: nil, resolution: "assigned", assignedUserId: "u1"
        )
        let pickers = [ImportPickerUser(id: "u1", name: "Simon Hull", email: "s@x.com")]
        #expect(ImportUserRowModel.resolve(snapshot: snapshot, pickerUsers: pickers) == .assigned(toName: "Simon Hull"))
    }

    @Test func assignedSnapshotFallsBackToIdWhenPickerMissing() {
        let snapshot = ImportReviewUserSnapshot(
            absUserId: "abs1", username: "simon", email: nil,
            suggestedUserId: nil, resolution: "assigned", assignedUserId: "u-unknown"
        )
        #expect(ImportUserRowModel.resolve(snapshot: snapshot, pickerUsers: []) == .assigned(toName: "u-unknown"))
    }

    @Test func skippedSnapshotResolvesToSkipped() {
        let snapshot = ImportReviewUserSnapshot(
            absUserId: "abs1", username: "root", email: nil,
            suggestedUserId: nil, resolution: "skipped", assignedUserId: nil
        )
        #expect(ImportUserRowModel.resolve(snapshot: snapshot, pickerUsers: []) == .skipped)
    }

    @Test func unresolvedSnapshotNeedsReview() {
        let snapshot = ImportReviewUserSnapshot(
            absUserId: "abs1", username: "taran", email: "t@x.com",
            suggestedUserId: "u2", resolution: "needs_review", assignedUserId: nil
        )
        #expect(ImportUserRowModel.resolve(snapshot: snapshot, pickerUsers: []) == .needsReview)
    }

    @Test func rowModelCarriesSuggestionNameFromPicker() {
        let snapshot = ImportReviewUserSnapshot(
            absUserId: "abs1", username: "taran", email: "t@x.com",
            suggestedUserId: "u2", resolution: "needs_review", assignedUserId: nil
        )
        let pickers = [ImportPickerUser(id: "u2", name: "Taran Hull", email: "t@x.com")]
        let row = ImportUserRowModel(snapshot: snapshot, pickerUsers: pickers)
        #expect(row.suggestedName == "Taran Hull")
        #expect(row.resolution == .needsReview)
        #expect(row.initial == "T")
    }

    @Test func blankUsernameYieldsQuestionMarkInitial() {
        let row = ImportUserRowModel(
            absUserId: "abs1", username: "", email: nil,
            suggestedUserId: nil, suggestedName: nil, resolution: .needsReview
        )
        #expect(row.initial == "?")
    }

    // MARK: - Review model counts

    @Test func reviewModelCountsResolvedAndUnresolved() {
        let users = [
            row(id: "a", resolution: .needsReview),
            row(id: "b", resolution: .assigned(toName: "B")),
            row(id: "c", resolution: .skipped)
        ]
        let review = ImportReviewModel(
            users: users, listenupUsers: [], booksMatchedCount: 0,
            ambiguousCount: 0, unmatchedCount: 0, importableSessionCount: 0
        )
        #expect(review.unresolvedCount == 1)
        #expect(review.matchedCount == 2)
    }

    // MARK: - Hub stage classification

    @Test func stageMapsAnalyzingStatuses() {
        #expect(ImportStage.from(status: "analyzing") == .analyzing)
        #expect(ImportStage.from(status: "ANALYZING") == .analyzing)
    }

    @Test func stageMapsPendingStatuses() {
        #expect(ImportStage.from(status: "uploaded") == .pending)
        #expect(ImportStage.from(status: "analyzed") == .pending)
        #expect(ImportStage.from(status: "pending") == .pending)
    }

    @Test func stageMapsReadyAndImported() {
        #expect(ImportStage.from(status: "mapped") == .ready)
        #expect(ImportStage.from(status: "ready") == .ready)
        #expect(ImportStage.from(status: "applied") == .imported)
        #expect(ImportStage.from(status: "imported") == .imported)
    }

    @Test func unknownStatusPassesThrough() {
        #expect(ImportStage.from(status: "weird") == .other("weird"))
    }

    // MARK: - Helpers

    private func row(id: String, resolution: ImportUserResolution) -> ImportUserRowModel {
        ImportUserRowModel(
            absUserId: id, username: id, email: nil,
            suggestedUserId: nil, suggestedName: nil, resolution: resolution
        )
    }
}
