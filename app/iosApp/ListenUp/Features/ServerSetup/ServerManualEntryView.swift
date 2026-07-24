import SwiftUI
import Shared

/// Manual server URL entry screen, presented as a sheet over the server picker.
///
/// When server is verified, AuthState updates automatically.
/// No onServerVerified callback needed.
struct ServerManualEntryView: View {

    // MARK: - State

    @State private var viewModel: ServerConnectViewModelWrapper

    // MARK: - Navigation

    var onBack: (() -> Void)?

    // MARK: - Initialization

    init(onBack: (() -> Void)? = nil) {
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
                            icon: "globe",
                            error: viewModel.error,
                            keyboardType: .URL,
                            textContentType: .URL,
                            onSubmit: { if viewModel.isConnectEnabled { viewModel.onConnectClicked() } }
                        )
                    }

                    Text(String(localized: "connect.server_url_hint"))
                        .font(.footnote).foregroundStyle(.secondary)

                    AuthPrimaryButton(
                        title: String(localized: "connect.connect"),
                        isLoading: viewModel.isLoading
                    ) { viewModel.onConnectClicked() }
                        .disabled(!viewModel.isConnectEnabled)
                        .padding(.top, 4)
                }
                .padding(20)
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
        .presentationDetents([.medium, .large])
        .presentationDragIndicator(.visible)
    }
}

// MARK: - Previews

#Preview("Manual Entry") {
    ServerManualEntryView()
}
