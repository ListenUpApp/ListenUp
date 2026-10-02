import SwiftUI

@MainActor
extension BookDetailView {
    /// The description, then who can't see the book — one helper so both layouts place the
    /// Visibility section in the same spot, directly under what the book is about.
    @ViewBuilder
    func descriptionAndVisibility(_ observer: BookDetailObserver) -> some View {
        BookDescriptionSection(
            description: observer.bookDescription,
            genres: observer.genres,
            tags: observer.tags,
            moods: observer.moods
        )
        visibilitySection(observer)
    }

    /// Who can't see this book — admins only (a member's `visibility` is nil), and never in the
    /// triage layout, where the held section already says "Hidden from all members".
    @ViewBuilder
    func visibilitySection(_ observer: BookDetailObserver) -> some View {
        if !observer.isHeld, let model = observer.visibility {
            BookVisibilitySection(
                model: model,
                isRestoring: observer.isRestoringToAllBooks,
                onShowToAllMembers: { observer.restoreToAllBooks() },
                onAddToCollection: { observer.openCollectionPicker() }
            )
        }
    }
}
