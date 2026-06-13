import SwiftUI

/// The prominent action on an auth screen — now a thin alias over the shared
/// `PrimaryButton` so auth matches the rest of the app.
struct AuthPrimaryButton: View {
    var title: String
    var isLoading: Bool = false
    var action: () -> Void

    var body: some View {
        PrimaryButton(title: title, isLoading: isLoading, action: action)
    }
}
