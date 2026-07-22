import SwiftUI
import Shared

/// The folder-picker step of first-run library setup. The user browses the server's
/// filesystem and chooses the folders that hold their audiobooks.
///
/// **Every** folder is directly selectable: each row carries a trailing selection circle
/// (tap to toggle inclusion) regardless of whether it has subfolders, mirroring the Android
/// picker. A folder that has subfolders *also* shows a chevron and drills in when its body is
/// tapped, so you can pick a parent wholesale or dive in to pick children — without ever
/// hunting for a single action at the bottom of a long list. When the current folder has no
/// subfolders at all, a lone "Select this folder" row lets you include it directly.
///
/// The view owns no business logic — it binds the shared ``LibrarySetupViewModelWrapper``,
/// which the setup coordinator (Task 5) constructs once and threads through the flow.
/// Errors surface as a native alert; navigation is driven by the wrapper's callbacks.
struct ChooseFoldersView: View {

    /// The shared wrapper, owned by the coordinator.
    var viewModel: LibrarySetupViewModelWrapper

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
        } footer: {
            selectionSummary
            AuthPrimaryButton(
                title: String(localized: "library_setup.start_scanning"),
                isLoading: viewModel.isCreatingLibrary
            ) {
                viewModel.completeSetup()
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
            // No subfolders here — the drill-in rows are gone, so offer to include this
            // folder itself as a library root.
            AuthFieldGroup { selectCurrentRow }
        } else {
            // Every folder is directly selectable via its own row (see FolderRow); a parent
            // that holds book subfolders is picked from this list without drilling in.
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

    /// Control to include the CURRENT folder as a library root — it toggles the
    /// folder's selection (mirroring a leaf `FolderRow`'s checkmark). This is what lets a parent
    /// folder that holds book subfolders be chosen, instead of only empty leaf folders.
    private var selectCurrentRow: some View {
        let isCurrentSelected = viewModel.selectedPaths.contains(viewModel.currentPath)
        return Button {
            viewModel.toggle(viewModel.currentPath)
        } label: {
            HStack(spacing: 12) {
                Image(systemName: isCurrentSelected ? "checkmark.circle.fill" : "circle")
                    .font(.title3)
                    .foregroundStyle(isCurrentSelected ? Color.listenUpOrange : .secondary)
                Text(String(localized: "library_setup.select_this_folder"))
                    .font(.body.weight(.medium))
                    .foregroundStyle(isCurrentSelected ? Color.listenUpOrange : .primary)
                Spacer()
            }
            .frame(minHeight: 56)
            .padding(.horizontal, 14)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
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

/// A single directory row with two independent tap targets, mirroring the Android picker:
/// the **body** (tile + name) drills in when the folder has subfolders, otherwise toggles it;
/// the trailing **selection circle** always toggles inclusion, so any folder can be chosen
/// straight from the list. A `chevron.right` follows the circle on folders that can be opened.
private struct FolderRow: View {
    let item: DirectoryItem
    let isLast: Bool
    let onOpen: () -> Void
    let onToggle: () -> Void

    var body: some View {
        HStack(spacing: 13) {
            // Primary tap target: drill into subfolders, or toggle a leaf folder.
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
                }
                .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            .accessibilityLabel(String(
                format: String(localized: "library_setup.folder_a11y_label"),
                item.name, item.itemCount
            ))
            .accessibilityHint(item.hasChildren
                ? String(localized: "library_setup.folder_opens_hint")
                : selectHint)

            // Always-present selection toggle — every folder is directly selectable.
            CircularCheckToggle(isOn: item.isSelected, action: onToggle)
                .accessibilityLabel(selectHint)

            // Drill affordance — its slot is ALWAYS reserved (hidden, not removed, on leaf
            // folders) so the selection dot sits in the same column on every row.
            Image(systemName: "chevron.right")
                .font(.footnote.weight(.semibold))
                .foregroundStyle(.tertiary)
                .frame(width: 12)
                .opacity(item.hasChildren ? 1 : 0)
                .accessibilityHidden(true)
        }
        .frame(minHeight: 60)
        .padding(.horizontal, 14)
        .padding(.vertical, 6)
        .overlay(alignment: .bottom) {
            if !isLast {
                Rectangle()
                    .fill(Color.primary.opacity(0.10))
                    .frame(height: 0.5)
                    .padding(.leading, 67)
            }
        }
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

    private var selectHint: String {
        item.isSelected
            ? String(localized: "library_setup.folder_deselect_hint")
            : String(localized: "library_setup.folder_select_hint")
    }
}

// MARK: - Preview

#Preview("Choose Folders") {
    ChooseFoldersView(
        viewModel: LibrarySetupViewModelWrapper(
            viewModel: Dependencies.shared.makeLibrarySetupViewModel()
        )
    )
}
