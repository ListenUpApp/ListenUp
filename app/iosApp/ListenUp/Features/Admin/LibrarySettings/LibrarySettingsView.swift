import SwiftUI
import Shared

/// Library Settings — the admin surface for managing the single library's scan folders and
/// triggering a rescan. Reached from Administration › Management.
///
/// Mirrors `AdminView`'s structure: grouped `.fieldCard()` sections under `AdminSectionHeader`
/// overlines, built on the native design system. The shared `LibrarySettingsViewModel` is
/// bridged through `LibrarySettingsObserver`, which flattens its state into native value types.
struct LibrarySettingsView: View {
    @Environment(\.dependencies) private var deps

    @State private var observer: LibrarySettingsObserver?
    @State private var pendingRemove: LibraryFolderRowModel?

    var body: some View {
        Group {
            if let observer {
                content(observer)
            } else {
                LoadingStateView()
            }
        }
        .background(Color.luSurface)
        .navigationTitle(String(localized: "admin.library_settings"))
        .navigationBarTitleDisplayMode(.large)
        .onAppear {
            if observer == nil {
                observer = LibrarySettingsObserver(viewModel: deps.createLibrarySettingsViewModel())
            }
        }
    }

    // MARK: - Phase routing

    @ViewBuilder
    private func content(_ observer: LibrarySettingsObserver) -> some View {
        switch observer.phase {
        case .loading:
            LoadingStateView()
        case .error(let message):
            ContentUnavailableView(
                String(localized: "common.something_went_wrong"),
                systemImage: "exclamationmark.triangle",
                description: Text(message)
            )
        case .ready(let model):
            ready(model, observer: observer)
        }
    }

    // MARK: - Ready

    private func ready(_ model: LibrarySettingsReadyModel, observer: LibrarySettingsObserver) -> some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 28) {
                scanPathsSection(model, observer: observer)
                rescanSection(model, observer: observer)
            }
            .padding(.horizontal, 18)
            .padding(.vertical, 20)
            // Reading-width cap so the form stays comfortable on iPad / wide Split View
            // instead of stretching a phone layout across the full width.
            .frame(maxWidth: 640, alignment: .leading)
            .frame(maxWidth: .infinity)
        }
        .sheet(isPresented: browserBinding(observer)) {
            FolderBrowserSheet(observer: observer)
        }
        .alert(
            String(localized: "admin.remove_scan_path"),
            isPresented: removeBinding,
            presenting: pendingRemove
        ) { folder in
            Button(String(localized: "common.remove"), role: .destructive) {
                observer.removeFolder(id: folder.id)
                pendingRemove = nil
            }
            Button(String(localized: "common.cancel"), role: .cancel) { pendingRemove = nil }
        } message: { folder in
            Text(String(format: String(localized: "admin.remove_path_from_library_scan"), folder.displayPath))
        }
        .alert(
            String(localized: "common.something_went_wrong"),
            isPresented: transientErrorBinding(model, observer: observer),
            presenting: model.transientError
        ) { _ in
            Button(String(localized: "common.ok"), role: .cancel) { observer.clearError() }
        } message: { message in
            Text(message)
        }
    }

    // MARK: - Scan paths

    private func scanPathsSection(_ model: LibrarySettingsReadyModel, observer: LibrarySettingsObserver) -> some View {
        VStack(alignment: .leading, spacing: 0) {
            AdminSectionHeader(String(localized: "admin.scan_paths"))
            VStack(spacing: 0) {
                ForEach(model.folders) { folder in
                    folderRow(folder, model: model)
                    rowSeparator
                }
                addFolderRow(model, observer: observer)
            }
            .fieldCard()
        }
    }

    private func folderRow(_ folder: LibraryFolderRowModel, model: LibrarySettingsReadyModel) -> some View {
        HStack(spacing: 13) {
            IconTile(systemImage: "folder.fill", tint: .luTint)
            Text(folder.displayPath)
                .font(.callout.monospaced())
                .foregroundStyle(.primary)
                .lineLimit(1)
                .truncationMode(.head)
                .frame(maxWidth: .infinity, alignment: .leading)
            if model.canRemoveFolders && !model.isSaving {
                Button {
                    pendingRemove = folder
                } label: {
                    Image(systemName: "minus.circle.fill")
                        .font(.title3)
                        .foregroundStyle(.red)
                }
                .buttonStyle(.plain)
                .accessibilityLabel(String(localized: "admin.remove_path"))
            }
        }
        .padding(.horizontal, 14)
        .padding(.vertical, 11)
    }

    private func addFolderRow(_ model: LibrarySettingsReadyModel, observer: LibrarySettingsObserver) -> some View {
        Button {
            observer.showFolderBrowser(true)
        } label: {
            HStack(spacing: 13) {
                IconTile(systemImage: "plus", tint: .luTint)
                Text(String(localized: "admin.add_folder"))
                    .font(.body)
                    .foregroundStyle(Color.luTint)
                Spacer()
            }
            .padding(.horizontal, 14)
            .padding(.vertical, 11)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .disabled(model.isSaving)
    }

    // MARK: - Rescan

    private func rescanSection(_ model: LibrarySettingsReadyModel, observer: LibrarySettingsObserver) -> some View {
        VStack(alignment: .leading, spacing: 0) {
            AdminSectionHeader(String(localized: "admin.scanning"))
            Button {
                observer.rescan()
            } label: {
                HStack(spacing: 13) {
                    IconTile(systemImage: "arrow.clockwise", tint: .luTint)
                    VStack(alignment: .leading, spacing: 1) {
                        Text(String(localized: "admin.rescan_library"))
                            .font(.body)
                            .foregroundStyle(.primary)
                        Text(String(localized: "admin.scan_all_paths_for_new"))
                            .font(.footnote)
                            .foregroundStyle(Color.luLabel2)
                            .multilineTextAlignment(.leading)
                    }
                    Spacer(minLength: 12)
                    if model.isScanning {
                        ProgressView().controlSize(.small)
                    }
                }
                .padding(.horizontal, 14)
                .padding(.vertical, 11)
                .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            .disabled(model.isScanning)
            .fieldCard()
        }
    }

    // MARK: - Shared chrome

    private var rowSeparator: some View {
        Rectangle()
            .fill(Color.luSeparator)
            .frame(height: 0.5)
            .padding(.leading, 61)
    }

    // MARK: - Bindings

    private func browserBinding(_ observer: LibrarySettingsObserver) -> Binding<Bool> {
        Binding(
            get: {
                if case .ready(let model) = observer.phase { return model.showFolderBrowser }
                return false
            },
            set: { presenting in if !presenting { observer.showFolderBrowser(false) } }
        )
    }

    private var removeBinding: Binding<Bool> {
        Binding(
            get: { pendingRemove != nil },
            set: { presenting in if !presenting { pendingRemove = nil } }
        )
    }

    private func transientErrorBinding(
        _ model: LibrarySettingsReadyModel,
        observer: LibrarySettingsObserver
    ) -> Binding<Bool> {
        Binding(
            get: { model.transientError != nil },
            set: { presenting in if !presenting { observer.clearError() } }
        )
    }
}

// MARK: - Folder browser sheet

/// A modal server-filesystem browser for adding a scan folder. Reads live browser state from
/// the observer (so drilling in/out re-renders), then adds the current directory as a scan path.
private struct FolderBrowserSheet: View {
    let observer: LibrarySettingsObserver

    var body: some View {
        NavigationStack {
            Group {
                if case .ready(let model) = observer.phase {
                    browser(model)
                } else {
                    LoadingStateView()
                }
            }
            .background(Color.luSurface)
            .navigationTitle(String(localized: "admin.select_folder"))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button(String(localized: "common.cancel")) { observer.showFolderBrowser(false) }
                }
            }
        }
    }

    @ViewBuilder
    private func browser(_ model: LibrarySettingsReadyModel) -> some View {
        VStack(spacing: 0) {
            pathBar(model)
            addThisFolderButton(model)
            Divider()
            if model.isBrowserLoading {
                Spacer()
                ProgressView()
                Spacer()
            } else {
                List(model.browserEntries) { entry in
                    Button {
                        observer.openBrowserDirectory(entry.path)
                    } label: {
                        HStack(spacing: 12) {
                            Image(systemName: "folder")
                                .foregroundStyle(.secondary)
                            Text(entry.name)
                                .foregroundStyle(.primary)
                            Spacer()
                            Image(systemName: "chevron.right")
                                .font(.footnote.weight(.semibold))
                                .foregroundStyle(.tertiary)
                        }
                        .contentShape(Rectangle())
                    }
                    .buttonStyle(.plain)
                }
                .listStyle(.plain)
            }
        }
    }

    private func pathBar(_ model: LibrarySettingsReadyModel) -> some View {
        HStack(spacing: 12) {
            Image(systemName: "folder")
                .font(.footnote)
                .foregroundStyle(.secondary)
            Text(model.browserPath)
                .font(.callout.monospaced())
                .lineLimit(1)
                .truncationMode(.head)
                .frame(maxWidth: .infinity, alignment: .leading)
            if !model.browserIsRoot {
                Button {
                    observer.browserNavigateUp()
                } label: {
                    Label(String(localized: "common.back"), systemImage: "arrow.up")
                        .font(.footnote.weight(.semibold))
                        .labelStyle(.iconOnly)
                }
                .buttonStyle(.plain)
                .foregroundStyle(Color.luTint)
            }
        }
        .padding(.horizontal, 16)
        .padding(.vertical, 10)
    }

    private func addThisFolderButton(_ model: LibrarySettingsReadyModel) -> some View {
        Button {
            observer.addFolder(model.browserPath)
        } label: {
            Label(String(localized: "admin.save_and_scan_folder"), systemImage: "plus.circle.fill")
                .font(.body.weight(.medium))
                .foregroundStyle(Color.luTint)
                .frame(maxWidth: .infinity, alignment: .leading)
                .padding(.horizontal, 16)
                .padding(.vertical, 10)
                .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .disabled(model.isSaving)
    }
}
