import SwiftUI

@MainActor
extension BookDetailView {
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
