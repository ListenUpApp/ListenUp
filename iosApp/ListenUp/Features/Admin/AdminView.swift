import SwiftUI
import UIKit
@preconcurrency import Shared

/// Administration — the native server-management dashboard, wired to two shared ViewModels:
/// `AdminViewModel` (users, pending registrations, pending invites, open-registration) via
/// `AdminObserver`, and `AdminSettingsViewModel` (server name + remote URL) via
/// `AdminSettingsObserver`.
///
/// Sections: **Server** (name + remote URL fields with a dirty-gated Save; an open-registration
/// toggle), **Users** (active users with role badges + delete, an Invite button, plus pending
/// registrations and pending invites when present), and **Management** (Invite Someone — the only
/// row with a native destination today).
///
/// Layout is width-responsive (iosApp rule 12): a single readable column on iPhone; on iPad and
/// wide split views the Server + Users column sits beside the Management column. Transient
/// mutation errors surface as a native alert; destructive actions (delete user, revoke invite,
/// deny registration) go through a confirmation dialog.
struct AdminView: View {
    @Environment(\.dependencies) private var deps
    @Environment(\.horizontalSizeClass) private var horizontalSizeClass

    @State private var admin: AdminObserver?
    @State private var settings: AdminSettingsObserver?
    @State private var showingInviteSheet = false
    @State private var pendingDelete: AdminUserRowModel?
    @State private var pendingRevoke: AdminInviteRowModel?
    @State private var pendingDeny: AdminUserRowModel?
    @State private var copiedToast = false

    private var isRegularWidth: Bool { horizontalSizeClass == .regular }

    var body: some View {
        Group {
            if let admin, let settings {
                content(admin: admin, settings: settings)
            } else {
                LoadingStateView()
            }
        }
        .background(Color.luSurface)
        .navigationTitle(String(localized: "common.administration"))
        .navigationBarTitleDisplayMode(.large)
        .toolbar { saveToolbarItem }
        .onAppear {
            if admin == nil { admin = AdminObserver(viewModel: deps.createAdminViewModel()) }
            if settings == nil { settings = AdminSettingsObserver(viewModel: deps.createAdminSettingsViewModel()) }
        }
        .onDisappear {
            admin?.stopObserving()
            settings?.stopObserving()
        }
        .sheet(isPresented: $showingInviteSheet) {
            CreateInviteView(viewModel: deps.createCreateInviteViewModel())
        }
        .alert(item: alertBinding) { alert in
            mutationAlert(alert)
        }
        .confirmationDialog(
            confirmationTitle,
            isPresented: confirmationPresented,
            titleVisibility: .visible
        ) {
            confirmationButtons
        } message: {
            confirmationMessage
        }
        .overlay(alignment: .bottom) {
            if copiedToast {
                CopiedToast(text: String(localized: "admin.link_copied"))
                    .padding(.bottom, 24)
                    .transition(.move(edge: .bottom).combined(with: .opacity))
            }
        }
    }

    // MARK: - Content

    @ViewBuilder
    private func content(admin: AdminObserver, settings: AdminSettingsObserver) -> some View {
        switch admin.phase {
        case .loading:
            LoadingStateView()
        case .error(let message):
            ContentUnavailableView {
                Label(String(localized: "common.something_went_wrong"), systemImage: "exclamationmark.triangle")
            } description: {
                Text(message)
            } actions: {
                Button(String(localized: "common.retry")) { admin.reload() }
            }
        case .ready(let ready):
            readyBody(admin: admin, settings: settings, ready: ready)
        }
    }

    @ViewBuilder
    private func readyBody(admin: AdminObserver, settings: AdminSettingsObserver, ready: AdminReadyModel) -> some View {
        ScrollView {
            if isRegularWidth {
                // iPad / wide: Server + Users beside Management (improvement over the phone-first mockup).
                HStack(alignment: .top, spacing: 28) {
                    VStack(spacing: 26) {
                        serverSection(settings: settings, admin: admin, ready: ready)
                        usersColumn(admin: admin, ready: ready)
                    }
                    .frame(maxWidth: .infinity, alignment: .top)
                    VStack(spacing: 26) {
                        managementSection()
                    }
                    .frame(maxWidth: .infinity, alignment: .top)
                }
                .padding(.horizontal, 32)
                .padding(.vertical, 16)
            } else {
                VStack(spacing: 26) {
                    serverSection(settings: settings, admin: admin, ready: ready)
                    usersColumn(admin: admin, ready: ready)
                    managementSection()
                }
                .padding(.horizontal, 20)
                .padding(.vertical, 12)
                .readableWidth(640)
            }
        }
        .refreshable {
            admin.reload()
            settings.reload()
        }
    }

    // MARK: - Server section

    @ViewBuilder
    private func serverSection(
        settings: AdminSettingsObserver,
        admin: AdminObserver,
        ready: AdminReadyModel
    ) -> some View {
        VStack(alignment: .leading, spacing: 0) {
            AdminSectionHeader(String(localized: "admin.server_settings"))
            serverFields(settings: settings)
            if case .ready(let model) = settings.phase, let error = model.error {
                ErrorBanner(message: error)
                    .padding(.top, 10)
            }
            Spacer().frame(height: 14)
            ToggleRow(
                systemImage: "person.badge.plus",
                tint: .green,
                title: String(localized: "admin.open_registration"),
                subtitle: String(localized: "admin.allow_anyone_to_request_an"),
                isOn: openRegistrationBinding(admin: admin, ready: ready),
                isBusy: ready.isTogglingOpenRegistration
            )
            .fieldCard()
        }
    }

    @ViewBuilder
    private func serverFields(settings: AdminSettingsObserver) -> some View {
        let model: AdminSettingsReadyModel? = {
            if case .ready(let model) = settings.phase { return model }
            return nil
        }()
        VStack(spacing: 0) {
            AppTextField(
                placeholder: String(localized: "admin.server_name"),
                text: serverNameBinding(settings: settings, model: model),
                label: String(localized: "admin.server_name"),
                icon: "tag",
                isLast: false
            )
            AppTextField(
                placeholder: String(localized: "admin.remote_url_placeholder"),
                text: remoteUrlBinding(settings: settings, model: model),
                label: String(localized: "admin.remote_url"),
                icon: "globe",
                keyboardType: .URL
            )
        }
        .fieldCard()
    }

    // MARK: - Users column

    @ViewBuilder
    private func usersColumn(admin: AdminObserver, ready: AdminReadyModel) -> some View {
        VStack(alignment: .leading, spacing: 26) {
            usersSection(admin: admin, ready: ready)
            if ready.openRegistration {
                pendingRegistrationsSection(admin: admin, ready: ready)
            }
            if !ready.pendingInvites.isEmpty {
                pendingInvitesSection(admin: admin, ready: ready)
            }
        }
    }

    @ViewBuilder
    private func usersSection(admin: AdminObserver, ready: AdminReadyModel) -> some View {
        VStack(alignment: .leading, spacing: 0) {
            AdminSectionHeader("\(String(localized: "common.users")) · \(ready.users.count)") {
                Button { showingInviteSheet = true } label: {
                    Label(String(localized: "common.invite"), systemImage: "plus")
                        .font(.subheadline.weight(.medium))
                        .foregroundStyle(Color.luTint)
                }
            }
            VStack(spacing: 0) {
                ForEach(Array(ready.users.enumerated()), id: \.element.id) { index, user in
                    if index > 0 { rowSeparator }
                    AdminUserRow(
                        user: user,
                        isDeleting: ready.deletingUserId == user.id,
                        onDelete: { pendingDelete = user }
                    )
                }
            }
            .fieldCard()
        }
    }

    @ViewBuilder
    private func pendingRegistrationsSection(admin: AdminObserver, ready: AdminReadyModel) -> some View {
        VStack(alignment: .leading, spacing: 0) {
            AdminSectionHeader(String(localized: "admin.pending_registrations"))
            if ready.pendingUsers.isEmpty {
                emptyRow(String(localized: "admin.no_pending_registrations"))
            } else {
                VStack(spacing: 0) {
                    ForEach(Array(ready.pendingUsers.enumerated()), id: \.element.id) { index, user in
                        if index > 0 { rowSeparator }
                        AdminPendingUserRow(
                            user: user,
                            isBusy: ready.approvingUserId == user.id || ready.denyingUserId == user.id,
                            onApprove: { admin.approveUser(id: user.id) },
                            onDeny: { pendingDeny = user }
                        )
                    }
                }
                .fieldCard()
            }
        }
    }

    @ViewBuilder
    private func pendingInvitesSection(admin: AdminObserver, ready: AdminReadyModel) -> some View {
        VStack(alignment: .leading, spacing: 0) {
            AdminSectionHeader(String(localized: "admin.pending_invites"))
            VStack(spacing: 0) {
                ForEach(Array(ready.pendingInvites.enumerated()), id: \.element.id) { index, invite in
                    if index > 0 { rowSeparator }
                    AdminInviteRow(
                        invite: invite,
                        isRevoking: ready.revokingInviteId == invite.id,
                        onCopy: { copyToClipboard(invite.url) },
                        onRevoke: { pendingRevoke = invite }
                    )
                }
            }
            .fieldCard()
        }
    }

    // MARK: - Management section

    @ViewBuilder
    private func managementSection() -> some View {
        VStack(alignment: .leading, spacing: 0) {
            AdminSectionHeader(String(localized: "admin.management"))
            // Only Invite Someone has a native destination today; the mockup's Collections /
            // Categories / Unmapped Genres / Backup rows are omitted (no iOS screen yet).
            NavigationActionRow(
                systemImage: "person.2.fill",
                tint: .luTint,
                title: String(localized: "admin.invite_someone"),
                subtitle: String(localized: "admin.share_your_audiobook_library_with"),
                action: { showingInviteSheet = true }
            )
            .fieldCard()
        }
    }

    // MARK: - Shared row chrome

    private var rowSeparator: some View {
        Rectangle()
            .fill(Color.luSeparator)
            .frame(height: 0.5)
            .padding(.leading, 61)
    }

    private func emptyRow(_ text: String) -> some View {
        Text(text)
            .font(.subheadline)
            .foregroundStyle(Color.luLabel2)
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(16)
            .fieldCard()
    }

    // MARK: - Save toolbar

    @ToolbarContentBuilder
    private var saveToolbarItem: some ToolbarContent {
        ToolbarItem(placement: .topBarTrailing) {
            if case .ready(let model) = settings?.phase {
                if model.isSaving {
                    ProgressView()
                } else {
                    Button(String(localized: "admin.save_settings")) { settings?.save() }
                        .fontWeight(.semibold)
                        .disabled(!model.isDirty)
                }
            }
        }
    }

    // MARK: - Bindings

    private func serverNameBinding(
        settings: AdminSettingsObserver,
        model: AdminSettingsReadyModel?
    ) -> Binding<String> {
        Binding(get: { model?.serverName ?? "" }, set: { settings.setServerName($0) })
    }

    private func remoteUrlBinding(
        settings: AdminSettingsObserver,
        model: AdminSettingsReadyModel?
    ) -> Binding<String> {
        Binding(get: { model?.remoteUrl ?? "" }, set: { settings.setRemoteUrl($0) })
    }

    private func openRegistrationBinding(admin: AdminObserver, ready: AdminReadyModel) -> Binding<Bool> {
        Binding(get: { ready.openRegistration }, set: { admin.setOpenRegistration($0) })
    }

    // MARK: - Transient mutation error alert

    /// Bridges the observer's transient `error` string into an `Identifiable` alert payload.
    private var alertBinding: Binding<AdminAlert?> {
        Binding(
            get: {
                guard case .ready(let ready)? = admin?.phase, let message = ready.error else { return nil }
                return AdminAlert(message: message)
            },
            set: { newValue in
                if newValue == nil { admin?.clearError() }
            }
        )
    }

    private func mutationAlert(_ alert: AdminAlert) -> Alert {
        Alert(
            title: Text(String(localized: "common.something_went_wrong")),
            message: Text(alert.message),
            dismissButton: .default(Text(String(localized: "common.ok"))) { admin?.clearError() }
        )
    }

    // MARK: - Destructive confirmation

    private var confirmationPresented: Binding<Bool> {
        Binding(
            get: { pendingDelete != nil || pendingRevoke != nil || pendingDeny != nil },
            set: { presenting in
                if !presenting {
                    pendingDelete = nil
                    pendingRevoke = nil
                    pendingDeny = nil
                }
            }
        )
    }

    private var confirmationTitle: String {
        if pendingDelete != nil { return String(localized: "common.delete") }
        if pendingRevoke != nil { return String(localized: "admin.revoke_invite") }
        if pendingDeny != nil { return String(localized: "admin.deny_registration") }
        return ""
    }

    @ViewBuilder
    private var confirmationButtons: some View {
        if let user = pendingDelete {
            Button(String(localized: "common.delete"), role: .destructive) {
                admin?.deleteUser(id: user.id)
                pendingDelete = nil
            }
        }
        if let invite = pendingRevoke {
            Button(String(localized: "common.revoke"), role: .destructive) {
                admin?.revokeInvite(id: invite.id)
                pendingRevoke = nil
            }
        }
        if let user = pendingDeny {
            Button(String(localized: "common.deny"), role: .destructive) {
                admin?.denyUser(id: user.id)
                pendingDeny = nil
            }
        }
        Button(String(localized: "common.cancel"), role: .cancel) {}
    }

    @ViewBuilder
    private var confirmationMessage: some View {
        if let user = pendingDelete {
            Text(String(format: String(localized: "admin.confirm_delete_item"), user.name))
        } else if pendingRevoke != nil {
            Text(String(localized: "admin.they_wont_be_able_to"))
        } else if let user = pendingDeny {
            Text(String(localized: "admin.confirm_deny_registration") + user.name + "?")
        }
    }

    // MARK: - Clipboard

    private func copyToClipboard(_ url: String) {
        UIPasteboard.general.string = url
        withAnimation { copiedToast = true }
        Task {
            try? await Task.sleep(for: .seconds(1.6))
            withAnimation { copiedToast = false }
        }
    }
}

// MARK: - Alert payload

/// Identifiable wrapper so a transient error string drives `.alert(item:)`.
private struct AdminAlert: Identifiable {
    let message: String
    var id: String { message }
}

// MARK: - Copied toast

/// A small capsule confirmation shown when an invite link is copied.
private struct CopiedToast: View {
    let text: String

    var body: some View {
        HStack(spacing: 7) {
            Image(systemName: "checkmark.circle.fill")
            Text(text)
        }
        .font(.subheadline.weight(.medium))
        .foregroundStyle(.white)
        .padding(.horizontal, 16)
        .padding(.vertical, 10)
        .background(.black.opacity(0.82), in: Capsule())
        .shadow(color: .black.opacity(0.2), radius: 8, y: 3)
    }
}

// MARK: - Preview

#Preview {
    NavigationStack {
        AdminView()
            .environment(CurrentUserObserver())
    }
}
