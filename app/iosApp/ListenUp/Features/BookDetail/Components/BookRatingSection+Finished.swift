import Foundation

/// The finished reader's words (spec §3.2.3 Finished): "How Was It?" leads, and the note is for their listeners.
extension BookRatingSection {
    /// "How Was It?" once finished; "Your Rating" otherwise.
    nonisolated static func heading(isFinished: Bool) -> String {
        isFinished
            ? String(localized: "book.detail_rating_how_was_it_ios")
            : String(localized: "book.detail_rating_yours")
    }

    /// "Edit Note" when there is one; else "Add a Note for Your Listeners" once finished, or "Add a Note".
    nonisolated static func addNoteTitle(isFinished: Bool, hasNote: Bool) -> String {
        // Literal keys, so `verifySwiftStringKeys` sees them all.
        if hasNote { return String(localized: "book.detail_rating_edit_note").titleStyled }
        if isFinished { return String(localized: "book.detail_rating_add_note_for_listeners_ios") }
        return String(localized: "book.detail_rating_add_note").titleStyled
    }
}
