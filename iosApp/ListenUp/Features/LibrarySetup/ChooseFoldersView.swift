import SwiftUI
@preconcurrency import Shared

/// The folder-picker step of first-run library setup. The user browses the server's
/// filesystem and chooses the folders that hold their audiobooks.
///
/// Two row kinds are made visually unmistakable (the whole point of this screen):
/// a folder with subfolders is **navigable** (chevron, tap drills in) and a leaf folder
/// is **selectable** (checkmark, tap toggles inclusion). A parent that only contains
/// subfolders can still be picked wholesale via the empty-state "Select this folder".
///
/// The view owns no business logic — it binds the shared ``LibrarySetupViewModelWrapper``,
/// which the setup coordinator (Task 5) constructs once and threads through the flow.
/// Errors surface as a native alert; navigation is driven by the wrapper's callbacks.
struct ChooseFoldersView: View {

    /// The shared wrapper, owned by the coordinator. `@Bindable` so the inline
    /// library-name field can two-way bind `libraryName`.
    @Bindable var viewModel: LibrarySetupViewModelWrapper

    /// True once at least one leaf folder (or the current folder, via "Select this
    /// folder") has been chosen — the gate for creating the library. Reads the wrapper's
    /// total selection set, which spans every visited directory, not just the visible rows.
    private var hasSelection: Bool { viewModel.hasSelection }

    private var selectionCount: Int { viewModel.selectionCount }

    var body: some View {
        AuthScaffold {
            header
            pathBar
            directorySection
            nameSection
        } footer: {
            selectionSummary
            AuthPrimaryButton(
                title: String(localized: "library_setup.create_library"),
                isLoading: viewModel.isCreatingLibrary
            ) {
                viewModel.create()
            }
            .disabled(!hasSelection || viewModel.isCreatingLibrary)
        }
        .onAppear { viewModel.checkStatus() }
        .alert(
            String(localized: "common.something_went_wrong"),
            isPresented: errorBinding,
            presenting: viewModel.errorMessage
        ) { _ in
            Button(String(localized: "common.ok"), role: .cancel) { viewModel.dismissError() }
        } message: { message in
            Text(message)
        }
    }

    // MARK: - Header

    private var header: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text(String(localized: "library_setup.choose_folders_title"))
                .font(.largeTitle.weight(.bold))
                .foregroundStyle(.primary)
            Text(String(localized: "library_setup.choose_folders_subtitle"))
                .font(.subheadline)
                .foregroundStyle(.secondary)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }

    // MARK: - Path bar

    /// A mono breadcrumb of the current path with an "Up" affordance when not at root.
    private var pathBar: some View {
        HStack(spacing: 12) {
            Image(systemName: "folder")
                .font(.footnote)
                .foregroundStyle(.secondary)
            Text(viewModel.currentPath)
                .font(.callout.monospaced())
                .foregroundStyle(.primary)
                .lineLimit(1)
                .truncationMode(.head)
                .frame(maxWidth: .infinity, alignment: .leading)
            if !viewModel.isRoot {
                Button {
                    viewModel.up()
                } label: {
                    Label(String(localized: "library_setup.up"), systemImage: "arrow.up")
                        .font(.footnote.weight(.semibold))
                        .labelStyle(.titleAndIcon)
                }
                .buttonStyle(.plain)
                .foregroundStyle(Color.listenUpOrange)
                .accessibilityHint(String(localized: "library_setup.up_hint"))
            }
        }
        .padding(.horizontal, 14)
        .frame(minHeight: 44)
        .background(
            RoundedRectangle(cornerRadius: AuthMetrics.fieldGroupCornerRadius, style: .continuous)
                .fill(Color(.secondarySystemGroupedBackground))
        )
        .accessibilityElement(children: .combine)
        .accessibilityLabel(String(
            format: String(localized: "library_setup.current_path_label"),
            viewModel.currentPath
        ))
    }

    // MARK: - Directory list

    @ViewBuilder
    private var directorySection: some View {
        if viewModel.isLoadingDirectories {
            AuthFieldGroup { loadingRow }
        } else if viewModel.directories.isEmpty {
            AuthFieldGroup { emptyRow }
        } else {
            AuthFieldGroup {
                ForEach(Array(viewModel.directories.enumerated()), id: \.element.id) { index, item in
                    FolderRow(
                        item: item,
                        isLast: index == viewModel.directories.count - 1,
                        onOpen: { viewModel.open(item.path) },
                        onToggle: { viewModel.toggle(item.path) }
                    )
                }
            }
        }
    }

    private var loadingRow: some View {
        HStack(spacing: 10) {
            ProgressView().controlSize(.small)
            Text(String(localized: "library_setup.loading_folders"))
                .font(.subheadline)
                .foregroundStyle(.secondary)
            Spacer()
        }
        .frame(minHeight: 56)
        .padding(.horizontal, 14)
    }

    /// No subfolders here — offer to select the current folder wholesale so a parent
    /// without leaf children is still pickable.
    private var emptyRow: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text(String(localized: "library_setup.no_subfolders"))
                .font(.subheadline)
                .foregroundStyle(.secondary)
            Button {
                viewModel.selectCurrent()
            } label: {
                Label(String(localized: "library_setup.select_this_folder"), systemImage: "checkmark.circle")
                    .font(.body.weight(.medium))
                    .foregroundStyle(Color.listenUpOrange)
            }
            .buttonStyle(.plain)
        }
        .frame(maxWidth: .infinity, minHeight: 72, alignment: .leading)
        .padding(.horizontal, 14)
        .padding(.vertical, 10)
    }

    // MARK: - Library name

    private var nameSection: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text(String(localized: "library_setup.library_name").uppercased())
                .font(.footnote)
                .foregroundStyle(.secondary)
            AuthFieldGroup {
                AppTextField(
                    placeholder: String(localized: "library_setup.library_name_placeholder"),
                    text: $viewModel.libraryName,
                    icon: "books.vertical",
                    autocapitalization: .words
                )
            }
        }
    }

    // MARK: - Footer summary

    private var selectionSummary: some View {
        Text(summaryText)
            .font(.footnote)
            .foregroundStyle(hasSelection ? .secondary : .tertiary)
            .frame(maxWidth: .infinity)
            .multilineTextAlignment(.center)
            .accessibilityLabel(summaryText)
    }

    private var summaryText: String {
        if hasSelection {
            return String(
                format: String(localized: "library_setup.folders_selected"),
                selectionCount
            )
        }
        return String(localized: "library_setup.select_at_least_one")
    }

    // MARK: - Error binding

    private var errorBinding: Binding<Bool> {
        Binding(
            get: { viewModel.errorMessage != nil },
            set: { presenting in if !presenting { viewModel.dismissError() } }
        )
    }
}

// MARK: - Folder row

/// A single directory row. The trailing affordance is the delineation: a `chevron.right`
/// for a navigable folder (has subfolders → tap drills in), or a selection circle for a
/// leaf (tap toggles inclusion). The two never both appear.
private struct FolderRow: View {
    let item: DirectoryItem
    let isLast: Bool
    let onOpen: () -> Void
    let onToggle: () -> Void

    var body: some View {
        Button(action: item.hasChildren ? onOpen : onToggle) {
            HStack(spacing: 13) {
                glyph
                VStack(alignment: .leading, spacing: 2) {
                    Text(item.name)
                        .font(.headline)
                        .foregroundStyle(.primary)
                        .lineLimit(1)
                        .truncationMode(.middle)
                    Text(String(format: String(localized: "library_setup.item_count"), item.itemCount))
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                }
                Spacer(minLength: 8)
                trailing
            }
            .frame(minHeight: 60)
            .padding(.horizontal, 14)
            .padding(.vertical, 6)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .overlay(alignment: .bottom) {
            if !isLast {
                Rectangle()
                    .fill(Color.primary.opacity(0.10))
                    .frame(height: 0.5)
                    .padding(.leading, 67)
            }
        }
        .accessibilityLabel(String(
            format: String(localized: "library_setup.folder_a11y_label"),
            item.name, item.itemCount
        ))
        .accessibilityHint(accessibilityHint)
        .accessibilityAddTraits(item.isSelected ? .isSelected : [])
    }

    /// A coloured folder tile, mirroring `ServerRow`'s leading icon idiom.
    private var glyph: some View {
        RoundedRectangle(cornerRadius: 10, style: .continuous)
            .fill(item.isSelected ? Color.listenUpOrange : Color(.systemFill))
            .frame(width: 40, height: 40)
            .overlay {
                Image(systemName: item.isSelected ? "folder.fill" : "folder")
                    .foregroundStyle(item.isSelected ? .white : .secondary)
            }
    }

    @ViewBuilder
    private var trailing: some View {
        if item.hasChildren {
            // Navigable: drill into subfolders. No selection control.
            Image(systemName: "chevron.right")
                .font(.footnote.weight(.semibold))
                .foregroundStyle(.tertiary)
        } else {
            // Leaf: selectable. No chevron.
            Image(systemName: item.isSelected ? "checkmark.circle.fill" : "circle")
                .font(.title3)
                .foregroundStyle(item.isSelected ? Color.listenUpOrange : .secondary)
        }
    }

    private var accessibilityHint: String {
        if item.hasChildren {
            return String(localized: "library_setup.folder_opens_hint")
        }
        return item.isSelected
            ? String(localized: "library_setup.folder_deselect_hint")
            : String(localized: "library_setup.folder_select_hint")
    }
}

// MARK: - Preview

#Preview("Choose Folders") {
    ChooseFoldersView(
        viewModel: LibrarySetupViewModelWrapper(
            viewModel: Dependencies.shared.librarySetupViewModel,
            syncRepository: Dependencies.shared.syncRepository
        )
    )
}
