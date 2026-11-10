import ListenupContract
import Shared
import SwiftUI

/// Where an edit sheet stands, flattened from the shared `…EditUiState` sealed types (Book,
/// Contributor, Series) for a SwiftUI `switch`.
///
/// A sheet whose subject could not be loaded is `.loadFailed`, never an empty form that looks
/// editable — its Save would write blanks over a record that was never read. Save, merge and image
/// failures belong to `.editing` and show beside the form.
enum EditScreenPhase: Equatable {
    case loading
    /// The load failed with `message`; `canRetry` when asking again could help (`AppError.isRetryable`).
    case loadFailed(message: String, canRetry: Bool)
    case editing

    /// The failed-load phase for `error`, mapped once at the observer boundary.
    static func failed(_ error: any AppError) -> EditScreenPhase {
        .loadFailed(message: error.message, canRetry: error.isRetryable)
    }
}

/// The sheet shown when an edit sheet's subject could not be loaded: what happened, Retry when it
/// could help, and Close. HIG, Sheets: the dismiss control stays in the cancellation placement.
struct EditLoadFailedView: View {
    let message: String
    let canRetry: Bool
    let onRetry: () -> Void
    let onClose: () -> Void

    var body: some View {
        NavigationStack {
            ContentUnavailableView {
                Label(String(localized: "common.something_went_wrong"), systemImage: "exclamationmark.triangle")
            } description: {
                Text(message)
            } actions: {
                if canRetry {
                    Button(action: onRetry) {
                        ActionLabel(title: String(localized: "common.retry"), systemImage: "arrow.clockwise")
                    }
                    .prominentAction()
                    .frame(maxWidth: 240)
                }
            }
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button(String(localized: "common.close"), action: onClose)
                }
            }
        }
    }
}
