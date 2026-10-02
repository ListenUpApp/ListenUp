import SwiftUI

/// Book Detail's Hardcover section and the two confirmations it asks for, split out of
/// `BookDetailView.swift` for the same reason `+Held` is: that struct sits at SwiftLint's 400-line body cap.
@MainActor
extension BookDetailView {
    /// The Hardcover match, under Readers. Renders only for a connected user, whatever the book's match:
    /// matched, needs a match, never matched, or kept off Hardcover; otherwise it stays out of the layout,
    /// divider and all. Remove Match and Keep Off ride the section that asks for them, so on iPad each
    /// confirmation anchors to it.
    @ViewBuilder
    var hardcoverSection: some View {
        if let phase = hardcoverObserver?.phase, phase != .hidden {
            Divider()
            BookHardcoverSection(
                phase: phase,
                onFindMatch: { hardcoverMatchTarget = HardcoverMatchTarget(bookId: bookId) },
                onRemoveMatch: { confirmingHardcoverRemoval = true },
                onSetSynced: { hardcoverObserver?.setSynced($0) },
                onConfirmKeepOff: { keepOffMessage = $0 }
            )
            .confirmationDialog(
                String(localized: "hardcover.match_remove").titleStyled,
                isPresented: $confirmingHardcoverRemoval,
                titleVisibility: .hidden
            ) {
                Button(String(localized: "hardcover.match_remove").titleStyled, role: .destructive) {
                    hardcoverObserver?.removeMatch()
                }
                Button(String(localized: "common.cancel"), role: .cancel) {}
            } message: {
                Text(String(localized: "hardcover.match_remove_detail"))
            }
            .confirmationDialog(
                String(localized: "hardcover.keep_off_confirm_title"),
                isPresented: Binding(get: { keepOffMessage != nil }, set: { if !$0 { keepOffMessage = nil } }),
                titleVisibility: .visible
            ) {
                // Not destructive: Sync Again undoes it.
                Button(String(localized: "hardcover.keep_off_confirm_action").titleStyled) {
                    hardcoverObserver?.setSynced(false)
                }
                Button(String(localized: "common.cancel"), role: .cancel) {}
            } message: {
                Text(keepOffMessage ?? "")
            }
        }
    }
}
