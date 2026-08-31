import SwiftUI

/// Standard edit-sheet chrome: a `NavigationStack` with a Cancel (leading) / Done
/// (trailing) bar. Done is gated on `canSave` and shows a spinner while `isSaving`.
/// Shared by the Series and Contributor edit sheets.
struct EditSheetScaffold<Content: View>: View {
    let title: String
    let canSave: Bool
    let isSaving: Bool
    /// The confirm button's text. Defaults to "Done"; the bulk editor names the number of books it
    /// will change instead, because "Done" over a destructive many-book write says too little.
    var saveLabel: String = String(localized: "common.done")
    let onCancel: () -> Void
    let onSave: () -> Void
    @ViewBuilder var content: () -> Content

    var body: some View {
        NavigationStack {
            ScrollView {
                content()
                    .padding(.vertical, 12)
            }
            .background(Color.luSurface)
            .navigationTitle(title)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .topBarLeading) {
                    Button(String(localized: "common.cancel"), action: onCancel)
                }
                ToolbarItem(placement: .topBarTrailing) {
                    // Invariant: while `isSaving` the action is REPLACED by a spinner, not
                    // merely disabled — this is the load-bearing guard against double-submit.
                    // Any variant that keeps the button visible during save must also fold
                    // `isSaving` into `.disabled`.
                    if isSaving {
                        ProgressView()
                    } else {
                        Button(saveLabel, action: onSave)
                            .fontWeight(.semibold)
                            .disabled(!canSave)
                    }
                }
            }
        }
    }
}

#Preview("EditSheetScaffold") {
    EditSheetScaffold(title: "Edit Series", canSave: true, isSaving: false, onCancel: {}, onSave: {}) {
        Text("content").frame(maxWidth: .infinity).padding()
    }
}
