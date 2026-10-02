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
/// A grouped `Form` in a readable column at every width (iosApp rule 12). Transient
/// mutation errors surface as a native alert; destructive actions (delete user, revoke invite,
/// deny registration) go through a confirmation dialog.
struct AdminView: View {
    @Environment(\.dependencies) private var deps

    @State private var admin: AdminObserver?
    @State private var settings: AdminSettingsObserver?
    @State private var showingInviteSheet = false
    @State private var pendingDelete: AdminUserRowModel?
    @State private var pendingRevoke: AdminInviteRowModel?
    @State private var pendingDeny: AdminUserRowModel?
    @State private var pendingResetDeny: AdminResetRequestRowModel?
    /// Bumped once per copy, to fire the success haptic.
    @State private var copies = 0

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
        .messageAlert(alertBinding) { admin?.clearError() }
        .confirmationDialog(
            confirmationTitle,
            isPresented: confirmationPresented,
            titleVisibility: .visible
        ) {
            confirmationButtons
        } message: {
            confirmationMessage
        }
        .sheet(isPresented: resetCodePresented) {
            if let admin, case .ready(let ready) = admin.phase, let code = ready.resetCodeToConvey {
                ResetCodeSheet(
                    code: code,
                    recipientName: ready.resetCodeRecipientName,
                    onCopy: { copyToClipboard(code) },
                    onDone: { admin.dismissResetCode() }
                )
                // Only the explicit Done button clears the code — it is shown exactly once and
                // is never retrievable again (AdminViewModel.dismissResetCode contract).
                .interactiveDismissDisabled()
            }
        }
        .haptic(.commit, trigger: copies)
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

    /// One grouped `Form`: every block is a system `Section` with a real header, switches are list
    /// switches, and each management destination is a navigation row the list highlights and marks
    /// with a disclosure indicator. On iPad it keeps a readable column rather than hand-drawn panes.
    /// HIG, Lists and tables.
    @ViewBuilder
    private func readyBody(admin: AdminObserver, settings: AdminSettingsObserver, ready: AdminReadyModel) -> some View {
        Form {
            serverSection(settings: settings, admin: admin, ready: ready)
            if let model = settingsModel(settings), !model.ratingSources.isEmpty {
                ratingSourcesSection(model: model, settings: settings)
            }
            if let hardcover = settingsModel(settings)?.hardcover {
                HardcoverSourceSection(
                    model: hardcover,
                    onSave: { settings.saveHardcoverApiToken($0) },
                    onRemove: { settings.removeHardcoverApiToken() },
                    onMetadataEnabledChange: { settings.setHardcoverMetadataEnabled($0) },
                    onClearError: { settings.clearHardcoverTokenError() }
                )
            }
            usersSection(admin: admin, ready: ready)
            if ready.registrationPolicy == .approvalQueue {
                pendingRegistrationsSection(admin: admin, ready: ready)
            }
            passwordResetsSection(admin: admin, ready: ready)
            if !ready.pendingInvites.isEmpty {
                pendingInvitesSection(admin: admin, ready: ready)
            }
            managementSection(settings: settings)
        }
        .readableListWidth(720)
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
        let model = settingsModel(settings)
        Section {
            AppTextField(
                placeholder: String(localized: "admin.server_name"),
                text: serverNameBinding(settings: settings, model: model),
                entry: .words,
                label: String(localized: "admin.server_name"),
                icon: "tag"
            )
            AppTextField(
                placeholder: String(localized: "admin.remote_url_placeholder"),
                text: remoteUrlBinding(settings: settings, model: model),
                entry: .url,
                label: String(localized: "admin.remote_url"),
                icon: "globe"
            )
        } header: {
            Text(String(localized: "admin.server_settings"))
        } footer: {
            if let error = model?.error {
                ErrorBanner(message: error)
            }
        }

        Section {
            RegistrationPolicyRow(
                policy: ready.registrationPolicy,
                isBusy: ready.isTogglingRegistrationPolicy,
                onSelect: { admin.setRegistrationPolicy($0) }
            )
            ToggleRow(
                systemImage: "tray.and.arrow.down",
                title: String(localized: "admin.inbox_setting_title"),
                subtitle: String(localized: "admin.inbox_setting_subtitle"),
                isOn: holdNewBooksForReviewBinding(settings: settings, model: model)
            )
            ToggleRow(
                systemImage: "bell.badge",
                title: String(localized: "admin.push_setting_title"),
                subtitle: String(localized: "admin.push_setting_subtitle"),
                isOn: pushNotificationsEnabledBinding(settings: settings, model: model)
            )
        }
    }

    // MARK: - Rating sources section

    /// "Rating sources": one row per outside catalog the server can fetch a book's rating from, a
    /// switch to enable/disable it, and a health line saying why it cannot run, until when it has
    /// paused itself, or how its last fetch went (plus, for Hardcover, whose account it uses).
    /// Switching a source off hides its scores at once everywhere; switching it back on brings them
    /// back. The switch stays operable even for a source that cannot run.
    @ViewBuilder
    private func ratingSourcesSection(model: AdminSettingsReadyModel, settings: AdminSettingsObserver) -> some View {
        Section {
            ForEach(model.ratingSources, id: \.id) { row in
                ToggleRow(
                    systemImage: "star.fill",
                    title: row.source.displayName,
                    subtitle: row.subtitle(),
                    isOn: Binding(
                        get: { row.enabled },
                        set: { settings.setRatingSourceEnabled(row.source, $0) }
                    )
                )
            }
        } header: {
            Text(String(localized: "admin.rating_sources_title"))
        } footer: {
            Text(String(localized: "admin.rating_sources_hint"))
        }
    }

    // MARK: - Users

    @ViewBuilder
    private func usersSection(admin: AdminObserver, ready: AdminReadyModel) -> some View {
        Section {
            ForEach(ready.users, id: \.id) { user in
                // Tapping the row opens the user's detail (permissions incl. Can Share); the
                // trailing menu keeps its own tap. Parity with Android's tappable rows.
                NavigationLink(value: UserDetailDestination(userId: user.id)) {
                    AdminUserRow(
                        user: user,
                        isDeleting: ready.deletingUserId == user.id,
                        onDelete: { pendingDelete = user }
                    )
                }
                // A shortcut beside the row's visible menu (HIG, Gestures).
                .swipeActions {
                    if !user.isProtected && ready.deletingUserId != user.id {
                        Button(String(localized: "common.delete"), role: .destructive) { pendingDelete = user }
                    }
                }
            }
        } header: {
            AdminSectionHeader("\(String(localized: "common.users")) · \(ready.users.count)") {
                Button { showingInviteSheet = true } label: {
                    Label(String(localized: "common.invite"), systemImage: "plus")
                }
            }
        }
    }

    @ViewBuilder
    private func pendingRegistrationsSection(admin: AdminObserver, ready: AdminReadyModel) -> some View {
        Section(String(localized: "admin.pending_registrations")) {
            if ready.pendingUsers.isEmpty {
                emptyRow(String(localized: "admin.no_pending_registrations"))
            } else {
                ForEach(ready.pendingUsers, id: \.id) { user in
                    AdminPendingUserRow(
                        user: user,
                        isBusy: ready.approvingUserId == user.id || ready.denyingUserId == user.id,
                        onApprove: { admin.approveUser(id: user.id) },
                        onDeny: { pendingDeny = user }
                    )
                }
            }
        }
    }

    @ViewBuilder
    private func passwordResetsSection(admin: AdminObserver, ready: AdminReadyModel) -> some View {
        Section(String(localized: "admin.password_resets")) {
            if ready.pendingPasswordResets.isEmpty {
                emptyRow(String(localized: "admin.no_pending_password_resets"))
            } else {
                ForEach(ready.pendingPasswordResets, id: \.id) { request in
                    AdminPendingUserRow(
                        user: AdminUserRowModel(
                            id: request.id,
                            name: request.name,
                            email: request.email,
                            roleLabel: "",
                            isRootBadge: false,
                            isProtected: false
                        ),
                        isBusy: ready.decidingPasswordResetId == request.id,
                        onApprove: { admin.decidePasswordReset(id: request.id, approved: true) },
                        onDeny: { pendingResetDeny = request }
                    )
                }
            }
        }
    }

    @ViewBuilder
    private func pendingInvitesSection(admin: AdminObserver, ready: AdminReadyModel) -> some View {
        Section(String(localized: "admin.pending_invites")) {
            ForEach(ready.pendingInvites, id: \.id) { invite in
                AdminInviteRow(
                    invite: invite,
                    isRevoking: ready.revokingInviteId == invite.id,
                    onCopy: { copyToClipboard(invite.url) },
                    onRevoke: { pendingRevoke = invite }
                )
            }
        }
    }

    // MARK: - Management section

    @ViewBuilder
    private func managementSection(settings: AdminSettingsObserver) -> some View {
        Section(String(localized: "admin.management")) {
            NavigationLink(value: LibrarySettingsDestination()) {
                NavigationActionRow(
                    systemImage: "externaldrive.fill",
                    title: String(localized: "admin.library_settings"),
                    subtitle: String(localized: "admin.library_settings_subtitle")
                )
            }
            NavigationLink(value: OrganizeSettingsDestination()) {
                NavigationActionRow(
                    systemImage: "folder.badge.gearshape",
                    title: String(localized: "admin.organize"),
                    subtitle: String(localized: "admin.organize_subtitle")
                )
            }
            NavigationLink(value: UploadBooksDestination()) {
                NavigationActionRow(
                    systemImage: "square.and.arrow.up.on.square.fill",
                    title: String(localized: "admin.upload_books"),
                    subtitle: String(localized: "admin.upload_books_subtitle")
                )
            }
            NavigationLink(value: AdminBackupsDestination()) {
                NavigationActionRow(
                    systemImage: "archivebox.fill",
                    title: String(localized: "admin.backup_restore"),
                    subtitle: String(localized: "admin.create_backups_and_restore_server")
                )
            }
            NavigationActionRow(
                systemImage: "person.2.fill",
                title: String(localized: "admin.invite_someone"),
                subtitle: String(localized: "admin.share_your_audiobook_library_with"),
                action: { showingInviteSheet = true }
            )
            // Unconditional, matching Android. Hold-for-review governs whether *healthy*
            // books wait here; a folder the scanner could not import lands here regardless,
            // so the way in must not depend on a setting the admin may never have turned on.
            NavigationLink(value: AdminInboxDestination()) {
                NavigationActionRow(
                    systemImage: "tray.full",
                    title: String(localized: "common.inbox"),
                    subtitle: String(localized: "admin.inbox_subtitle")
                )
            }
            NavigationLink(value: AdminCollectionsDestination()) {
                NavigationActionRow(
                    systemImage: "folder.badge.person.crop",
                    title: String(localized: "common.collections"),
                    subtitle: String(localized: "admin.collection_shared_book_sets")
                )
            }
            NavigationLink(value: AdminCategoriesDestination()) {
                NavigationActionRow(
                    systemImage: "tag.fill",
                    title: String(localized: "common.categories"),
                    subtitle: String(localized: "admin.view_the_genre_hierarchy_tree")
                )
            }
            // Pushes the ABS import hub, which launches the import wizard. The mockup's
            // Unmapped Genres row is still omitted (no iOS screen yet).
            NavigationLink(value: ABSImportDestination()) {
                NavigationActionRow(
                    systemImage: "square.and.arrow.down.on.square.fill",
                    title: String(localized: "import.title"),
                    subtitle: String(localized: "import.entry_subtitle")
                )
            }
        }
    }

    // MARK: - Shared row chrome

    private func emptyRow(_ text: String) -> some View {
        Text(text)
            .font(.subheadline)
            .foregroundStyle(.secondary)
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

    private func holdNewBooksForReviewBinding(
        settings: AdminSettingsObserver,
        model: AdminSettingsReadyModel?
    ) -> Binding<Bool> {
        Binding(get: { model?.holdNewBooksForReview ?? false }, set: { settings.setHoldNewBooksForReview($0) })
    }

    private func pushNotificationsEnabledBinding(
        settings: AdminSettingsObserver,
        model: AdminSettingsReadyModel?
    ) -> Binding<Bool> {
        Binding(get: { model?.pushNotificationsEnabled ?? false }, set: { settings.setPushNotificationsEnabled($0) })
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

    /// Presents the one-time reset-code sheet whenever the VM surfaces a code. Setting `false`
    /// is ignored — dismissal happens only through `dismissResetCode()` (the Done button), so a
    /// swipe or stray dismissal can never lose an unread code.
    private var resetCodePresented: Binding<Bool> {
        Binding(
            get: {
                guard case .ready(let ready)? = admin?.phase else { return false }
                return ready.resetCodeToConvey != nil
            },
            set: { _ in }
        )
    }

    // MARK: - Destructive confirmation

    private var confirmationPresented: Binding<Bool> {
        Binding(
            get: { pendingDelete != nil || pendingRevoke != nil || pendingDeny != nil || pendingResetDeny != nil },
            set: { presenting in
                if !presenting {
                    pendingDelete = nil
                    pendingRevoke = nil
                    pendingDeny = nil
                    pendingResetDeny = nil
                }
            }
        )
    }

    private var confirmationTitle: String {
        if pendingDelete != nil { return String(localized: "common.delete") }
        if pendingRevoke != nil { return String(localized: "admin.revoke_invite") }
        if pendingDeny != nil { return String(localized: "admin.deny_registration") }
        if pendingResetDeny != nil { return String(localized: "admin.deny_reset") }
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
        if let request = pendingResetDeny {
            Button(String(localized: "common.deny"), role: .destructive) {
                admin?.decidePasswordReset(id: request.id, approved: false)
                pendingResetDeny = nil
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
        } else if let request = pendingResetDeny {
            Text(request.name)
        }
    }

    // MARK: - Clipboard

    /// A copy changes nothing on screen, so it is confirmed by a success haptic and a VoiceOver
    /// announcement rather than a toast (HIG, Feedback; iosApp rule 10).
    private func copyToClipboard(_ url: String) {
        UIPasteboard.general.string = url
        copies += 1
        VoiceOverAnnouncement.post(String(localized: "admin.link_copied"))
    }
}

// MARK: - Registration policy control

/// Three-state registration control (Open / Approval / Closed) backed by the server's
/// `RegistrationPolicy` — a segmented selector, not a boolean switch, so all three states are
/// visible and round-trip correctly. The subtitle reflects the current policy. A `Form` row.
private struct RegistrationPolicyRow: View {
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
        .padding(.vertical, Spacing.xxs)
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
