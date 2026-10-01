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
                case .match: showMetadataMatch = true
                case .editChapters: showChapterEditor = true
                }
            }
            .releaseConfirmation(isPresented: $showReleaseConfirmation) { observer.releaseFromInbox() }
        }
    }
}

extension View {
    /// The Release confirmation (HIG, Alerts): Cancel leading as the cancel role, Release trailing in
    /// the default style — it can't simply be undone, but nothing is destroyed, so not destructive.
    func releaseConfirmation(isPresented: Binding<Bool>, onRelease: @escaping () -> Void) -> some View {
        alert(ReleaseToEveryone.title, isPresented: isPresented) {
            Button(String(localized: "common.cancel"), role: .cancel) {}
            // The preferred action, so Return confirms it (HIG, Alerts).
            Button(ReleaseToEveryone.confirm, action: onRelease)
                .keyboardShortcut(.defaultAction)
        } message: {
            Text(ReleaseToEveryone.message(count: 1))
        }
    }
}
