import SwiftUI

/// What an edit sheet does when someone tries to leave it, given whether it holds unsaved changes.
///
/// HIG, Sheets: "Support swiping to dismiss a sheet … If people have unsaved changes in the sheet when
/// they begin swiping to dismiss it, use an action sheet to let them confirm their action."
struct EditSheetDismissal: Equatable {
    enum CancelOutcome: Equatable {
        /// Nothing to lose: close the sheet.
        case dismiss
        /// Ask "Discard Changes?" first.
        case confirmDiscard
    }

    let hasChanges: Bool

    var onCancel: CancelOutcome { hasChanges ? .confirmDiscard : .dismiss }

    /// While there are changes, swipe-down is held so Cancel's confirmation is the only way out
    /// without saving.
    var blocksInteractiveDismiss: Bool { hasChanges }
}

/// Standard edit-sheet chrome: a `NavigationStack` over a grouped `Form` — the content is `Section`s,
/// so fields get the list's insets, separators, Dynamic Type metrics and keyboard avoidance (HIG,
/// Lists and tables) — with Cancel and Done in the system's cancellation
/// and confirmation placements (HIG, Sheets: "the Cancel button belongs on the leading edge of the
/// top toolbar … the Done button belongs on the trailing edge"). Done is gated on `canSave` and shows
/// a spinner while `isSaving`.
///
/// It never drops edits silently: with `hasChanges`, swipe-down is held and Cancel asks
/// "Discard Changes?" first (`EditSheetDismissal`).
struct EditSheetScaffold<Content: View>: View {
    let title: String
    /// Whether the sheet holds edits that leaving would lose.
    let hasChanges: Bool
    let canSave: Bool
    let isSaving: Bool
    /// The confirm button's text. Defaults to "Done"; the bulk editor names the number of books it
    /// will change instead, because "Done" over a destructive many-book write says too little.
    var saveLabel: String = String(localized: "common.done")
    let onCancel: () -> Void
    let onSave: () -> Void
    @ViewBuilder var content: () -> Content

    @State private var isConfirmingDiscard = false

    private var dismissal: EditSheetDismissal { EditSheetDismissal(hasChanges: hasChanges) }

    var body: some View {
        NavigationStack {
            Form {
                content()
            }
            // Dragging the form down pulls the keyboard with it, the way Notes and Mail do.
            .scrollDismissesKeyboard(.interactively)
            .readableListWidth()
            .navigationTitle(title)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button(String(localized: "common.cancel"), action: cancel)
                        .confirmationDialog(
                            String(localized: "edit.discard_changes_prompt"),
                            isPresented: $isConfirmingDiscard,
                            titleVisibility: .visible
                        ) {
                            Button(String(localized: "edit.discard_changes"), role: .destructive, action: onCancel)
                            Button(String(localized: "edit.keep_editing"), role: .cancel) {}
                        }
                }
                ToolbarItem(placement: .confirmationAction) {
                    // Invariant: while `isSaving` the action is REPLACED by a spinner, not
                    // merely disabled — this is the load-bearing guard against double-submit.
                    // Any variant that keeps the button visible during save must also fold
                    // `isSaving` into `.disabled`.
                    if isSaving {
                        ProgressView()
                    } else {
                        Button(saveLabel, action: onSave)
                            .disabled(!canSave)
                    }
                }
            }
        }
        .interactiveDismissDisabled(dismissal.blocksInteractiveDismiss)
    }

    private func cancel() {
        switch dismissal.onCancel {
        case .dismiss: onCancel()
        case .confirmDiscard: isConfirmingDiscard = true
        }
    }
}

#Preview("EditSheetScaffold") {
    EditSheetScaffold(
        title: "Edit Series",
        hasChanges: true,
        canSave: true,
        isSaving: false,
        onCancel: {},
        onSave: {}
    ) {
        Section { Text("content") }
    }
}
