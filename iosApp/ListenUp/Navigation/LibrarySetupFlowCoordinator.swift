import SwiftUI
@preconcurrency import Shared

/// Routes within the library-setup flow. The root is ``ChooseFoldersView``; scan progress
/// is shown on ``BuildingLibraryView`` if pushed later.
enum LibrarySetupRoute: Hashable {
    case building
}

/// Coordinates the first-run library-setup flow: ChooseFolders → completeSetup → Shell.
///
/// Owns a single ``LibrarySetupViewModelWrapper`` shared across both screens, so the folder
/// selection and live scan progress flow through one source of truth. The wrapper's `onFinished`
/// callback signals completion up via ``onComplete``, which the root uses to leave setup and
/// mount the main app.
struct LibrarySetupFlowCoordinator: View {
    /// Called when the admin finishes setup and is ready to enter the app.
    let onComplete: () -> Void

    @State private var viewModel: LibrarySetupViewModelWrapper
    @State private var path: [LibrarySetupRoute] = []

    init(onComplete: @escaping () -> Void) {
        self.onComplete = onComplete
        _viewModel = State(
            wrappedValue: LibrarySetupViewModelWrapper(
                viewModel: Dependencies.shared.librarySetupViewModel,
                syncRepository: Dependencies.shared.syncRepository
            )
        )
    }

    var body: some View {
        NavigationStack(path: $path) {
            ChooseFoldersView(viewModel: viewModel)
                .navigationDestination(for: LibrarySetupRoute.self) { route in
                    switch route {
                    case .building:
                        BuildingLibraryView(viewModel: viewModel)
                    }
                }
        }
        .onAppear {
            viewModel.onFinished = onComplete
            viewModel.checkStatus()
        }
        .onDisappear { viewModel.stopObserving() }
    }
}
