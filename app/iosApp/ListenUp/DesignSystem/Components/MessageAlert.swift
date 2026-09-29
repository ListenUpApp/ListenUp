import SwiftUI

/// Identifiable wrapper so a transient error string drives an alert.
struct MessageAlert: Identifiable {
    let message: String
    var id: String { message }
}

extension View {
    /// Shows `alert`'s message in a system alert with one OK button, through the current
    /// `.alert(_:isPresented:presenting:actions:message:)` — the `Alert` value type it replaces is
    /// deprecated.
    func messageAlert(
        _ alert: Binding<MessageAlert?>,
        title: String = String(localized: "common.something_went_wrong"),
        onDismiss: @escaping () -> Void = {}
    ) -> some View {
        self.alert(
            title,
            isPresented: Binding(
                get: { alert.wrappedValue != nil },
                set: { if !$0 { alert.wrappedValue = nil } }
            ),
            presenting: alert.wrappedValue
        ) { _ in
            Button(String(localized: "common.ok"), role: .cancel, action: onDismiss)
        } message: { presented in
            Text(presented.message)
        }
    }
}
