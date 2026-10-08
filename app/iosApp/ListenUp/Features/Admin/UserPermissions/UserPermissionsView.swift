import SwiftUI
import Shared

/// Admin → a user → Permissions: role, preset and toggles, edited as a draft. Once anything changes, Back
/// becomes Cancel and Save appears, so nobody loses changes by going back. Promoting to Admin asks first.
struct UserPermissionsView: View {
    let userId: String

    @Environment(\.dependencies) private var deps
    @Environment(\.dismiss) private var dismiss
    @State private var observer: UserPermissionsObserver?
    @State private var confirmingDiscard = false

    var body: some View {
        Group {
            switch observer?.phase {
            case .none, .loading:
                LoadingStateView()
            case .ready(let ready):
                content(ready)
            case .error(let message):
                ContentUnavailableView(
                    String(localized: "common.error"),
                    systemImage: "exclamationmark.triangle",
                    description: Text(message)
                )
            }
        }
        .navigationTitle(String(localized: "common.permissions"))
        .navigationSubtitle(subtitle)
        .navigationBarTitleDisplayMode(.inline)
        .onAppear {
            if observer == nil {
                observer = UserPermissionsObserver(viewModel: deps.createUserPermissionsViewModel(userId: userId))
            }
        }
    }

    private var subtitle: String {
        if case .ready(let ready) = observer?.phase { return ready.name }
        return ""
    }

    private func content(_ ready: UserPermissionsReadyModel) -> some View {
        Form {
            roleAndPreset(ready)
            if ready.isAdmin {
                adminSummary(ready)
            } else {
                ForEach(ready.sections) { section in
                    permissionSection(section, ready: ready)
                }
            }
            if let error = ready.error {
                Section { Text(error).foregroundStyle(.red) }
            }
        }
        .formStyle(.grouped)
        .navigationBarBackButtonHidden(ready.hasChanges)
        .toolbar { saveToolbar(ready) }
        .confirmationDialog(
            String(localized: "book.edit_unsaved_changes"),
            isPresented: $confirmingDiscard,
            titleVisibility: .visible
        ) {
            Button(String(localized: "common.discard"), role: .destructive) {
                observer?.discard()
                dismiss()
            }
            Button(String(localized: "book.edit_keep_editing"), role: .cancel) {}
        }
        .alert(
            String(format: String(localized: "admin.make_admin_title"), ready.name),
            isPresented: Binding(
                get: { ready.isConfirmingAdminPromotion },
                set: { if !$0 { observer?.cancelAdminPromotion() } }
            )
        ) {
            Button(String(localized: "admin.make_admin_confirm")) { observer?.confirmAdminPromotion() }
            Button(String(localized: "common.cancel"), role: .cancel) { observer?.cancelAdminPromotion() }
        } message: {
            Text(String(localized: "admin.make_admin_body"))
        }
    }

    @ToolbarContentBuilder
    private func saveToolbar(_ ready: UserPermissionsReadyModel) -> some ToolbarContent {
        if ready.hasChanges {
            ToolbarItem(placement: .cancellationAction) {
                Button(String(localized: "common.cancel")) { confirmingDiscard = true }
                    .disabled(ready.isSaving)
            }
            ToolbarItem(placement: .confirmationAction) {
                if ready.isSaving {
                    ProgressView()
                } else {
                    Button(String(localized: "common.save")) { observer?.save() }
                }
            }
        }
    }

    // MARK: - Role and preset

    private func roleAndPreset(_ ready: UserPermissionsReadyModel) -> some View {
        Section {
            if ready.isOwner {
                LabeledContent(String(localized: "common.role"), value: String(localized: "admin.role_owner"))
            } else {
                Picker(
                    String(localized: "common.role"),
                    selection: Binding(get: { ready.role }, set: { observer?.requestRole($0) })
                ) {
                    Text(String(localized: "common.member")).tag(UserRole.member)
                    Text(String(localized: "common.admin")).tag(UserRole.admin)
                }
                .pickerStyle(.menu)
                .disabled(ready.isProtected || ready.isSaving)
            }
            if !ready.isAdmin && ready.presetsShown {
                Picker(
                    String(localized: "admin.preset"),
                    selection: Binding(get: { ready.preset }, set: { observer?.selectPreset($0) })
                ) {
                    ForEach(UserPermissionsReadyModel.pickablePresets, id: \.self) { preset in
                        Text(PermissionLabels.title(preset)).tag(preset)
                    }
                    // Custom is arrived at, never picked: it is listed only to name the current reading.
                    if ready.preset == .custom {
                        Text(PermissionLabels.title(PermissionPreset.custom)).tag(PermissionPreset.custom)
                    }
                }
                .pickerStyle(.menu)
                .disabled(ready.isProtected || ready.isSaving)
            }
        } footer: {
            if !ready.isAdmin && ready.presetsShown {
                Text(PermissionLabels.description(ready.preset))
            }
        }
    }

    // MARK: - Toggles

    private func permissionSection(_ section: PermissionSectionModel, ready: UserPermissionsReadyModel) -> some View {
        Section {
            ForEach(section.rows) { row in
                Toggle(
                    isOn: Binding(
                        get: { row.granted },
                        set: { observer?.setPermission(row.permission, granted: $0) }
                    )
                ) {
                    Text(PermissionLabels.title(row.permission))
                    Text(PermissionLabels.description(row.permission))
                }
                .disabled(ready.isProtected || ready.isSaving)
            }
        } header: {
            Text(PermissionLabels.title(section.group))
        } footer: {
            if ready.curateWarningShown && section.rows.contains(where: { $0.permission == .curateLibrary }) {
                Label {
                    Text(String(format: String(localized: "admin.curate_library_warning"), ready.name))
                        .foregroundStyle(.primary)
                } icon: {
                    Image(systemName: "exclamationmark.triangle.fill")
                        .foregroundStyle(.orange)
                }
            } else if !ready.presetsShown {
                Text(String(localized: "admin.older_server_one_permission"))
            }
        }
    }

    // MARK: - Admin

    private func adminSummary(_ ready: UserPermissionsReadyModel) -> some View {
        Section {
            VStack(spacing: 10) {
                Image(systemName: "checkmark.shield.fill")
                    .font(.largeTitle)
                    .foregroundStyle(.tint)
                    .accessibilityHidden(true)
                Text(String(localized: "admin.admins_can_do_everything"))
                    .font(.headline)
                Text(String(format: String(localized: "admin.admins_can_do_everything_body"), ready.name))
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
            }
            .multilineTextAlignment(.center)
            .frame(maxWidth: .infinity)
            .padding(.vertical, 8)
            .accessibilityElement(children: .combine)
        } footer: {
            if !ready.isOwner {
                Text(String(format: String(localized: "admin.admins_change_role_hint"), ready.name))
            }
        }
    }
}
