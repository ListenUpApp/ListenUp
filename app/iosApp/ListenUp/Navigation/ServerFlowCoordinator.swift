import SwiftUI

/// Coordinates the server-setup flow: ServerSelect → (optional) ManualEntry sheet.
/// Server activation moves `AuthState` in the KMP layer, which transitions the app.
struct ServerFlowCoordinator: View {
    @State private var showManualEntry = false
    @State private var localNetworkDenied = false

    var body: some View {
        ServerSelectView(showManualEntry: $showManualEntry, localNetworkDenied: $localNetworkDenied)
            .sheet(isPresented: $showManualEntry) {
                ServerManualEntryView(localNetworkDenied: localNetworkDenied, onBack: { showManualEntry = false })
            }
    }
}
