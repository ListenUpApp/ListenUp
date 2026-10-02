/// The inbox page's two modes. Browsing, a held book's row opens its triage page (spec §8); selecting,
/// the row toggles it for a bulk Release. Selecting several rows is a mode entered on purpose — the
/// toolbar's Select, left with Done (HIG, Lists and tables: "Let people select multiple items") — so a
/// tap is never ambiguous between opening a book and choosing it.
enum InboxMode: Equatable {
    case browsing
    case selecting

    /// Selecting while Select is on, or while anything is selected — so a selection made by the iPad
    /// header's Select all, or one half made, is never abandoned by a tap that navigates away from it.
    static func resolve(selectRequested: Bool, hasSelection: Bool) -> InboxMode {
        selectRequested || hasSelection ? .selecting : .browsing
    }

    /// What tapping a held book's row does.
    var rowTap: RowTap {
        switch self {
        case .browsing: .openDetail
        case .selecting: .toggleSelection
        }
    }

    enum RowTap: Equatable {
        /// Push the book's triage page onto the inbox's own stack, so Back returns to the inbox.
        case openDetail
        /// Add the book to, or take it out of, the selection.
        case toggleSelection
    }
}
