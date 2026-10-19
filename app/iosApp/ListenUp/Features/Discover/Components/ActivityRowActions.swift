import Foundation

/// What an activity row offers beyond its tap, in the board's order (spec §3.5): a book row opens the
/// book, views the person and adds the book to a shelf; a shelf row opens the shelf and views the
/// person; a row about neither (a join, a milestone) offers nothing, since its tap already opens the
/// person (plan Q5). The context menu and the VoiceOver actions read this one list (plan C7).
enum ActivityRowActions {
    enum Action: Hashable {
        case openBook(String)
        case openShelf(String)
        case viewPerson(String)
        case addToShelf(String)
    }

    /// The row's actions. `canShelve` is false where the screen has no selection to shelve through.
    static func actions(for item: ActivityRowItem, canShelve: Bool = true) -> [Action] {
        if let bookId = item.bookId {
            return [.openBook(bookId), .viewPerson(item.userId)] + (canShelve ? [.addToShelf(bookId)] : [])
        }
        if let shelfId = item.shelfId {
            return [.openShelf(shelfId), .viewPerson(item.userId)]
        }
        return []
    }

    /// Whether a row may offer Add to Shelf: only with a selection to shelve through, and never while
    /// one is under way — shelving one book replaces the selection and ends it, wiping the picks.
    static func canShelve(hasSelection: Bool, isSelecting: Bool) -> Bool { hasSelection && !isSelecting }

    /// "Open Piranesi", "View Maya Chen" (or "Your profile" on your own row), "Add Piranesi to a shelf".
    static func title(_ action: Action, item: ActivityRowItem) -> String {
        switch action {
        case .openBook, .openShelf:
            return String(format: String(localized: "discover.activity_open"), item.highlight ?? "")
        case .viewPerson:
            return item.isYou
                ? String(localized: "discover.activity_your_profile_a11y")
                : String(format: String(localized: "discover.activity_view_person_ios"), item.actorName)
        case .addToShelf:
            return String(format: String(localized: "discover.activity_add_book_to_shelf"), item.highlight ?? "")
        }
    }

    /// The SF Symbol each action wears in the menu.
    static func systemImage(_ action: Action) -> String {
        switch action {
        case .openBook: return "book"
        case .openShelf: return "rectangle.stack"
        case .viewPerson: return "person.crop.circle"
        case .addToShelf: return "text.badge.plus"
        }
    }
}
