import SwiftUI
import Shared
import UniformTypeIdentifiers

/// The admin Backups screen — reached from Administration › Management. Lists the server's stored
/// backups (restore / delete each), creates a new backup via a sheet, and folds Android's separate
/// restore-from-file screen into a single entry row (iosApp rule 1/3): pick a `.listenup.zip`,
/// upload it, then flow straight into the destructive restore-confirmation for the staged archive.
///
/// Bound to `AdminBackupsObserver` (list + create + delete) and `RestoreFromFileObserver` (the
/// pick + upload step). Responsive: a single `.readableWidth()` column (rule 12). Download-to-device
/// is intentionally omitted — its `RawSink` sink isn't Swift-exported (see plan 106).
struct AdminBackupsView: View {
    @Environment(\.dependencies) private var deps

    @State private var observer: AdminBackupsObserver?
    @State private var restoreFromFile: RestoreFromFileObserver?
    @State private var showingCreateSheet = false
    @State private var showingFileImporter = false
    @State private var pendingDelete: BackupRowModel?
    @State private var restoreTarget: RestoreBackupDestination?
    @State private var pickError: String?

    var body: some View {
        Group {
            if let observer, restoreFromFile != nil {
                content(observer: observer)
            } else {
                LoadingStateView()
            }
        }
        .background(Color.luSurface)
        .navigationTitle(String(localized: "admin.backups"))
        .navigationBarTitleDisplayMode(.large)
        .navigationBarBackButtonHidden(isUploading)
        .toolbar { toolbar }
        .onAppear {
            if observer == nil {
                observer = AdminBackupsObserver(viewModel: deps.createAdminBackupViewModel())
            }
            if restoreFromFile == nil {
                restoreFromFile = RestoreFromFileObserver(viewModel: deps.createRestoreFromFileViewModel())
            }
        }
        .sheet(isPresented: $showingCreateSheet) {
            if let observer {
                CreateBackupSheet(observer: observer)
            }
        }
        .fileImporter(
            isPresented: $showingFileImporter,
            allowedContentTypes: Self.backupContentTypes
        ) { result in
            handlePickResult(result)
        }
        .navigationDestination(item: $restoreTarget) { target in
            RestoreBackupView(backupId: target.backupId)
        }
        .onChange(of: restoreFromFile?.stagedBackupId) { _, newValue in
            // `newValue` is doubly optional (optional-chained String?); flatten to the staged id.
            guard let staged = newValue.flatMap({ $0 }) else { return }
            restoreFromFile?.stagedBackupId = nil
            restoreTarget = RestoreBackupDestination(backupId: staged)
        }
        .confirmationDialog(
            String(localized: "admin.delete_backup"),
            isPresented: deleteConfirmationPresented,
            titleVisibility: .visible
        ) {
            if let pendingDelete {
                Button(String(localized: "common.delete"), role: .destructive) {
                    observer?.confirmDelete(id: pendingDelete.id)
                }
                Button(String(localized: "common.cancel"), role: .cancel) {
                    observer?.cancelDelete()
                }
            }
        } message: {
            if let pendingDelete {
                Text(String(format: String(localized: "admin.confirm_delete_backup"), pendingDelete.id))
            }
        }
        .alert(item: errorAlertBinding) { alert in
            Alert(
                title: Text(String(localized: "common.something_went_wrong")),
                message: Text(alert.message),
                dismissButton: .default(Text(String(localized: "common.ok"))) { observer?.clearError() }
            )
        }
        .alert(
            String(localized: "admin.restore_from_file_upload_failed"),
            isPresented: uploadErrorPresented,
            presenting: uploadErrorMessage
        ) { _ in
            Button(String(localized: "admin.restore_from_file_choose")) {
                restoreFromFile?.reset()
                showingFileImporter = true
            }
            Button(String(localized: "common.cancel"), role: .cancel) { restoreFromFile?.reset() }
        } message: { message in
            Text(message)
        }
        .alert(
            String(localized: "common.something_went_wrong"),
            isPresented: pickErrorPresented,
            presenting: pickError
        ) { _ in
            Button(String(localized: "common.ok")) { pickError = nil }
        } message: { message in
            Text(message)
        }
    }

    // MARK: - Phase routing

    @ViewBuilder
    private func content(observer: AdminBackupsObserver) -> some View {
        switch observer.phase {
        case .loading:
            LoadingStateView()
        case .error(let message):
            ContentUnavailableView {
                Label(String(localized: "common.something_went_wrong"), systemImage: "exclamationmark.triangle")
            } description: {
                Text(message)
            } actions: {
                Button(String(localized: "common.retry")) { observer.reload() }
            }
        case .ready(let ready):
            readyBody(observer: observer, ready: ready)
        }
    }

    @ViewBuilder
    private func readyBody(observer: AdminBackupsObserver, ready: BackupsReadyModel) -> some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 18) {
                restoreFromFileRow
                if ready.backups.isEmpty {
                    emptyState
                } else {
                    AdminSectionHeader(String(localized: "admin.backups"))
                    FieldGroup(ready.backups, separatorInset: 57) { backup in
                        BackupRow(backup: backup, onDelete: { pendingDelete = backup })
                    }
                }
            }
            .padding(.horizontal, 20)
            .padding(.vertical, 16)
            .readableWidth(640)
        }
        .refreshable { observer.reload() }
        .overlay {
            if case .uploading(let filename) = restoreFromFile?.phase {
                uploadingOverlay(filename: filename)
            }
        }
    }

    // MARK: - Restore from file

    private var restoreFromFileRow: some View {
        NavigationActionRow(
            systemImage: "arrow.down.doc.fill",
            tint: .luTint,
            title: String(localized: "admin.restore_from_file"),
            subtitle: String(localized: "admin.restore_from_file_description"),
            action: { showingFileImporter = true }
        )
        .fieldCard()
    }

    private func uploadingOverlay(filename: String) -> some View {
        ZStack {
            Color.luSurface.opacity(0.96).ignoresSafeArea()
            VStack(spacing: 20) {
                ProgressView()
                    .controlSize(.large)
                Text(String(format: String(localized: "admin.restore_from_file_uploading"), filename))
                    .font(.subheadline)
                    .foregroundStyle(Color.luLabel2)
                    .multilineTextAlignment(.center)
            }
            .padding(32)
        }
    }

    // MARK: - Empty state

    private var emptyState: some View {
        VStack(spacing: 16) {
            Circle()
                .fill(Color.luTint.opacity(0.13))
                .frame(width: 86, height: 86)
                .overlay {
                    Image(systemName: "archivebox")
                        .font(.system(size: 38, weight: .regular)) // decorative fixed size
                        .foregroundStyle(Color.luTint)
                }
            Text(String(localized: "admin.no_backups_yet"))
                .font(.title3.weight(.bold))
                .foregroundStyle(.primary)
            Text(String(localized: "admin.create_backup_to_protect"))
                .font(.subheadline)
                .foregroundStyle(Color.luLabel2)
                .multilineTextAlignment(.center)
                .frame(maxWidth: 300)
        }
        .frame(maxWidth: .infinity)
        .padding(.vertical, 40)
    }

    // MARK: - Toolbar

    @ToolbarContentBuilder
    private var toolbar: some ToolbarContent {
        ToolbarItem(placement: .topBarTrailing) {
            Button {
                showingCreateSheet = true
            } label: {
                Label(String(localized: "admin.create_backup"), systemImage: "plus")
            }
            .disabled(isCreating)
        }
    }

    // MARK: - Derived state

    private var isCreating: Bool {
        guard case .ready(let ready) = observer?.phase else { return false }
        return ready.isCreating
    }

    private var isUploading: Bool {
        if case .uploading = restoreFromFile?.phase { return true }
        return false
    }

    // MARK: - Bindings

    private var deleteConfirmationPresented: Binding<Bool> {
        Binding(
            get: { pendingDelete != nil },
            set: { presenting in
                if !presenting {
                    pendingDelete = nil
                    observer?.cancelDelete()
                }
            }
        )
    }

    private var errorAlertBinding: Binding<MessageAlert?> {
        Binding(
            get: {
                guard case .ready(let ready)? = observer?.phase, let message = ready.error else { return nil }
                return MessageAlert(message: message)
            },
            set: { if $0 == nil { observer?.clearError() } }
        )
    }

    private var uploadErrorMessage: String? {
        if case .error(let message)? = restoreFromFile?.phase { return message }
        return nil
    }

    private var uploadErrorPresented: Binding<Bool> {
        Binding(
            get: { uploadErrorMessage != nil },
            set: { if !$0 { restoreFromFile?.reset() } }
        )
    }

    private var pickErrorPresented: Binding<Bool> {
        Binding(get: { pickError != nil }, set: { if !$0 { pickError = nil } })
    }

    // MARK: - File pick handling

    /// A `.listenup.zip` backup is an opaque zip; also accept plain archives / data for
    /// hand-renamed files. Distinct from `ImportFileSourceBridge.allowedContentTypes`, which
    /// targets `.audiobookshelf` exports.
    private static var backupContentTypes: [UTType] { [.zip, .archive, .data] }

    private func handlePickResult(_ result: Result<URL, Error>) {
        switch result {
        case .success(let url):
            Task {
                do {
                    // Read the (multi-MB) archive OFF the main thread; only the `Sendable` `Data`
                    // crosses back — the non-`Sendable` Kotlin `FileSource` is built on the main actor.
                    let data = try await Task.detached {
                        try ImportFileSourceBridge.readData(from: url)
                    }.value
                    let fileSource = ImportFileSourceBridge.makeFileSource(
                        data: data,
                        filename: url.lastPathComponent
                    )
                    restoreFromFile?.onFilePicked(fileSource: fileSource)
                } catch let error as ImportFilePickError {
                    pickError = error.message
                } catch {
                    pickError = String(localized: "admin.restore_from_file_upload_failed")
                }
            }
        case .failure:
            // User cancelled the picker, or it failed — stay put silently.
            break
        }
    }
}

// MARK: - Backup row

/// One backup in the list: an archive tile, the id, the created timestamp, and the formatted size.
/// The whole row pushes the restore-confirmation flow; a trailing swipe / context menu deletes.
private struct BackupRow: View {
    let backup: BackupRowModel
    let onDelete: () -> Void

    var body: some View {
        NavigationLink(value: RestoreBackupDestination(backupId: backup.id)) {
            HStack(spacing: 13) {
                IconTile(systemImage: "archivebox.fill", tint: .luTint, size: 38)
                VStack(alignment: .leading, spacing: 2) {
                    Text(backup.id)
                        .font(.body.weight(.medium))
                        .foregroundStyle(.primary)
                        .lineLimit(1)
                        .truncationMode(.middle)
                    Text(backup.createdAt.formatted(date: .abbreviated, time: .shortened))
                        .font(.footnote)
                        .foregroundStyle(Color.luLabel2)
                        .lineLimit(1)
                    Text(String(format: String(localized: "admin.backup_size"), backup.sizeFormatted))
                        .font(.caption)
                        .foregroundStyle(Color.luLabel3)
                        .lineLimit(1)
                }
                Spacer(minLength: 8)
                Image(systemName: "chevron.right")
                    .font(.footnote.weight(.semibold))
                    .foregroundStyle(Color.luLabel3)
                    .accessibilityHidden(true)
            }
            .padding(.horizontal, 14)
            .padding(.vertical, 11)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .swipeActions(edge: .trailing) {
            Button(role: .destructive, action: onDelete) {
                Label(String(localized: "common.delete"), systemImage: "trash")
            }
        }
        .contextMenu {
            Button(role: .destructive, action: onDelete) {
                Label(String(localized: "common.delete"), systemImage: "trash")
            }
        }
    }
}

// MARK: - Preview

#Preview {
    NavigationStack {
        AdminBackupsView()
    }
}
