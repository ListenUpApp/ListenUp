import SwiftUI
import Shared

/// Presented sheet for editing a series: cover, name, description. Bound to
/// `SeriesEditViewModel` via `SeriesEditObserver`.
struct SeriesEditView: View {
    let seriesId: String
    /// Called with the surviving series' id when a merge commits. The series this sheet was editing
    /// has been soft-deleted by then, so the presenter must go somewhere other than back to it.
    var onMergedInto: ((String) -> Void)?

    @Environment(\.dependencies) private var deps
    @Environment(\.dismiss) private var dismiss
    @State private var observer: SeriesEditObserver?
    @State private var showMergeSheet = false

    var body: some View {
        Group {
            if let observer {
                EditSheetScaffold(
                    title: String(localized: "series.edit_title"),
                    canSave: observer.hasChanges,
                    isSaving: observer.isSaving,
                    onCancel: { observer.onCancel(); dismiss() },
                    onSave: { observer.onSave() }
                ) {
                    VStack(spacing: 20) {
                        ImageEditHeader(
                            shape: .rounded,
                            size: 120,
                            isUploading: observer.isUploadingCover,
                            canRemove: observer.displayCoverPath != nil,
                            onPicked: { observer.onCoverSelected($0) },
                            onRemove: { observer.onCoverRemoved() }
                        ) {
                            BookCoverImage(coverPath: observer.displayCoverPath)
                        }
                        .padding(.top, 8)

                        AppTextField(
                            placeholder: "",
                            text: Binding(get: { observer.name }, set: { observer.onNameChanged($0) }),
                            entry: .words,
                            label: String(localized: "series.edit_name")
                        )
                        .fieldCard()
                        .padding(.horizontal)

                        AppTextField(
                            placeholder: String(localized: "series.edit_description_placeholder"),
                            text: Binding(
                                get: { observer.seriesDescription },
                                set: { observer.onDescriptionChanged($0) }
                            ),
                            entry: .sentences,
                            label: String(localized: "series.edit_description"),
                            axis: .vertical
                        )
                        .fieldCard()
                        .padding(.horizontal)

                        mergeSection(observer)
                            .fieldCard()
                            .padding(.horizontal)

                        VStack(alignment: .leading, spacing: 12) {
                            Text(String(localized: "merge_history.section_title")).font(.headline)
                            MergeHistoryListView(
                                model: observer.mergeHistory,
                                onUndo: { observer.onUndoMerge($0) },
                                onRetry: { observer.onRetryMergeHistory() }
                            )
                        }
                        .fieldCard()
                        .padding(.horizontal)
                    }
                }
                .sheet(isPresented: $showMergeSheet, onDismiss: { observer.onMergeDialogDismissed() }) {
                    SeriesMergeSheet(
                        candidates: observer.mergeCandidates,
                        query: observer.mergeQuery,
                        bookCount: observer.bookCount,
                        onQueryChange: { observer.onMergeQueryChange($0) },
                        onSelect: { observer.onMergeInto($0) },
                        onDismiss: { showMergeSheet = false }
                    )
                }
                .alert(
                    String(localized: "common.error"),
                    isPresented: Binding(get: { observer.error != nil }, set: { _ in observer.onDismissError() })
                ) {
                    Button(String(localized: "common.ok"), role: .cancel) { observer.onDismissError() }
                } message: {
                    Text(observer.error ?? "")
                }
                .onChange(of: observer.didFinish) { _, finished in
                    guard finished else { return }
                    // Report the merge BEFORE dismissing: once this sheet is gone the observer goes
                    // with it, and the presenter would have no way to learn where the books went.
                    if let merged = observer.mergedIntoSeriesId { onMergedInto?(merged) }
                    dismiss()
                }
            } else {
                LoadingStateView()
            }
        }
        .task(id: seriesId) {
            let obs = SeriesEditObserver(viewModel: deps.createSeriesEditViewModel())
            observer = obs
            obs.loadSeries(seriesId: seriesId)
        }
    }

    /// What this series holds, and the way to fold it into another — which the merge history can
    /// undo, so the copy says so rather than calling it permanent.
    private func mergeSection(_ observer: SeriesEditObserver) -> some View {
        VStack(alignment: .leading, spacing: 10) {
            Text(
                String(
                    format: String(localized: observer.bookCount == 1 ? "common.book_count" : "common.books_count"),
                    Int32(observer.bookCount)
                )
            )
            .font(.subheadline)
            .foregroundStyle(.secondary)
            Button {
                observer.onMergeDialogOpened()
                showMergeSheet = true
            } label: {
                Label(String(localized: "series.merge_into"), systemImage: "arrow.triangle.merge")
            }
            .buttonStyle(.bordered)
            .disabled(observer.mergeInProgress)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }
}

private func bookCountLine(_ count: Int) -> String {
    String(
        format: String(localized: count == 1 ? "series.merge_book_count" : "series.merge_book_count_plural"),
        Int32(count)
    )
}

/// Searchable picker for the series to fold this one into — the iOS peer of the Compose and web
/// merge pickers. Select, then confirm: the merge moves every book, and the confirmation says how
/// many and that it can be undone from the merge history.
private struct SeriesMergeSheet: View {
    let candidates: [MergeCandidate]
    let query: String
    let bookCount: Int
    let onQueryChange: (String) -> Void
    let onSelect: (String) -> Void
    let onDismiss: () -> Void

    @State private var pendingTarget: MergeCandidate?

    var body: some View {
        NavigationStack {
            List {
                ForEach(candidates) { candidate in
                    Button { pendingTarget = candidate } label: {
                        Text(candidate.name).foregroundStyle(.primary)
                    }
                }
                if candidates.isEmpty {
                    Text(String(localized: "series.merge_no_matches")).foregroundStyle(.secondary)
                } else if candidates.count >= Self.cap {
                    // A full page means more exist behind a search; a silently capped list reads
                    // as complete, and "it isn't in the list" is how the wrong series gets picked.
                    Text(String(format: String(localized: "series.merge_truncated"), Int32(Self.cap)))
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }
            }
            .navigationTitle(String(localized: "series.merge_title"))
            .navigationBarTitleDisplayMode(.inline)
            .searchable(
                text: Binding(get: { query }, set: { onQueryChange($0) }),
                prompt: String(localized: "series.merge_search_placeholder")
            )
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button(String(localized: "common.cancel")) { onDismiss() }
                }
            }
            .confirmationDialog(
                pendingTarget?.name ?? "",
                isPresented: Binding(get: { pendingTarget != nil }, set: { if !$0 { pendingTarget = nil } }),
                titleVisibility: .visible
            ) {
                Button(String(localized: "series.merge_confirm"), role: .destructive) {
                    if let target = pendingTarget { onSelect(target.id) }
                    pendingTarget = nil
                    onDismiss()
                }
                Button(String(localized: "common.cancel"), role: .cancel) { pendingTarget = nil }
            } message: {
                Text(
                    [
                        String(localized: "series.merge_body"),
                        bookCountLine(bookCount),
                        String(localized: "merge_history.can_undo")
                    ].joined(separator: " ")
                )
            }
        }
    }

    private static let cap = Int(
        ExportedKotlinPackages.com.calypsan.listenup.client.presentation.seriesedit.MAX_MERGE_CANDIDATES
    )
}
