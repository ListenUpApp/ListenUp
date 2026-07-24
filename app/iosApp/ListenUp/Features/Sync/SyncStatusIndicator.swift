import SwiftUI
import Shared

/// The shell's sync-outbox indicator: a compact toolbar glyph that appears only when the outbox is
/// non-empty — a spinner while syncing, a coral pending badge ("N") while changes wait, a red error
/// glyph when operations failed. Tapping opens a lightweight details sheet with per-operation and
/// bulk retry/dismiss. Matches the intent of Android's `AppHeader` sync indicator without porting
/// its dropdown chrome.
struct SyncStatusIndicator: View {
    @Environment(\.dependencies) private var deps
    @State private var observer: SyncStatusObserver?
    @State private var showDetails = false

    var body: some View {
        Group {
            if let observer, observer.presentation.isVisible {
                Button {
                    showDetails = true
                } label: {
                    glyph(observer.presentation)
                }
                .buttonStyle(.plain)
                .accessibilityLabel(String(localized: "shell.sync_status"))
                .sheet(isPresented: $showDetails) {
                    SyncDetailsSheet(observer: observer)
                        .presentationDetents([.medium, .large])
                }
            }
        }
        .onAppear {
            if observer == nil {
                observer = SyncStatusObserver(viewModel: deps.createSyncIndicatorViewModel())
            }
        }
    }

    @ViewBuilder
    private func glyph(_ presentation: SyncStatusPresentation) -> some View {
        switch presentation.icon {
        case .syncing:
            ProgressView()
                .controlSize(.small)
        case .pending:
            Image(systemName: "arrow.triangle.2.circlepath")
                .foregroundStyle(Color.listenUpOrange)
                .overlay(alignment: .topTrailing) {
                    if let count = presentation.badgeCount {
                        Text("\(count)")
                            .font(.system(size: 10, weight: .bold)) // decorative fixed size
                            .foregroundStyle(.white)
                            .padding(.horizontal, 4)
                            .padding(.vertical, 1)
                            .background(Color.listenUpOrange, in: Capsule())
                            .offset(x: 8, y: -8)
                    }
                }
        case .error:
            Image(systemName: "exclamationmark.arrow.triangle.2.circlepath")
                .foregroundStyle(.red)
        case .none:
            EmptyView()
        }
    }
}

/// The details sheet behind the sync indicator: a status header, the pending-count line, and the
/// list of failed operations with per-row and bulk retry/dismiss.
private struct SyncDetailsSheet: View {
    let observer: SyncStatusObserver
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            List {
                Section {
                    statusRow
                }

                if !observer.failedOperations.isEmpty {
                    Section {
                        ForEach(observer.failedOperations) { op in
                            failedRow(op)
                        }
                    } header: {
                        Text(String(localized: "shell.sync_error"))
                    } footer: {
                        HStack {
                            Button(String(localized: "shell.retry_all")) { observer.retryAll() }
                            Spacer()
                            Button(String(localized: "shell.dismiss_all"), role: .destructive) {
                                observer.dismissAll()
                            }
                        }
                        .font(.subheadline.weight(.medium))
                        .padding(.top, 4)
                    }
                }
            }
            .navigationTitle(String(localized: "shell.sync_status"))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .confirmationAction) {
                    Button(String(localized: "common.done")) { dismiss() }
                }
            }
        }
    }

    private var statusRow: some View {
        HStack(spacing: 12) {
            if observer.isSyncing {
                ProgressView().controlSize(.small)
            }
            VStack(alignment: .leading, spacing: 2) {
                Text(statusTitle)
                    .font(.body)
                    .foregroundStyle(.primary)
                if let subtitle = statusSubtitle {
                    Text(subtitle)
                        .font(.caption)
                        .foregroundStyle(Color.luLabel2)
                }
            }
        }
    }

    private func failedRow(_ op: PendingOperationRow) -> some View {
        VStack(alignment: .leading, spacing: 6) {
            Text(op.text)
                .font(.subheadline)
                .foregroundStyle(.primary)
            if let error = op.error {
                Text(error)
                    .font(.caption)
                    .foregroundStyle(.red)
                    .lineLimit(2)
            }
            HStack(spacing: 16) {
                Button(String(localized: "common.retry")) { observer.retry(id: op.id) }
                Button(String(localized: "common.dismiss"), role: .destructive) { observer.dismiss(id: op.id) }
            }
            .font(.caption.weight(.medium))
            .buttonStyle(.borderless)
        }
        .padding(.vertical, 2)
    }

    private var statusTitle: String {
        if observer.hasErrors {
            return String(localized: "shell.sync_error")
        }
        if observer.isSyncing {
            return String(localized: "shell.syncing")
        }
        if observer.pendingCount > 0 {
            return String(format: String(localized: "shell.pendingcount_pending"), "\(observer.pendingCount)")
        }
        return String(localized: "shell.all_synced")
    }

    private var statusSubtitle: String? {
        if observer.isSyncing {
            return observer.currentOperationDescription
        }
        if observer.pendingCount > 0 {
            return String(localized: "shell.changes_waiting_to_sync")
        }
        if !observer.hasErrors {
            return String(localized: "shell.no_pending_changes")
        }
        return nil
    }
}
