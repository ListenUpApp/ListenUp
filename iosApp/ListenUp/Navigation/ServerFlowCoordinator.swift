import SwiftUI

/// Coordinates the server-setup flow: ServerSelect → (optional) ManualEntry.
/// Server activation moves `AuthState` in the KMP layer, which transitions the app.
struct ServerFlowCoordinator: View {
    @State private var showManualEntry = false

    var body: some View {
        NavigationStack {
            ServerSelectView(showManualEntry: $showManualEntry)
                .navigationDestination(isPresented: $showManualEntry) {
                    ServerManualEntryView(onBack: { showManualEntry = false })
                }
        }
    }
}
