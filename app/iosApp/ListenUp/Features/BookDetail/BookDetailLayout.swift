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
    var triageActions: [TriageAction] { self == .triage ? [.release, .edit, .match, .editChapters] : [] }

    enum TriageAction: Equatable {
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
