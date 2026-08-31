import Testing
@testable import ListenUp

/// Pure-seam coverage for the bulk metadata editor.
///
/// The state flatten itself can't be exercised here — `BulkEditUiState.Editing` is a bridged Kotlin
/// type produced by a `BulkEditViewModel` whose constructor is deliberately `internal`, so there is
/// no way to build one from Swift. What *is* pure and constructible is every sentence the sheet
/// speaks about counts, which is exactly where a bulk edit would lie about itself if it lied at all.
@Suite("BulkEditFormatting")
struct BulkEditObserverTests {
    // MARK: - Title

    @Test func titleNamesASingleBookRatherThanCountingIt() {
        #expect(BulkEditFormatting.title(bookCount: 1) == String(localized: "bulk_edit.title_one"))
    }

    @Test func titleCountsASelection() {
        let expected = String(format: String(localized: "bulk_edit.title_plural"), 40)
        #expect(BulkEditFormatting.title(bookCount: 40) == expected)
    }

    // MARK: - Apply label

    /// The resting state of an untouched form. "Change 0 books" is true and reads as a bug, so the
    /// button names what it does and waits for something to count.
    @Test func applyLabelNamesTheActionWhenNothingWouldChange() {
        #expect(BulkEditFormatting.applyLabel(changedBookCount: 0) == String(localized: "bulk_edit.apply_none"))
    }

    @Test func applyLabelCountsOneBook() {
        #expect(BulkEditFormatting.applyLabel(changedBookCount: 1) == String(localized: "bulk_edit.apply_one"))
    }

    /// The button counts books that will **change**, never books that were selected — promising
    /// forty and then reporting twelve is the overstatement the preview exists to prevent.
    @Test func applyLabelCountsTheBooksThatChangeNotTheSelection() {
        let expected = String(format: String(localized: "bulk_edit.apply_plural"), 12)
        #expect(BulkEditFormatting.applyLabel(changedBookCount: 12) == expected)
    }

    // MARK: - Preview wording

    @Test func affectsSaysNothingChangesWhenNoBookWould() {
        #expect(
            BulkEditFormatting.affects(affectedCount: 0, bookCount: 40)
                == String(localized: "bulk_edit.preview_affects_none")
        )
    }

    @Test func affectsDropsTheCountForASingleSelectedBook() {
        #expect(
            BulkEditFormatting.affects(affectedCount: 1, bookCount: 1)
                == String(localized: "bulk_edit.preview_affects_single_book")
        )
    }

    @Test func affectsCountsAgainstTheSelection() {
        let expected = String(format: String(localized: "bulk_edit.preview_affects_plural"), 12, 40)
        #expect(BulkEditFormatting.affects(affectedCount: 12, bookCount: 40) == expected)
    }

    // MARK: - The books that could not be loaded

    @Test func noNoticeWhenEverySelectedBookLoaded() {
        #expect(BulkEditFormatting.notLoadedNotice(bookCount: 40, requestedCount: 40) == nil)
    }

    /// A book deleted from another device between the grid and the sheet. An operation with no undo
    /// does not get to quietly do less than it was asked to.
    @Test func oneMissingBookIsStatedAgainstWhatWasSelected() {
        let expected = String(format: String(localized: "bulk_edit.some_not_loaded_one"), 40)
        #expect(BulkEditFormatting.notLoadedNotice(bookCount: 39, requestedCount: 40) == expected)
    }

    @Test func severalMissingBooksReportBothCounts() {
        let expected = String(format: String(localized: "bulk_edit.some_not_loaded_plural"), 3, 40)
        #expect(BulkEditFormatting.notLoadedNotice(bookCount: 37, requestedCount: 40) == expected)
    }

    // MARK: - Field placeholders

    @Test func aSharedValueBecomesThePlaceholder() {
        #expect(BulkEditFormatting.placeholder(shared: "Tor") == "Tor")
    }

    @Test func disagreeingBooksSaySoRatherThanShowingOneBooksValue() {
        #expect(BulkEditFormatting.placeholder(shared: nil) == String(localized: "bulk_edit.multiple_values"))
    }

    // MARK: - Failure

    @Test func aFailureBeforeAnythingCommittedIsJustTheReason() {
        #expect(BulkEditFormatting.failureMessage(reason: "No connection.", appliedCount: 0) == "No connection.")
    }

    /// The committed books are not rolled back, so naming them is the difference between "nothing
    /// happened" and "some of it happened" — the only thing the user can act on.
    @Test func aFailurePartWayThroughNamesWhatAlreadyStands() {
        let message = BulkEditFormatting.failureMessage(reason: "No connection.", appliedCount: 1)
        #expect(message.contains("No connection."))
        #expect(message.contains(String(localized: "bulk_edit.failed_after_one")))
    }

    @Test func aFailureAfterSeveralBooksCountsThem() {
        let message = BulkEditFormatting.failureMessage(reason: "No connection.", appliedCount: 5)
        #expect(message.contains(String(format: String(localized: "bulk_edit.failed_after_plural"), 5)))
    }

    // MARK: - Preview rows

    @Test func previewLineIsKeyedByItsFieldAndNamedLikeTheForm() {
        let line = BulkEditMapping.previewLine(field: .publisher, affectedCount: 12, bookCount: 40)

        #expect(line.id == "publisher")
        #expect(line.label == String(localized: "bulk_edit.publisher"))
        #expect(line.detail == String(format: String(localized: "bulk_edit.preview_affects_plural"), 12, 40))
        #expect(line.changesNothing == false)
    }

    /// A row that changes nothing is dimmed, never dropped — a vanished row reads as a lost edit.
    @Test func previewLineMarksAnInstructionThatChangesNothing() {
        let line = BulkEditMapping.previewLine(field: .language, affectedCount: 0, bookCount: 40)

        #expect(line.changesNothing)
        #expect(line.detail == String(localized: "bulk_edit.preview_affects_none"))
    }

    @Test func everyFieldCanNameItself() {
        for field in BulkEditField.allCases {
            #expect(!field.label.isEmpty)
            #expect(field.label != field.rawValue, "\(field.rawValue) fell through to its raw key")
        }
    }
}
