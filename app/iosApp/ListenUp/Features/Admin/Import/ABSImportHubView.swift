import SwiftUI
import Shared

/// The Audiobookshelf import hub — the entry to the import flow, reached from Administration ›
/// Management. Lists existing (resumable) imports and launches the wizard for a new one; an empty
/// state invites the first import.
///
/// Bound to `ABSImportHubObserver` (the VM's list surface). "New Import" presents
/// ``ImportWizardView`` as a sheet, which owns the whole upload → analyze → review → apply →
/// complete pipeline; on completion the hub refreshes its roster. The tabbed per-import detail the
/// VM also drives is intentionally not surfaced — the iOS flow is the linear wizard (resuming an
/// in-progress import is a deferred follow-up).
///
/// Responsive: a single `.readableWidth()` column that stays comfortable on iPhone and centres on
/// iPad / wide split views (rule 12).
struct ABSImportHubView: View {
    @Environment(\.dependencies) private var deps

    @State private var observer: ABSImportHubObserver?
    @State private var showingWizard = false
    @State private var pendingDelete: ImportSummaryRowModel?

    var body: some View {
        Group {
            if let observer {
                content(observer: observer)
            } else {
                LoadingStateView()
            }
        }
        .background(Color.luSurface)
        .navigationTitle(String(localized: "import.title"))
        .navigationBarTitleDisplayMode(.large)
        .toolbar { toolbar }
        .onAppear {
            if observer == nil {
                observer = ABSImportHubObserver(viewModel: deps.createABSImportHubViewModel())
            }
        }
        .sheet(isPresented: $showingWizard) {
            ImportWizardView { observer?.reload() }
        }
        .alert(item: errorAlertBinding) { alert in
            Alert(
                title: Text(String(localized: "common.something_went_wrong")),
                message: Text(alert.message),
                dismissButton: .default(Text(String(localized: "common.ok"))) { observer?.clearError() }
            )
        }
        .confirmationDialog(
            String(localized: "import.delete_import"),
            isPresented: deleteConfirmationPresented,
            titleVisibility: .visible
        ) {
            if let pendingDelete {
                Button(String(localized: "common.delete"), role: .destructive) {
                    observer?.deleteImport(id: pendingDelete.id)
                    self.pendingDelete = nil
                }
            }
            Button(String(localized: "common.cancel"), role: .cancel) { pendingDelete = nil }
        } message: {
            Text(String(localized: "import.delete_import_confirm"))
        }
    }

    // MARK: - Phase routing

    @ViewBuilder
    private func content(observer: ABSImportHubObserver) -> some View {
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
    private func readyBody(observer: ABSImportHubObserver, ready: ImportHubReadyModel) -> some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 18) {
                if ready.imports.isEmpty {
                    emptyState
                } else {
                    AdminSectionHeader(String(localized: "import.hub_section_imports"))
                    FieldGroup(ready.imports, separatorInset: 57) { summary in
                        ImportSummaryRow(summary: summary, onDelete: { pendingDelete = summary })
                    }
                }
            }
            .padding(.horizontal, 20)
            .padding(.vertical, 16)
            .readableWidth(640)
        }
        .refreshable { observer.reload() }
    }

    private var emptyState: some View {
        VStack(spacing: 16) {
            Circle()
                .fill(Color.luTint.opacity(0.13))
                .frame(width: 86, height: 86)
                .overlay {
                    Image(systemName: "shippingbox")
                        .font(.system(size: 38, weight: .regular))
                        .foregroundStyle(Color.luTint)
                }
            Text(String(localized: "import.hub_empty_title"))
                .font(.title3.weight(.bold))
                .foregroundStyle(.primary)
            Text(String(localized: "import.hub_empty_subtitle"))
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
                showingWizard = true
            } label: {
                Label(String(localized: "import.new_import"), systemImage: "plus")
            }
            .accessibilityLabel(String(localized: "import.new_import_a11y"))
        }
    }

    // MARK: - Bindings

    private var errorAlertBinding: Binding<MessageAlert?> {
        Binding(
            get: {
                guard case .ready(let ready)? = observer?.phase, let message = ready.error else { return nil }
                return MessageAlert(message: message)
            },
            set: { if $0 == nil { observer?.clearError() } }
        )
    }

    private var deleteConfirmationPresented: Binding<Bool> {
        Binding(get: { pendingDelete != nil }, set: { if !$0 { pendingDelete = nil } })
    }
}

// MARK: - Summary row

/// One import in the hub list: a stage-tinted icon tile, the created timestamp as the title, a
/// stage badge, and a contextual delete. The book tally reads as the subtitle.
private struct ImportSummaryRow: View {
    let summary: ImportSummaryRowModel
    let onDelete: () -> Void

    var body: some View {
        HStack(spacing: 13) {
            IconTile(systemImage: stageIcon, tint: stageTint, size: 38)
            VStack(alignment: .leading, spacing: 2) {
                Text(summary.createdAt.formatted(date: .abbreviated, time: .shortened))
                    .font(.body.weight(.medium))
                    .foregroundStyle(.primary)
                    .lineLimit(1)
                Text(subtitle)
                    .font(.footnote)
                    .foregroundStyle(Color.luLabel2)
                    .lineLimit(1)
            }
            Spacer(minLength: 8)
            stageBadge
        }
        .padding(.horizontal, 14)
        .padding(.vertical, 11)
        .contentShape(Rectangle())
        .swipeActions(edge: .trailing) {
            Button(role: .destructive, action: onDelete) {
                Label(String(localized: "common.delete"), systemImage: "trash")
            }
        }
    }

    private var subtitle: String {
        String(format: String(localized: "common.books_count"), summary.bookCount)
    }

    private var stageBadge: some View {
        Text(stageLabel)
            .font(.caption.weight(.semibold))
            .foregroundStyle(stageTint)
            .padding(.horizontal, 10)
            .padding(.vertical, 4)
            .background(stageTint.opacity(0.15), in: Capsule())
    }

    private var stageLabel: String {
        switch summary.stage {
        case .analyzing: String(localized: "import.pending")
        case .pending: String(localized: "import.pending")
        case .ready: String(localized: "import.ready")
        case .imported: String(localized: "import.imported")
        case .other(let raw): raw
        }
    }

    private var stageIcon: String {
        switch summary.stage {
        case .analyzing: "hourglass"
        case .pending: "person.crop.circle.badge.questionmark"
        case .ready: "checkmark.circle"
        case .imported: "checkmark.seal"
        case .other: "shippingbox"
        }
    }

    private var stageTint: Color {
        switch summary.stage {
        case .imported: .green
        case .ready: .luTint
        default: .orange
        }
    }
}

// MARK: - Preview

#Preview {
    NavigationStack {
        ABSImportHubView()
    }
}
