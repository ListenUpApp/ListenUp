import SwiftUI

/// A screen whose load failed: what went wrong, and Try Again — so the only way out is not leaving and coming back.
///
/// The system's own empty-state shape (`ContentUnavailableView`), with the retry as its action, as Devices and
/// Backups already did. `title` defaults to the generic heading; a screen with a better one passes it.
struct LoadFailedView: View {
    let message: String
    var title = String(localized: "common.something_went_wrong")
    let onRetry: () -> Void

    var body: some View {
        ContentUnavailableView {
            Label(title, systemImage: "exclamationmark.triangle")
        } description: {
            Text(message)
        } actions: {
            Button(String(localized: "common.try_again"), action: onRetry)
        }
    }
}
