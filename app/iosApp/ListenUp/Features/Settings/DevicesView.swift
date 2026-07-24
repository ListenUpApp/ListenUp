import SwiftUI
@preconcurrency import Shared

/// The Devices screen — lists the user's active sessions, lets them revoke individual
/// devices (swipe-to-sign-out), and offers a single "Sign Out All Other Devices" action.
///
/// Backed by the shared `DevicesViewModel` (via `DevicesObserver`). Layout is width-responsive
/// (iosApp rule 12): a single readable column on compact; on regular width (iPad / wide split view)
/// the "This Device" card sits beside the "Other Devices" list in a two-pane HStack.
struct DevicesView: View {
    @Environment(\.dependencies) private var deps
    @Environment(\.horizontalSizeClass) private var horizontalSizeClass

    @State private var observer: DevicesObserver?
    @State private var showSignOutAllConfirmation = false

    private var isRegularWidth: Bool { horizontalSizeClass == .regular }

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

    @ViewBuilder
    private func readyBody(observer: DevicesObserver, devices: [DeviceRow], signingOut: Set<String>) -> some View {
        let currentDevice = devices.first { $0.isCurrent }
        let otherDevices = devices.filter { !$0.isCurrent }

        ScrollView {
            if isRegularWidth {
                // iPad / wide split view: "This Device" card beside "Other Devices" list.
                HStack(alignment: .top, spacing: 28) {
                    // Left pane — current device + sign-out-all
                    VStack(alignment: .leading, spacing: 24) {
                        if let current = currentDevice {
                            thisDeviceSection(current)
                        }
                        if !otherDevices.isEmpty {
                            signOutAllSection(observer: observer)
                        }
                    }
                    .frame(maxWidth: .infinity, alignment: .top)

                    // Right pane — other devices list
                    VStack(alignment: .leading, spacing: 24) {
                        if !otherDevices.isEmpty {
                            otherDevicesSection(
                                observer: observer,
                                devices: otherDevices,
                                signingOut: signingOut
                            )
                        } else if currentDevice != nil {
                            emptyOtherDevices
                        }
                    }
                    .frame(maxWidth: .infinity, alignment: .top)
                }
                .padding(.horizontal, 32)
                .padding(.vertical, 16)
            } else {
                // iPhone / compact split view: single scrolling column.
                VStack(alignment: .leading, spacing: 24) {
                    if let current = currentDevice {
                        thisDeviceSection(current)
                    }

                    if !otherDevices.isEmpty {
                        otherDevicesSection(
                            observer: observer,
                            devices: otherDevices,
                            signingOut: signingOut
                        )
                    } else if currentDevice != nil {
                        emptyOtherDevices
                    }

                    if !otherDevices.isEmpty {
                        signOutAllSection(observer: observer)
                    }
                }
                .padding(.horizontal, 20)
                .padding(.vertical, 16)
                .readableWidth(720)
            }
        }
    }

    // MARK: - This Device

    @ViewBuilder
    private func thisDeviceSection(_ device: DeviceRow) -> some View {
        VStack(alignment: .leading, spacing: 8) {
            Text(String(localized: "devices.this_device").uppercased())
                .font(.footnote.weight(.semibold))
                .foregroundStyle(Color.luLabel2)
                .padding(.leading, 4)

            FieldGroup([device], id: \.sessionId, separatorInset: 58) { _ in
                thisDeviceRow(device)
            }
        }
    }

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
        .padding(.horizontal, 14)
        .padding(.vertical, 14)
    }

    // MARK: - Other Devices

    @ViewBuilder
    private func otherDevicesSection(
        observer: DevicesObserver,
        devices: [DeviceRow],
        signingOut: Set<String>
    ) -> some View {
        VStack(alignment: .leading, spacing: 8) {
            Text(String(localized: "devices.other_devices").uppercased())
                .font(.footnote.weight(.semibold))
                .foregroundStyle(Color.luLabel2)
                .padding(.leading, 4)

            FieldGroup(devices, id: \.sessionId, separatorInset: 57) { device in
                otherDeviceRow(device, signingOut: signingOut, observer: observer)
            }

            // Footer note
            HStack(alignment: .top, spacing: 8) {
                Image(systemName: "iphone.gen3")
                    .font(.caption2)
                    .foregroundStyle(Color.luLabel2)
                    .padding(.top, 1)
                Text(String(localized: "devices.note_sign_out_effect"))
                    .font(.footnote)
                    .foregroundStyle(Color.luLabel2)
            }
            .padding(.horizontal, 4)
        }
    }

    @ViewBuilder
    private func otherDeviceRow(_ device: DeviceRow, signingOut: Set<String>, observer: DevicesObserver) -> some View {
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
        .padding(.horizontal, 14)
        .padding(.vertical, 11)
        .swipeActions(edge: .trailing) {
            Button(role: .destructive) {
                observer.revokeDevice(device.sessionId)
            } label: {
                Label(String(localized: "devices.sign_out"), systemImage: "iphone.slash")
            }
        }
    }

    // MARK: - Empty other devices

    private var emptyOtherDevices: some View {
        Text(String(localized: "devices.empty"))
            .font(.footnote)
            .foregroundStyle(Color.luLabel2)
            .padding(.leading, 4)
    }

    // MARK: - Sign Out All

    @ViewBuilder
    private func signOutAllSection(observer: DevicesObserver) -> some View {
        Button {
            showSignOutAllConfirmation = true
        } label: {
            HStack(spacing: 8) {
                Image(systemName: "iphone.slash")
                    .font(.body)
                    .foregroundStyle(.red)
                Text(String(localized: "devices.sign_out_all_others"))
                    .font(.body.weight(.medium))
                    .foregroundStyle(.red)
            }
            .frame(maxWidth: .infinity)
            .padding(.vertical, 13)
            .background(Color.luSurface2)
            .clipShape(RoundedRectangle(cornerRadius: 12, style: .continuous))
        }
        .buttonStyle(.plain)
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

// MARK: - Preview

#Preview {
    NavigationStack {
        DevicesView()
    }
}
