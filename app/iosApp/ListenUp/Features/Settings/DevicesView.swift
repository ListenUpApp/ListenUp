import SwiftUI
@preconcurrency import Shared

/// The Devices screen — lists the user's active sessions, lets them sign out individual devices
/// (swipe or context menu, confirmed), and offers a single "Sign Out All Other Devices" action.
///
/// Backed by the shared `DevicesViewModel` (via `DevicesObserver`), rendered as a system
/// inset-grouped `List`.
struct DevicesView: View {
    @Environment(\.dependencies) private var deps

    @State private var observer: DevicesObserver?
    @State private var showSignOutAllConfirmation = false
    /// The device whose sign-out is waiting on confirmation — ending another device's session also
    /// removes its downloads, which it can't get back without signing in again.
    @State private var pendingRevoke: RevokeTarget?

    var body: some View {
        Group {
            if let observer {
                content(observer: observer)
            } else {
                LoadingStateView()
            }
        }
        .background(Color.luSurface)
        .navigationTitle(String(localized: "devices.title"))
        .navigationBarTitleDisplayMode(.large)
        .onAppear {
            if observer == nil {
                observer = DevicesObserver(viewModel: deps.createDevicesViewModel())
            }
        }
        .confirmationDialog(
            String(localized: "devices.sign_out_everywhere"),
            isPresented: $showSignOutAllConfirmation,
            titleVisibility: .visible
        ) {
            Button(String(localized: "devices.sign_out_everywhere"), role: .destructive) {
                observer?.signOutEverywhere(onDone: {
                    // Clearing auth tokens routes the app back to login via AuthStateObserver.
                    Task {
                        do {
                            try await deps.authSession.clearAuthTokens()
                        } catch is CancellationError {
                        } catch {
                            Log.error("clearAuthTokens failed after sign-out-everywhere", error: error)
                        }
                    }
                })
            }
            Button(String(localized: "common.cancel"), role: .cancel) {}
        } message: {
            Text(String(localized: "devices.sign_out_everywhere_confirm"))
        }
    }

    // MARK: - Phase routing

    @ViewBuilder
    private func content(observer: DevicesObserver) -> some View {
        switch observer.phase {
        case .loading:
            LoadingStateView()
        case .error(let message):
            ContentUnavailableView {
                Label(String(localized: "common.something_went_wrong"), systemImage: "exclamationmark.triangle")
            } description: {
                Text(message)
            } actions: {
                Button(String(localized: "common.retry")) { observer.retry() }
            }
        case .ready(let devices, let signingOut):
            readyBody(observer: observer, devices: devices, signingOut: signingOut)
        }
    }

    /// A real inset-grouped `List`, not hand-drawn cards: `.swipeActions` only fires inside a `List`,
    /// and signing out a single device used to live on a swipe that could never trigger. The swipe
    /// is a shortcut; the row's context menu is the path everyone can find. HIG, Lists and tables;
    /// Gestures ("Use shortcut gestures to supplement standard gestures, not replace them").
    /// The system list is width-responsive on its own (readable margins on iPad), so the old
    /// two-pane iPad HStack goes with the cards.
    private func readyBody(observer: DevicesObserver, devices: [DeviceRow], signingOut: Set<String>) -> some View {
        let currentDevice = devices.first { $0.isCurrent }
        let otherDevices = devices.filter { !$0.isCurrent }

        return List {
            if let current = currentDevice {
                Section(String(localized: "devices.this_device")) {
                    thisDeviceRow(current)
                }
            }

            if !otherDevices.isEmpty {
                Section {
                    ForEach(otherDevices, id: \.sessionId) { device in
                        otherDeviceRow(device, signingOut: signingOut)
                    }
                } header: {
                    Text(String(localized: "devices.other_devices"))
                } footer: {
                    Text(String(localized: "devices.note_sign_out_effect"))
                }

                Section {
                    Button(role: .destructive) {
                        showSignOutAllConfirmation = true
                    } label: {
                        Label(String(localized: "devices.sign_out_all_others"), systemImage: "iphone.slash")
                    }
                }
            } else if currentDevice != nil {
                Section(String(localized: "devices.other_devices")) {
                    Text(String(localized: "devices.empty"))
                        .foregroundStyle(Color.luLabel2)
                }
            }
        }
        .listStyle(.insetGrouped)
        .confirmationDialog(
            String(localized: "devices.sign_out_device"),
            isPresented: revokeConfirmationPresented,
            titleVisibility: .visible,
            presenting: pendingRevoke
        ) { target in
            Button(String(localized: "devices.sign_out"), role: .destructive) {
                observer.revokeDevice(target.sessionId)
                pendingRevoke = nil
            }
            Button(String(localized: "common.cancel"), role: .cancel) { pendingRevoke = nil }
        } message: { target in
            Text(target.displayName)
        }
    }

    // MARK: - This Device

    @ViewBuilder
    private func thisDeviceRow(_ device: DeviceRow) -> some View {
        HStack(spacing: 14) {
            IconTile(
                systemImage: deviceIcon(for: device.secondary),
                tint: .blue,
                size: 50
            )
            VStack(alignment: .leading, spacing: 3) {
                HStack(spacing: 8) {
                    Text(device.displayName)
                        .font(.body.weight(.semibold))
                        .foregroundStyle(.primary)
                    // "THIS DEVICE" badge
                    Text(String(localized: "devices.this_device").uppercased())
                        .font(.system(size: 10.5, weight: .bold))
                        .foregroundStyle(Color.luTint)
                        .padding(.horizontal, 8)
                        .padding(.vertical, 3)
                        .background(Color.luTint.opacity(0.14), in: Capsule())
                }
                if !device.secondary.isEmpty {
                    Text(device.secondary)
                        .font(.footnote)
                        .foregroundStyle(Color.luLabel2)
                }
            }
            Spacer(minLength: 8)
            // Active indicator
            HStack(spacing: 5) {
                Circle()
                    .fill(Color.green)
                    .frame(width: 7, height: 7)
                Text(String(localized: "devices.active"))
                    .font(.footnote.weight(.semibold))
                    .foregroundStyle(Color(red: 0.12, green: 0.54, blue: 0.31))
            }
        }
        .padding(.vertical, 6)
    }

    // MARK: - Other Devices

    @ViewBuilder
    private func otherDeviceRow(_ device: DeviceRow, signingOut: Set<String>) -> some View {
        let target = RevokeTarget(sessionId: device.sessionId, displayName: device.displayName)
        HStack(spacing: 13) {
            IconTile(
                systemImage: deviceIcon(for: device.secondary),
                tint: deviceTint(for: device.secondary),
                size: 44
            )
            VStack(alignment: .leading, spacing: 2) {
                Text(device.displayName)
                    .font(.callout.weight(.semibold))
                    .foregroundStyle(.primary)
                    .lineLimit(1)
                if !device.secondary.isEmpty {
                    Text(device.secondary)
                        .font(.footnote)
                        .foregroundStyle(Color.luLabel2)
                        .lineLimit(1)
                }
            }
            Spacer(minLength: 8)
            if signingOut.contains(device.sessionId) {
                ProgressView()
                    .controlSize(.small)
            } else {
                Text(relativeDate(epochMs: device.lastUsedAt))
                    .font(.footnote)
                    .foregroundStyle(Color.luLabel2)
                    .lineLimit(1)
            }
        }
        .padding(.vertical, 3)
        .swipeActions(edge: .trailing) {
            Button(role: .destructive) {
                pendingRevoke = target
            } label: {
                Label(String(localized: "devices.sign_out"), systemImage: "iphone.slash")
            }
        }
        .contextMenu {
            Button(role: .destructive) {
                pendingRevoke = target
            } label: {
                Label(String(localized: "devices.sign_out"), systemImage: "iphone.slash")
            }
        }
    }

    private var revokeConfirmationPresented: Binding<Bool> {
        Binding(get: { pendingRevoke != nil }, set: { if !$0 { pendingRevoke = nil } })
    }

    // MARK: - Helpers

    /// Heuristic: map a device's secondary descriptor to an SF Symbol icon name.
    private func deviceIcon(for secondary: String) -> String {
        let lower = secondary.lowercased()
        if lower.contains("ipad") { return "ipad" }
        if lower.contains("iphone") || lower.contains("ios") { return "iphone.gen3" }
        if lower.contains("mac") || lower.contains("desktop") { return "laptopcomputer" }
        if lower.contains("web") || lower.contains("safari") || lower.contains("chrome") {
            return "globe"
        }
        return "ipad.and.iphone"
    }

    /// Tint palette for device icons — rotates through a small set of brand-adjacent blues.
    private func deviceTint(for secondary: String) -> Color {
        let lower = secondary.lowercased()
        if lower.contains("ipad") { return Color(red: 0.48, green: 0.35, blue: 0.97) }
        if lower.contains("mac") || lower.contains("desktop") { return .blue }
        if lower.contains("web") || lower.contains("safari") || lower.contains("chrome") { return .teal }
        return Color(red: 0.16, green: 0.54, blue: 0.86)
    }

    /// Formats `epochMs` as a relative date string ("2 hours ago", "Yesterday", etc.).
    private func relativeDate(epochMs: Int64) -> String {
        let date = Date(timeIntervalSince1970: Double(epochMs) / 1_000)
        let formatter = RelativeDateTimeFormatter()
        formatter.unitsStyle = .full
        return formatter.localizedString(for: date, relativeTo: Date())
    }
}

/// A native snapshot of the device awaiting sign-out, so the confirmation never holds a bridged row.
private struct RevokeTarget: Equatable {
    let sessionId: String
    let displayName: String
}

// MARK: - Preview

#Preview {
    NavigationStack {
        DevicesView()
    }
}
