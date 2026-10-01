/// Which Book Detail a book gets. A held book is triage-only (spec §8): its page offers Edit and
/// Release and nothing else. One switch, read in one place per region, so a new section cannot
/// forget the rule by omission — it has to choose a side.
enum BookDetailLayout: Equatable {
    case full
    case triage

    nonisolated static func forBook(isHeld: Bool) -> BookDetailLayout { isHeld ? .triage : .full }

    /// The resume bar (Play), the server banner, and the action pills (shelf, mark finished).
    var showsPlayback: Bool { self == .full }
    /// Rating, readers and Hardcover — hidden for a held book (spec §9: hidden, not refused).
    var showsSocial: Bool { self == .full }
    /// Shelf, collection, share, progress resets and delete; triage puts Edit in the toolbar instead.
    var showsOverflowMenu: Bool { self == .full }
    /// "Held for review", with Release, Match and Edit chapters.
    var showsHeldSection: Bool { self == .triage }

    /// What a held book allows (spec §8, §10): Release and Edit, plus the two metadata fixes that
    /// otherwise live in the overflow menu. Empty for an ordinary book, whose actions are the menu's.
    /// The held section draws every one but Edit (`BookDetailHeldSection.arrange`); `toolbar(for:)`
    /// puts Edit in the toolbar.
    var triageActions: [TriageAction] { self == .triage ? [.release, .edit, .match, .editChapters] : [] }

    /// The page's one trailing toolbar item, for the layout once the book has loaded — `nil` while it
    /// is still loading. Nothing is offered until it is known whether the book is held: the full menu
    /// (Delete, Share, shelf) must never flash for a held book and then swap to Edit.
    nonisolated static func toolbar(for layout: BookDetailLayout?) -> Toolbar {
        guard let layout else { return .none }
        if layout.showsOverflowMenu { return .overflowMenu }
        return layout.triageActions.contains(.edit) ? .edit : .none
    }

    enum Toolbar: Equatable {
        /// Still loading: no item yet.
        case none
        /// A held book: Edit alone, in the top bar of the view it edits (HIG, Toolbars).
        case edit
        /// An ordinary book: the overflow menu.
        case overflowMenu
    }

    enum TriageAction: Hashable {
        /// The section's one prominent button.
        case release
        /// The toolbar (HIG, Toolbars).
        case edit
        /// The section's secondary "Match metadata".
        case match
        /// The section's secondary "Edit chapters".
        case editChapters
    }
}
