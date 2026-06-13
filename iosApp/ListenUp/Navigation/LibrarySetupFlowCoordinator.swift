import SwiftUI
@preconcurrency import Shared

/// Routes within the library-setup flow. The root is ``ChooseFoldersView``; creating the
/// library pushes ``building``.
enum LibrarySetupRoute: Hashable {
    case building
}

/// Coordinates the first-run library-setup flow: ChooseFolders → (create) → Building.
///
/// Owns a single ``LibrarySetupViewModelWrapper`` shared across both screens, so the folder
/// selection and live scan progress flow through one source of truth. The wrapper's nav
/// callbacks drive the stack: `onLibraryCreated` pushes ``LibrarySetupRoute/building``;
/// `onFinished` (the "Browse" affordance) signals completion up via ``onComplete``, which the
/// root uses to leave setup and mount the main app.
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
            viewModel.onLibraryCreated = {
                // `onLibraryCreated` fires on every create; only push if we aren't
                // already on the building screen so a second create can't stack it.
                if path.last != .building { path.append(.building) }
            }
            viewModel.onFinished = onComplete
            viewModel.checkStatus()
        }
        .onDisappear { viewModel.stopObserving() }
    }
}
