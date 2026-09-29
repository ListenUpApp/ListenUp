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
    /// What the form opened on — empty for a new shelf, the loaded shelf once seeded.
    @State private var openedOn = ShelfDraft(name: "", description: "", isPrivate: false)
    @State private var showDeleteConfirmation: Bool = false

    private var isEditMode: Bool { shelfId != nil }
    private var isSaving: Bool { observer?.phase == .saving }
    private var trimmedName: String { name.trimmingCharacters(in: .whitespacesAndNewlines) }
    private var canSave: Bool { !trimmedName.isEmpty && observer?.phase != .saving }
    private var draft: ShelfDraft { ShelfDraft(name: name, description: description, isPrivate: isPrivate) }
    /// Edit mode has nothing to lose until the shelf has loaded into the form.
    private var hasChanges: Bool { (!isEditMode || seeded) && draft.differs(from: openedOn) }

    var body: some View {
        Group {
            if let observer {
                EditSheetScaffold(
                    title: isEditMode
                        ? String(localized: "shelf.edit_shelf_title")
                        : String(localized: "shelf.create_shelf_title"),
                    hasChanges: hasChanges,
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
                        openedOn = ShelfDraft(name: loadedName, description: loadedDesc, isPrivate: loadedPrivate)
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
            Section {
                LoadingStateView()
                    .frame(minHeight: 200)
                    .listRowBackground(Color.clear)
            }
        default:
            formFields()
        }
    }

    @ViewBuilder
    private func formFields() -> some View {
        Section(String(localized: "shelf.shelf_details")) {
            AppTextField(
                placeholder: String(localized: "common.shelf_name_hint"),
                text: $name,
                entry: .words,
                label: String(localized: "shelf.form_name")
            )
            AppTextField(
                placeholder: String(localized: "shelf.whats_this_shelf_for"),
                text: $description,
                entry: .sentences,
                label: String(localized: "shelf.description_optional"),
                axis: .vertical
            )
        }

        Section {
            privacyRow()
        }

        Section(String(localized: "shelf.preview")) {
            previewRow()
        }

        // Delete (edit mode only): a destructive list button, confirmed before it runs.
        if isEditMode {
            Section {
                Button(role: .destructive) {
                    showDeleteConfirmation = true
                } label: {
                    Label(String(localized: "shelf.delete_shelf"), systemImage: "trash")
                        .font(.body.weight(.medium))
                        .frame(maxWidth: .infinity)
                }
            }
        }
    }

    private func privacyRow() -> some View {
        Toggle(isOn: $isPrivate) {
            HStack(spacing: 13) {
                IconTile(
                    systemImage: isPrivate ? "lock.fill" : "globe",
                    isActive: isPrivate,
                    size: 32
                )
                .accessibilityHidden(true)
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
                    .foregroundStyle(Color.secondary)
                }
            }
        }
        // No tint of its own: the switch takes the app's accent from the root `.tint`, like every
        // other switch in the app.
    }

    private func previewRow() -> some View {
        HStack(spacing: 16) {
            RoundedRectangle(cornerRadius: Radius.m, style: .continuous)
                .fill(Color.luFill)
                .frame(width: 56, height: 56)
                .overlay {
                    Image(systemName: "bookmark")
                        .font(.system(size: 22, weight: .semibold)) // decorative fixed size
                        .foregroundStyle(.tertiary)
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
                            .foregroundStyle(.secondary)
                    }
                }
                Text(
                    isPrivate
                        ? String(localized: "shelf.private_shelf")
                        : String(localized: "shelf.visibility")
                )
                .font(.footnote)
                .foregroundStyle(.secondary)
            }
            Spacer()
        }
        .padding(.vertical, Spacing.xs)
        .accessibilityElement(children: .combine)
    }
}

#Preview("Create") {
    CreateEditShelfView(shelfId: nil)
}

#Preview("Edit") {
    CreateEditShelfView(shelfId: "preview-shelf-id")
}

/// A shelf form's editable values, compared to tell whether leaving would lose anything.
struct ShelfDraft: Equatable {
    let name: String
    let description: String
    let isPrivate: Bool

    func differs(from other: ShelfDraft) -> Bool { self != other }
}
