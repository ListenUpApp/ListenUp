import SwiftUI
import Shared

/// Manual server URL entry screen, presented as a sheet over the server picker.
///
/// When server is verified, AuthState updates automatically.
/// No onServerVerified callback needed.
struct ServerManualEntryView: View {

    // MARK: - State

    @State private var viewModel: ServerConnectViewModelWrapper
    @Environment(\.scenePhase) private var scenePhase

    /// True when the server picker already learned that Local Network access is off, so the notice
    /// shows before the user even tries.
    private let localNetworkDenied: Bool

    // MARK: - Navigation

    var onBack: (() -> Void)?

    // MARK: - Initialization

    init(localNetworkDenied: Bool = false, onBack: (() -> Void)? = nil) {
        self.localNetworkDenied = localNetworkDenied
        self.onBack = onBack
        _viewModel = State(initialValue: ServerConnectViewModelWrapper(
            viewModel: Dependencies.shared.makeServerConnectViewModel()
        ))
    }

    // MARK: - Body

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 18) {
                    Text(String(localized: "connect.enter_server_url"))
                        .font(.subheadline).foregroundStyle(.secondary)

                    AuthFieldGroup {
                        AppTextField(
                            placeholder: String(localized: "connect.server_url_placeholder"),
                            text: Binding(get: { viewModel.serverUrl },
                                          set: { viewModel.onUrlChanged($0) }),
                            entry: .url,
                            icon: "globe",
                            // The notice below carries the denial, with its fix; don't say it twice.
                            error: showsLocalNetworkNotice ? nil : viewModel.error,
                            onSubmit: { if viewModel.isConnectEnabled { viewModel.onConnectClicked() } }
                        )
                    }

                    if showsLocalNetworkNotice {
                        LocalNetworkNotice()
                    }

                    Text(String(localized: "connect.server_url_hint"))
                        .font(.footnote).foregroundStyle(.secondary)

                    Button { viewModel.onConnectClicked() } label: {
                        ActionLabel(title: String(localized: "connect.connect"), isBusy: viewModel.isLoading)
                    }
                    .prominentAction()
                    .disabled(viewModel.isLoading || !viewModel.isConnectEnabled)
                    .padding(.top, Spacing.xxs)
                }
                .padding(Spacing.l)
            }
            .background(Color(.systemGroupedBackground))
            .navigationTitle(String(localized: "connect.add_server"))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button(String(localized: "common.cancel")) { onBack?() }
                }
            }
        }
        // Returning from Settings re-runs the attempt the denial blocked.
        .onChange(of: scenePhase) { _, phase in
            if phase == .active { viewModel.retryAfterLocalNetworkGrant() }
        }
        .presentationDetents([.medium, .large])
        .presentationDragIndicator(.visible)
    }
}

// MARK: - Private

private extension ServerManualEntryView {
    var showsLocalNetworkNotice: Bool {
        viewModel.recovery == .openSettings || (localNetworkDenied && viewModel.error == nil)
    }
}

// MARK: - Previews

#Preview("Manual Entry") {
    ServerManualEntryView()
}
