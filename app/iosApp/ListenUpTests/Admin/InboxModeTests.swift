import Testing
@testable import ListenUp

/// Spec §8: a held book's detail page opens from the inbox. A row opens it; selecting for a bulk
/// Release is a mode entered with Select.
@Suite("Inbox row tap")
struct InboxModeTests {
    @Test func browsingTheInboxARowOpensItsBook() {
        let mode = InboxMode.resolve(selectRequested: false, hasSelection: false)
        #expect(mode == .browsing)
        #expect(mode.rowTap == .openDetail)
    }

    @Test func afterSelectARowTogglesItsBook() {
        let mode = InboxMode.resolve(selectRequested: true, hasSelection: false)
        #expect(mode == .selecting)
        #expect(mode.rowTap == .toggleSelection)
    }

    /// Select all (the iPad header) selects without Select: a tap must not navigate away from it.
    @Test func aSelectionMadeWithoutSelectStillTogglesRows() {
        #expect(InboxMode.resolve(selectRequested: false, hasSelection: true).rowTap == .toggleSelection)
    }
}
