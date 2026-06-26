import SwiftUI
import Shared

/// Create or edit a shelf. `shelfId == nil` means create mode.
///
/// Presented as a sheet with `EditSheetScaffold` chrome (Cancel / Done bar).
/// Seeds local `@State` text fields once from `.loaded(...)` when entering edit
/// mode — the inputs are Swift-owned so they survive transient state transitions.
struct CreateEditShelfView: View {
    let shelfId: String?

    @Environment(\.dependencies) private var deps
    @Environment(\.dismiss) private var dismiss

    @State private var observer: CreateEditShelfObserver?
    @State private var name: String = ""
    @State private var description: String = ""
    @State private var isPrivate: Bool = false
    @State private var seeded: Bool = false
    @State private var showDeleteConfirmation: Bool = false

    private var isEditMode: Bool { shelfId != nil }
    private var isSaving: Bool { observer?.phase == .saving }
    private var trimmedName: String { name.trimmingCharacters(in: .whitespacesAndNewlines) }
    private var canSave: Bool { !trimmedName.isEmpty && observer?.phase != .saving }

    var body: some View {
        Group {
            if let observer {
                EditSheetScaffold(
                    title: isEditMode
                        ? String(localized: "shelf.edit_shelf_title")
                        : String(localized: "shelf.create_shelf_title"),
                    canSave: canSave,
                    isSaving: isSaving,
                    onCancel: { dismiss() },
                    onSave: {
                        observer.save(
                            name: trimmedName,
                            description: description.trimmingCharacters(in: .whitespacesAndNewlines),
                            isPrivate: isPrivate
                        )
                    }
                ) {
                    formContent(observer)
                }
                .alert(
                    String(localized: "common.error"),
                    isPresented: Binding(
                        get: {
                            if case .error = observer.phase { return true }
                            return false
                        },
                        set: { if !$0 { observer.dismissError() } }
                    )
                ) {
                    Button(String(localized: "common.ok"), role: .cancel) { observer.dismissError() }
                } message: {
                    if case .error(let msg) = observer.phase {
                        Text(msg)
                    }
                }
                .confirmationDialog(
                    String(localized: "shelf.delete_shelf"),
                    isPresented: $showDeleteConfirmation,
                    titleVisibility: .visible
                ) {
                    Button(String(localized: "shelf.delete_shelf"), role: .destructive) {
                        observer.delete()
                    }
                    Button(String(localized: "common.cancel"), role: .cancel) {}
                } message: {
                    Text(String(localized: "shelf.this_will_permanently_delete_this"))
                }
                .onChange(of: observer.phase) { _, phase in
                    if case .loaded(let loadedName, let loadedDesc, let loadedPrivate) = phase, !seeded {
                        name = loadedName
                        description = loadedDesc
                        isPrivate = loadedPrivate
                        seeded = true
                    }
                }
            } else {
                LoadingStateView()
            }
        }
        .task(id: shelfId) {
            let obs = CreateEditShelfObserver(viewModel: deps.createCreateEditShelfViewModel())
            obs.onClose = { dismiss() }
            observer = obs
            if let id = shelfId {
                obs.prepareEdit(shelfId: id)
            } else {
                obs.prepareCreate()
            }
        }
        .onDisappear {
            // Release the observer; its deinit cancels the FlowBridge subscriptions.
            observer = nil
        }
    }

    // MARK: - Form content

    @ViewBuilder
    private func formContent(_ observer: CreateEditShelfObserver) -> some View {
        switch observer.phase {
        case .loadingExisting:
            LoadingStateView()
        default:
            formFields()
        }
    }

    @ViewBuilder
    private func formFields() -> some View {
        VStack(spacing: 20) {
            // Name + description
            VStack(alignment: .leading, spacing: 6) {
                Text(String(localized: "shelf.shelf_details"))
                    .font(.footnote.weight(.semibold))
                    .foregroundStyle(Color.luLabel2)
                    .textCase(.uppercase)
                    .padding(.horizontal, 4)

                AppTextField(
                    placeholder: String(localized: "common.shelf_name_hint"),
                    text: $name,
                    label: String(localized: "shelf.form_name"),
                    isLast: false
                )
                AppTextField(
                    placeholder: String(localized: "shelf.whats_this_shelf_for"),
                    text: $description,
                    label: String(localized: "shelf.description_optional"),
                    axis: .vertical,
                    isLast: true
                )
            }
            .fieldCard()

            // Privacy toggle
            privacyRow()

            // Preview card
            previewSection()

            // Delete button (edit mode only)
            if isEditMode {
                Button(role: .destructive) {
                    showDeleteConfirmation = true
                } label: {
                    HStack(spacing: 8) {
                        Image(systemName: "trash")
                            .font(.system(size: 16, weight: .semibold))
                        Text(String(localized: "shelf.delete_shelf"))
                            .font(.body.weight(.medium))
                    }
                    .frame(maxWidth: .infinity)
                    .padding(.vertical, 4)
                }
                .fieldCard()
                .padding(.bottom, 8)
            }
        }
        .padding(.horizontal)
        .readableWidth()
    }

    @ViewBuilder
    private func privacyRow() -> some View {
        HStack(spacing: 13) {
            IconTile(
                systemImage: isPrivate ? "lock.fill" : "globe",
                isActive: isPrivate,
                tint: .luTint,
                size: 32
            )
            VStack(alignment: .leading, spacing: 2) {
                Text(String(localized: "shelf.private_shelf"))
                    .font(.body)
                    .foregroundStyle(.primary)
                Text(
                    isPrivate
                        ? String(localized: "shelf.private_shelf_description")
                        : String(localized: "shelf.visible_to_anyone")
                )
                .font(.caption)
                .foregroundStyle(Color.luLabel2)
            }
            Spacer()
            Toggle("", isOn: $isPrivate)
                .labelsHidden()
                .tint(.green)
        }
        .padding(.horizontal, 14)
        .padding(.vertical, 9)
        .fieldCard()
    }

    @ViewBuilder
    private func previewSection() -> some View {
        VStack(alignment: .leading, spacing: 6) {
            Text(String(localized: "shelf.preview"))
                .font(.footnote.weight(.semibold))
                .foregroundStyle(Color.luLabel2)
                .textCase(.uppercase)
                .padding(.horizontal, 4)

            HStack(spacing: 16) {
                RoundedRectangle(cornerRadius: 12, style: .continuous)
                    .fill(Color.luFill)
                    .frame(width: 56, height: 56)
                    .overlay {
                        Image(systemName: "bookmark")
                            .font(.system(size: 22, weight: .semibold))
                            .foregroundStyle(Color.luLabel3)
                    }

                VStack(alignment: .leading, spacing: 4) {
                    HStack(spacing: 6) {
                        Text(trimmedName.isEmpty ? String(localized: "shelf.form_name") : trimmedName)
                            .font(.headline)
                            .foregroundStyle(trimmedName.isEmpty ? Color.luLabel3 : Color.primary)
                            .lineLimit(1)
                        if isPrivate {
                            Image(systemName: "lock.fill")
                                .font(.caption.weight(.semibold))
                                .foregroundStyle(Color.luLabel2)
                        }
                    }
                    Text(
                        isPrivate
                            ? String(localized: "shelf.private_shelf")
                            : String(localized: "shelf.visibility")
                    )
                    .font(.footnote)
                    .foregroundStyle(Color.luLabel2)
                }
                Spacer()
            }
            .padding(16)
            .fieldCard()
        }
    }
}

#Preview("Create") {
    CreateEditShelfView(shelfId: nil)
}

#Preview("Edit") {
    CreateEditShelfView(shelfId: "preview-shelf-id")
}
