import SwiftUI
import Shared

/// Settings › Account › Hardcover: connect with Hardcover's device sign-in, watch it complete, see
/// who you're connected as, when it last synced and which books need a match, disconnect, and
/// reconnect a broken connection.
///
/// A grouped `Form` per phase, with the phase's actions held at the bottom edge as prominent
/// capsule buttons, where a thumb reaches them. Disconnect confirms with an action sheet; Cancel
/// while linking doesn't, since nothing is lost (the code simply stops working).
struct HardcoverSettingsView: View {
    @Environment(\.dependencies) private var deps
    @Environment(\.openURL) private var openURL
    @State private var observer: HardcoverSettingsObserver?
    @State private var showingDisconnectConfirmation = false
    /// The book Find on Hardcover is open for, from the Needs a Match list.
    @State private var matchTarget: HardcoverMatchTarget?

    var body: some View {
        Group {
            if let observer {
                content(observer: observer)
            } else {
                LoadingStateView()
            }
        }
        .background(Color.luSurface)
        .navigationTitle(String(localized: "hardcover.screen_title"))
        .navigationBarTitleDisplayMode(.large)
        .onAppear {
            if observer == nil {
                // Captured here, inside a view update, so the observer holds the action itself rather
                // than reading the environment later from outside the view.
                let openURL = openURL
                observer = HardcoverSettingsObserver(
                    viewModel: deps.createHardcoverSettingsViewModel(),
                    openURL: { openURL($0) }
                )
            }
        }
        .confirmationDialog(
            String(localized: "hardcover.disconnect_confirm_title"),
            isPresented: $showingDisconnectConfirmation,
            titleVisibility: .visible
        ) {
            Button(String(localized: "hardcover.disconnect_confirm_action"), role: .destructive) {
                observer?.disconnect()
            }
            Button(String(localized: "common.cancel"), role: .cancel) {}
        } message: {
            Text(String(localized: "hardcover.disconnect_confirm_body"))
        }
        .messageAlert(alertBinding)
        .sheet(item: $matchTarget) { HardcoverMatchSheet(bookId: $0.bookId) }
    }

    private var alertBinding: Binding<MessageAlert?> {
        Binding(get: { observer?.alert }, set: { observer?.alert = $0 })
    }

    // MARK: - Phase routing

    @ViewBuilder
    private func content(observer: HardcoverSettingsObserver) -> some View {
        Group {
            switch observer.phase {
            case .loading:
                ProgressView(String(localized: "hardcover.loading"))
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
            case .notOffered:
                ContentUnavailableView(String(localized: "hardcover.not_offered"), systemImage: "books.vertical")
            case .notConnected(let failureMessage, let isStarting):
                HardcoverNotConnectedPhase(failureMessage: failureMessage, isStarting: isStarting) {
                    observer.connect()
                }
            case .linking(let linking):
                HardcoverLinkingPhase(
                    model: linking,
                    onOpen: { if let url = linking.pageURL { openURL(url) } },
                    onCancel: { observer.disconnect() }
                )
            case .connected(let connected):
                HardcoverConnectedPhase(
                    model: connected,
                    onSyncNow: { observer.syncNow() },
                    onSetShareMode: { observer.setShareMode($0) },
                    onSendHistory: { observer.sendHistory() },
                    onDismissHistory: { observer.dismissHistory() },
                    onFindMatch: { matchTarget = HardcoverMatchTarget(bookId: $0) },
                    onDisconnect: { showingDisconnectConfirmation = true }
                )
            case .broken(let broken):
                HardcoverBrokenPhase(
                    model: broken,
                    onReconnect: { observer.connect() },
                    onDisconnect: { showingDisconnectConfirmation = true }
                )
            }
        }
        .onChange(of: observer.phase) { old, new in
            if let announcement = HardcoverSettingsObserver.announcement(from: old, to: new) {
                AccessibilityNotification.Announcement(announcement).post()
            }
        }
    }
}

// MARK: - Not connected

private struct HardcoverNotConnectedPhase: View {
    let failureMessage: String?
    let isStarting: Bool
    let onConnect: () -> Void

    var body: some View {
        Form {
            Section {
                HardcoverHero(
                    systemImage: "books.vertical.fill",
                    title: String(localized: "hardcover.not_connected_title"),
                    detail: String(localized: "hardcover.intro")
                )
            }
            if let failureMessage {
                Section {
                    Label(failureMessage, systemImage: "info.circle")
                        .foregroundStyle(.primary)
                }
            }
            Section {
                HardcoverStatementRow(
                    systemImage: "checkmark",
                    text: String(localized: "hardcover.shares_finished"),
                    tint: .luTint
                )
                HardcoverStatementRow(
                    systemImage: "lock",
                    text: String(localized: "hardcover.shares_no_password"),
                    tint: .luTint
                )
            }
        }
        .readableListWidth(720)
        .safeAreaInset(edge: .bottom) {
            HardcoverActions {
                HardcoverPrimaryButton(
                    title: String(localized: "hardcover.connect"),
                    isBusy: isStarting,
                    action: onConnect
                )
            }
        }
    }
}

// MARK: - Linking

private struct HardcoverLinkingPhase: View {
    let model: HardcoverLinkingModel
    let onOpen: () -> Void
    let onCancel: () -> Void

    @State private var copied = false

    var body: some View {
        Form {
            Section {
                VStack(alignment: .leading, spacing: 6) {
                    Text(String(localized: "hardcover.linking_title"))
                        .font(.title3.weight(.semibold))
                        .accessibilityAddTraits(.isHeader)
                    Text(instructions)
                        .font(.body)
                        .foregroundStyle(.secondary)
                }
                .listRowBackground(Color.clear)
                .listRowInsets(EdgeInsets(top: 0, leading: 4, bottom: 0, trailing: 4))
            }
            Section {
                codeCard
            }
            Section {
                HStack(spacing: 12) {
                    ProgressView()
                    VStack(alignment: .leading, spacing: 2) {
                        Text(String(localized: "hardcover.waiting_title"))
                            .font(.headline)
                        Text(String(localized: "hardcover.waiting_detail"))
                            .font(.footnote)
                            .foregroundStyle(.secondary)
                    }
                }
                .padding(.vertical, 2)
                .accessibilityElement(children: .combine)
            }
        }
        .readableListWidth(720)
        .safeAreaInset(edge: .bottom) {
            HardcoverActions {
                HardcoverPrimaryButton(
                    title: String(localized: "hardcover.open_hardcover"),
                    systemImage: "arrow.up.right",
                    action: onOpen
                )
                HardcoverSecondaryButton(title: String(localized: "hardcover.cancel_linking"), action: onCancel)
            }
        }
        .onChange(of: model.userCode) { copied = false }
    }

    /// The instructions, with the address to type set in bold.
    private var instructions: AttributedString {
        let sentence = String(format: String(localized: "hardcover.linking_instructions"), model.address)
        var attributed = AttributedString(sentence)
        if let range = attributed.range(of: model.address) {
            attributed[range].inlinePresentationIntent = .stronglyEmphasized
            attributed[range].foregroundColor = .primary
        }
        return attributed
    }

    private var codeCard: some View {
        VStack(spacing: 12) {
            Text(String(localized: "hardcover.your_code"))
                .font(.footnote.weight(.semibold))
                .foregroundStyle(.secondary)
            Text(model.userCode)
                .font(.system(.largeTitle, design: .monospaced).weight(.semibold))
                .tracking(4)
                .lineLimit(1)
                .minimumScaleFactor(0.5)
                .textSelection(.enabled)
            Button {
                UIPasteboard.general.string = model.userCode
                copied = true
                AccessibilityNotification.Announcement(String(localized: "hardcover.code_copied")).post()
            } label: {
                Label(
                    copied ? String(localized: "hardcover.code_copied") : String(localized: "hardcover.copy_code"),
                    systemImage: copied ? "checkmark" : "doc.on.doc"
                )
                .font(.subheadline.weight(.semibold))
            }
            .buttonStyle(.bordered)
            .buttonBorderShape(.capsule)
            .haptic(.commit, trigger: copied)
        }
        .frame(maxWidth: .infinity)
        .padding(.vertical, Spacing.s)
        .accessibilityElement(children: .contain)
        .accessibilityLabel(String(localized: "hardcover.your_code"))
    }
}

// MARK: - Needs reconnecting

private struct HardcoverBrokenPhase: View {
    let model: HardcoverBrokenModel
    let onReconnect: () -> Void
    let onDisconnect: () -> Void

    var body: some View {
        Form {
            Section {
                VStack(spacing: 12) {
                    HardcoverHero(
                        systemImage: "exclamationmark.arrow.trianglehead.2.clockwise.rotate.90",
                        title: String(localized: "hardcover.broken_title"),
                        detail: model.reasonMessage
                    )
                    if let username = model.username {
                        Text(wasConnectedAs(username))
                            .font(.footnote)
                            .foregroundStyle(.secondary)
                            .multilineTextAlignment(.center)
                    }
                }
            }
        }
        .readableListWidth(720)
        .safeAreaInset(edge: .bottom) {
            HardcoverActions {
                HardcoverPrimaryButton(
                    title: String(localized: "hardcover.reconnect"),
                    isBusy: model.isStarting,
                    action: onReconnect
                )
                HardcoverSecondaryButton(
                    title: String(localized: "hardcover.disconnect"),
                    role: .destructive,
                    action: onDisconnect
                )
            }
        }
    }

    /// "Was connected as simon.", with the name set in bold.
    private func wasConnectedAs(_ username: String) -> AttributedString {
        var attributed = AttributedString(String(format: String(localized: "hardcover.was_connected_as"), username))
        if let range = attributed.range(of: username) {
            attributed[range].inlinePresentationIntent = .stronglyEmphasized
            attributed[range].foregroundColor = .primary
        }
        return attributed
    }
}

// MARK: - Shared pieces

/// A phase's centred lead: a tonal icon tile, a title, and a sentence.
private struct HardcoverHero: View {
    let systemImage: String
    let title: String
    let detail: String

    var body: some View {
        VStack(spacing: 12) {
            IconTile(systemImage: systemImage, tint: .luTint, size: 64)
            Text(title)
                .font(.title2.weight(.bold))
                .accessibilityAddTraits(.isHeader)
            Text(detail)
                .font(.subheadline)
                .foregroundStyle(.secondary)
        }
        .multilineTextAlignment(.center)
        .frame(maxWidth: .infinity)
        .padding(.vertical, Spacing.s)
    }
}

/// One promise about what connecting does, with its glyph.
struct HardcoverStatementRow: View {
    let systemImage: String
    let text: String
    let tint: Color

    var body: some View {
        Label {
            Text(text)
        } icon: {
            Image(systemName: systemImage)
                .foregroundStyle(tint)
        }
    }
}

/// The bottom action stack: the phase's buttons, primary first, over the form.
private struct HardcoverActions<Content: View>: View {
    @ViewBuilder let content: Content

    var body: some View {
        VStack(spacing: 10) {
            content
        }
        .padding(.horizontal, Spacing.l)
        .padding(.top, Spacing.xs)
        .padding(.bottom, Spacing.s)
        .readableWidth()
    }
}

/// The phase's prominent capsule action. Shows a spinner, and ignores taps, while `isBusy`.
private struct HardcoverPrimaryButton: View {
    let title: String
    var systemImage: String?
    var isBusy = false
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            Group {
                if isBusy {
                    ProgressView()
                } else {
                    HStack(spacing: 8) {
                        Text(title)
                        if let systemImage { Image(systemName: systemImage) }
                    }
                }
            }
            .font(.headline)
            .frame(maxWidth: .infinity)
        }
        .buttonStyle(.glassProminent)
        .buttonBorderShape(.capsule)
        .controlSize(.large)
        .onBrandFillLabel()
        .disabled(isBusy)
        .accessibilityLabel(title)
    }
}

/// The quieter capsule action under the primary one: Cancel, or the destructive Disconnect.
private struct HardcoverSecondaryButton: View {
    let title: String
    var role: ButtonRole?
    let action: () -> Void

    var body: some View {
        Button(role: role, action: action) {
            Text(title)
                .font(.headline.weight(role == .destructive ? .regular : .semibold))
                .frame(maxWidth: .infinity)
        }
        .buttonStyle(.glass)
        .buttonBorderShape(.capsule)
        .controlSize(.large)
        .tint(role == .destructive ? Color.red : nil)
    }
}

// MARK: - Preview

#Preview {
    NavigationStack {
        HardcoverSettingsView()
    }
}
