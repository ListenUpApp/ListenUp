import SwiftUI

/// The destructive restore-confirmation flow for one staged backup, reached from a backup row or
/// from the restore-from-file hand-off. Bound to ``RestoreBackupObserver`` (the VM owns the confirm
/// state machine and runs the post-restore full resync itself).
///
/// Flow: **Idle** (a destructive-action warning + the backup id + a restore button) → tapping asks
/// the VM to `requestRestore()`, surfacing the **Confirming** dialog whose confirm runs the restore
/// → **Restoring** (a spinner with a live status label) → **Completed** (restored-from + schema
/// migration + images note + Done). Back navigation is locked in every non-Idle state so a running
/// restore is never abandoned (mirrors Android RestoreBackupScreen.kt:66-68).
struct RestoreBackupView: View {
    @Environment(\.dependencies) private var deps
    @Environment(\.dismiss) private var dismiss

    let backupId: String

    @State private var observer: RestoreBackupObserver?

    var body: some View {
        Group {
            if let observer {
                content(observer: observer)
            } else {
                LoadingStateView()
            }
        }
        .background(Color.luSurface)
        .navigationTitle(String(localized: "admin.restore_backup"))
        .navigationBarTitleDisplayMode(.inline)
        .navigationBarBackButtonHidden(!canNavigateBack)
        .onAppear {
            if observer == nil {
                observer = RestoreBackupObserver(viewModel: deps.createRestoreBackupViewModel(backupId: backupId))
            }
        }
        .confirmationDialog(
            String(localized: "admin.restore_confirm_title"),
            isPresented: confirmationPresented,
            titleVisibility: .visible
        ) {
            Button(String(localized: "admin.restore"), role: .destructive) { observer?.confirmRestore() }
            Button(String(localized: "common.cancel"), role: .cancel) { observer?.cancelRestore() }
        } message: {
            Text(String(localized: "admin.restore_confirm_message"))
        }
    }

    // MARK: - Phase routing

    @ViewBuilder
    private func content(observer: RestoreBackupObserver) -> some View {
        switch observer.phase {
        case .idle(let error):
            idleContent(observer: observer, error: error)
        case .confirming:
            // Keep the idle content beneath the confirmation dialog so the screen never blanks.
            idleContent(observer: observer, error: nil)
        case .restoring:
            restoringContent(observer: observer)
        case .completed(let model):
            completedContent(model: model)
        }
    }

    // MARK: - Idle

    private func idleContent(observer: RestoreBackupObserver, error: String?) -> some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 18) {
                warningCard
                backupCard
                if let error {
                    ErrorBanner(message: error)
                }
                Button {
                    observer.requestRestore()
                } label: {
                    Text(String(localized: "admin.restore_this_backup"))
                        .font(.body.weight(.semibold))
                        .frame(maxWidth: .infinity)
                        .padding(.vertical, 6)
                }
                .buttonStyle(.borderedProminent)
                .tint(.red)
            }
            .padding(.horizontal, 20)
            .padding(.vertical, 16)
            .readableWidth(640)
        }
    }

    private var warningCard: some View {
        VStack(alignment: .leading, spacing: 8) {
            Label(String(localized: "admin.restore_destructive_title"), systemImage: "exclamationmark.triangle.fill")
                .font(.body.weight(.semibold))
                .foregroundStyle(.red)
            Text(String(localized: "admin.restore_destructive_body"))
                .font(.subheadline)
                .foregroundStyle(Color.luLabel2)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(16)
        .background(.red.opacity(0.1), in: RoundedRectangle(cornerRadius: 12, style: .continuous))
    }

    private var backupCard: some View {
        VStack(alignment: .leading, spacing: 4) {
            Text(String(localized: "admin.backup"))
                .font(.footnote.weight(.semibold))
                .foregroundStyle(Color.luLabel2)
            Text(backupId)
                .font(.body)
                .foregroundStyle(.primary)
                .textSelection(.enabled)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(16)
        .fieldCard()
    }

    // MARK: - Restoring

    private func restoringContent(observer: RestoreBackupObserver) -> some View {
        VStack(spacing: 20) {
            ProgressView()
                .controlSize(.large)
            Text(observer.statusLabel)
                .font(.subheadline)
                .foregroundStyle(Color.luLabel2)
                .multilineTextAlignment(.center)
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .padding(32)
    }

    // MARK: - Completed

    private func completedContent(model: RestoreCompletedModel) -> some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 18) {
                VStack(alignment: .leading, spacing: 10) {
                    Label(String(localized: "admin.restore_complete"), systemImage: "checkmark.circle.fill")
                        .font(.title3.weight(.bold))
                        .foregroundStyle(.green)
                    Text(String(format: String(localized: "admin.restored_from"), model.restoredFrom))
                        .font(.subheadline)
                        .foregroundStyle(.primary)
                    Text(String(format: String(localized: "admin.schema_migrated"), model.schemaMigratedFrom, model.schemaMigratedTo))
                        .font(.subheadline)
                        .foregroundStyle(Color.luLabel2)
                    Text(model.includedImages
                        ? String(localized: "admin.restore_images_included")
                        : String(localized: "admin.restore_images_not_included"))
                        .font(.footnote)
                        .foregroundStyle(Color.luLabel2)
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                .padding(16)
                .fieldCard()

                Button {
                    dismiss()
                } label: {
                    Text(String(localized: "common.done"))
                        .font(.body.weight(.semibold))
                        .frame(maxWidth: .infinity)
                        .padding(.vertical, 6)
                }
                .buttonStyle(.borderedProminent)
                .tint(.luTint)
            }
            .padding(.horizontal, 20)
            .padding(.vertical, 16)
            .readableWidth(640)
        }
    }

    // MARK: - Derived state

    private var canNavigateBack: Bool {
        if case .idle = observer?.phase { return true }
        // Before the observer is created the screen is still safely dismissible.
        return observer == nil
    }

    private var confirmationPresented: Binding<Bool> {
        Binding(
            get: {
                if case .confirming = observer?.phase { return true }
                return false
            },
            set: { presenting in
                // Any dismissal of the dialog must return the VM to Idle.
                if !presenting {
                    if case .confirming = observer?.phase { observer?.cancelRestore() }
                }
            }
        )
    }
}

// MARK: - Preview

#Preview {
    NavigationStack {
        RestoreBackupView(backupId: "backup-2026-07-05")
    }
}
