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
            ChooseFoldersView(viewModel: viewModel)
        }
        .onAppear {
            viewModel.onFinished = onComplete
            viewModel.checkStatus()
        }
    }
}
