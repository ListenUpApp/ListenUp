import SwiftUI
import Shared

/// File organization — where books live on disk, and the one explicit action that moves them.
/// Reached from Administration › Management.
///
/// **Two actions that must not look alike.** Save (the navigation bar's confirmation action) keeps
/// the rules for books that arrive from now on and moves nothing. Organize Library is the sweep over
/// the books already there: it fetches the server's plan, shows it in a consent sheet, and only on
/// confirm saves the rules *and* moves files, with progress and a report in the form itself.
///
/// Each rule is an inline picker in a grouped `Form`, so every choice is visible with a checkmark on
/// the current one (HIG, Pickers; Lists and tables). Styles appear only where their folder exists, as
/// on Android and web.
struct OrganizeSettingsView: View {
    @Environment(\.dependencies) private var deps

    @State private var observer: OrganizeSettingsObserver?

    var body: some View {
        Group {
            if let observer {
                content(observer)
            } else {
                LoadingStateView()
            }
        }
        .background(Color.luSurface)
        .navigationTitle(String(localized: "admin.organize"))
        .navigationBarTitleDisplayMode(.large)
        .onAppear {
            if observer == nil {
                observer = OrganizeSettingsObserver(viewModel: deps.createOrganizeSettingsViewModel())
            }
        }
    }

    // MARK: - Phase routing

    @ViewBuilder
    private func content(_ observer: OrganizeSettingsObserver) -> some View {
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

    private func ready(_ model: OrganizeReadyModel, observer: OrganizeSettingsObserver) -> some View {
        Form {
            if let run = model.run {
                OrganizeRunSection(
                    run: run,
                    onResume: observer.resumeAfterFailure,
                    onDone: observer.dismissRunReport
                )
            }
            rulePicker(
                String(localized: "admin.organize_structure"),
                options: OrganizeReadyModel.presets,
                selection: Binding(get: { model.preset }, set: observer.setPreset),
                title: OrganizeReadyModel.title
            )
            if model.showsSeriesPrefix {
                rulePicker(
                    String(localized: "admin.organize_series_prefix"),
                    options: OrganizeReadyModel.seriesPrefixes,
                    selection: Binding(get: { model.seriesPrefix }, set: observer.setSeriesPrefix),
                    title: OrganizeReadyModel.title
                )
            }
            if model.showsAuthorForm {
                rulePicker(
                    String(localized: "admin.organize_author_form"),
                    options: OrganizeReadyModel.authorForms,
                    selection: Binding(get: { model.authorForm }, set: observer.setAuthorForm),
                    title: OrganizeReadyModel.title
                )
            }
            organizeSection(model, observer: observer)
        }
        .readableListWidth()
        // No rule changes, and no second sweep, while files are moving under the first.
        .disabled(model.isRunning)
        .toolbar {
            ToolbarItem(placement: .confirmationAction) {
                Button(String(localized: "common.save"), action: observer.saveRules)
                    .disabled(model.isWorking || model.isRunning)
            }
        }
        .sheet(isPresented: previewBinding(model, observer: observer)) {
            if let preview = model.preview {
                OrganizePreviewSheet(
                    preview: preview,
                    onConfirm: observer.confirmOrganize,
                    onCancel: observer.dismissPreview
                )
            }
        }
        .haptic(.commit, trigger: observer.noticeCount)
    }

    private func rulePicker<Option: Hashable>(
        _ label: String,
        options: [Option],
        selection: Binding<Option>,
        title: @escaping (Option) -> String
    ) -> some View {
        Section(label) {
            Picker(label, selection: selection) {
                ForEach(options, id: \.self) { option in
                    Text(title(option)).tag(option)
                }
            }
            .pickerStyle(.inline)
            .labelsHidden()
        }
    }

    /// The sweep, as a full-width row of its own rather than a toolbar item beside Save — the one
    /// action here that moves files should never be a slip away from the one that doesn't.
    private func organizeSection(_ model: OrganizeReadyModel, observer: OrganizeSettingsObserver) -> some View {
        Section {
            Button(action: observer.organize) {
                HStack {
                    Text(String(localized: "admin.organize_run"))
                    Spacer()
                    if model.isWorking {
                        ProgressView()
                    }
                }
            }
            .disabled(model.isWorking)
        } footer: {
            if let notice = observer.notice {
                Label(notice, systemImage: "checkmark.circle.fill")
            }
        }
    }

    private func previewBinding(_ model: OrganizeReadyModel, observer: OrganizeSettingsObserver) -> Binding<Bool> {
        Binding(
            get: { model.preview != nil },
            set: { presented in if !presented { observer.dismissPreview() } }
        )
    }
}

// MARK: - Consent sheet

/// What the sweep would do, and a sample of it, before anything moves. A sheet rather than an alert
/// because the plan is a list, and an alert that scrolls is one the HIG asks us to avoid (HIG, Alerts;
/// Sheets). Cancel leads, Organize Now trails (HIG, Sheets).
private struct OrganizePreviewSheet: View {
    let preview: OrganizePreviewModel
    let onConfirm: () -> Void
    let onCancel: () -> Void

    var body: some View {
        NavigationStack {
            List {
                Section {
                    if let moves = preview.movesSummary { Text(moves) }
                    if let renames = preview.renamesSummary { Text(renames) }
                }
                Section {
                    ForEach(preview.rows) { row in
                        VStack(alignment: .leading, spacing: 2) {
                            Text(row.before)
                                .font(.footnote)
                                .foregroundStyle(.secondary)
                            Label(row.after, systemImage: "arrow.turn.down.right")
                                .font(.subheadline)
                        }
                        .accessibilityElement(children: .ignore)
                        .accessibilityLabel(
                            String(format: String(localized: "admin.organize_confirm_row"), row.before, row.after)
                        )
                    }
                } footer: {
                    if preview.moreCount > 0 {
                        Text(String(format: String(localized: "admin.organize_confirm_more_rows"), preview.moreCount))
                    }
                }
            }
            .navigationTitle(String(localized: "admin.organize_confirm_title"))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button(String(localized: "common.cancel"), action: onCancel)
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button(String(localized: "admin.organize_confirm_run"), action: onConfirm)
                }
            }
        }
    }
}

// MARK: - Run

/// Progress while a run moves files — a determinate bar, since the server counts books as it goes
/// (HIG, Progress indicators: "When possible, use a determinate progress indicator") — then the report,
/// with Resume beside Done when some books failed.
private struct OrganizeRunSection: View {
    let run: OrganizeRunModel
    let onResume: () -> Void
    let onDone: () -> Void

    var body: some View {
        Section {
            if run.isFinished {
                Text(String(
                    format: String(localized: "admin.organize_report_summary"), run.movedBooks, run.failedBooks
                ))
                if run.hasFailures {
                    Button(String(localized: "admin.organize_report_resume"), action: onResume)
                }
                Button(String(localized: "common.done"), action: onDone)
            } else {
                ProgressView(value: run.fraction) {
                    Text(String(localized: "admin.organize_progress_title"))
                } currentValueLabel: {
                    Text(String(format: String(localized: "admin.organize_progress_count"), run.completed, run.total))
                }
            }
        } header: {
            if run.isFinished {
                Text(String(localized: "admin.organize_report_done"))
            }
        }
    }
}
