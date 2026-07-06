import SwiftUI
import UIKit
import Shared

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
            RegistrationPolicyCard(
                policy: ready.registrationPolicy,
                isBusy: ready.isTogglingRegistrationPolicy,
                onSelect: { admin.setRegistrationPolicy($0) }
            )
            .fieldCard()
            Spacer().frame(height: 10)
            ToggleRow(
                systemImage: "tray.and.arrow.down",
                tint: .luTint,
                title: String(localized: "admin.inbox_setting_title"),
                subtitle: String(localized: "admin.inbox_setting_subtitle"),
                isOn: inboxEnabledBinding(settings: settings, model: settingsModel(settings))
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
            if ready.registrationPolicy == .approvalQueue {
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
            VStack(spacing: 0) {
                NavigationLink(value: LibrarySettingsDestination()) {
                    NavigationActionRow(
                        systemImage: "externaldrive.fill",
                        tint: .luTint,
                        title: String(localized: "admin.library_settings"),
                        subtitle: String(localized: "admin.library_settings_subtitle")
                    )
                }
                .buttonStyle(.plain)
                rowSeparator
                NavigationLink(value: AdminBackupsDestination()) {
                    NavigationActionRow(
                        systemImage: "archivebox.fill",
                        tint: .luTint,
                        title: String(localized: "admin.backup_restore"),
                        subtitle: String(localized: "admin.create_backups_and_restore_server")
                    )
                }
                .buttonStyle(.plain)
                rowSeparator
                NavigationActionRow(
                    systemImage: "person.2.fill",
                    tint: .luTint,
                    title: String(localized: "admin.invite_someone"),
                    subtitle: String(localized: "admin.share_your_audiobook_library_with"),
                    action: { showingInviteSheet = true }
                )
                rowSeparator
                NavigationLink(value: AdminInboxDestination()) {
                    NavigationActionRow(
                        systemImage: "tray.full",
                        tint: .luTint,
                        title: String(localized: "common.inbox"),
                        subtitle: String(localized: "admin.inbox_setting_subtitle")
                    )
                }
                .buttonStyle(.plain)
                rowSeparator
                NavigationLink(value: AdminCollectionsDestination()) {
                    NavigationActionRow(
                        systemImage: "folder.badge.person.crop",
                        tint: .luTint,
                        title: String(localized: "common.collections"),
                        subtitle: String(localized: "admin.collection_shared_book_sets")
                    )
                }
                .buttonStyle(.plain)
                rowSeparator
                // Pushes the ABS import hub, which launches the import wizard. The mockup's
                // Categories / Unmapped Genres rows are still omitted (no iOS screen yet).
                NavigationLink(value: ABSImportDestination()) {
                    NavigationActionRow(
                        systemImage: "square.and.arrow.down.on.square.fill",
                        tint: .luTint,
                        title: String(localized: "import.title"),
                        subtitle: String(localized: "import.entry_subtitle")
                    )
                }
                .buttonStyle(.plain)
            }
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


    private func settingsModel(_ settings: AdminSettingsObserver) -> AdminSettingsReadyModel? {
        if case .ready(let model) = settings.phase { return model }
        return nil
    }

    private func inboxEnabledBinding(
        settings: AdminSettingsObserver,
        model: AdminSettingsReadyModel?
    ) -> Binding<Bool> {
        Binding(get: { model?.inboxEnabled ?? false }, set: { settings.setInboxEnabled($0) })
    }

    // MARK: - Transient mutation error alert

    /// Bridges the observer's transient `error` string into an `Identifiable` alert payload.
    private var alertBinding: Binding<MessageAlert?> {
        Binding(
            get: {
                guard case .ready(let ready)? = admin?.phase, let message = ready.error else { return nil }
                return MessageAlert(message: message)
            },
            set: { newValue in
                if newValue == nil { admin?.clearError() }
            }
        )
    }

    private func mutationAlert(_ alert: MessageAlert) -> Alert {
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

// MARK: - Registration policy control

/// Three-state registration control (Open / Approval / Closed) backed by the server's
/// `RegistrationPolicy` — a segmented selector, not a boolean switch, so all three states are
/// visible and round-trip correctly. The subtitle reflects the current policy.
private struct RegistrationPolicyCard: View {
    let policy: RegistrationPolicy
    let isBusy: Bool
    let onSelect: (RegistrationPolicy) -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            HStack(spacing: 12) {
                Image(systemName: "person.badge.plus")
                    .foregroundStyle(.green)
                VStack(alignment: .leading, spacing: 2) {
                    Text(String(localized: "admin.registration_policy"))
                        .font(.body.weight(.semibold))
                    Text(Self.subtitle(policy))
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                }
                Spacer()
                if isBusy { ProgressView() }
            }
            Picker("", selection: Binding(get: { policy }, set: { onSelect($0) })) {
                ForEach(Array(RegistrationPolicy.allCases), id: \.self) { option in
                    Text(Self.label(option)).tag(option)
                }
            }
            .pickerStyle(.segmented)
            .disabled(isBusy)
        }
        .padding(.horizontal, 14)
        .padding(.vertical, 11)
    }

    private static func label(_ policy: RegistrationPolicy) -> String {
        switch policy {
        case .open: return String(localized: "admin.registration_policy_open")
        case .approvalQueue: return String(localized: "admin.registration_policy_approval")
        case .closed: return String(localized: "admin.registration_policy_closed")
        }
    }

    private static func subtitle(_ policy: RegistrationPolicy) -> String {
        switch policy {
        case .open: return String(localized: "admin.registration_open_desc")
        case .approvalQueue: return String(localized: "admin.registration_approval_desc")
        case .closed: return String(localized: "admin.registration_closed_desc")
        }
    }
}

// MARK: - Preview

#Preview {
    NavigationStack {
        AdminView()
            .environment(CurrentUserObserver())
    }
}
