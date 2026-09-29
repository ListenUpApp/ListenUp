import SwiftUI

/// The create-backup sheet, presented from ``AdminBackupsView``. Mirrors Android's
/// `CreateBackupScreen` semantics: an intro line, a "What to include" section with a cover-images
/// toggle (default OFF, with a size warning), and a Create action. While the backup is being built
/// it shows a spinner and blocks dismissal; on success it dismisses back to the list; a failure
/// keeps the sheet open with the transient error inline.
///
/// It observes the shared ``AdminBackupsObserver`` (the same instance the list binds), so create
/// progress and errors come straight from the VM — no parallel state.
struct CreateBackupSheet: View {
    @Environment(\.dismiss) private var dismiss

    let observer: AdminBackupsObserver

    @State private var includeImages = false
    @State private var hasStartedCreation = false

    var body: some View {
        NavigationStack {
            Group {
                if isCreating {
                    creatingContent
                } else {
                    form
                }
            }
            .background(Color.luSurface)
            .navigationTitle(String(localized: "admin.create_backup"))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    if !isCreating {
                        Button(String(localized: "common.cancel")) { dismiss() }
                    }
                }
            }
        }
        .interactiveDismissDisabled(isCreating)
        .onChange(of: readySnapshot) { _, snapshot in
            // Success = a creation we started has finished with no error (CreateBackupScreen.kt:74-78).
            if hasStartedCreation, let snapshot, !snapshot.isCreating, snapshot.error == nil {
                dismiss()
            }
        }
    }

    // MARK: - Form

    /// A grouped `Form`: the intro as the section's header, the switch as a system row with its size
    /// warning as the footer, and the Create action beneath. HIG, Lists and tables.
    private var form: some View {
        Form {
            Section {
                Toggle(isOn: $includeImages) {
                    VStack(alignment: .leading, spacing: 2) {
                        Text(String(localized: "admin.cover_images"))
                            .font(.body)
                            .foregroundStyle(.primary)
                        Text(String(localized: "admin.book_covers_and_user_avatars"))
                            .font(.footnote)
                            .foregroundStyle(Color.luLabel2)
                    }
                }
            } header: {
                VStack(alignment: .leading, spacing: 12) {
                    Text(String(localized: "admin.create_a_backup_of_your"))
                        .font(.subheadline)
                        .foregroundStyle(Color.luLabel2)
                        .textCase(nil)
                    Text(String(localized: "admin.what_to_include"))
                }
            } footer: {
                Label(String(localized: "admin.significantly_increases_backup_size"), systemImage: "exclamationmark.triangle.fill")
                    .foregroundStyle(Color.luWarning)
            }

            Section {
                if let error = readySnapshot?.error {
                    ErrorBanner(message: error)
                        .listRowInsets(EdgeInsets())
                        .listRowBackground(Color.clear)
                }
                Button {
                    hasStartedCreation = true
                    observer.createBackup(includeImages: includeImages)
                } label: {
                    Text(String(localized: "admin.create_backup"))
                        .font(.body.weight(.semibold))
                        .frame(maxWidth: .infinity)
                        .padding(.vertical, 6)
                }
                .buttonStyle(.borderedProminent)
                .onBrandFillLabel()
                .listRowInsets(EdgeInsets())
                .listRowBackground(Color.clear)
            }
        }
        .readableListWidth()
    }

    private var creatingContent: some View {
        LoadingStateView(label: String(localized: "admin.creating_backup"))
    }

    // MARK: - Derived state

    private var readySnapshot: BackupsReadyModel? {
        if case .ready(let ready) = observer.phase { return ready }
        return nil
    }

    private var isCreating: Bool { readySnapshot?.isCreating ?? false }
}
