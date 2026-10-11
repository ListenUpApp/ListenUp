import SwiftUI

/// The back affordance of a pushed screen that holds an unsaved edit.
///
/// A sheet asks through `EditSheetScaffold`; a screen pushed onto a stack has no Cancel bar, so while
/// it holds changes the back button is replaced by a Cancel that asks "Discard Changes?" first (the
/// pattern `UserPermissionsView` set), and the edge-swipe back goes with the hidden button. The
/// decision itself is `EditSheetDismissal`, so a sheet and a pushed screen can never disagree about
/// when leaving loses work.
private struct UnsavedEditsGuard: ViewModifier {
    let hasChanges: Bool

    @Environment(\.dismiss) private var dismiss
    @State private var isConfirmingDiscard = false

    private var dismissal: EditSheetDismissal { EditSheetDismissal(hasChanges: hasChanges) }

    func body(content: Content) -> some View {
        content
            .navigationBarBackButtonHidden(dismissal.blocksInteractiveDismiss)
            .toolbar {
                if dismissal.blocksInteractiveDismiss {
                    ToolbarItem(placement: .cancellationAction) {
                        Button(String(localized: "common.cancel")) { isConfirmingDiscard = true }
                    }
                }
            }
            .confirmationDialog(
                String(localized: "edit.discard_changes_prompt"),
                isPresented: $isConfirmingDiscard,
                titleVisibility: .visible
            ) {
                Button(String(localized: "edit.discard_changes"), role: .destructive) { dismiss() }
                Button(String(localized: "edit.keep_editing"), role: .cancel) {}
            }
    }
}

extension View {
    /// Asks "Discard Changes?" before a pushed screen is left while it holds `hasChanges`.
    func guardsUnsavedEdits(hasChanges: Bool) -> some View {
        modifier(UnsavedEditsGuard(hasChanges: hasChanges))
    }
}
