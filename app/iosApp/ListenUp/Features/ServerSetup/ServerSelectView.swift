import SwiftUI
import Shared

/// Server selection screen showing discovered servers via mDNS.
///
/// When a server is selected and verified, the Kotlin layer updates AuthState,
/// which automatically transitions the app. No callback needed.
struct ServerSelectView: View {

    // MARK: - State

    @State private var viewModel: ServerSelectViewModelWrapper
    @Environment(\.scenePhase) private var scenePhase

    // MARK: - Navigation

    @Binding var showManualEntry: Bool
    /// Mirrors the picker's knowledge that Local Network access is off, so manual entry can say so.
    @Binding var localNetworkDenied: Bool

    // MARK: - Initialization

    init(showManualEntry: Binding<Bool>, localNetworkDenied: Binding<Bool>) {
        self._showManualEntry = showManualEntry
        self._localNetworkDenied = localNetworkDenied
        _viewModel = State(initialValue: ServerSelectViewModelWrapper(
            viewModel: Dependencies.shared.makeServerSelectViewModel()
        ))
    }

    // MARK: - Body

    var body: some View {
        AuthScaffold {
            VStack(alignment: .center, spacing: 16) {
                BrandLockup()
                Text(String(localized: "connect.choose_server"))
                    .font(.subheadline).foregroundStyle(.secondary)
                    .multilineTextAlignment(.center)
            }
            .frame(maxWidth: .infinity)

            groupHeader
            serverList
            failure
        } footer: {
            Button {
                if let first = viewModel.servers.first { viewModel.selectServer(first) }
            } label: {
                ActionLabel(title: String(localized: "connect.continue"))
            }
            .prominentAction()
            .disabled(viewModel.servers.isEmpty || viewModel.isConnecting)
        }
        .onAppear {
            viewModel.onManualEntryRequested = { showManualEntry = true }
            viewModel.startDiscovery()
        }
        .onChange(of: viewModel.isLocalNetworkDenied) { _, denied in localNetworkDenied = denied }
        // Returning from Settings re-runs the browse or activation the denial blocked.
        .onChange(of: scenePhase) { _, phase in
            if phase == .active { viewModel.retryAfterLocalNetworkGrant() }
        }
    }

    /// The last failure, inline under the list: the Local Network notice with its Settings link
    /// when that is the cause, the error's own message otherwise.
    @ViewBuilder
    private var failure: some View {
        if viewModel.recovery == .openSettings {
            LocalNetworkNotice()
        } else if let error = viewModel.error {
            Label(error, systemImage: "exclamationmark.triangle")
                .font(.footnote).foregroundStyle(.red)
                .frame(maxWidth: .infinity, alignment: .leading)
        }
    }

    // MARK: - Private views

    private var groupHeader: some View {
        HStack(spacing: 9) {
            Text(String(localized: "connect.on_your_network").uppercased())
                .font(.footnote).foregroundStyle(.secondary)
            Text("\(viewModel.servers.count)")
                .font(.caption.weight(.bold)).foregroundStyle(.secondary)
                .padding(.horizontal, Spacing.xs).padding(.vertical, 2)
                .background(Capsule().fill(Color.luFill))
            Spacer()
            RescanPill(isBusy: viewModel.isDiscovering) { viewModel.refresh() }
        }
    }

    private var serverList: some View {
        AuthFieldGroup {
            if viewModel.servers.isEmpty {
                DiscoveryRow(isDiscovering: viewModel.isDiscovering)
            } else {
                ForEach(viewModel.servers) { server in
                    ServerRow(
                        server: server,
                        isSelected: viewModel.selectedServerId == server.id,
                        isConnecting: viewModel.isConnecting && viewModel.selectedServerId == server.id
                    ) { viewModel.selectServer(server) }
                }
            }
            AddServerRow { viewModel.requestManualEntry() }
        }
    }
}

// MARK: - Rows

private struct ServerRow: View {
    let server: DiscoveredServerItem
    let isSelected: Bool
    let isConnecting: Bool
    let onTap: () -> Void

    var body: some View {
        Button(action: onTap) {
            HStack(spacing: 13) {
                RoundedRectangle(cornerRadius: Radius.m, style: .continuous)
                    .fill(isSelected ? Color.listenUpOrange : Color(.systemFill))
                    .frame(width: 40, height: 40)
                    .overlay {
                        Image(systemName: "server.rack")
                            .foregroundStyle(isSelected ? Color.luOnTint : .secondary)
                    }
                VStack(alignment: .leading, spacing: 2) {
                    Text(server.name).font(.headline).foregroundStyle(.primary)
                    HStack(spacing: 7) {
                        Circle().fill(server.isOnline ? .green : .secondary).frame(width: 9, height: 9)
                        Text(server.isOnline ? String(localized: "common.online") : String(localized: "common.offline"))
                            .font(.footnote).foregroundStyle(.secondary)
                        Circle().fill(Color.secondary).frame(width: 3, height: 3)
                        Text(server.hostPort).font(.footnote).foregroundStyle(.secondary)
                            .lineLimit(1).truncationMode(.middle)
                        if server.version != "unknown" {
                            Circle().fill(Color.secondary).frame(width: 3, height: 3)
                            Text("v\(server.version)").font(.footnote).foregroundStyle(.secondary)
                        }
                    }
                }
                Spacer(minLength: 8)
                trailing
            }
            .frame(minHeight: 68)
            .padding(.horizontal, Spacing.m)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .disabled(isConnecting)
        .accessibilityAddTraits(isSelected ? .isSelected : [])
        .overlay(alignment: .bottom) {
            Rectangle().fill(Color.luSeparator).frame(height: 0.5).padding(.leading, 67)
        }
    }

    @ViewBuilder
    private var trailing: some View {
        if isConnecting {
            ProgressView().controlSize(.small)
        } else if isSelected {
            Image(systemName: "checkmark").font(.headline.weight(.bold)).foregroundStyle(Color.listenUpOrange)
        } else {
            Image(systemName: "chevron.right").font(.footnote.weight(.semibold)).foregroundStyle(.tertiary)
        }
    }
}

private struct AddServerRow: View {
    let onTap: () -> Void
    var body: some View {
        Button(action: onTap) {
            HStack(spacing: 13) {
                RoundedRectangle(cornerRadius: Radius.m, style: .continuous)
                    .fill(Color.listenUpOrange.opacity(0.14)).frame(width: 40, height: 40)
                    .overlay { Image(systemName: "plus").foregroundStyle(Color.listenUpOrange) }
                Text(String(localized: "connect.add_server_manually"))
                    .font(.body).foregroundStyle(Color.listenUpOrange)
                Spacer()
                Image(systemName: "chevron.right").font(.footnote.weight(.semibold)).foregroundStyle(.tertiary)
            }
            .frame(minHeight: 56).padding(.horizontal, Spacing.m).contentShape(Rectangle())
        }
        .buttonStyle(.plain)
    }
}

private struct DiscoveryRow: View {
    let isDiscovering: Bool
    var body: some View {
        HStack(spacing: 10) {
            if isDiscovering {
                ProgressView().controlSize(.small)
                Text(String(localized: "connect.searching")).font(.subheadline).foregroundStyle(.secondary)
            } else {
                Text(String(format: String(localized: "common.no_items_found"),
                            String(localized: "connect.listenup_server")))
                    .font(.subheadline).foregroundStyle(.secondary)
            }
            Spacer()
        }
        .frame(minHeight: 56).padding(.horizontal, Spacing.m)
        .overlay(alignment: .bottom) {
            Rectangle().fill(Color.luSeparator).frame(height: 0.5).padding(.leading, Spacing.m)
        }
    }
}

// MARK: - Previews

#Preview("Server Select") {
    ServerSelectView(showManualEntry: .constant(false), localNetworkDenied: .constant(false))
}
