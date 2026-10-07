import SwiftUI

/// Book Detail's triage pieces for a book held for review, split out of `BookDetailView.swift` for
/// the same reason `+OverflowMenu` is: that struct sits at SwiftLint's 400-line body cap.
@MainActor
extension BookDetailView {
    /// The held section, drawing the layout's triage actions (Release, Match, Edit chapters); nothing
    /// for an ordinary book. Match and Edit chapters open the same sheets the overflow menu's items
    /// do. The Release alert rides the section that asks for it, so the main view's body carries
    /// none of the triage.
    @ViewBuilder
    func heldSection(_ observer: BookDetailObserver) -> some View {
        if observer.layout.showsHeldSection {
            BookDetailHeldSection(
                actions: observer.layout.triageActions,
                isReleasing: observer.isReleasingFromInbox
            ) { action in
                switch action {
                case .release: showReleaseConfirmation = true
                case .edit: showEdit = true
                case .match: matchTarget = BookMatchTarget(bookId: bookId)
                case .editChapters: showChapterEditor = true
                }
            }
            .releaseConfirmation(isPresented: $showReleaseConfirmation, count: 1) {
                observer.releaseFromInbox()
            }
        }
    }
}
