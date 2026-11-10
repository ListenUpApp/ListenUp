import SwiftUI
import Shared

/// Coordinates the first-run library-setup flow: ChooseFolders → completeSetup → Shell.
///
/// Owns a single ``LibrarySetupViewModelWrapper`` shared with ``ChooseFoldersView``. The
/// wrapper's `onFinished` callback signals completion up via ``onComplete``, which the
/// root uses to leave setup and mount the main app.
struct LibrarySetupFlowCoordinator: View {
    /// Called when the admin finishes setup and is ready to enter the app.
    let onComplete: () -> Void

    @State private var viewModel: LibrarySetupViewModelWrapper

    init(onComplete: @escaping () -> Void) {
        self.onComplete = onComplete
        _viewModel = State(
            wrappedValue: LibrarySetupViewModelWrapper(
                viewModel: Dependencies.shared.makeLibrarySetupViewModel()
            )
        )
    }

    var body: some View {
        NavigationStack {
            switch viewModel.phase {
            case .checking, .notNeeded:
                ProgressView()
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
            case .checkFailed(let message):
                SetupCheckFailedView(message: message) { viewModel.retryStatusCheck() }
            case .browsing:
                ChooseFoldersView(viewModel: viewModel)
            }
        }
        .onAppear {
            viewModel.onFinished = onComplete
            // The check may already have answered "nothing to set up" before this callback existed.
            if viewModel.phase == .notNeeded { onComplete() }
        }
    }
}

/// The server could not say whether setup is needed: what happened, and Try Again — never a
/// spinner that outlives the failure, and never a folder picker for a server that did not answer.
struct SetupCheckFailedView: View {
    let message: String
    let onRetry: () -> Void

    var body: some View {
        ContentUnavailableView {
            Label(String(localized: "startup.setup_check_failed_title"), systemImage: "exclamationmark.triangle")
        } description: {
            Text(message)
        } actions: {
            Button(action: onRetry) {
                ActionLabel(title: String(localized: "common.try_again"), systemImage: "arrow.clockwise")
            }
            .prominentAction()
            .frame(maxWidth: 240)
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
    }
}
