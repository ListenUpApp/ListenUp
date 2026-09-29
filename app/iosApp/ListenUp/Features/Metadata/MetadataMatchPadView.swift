import SwiftUI
import Shared

/// iPad / regular-width layout: a `NavigationSplitView` with the Audible search in the sidebar and
/// the matched edition with its field checklists in the detail column. The detail column's own stack
/// takes the later steps (chapter review, the updated summary). The detail reuses `MetadataSelectBody`
/// verbatim, so the iPhone push screen and this split stay in lockstep.
///
/// System chrome throughout (HIG, Split views; Sheets): Cancel in the cancellation placement, Apply
/// in the confirmation placement, the title in the navigation bar — where the hand-built modal bar
/// hid the system's and drew its own. The column widths are the split view's own, so a narrow
/// window collapses it to one column rather than crushing a fixed rail.
struct MetadataMatchPadView<Destination: View>: View {
    let observer: MetadataMatchObserver
    @Binding var path: [MetadataStep]
    let onCancel: () -> Void
    let onReviewChapters: () -> Void
    @ViewBuilder let destination: (MetadataStep) -> Destination

    @State private var queryDraft: String = ""
    @State private var selectedAsin: String?

    var body: some View {
        NavigationSplitView {
            searchRail
                .navigationTitle(String(localized: "metadata.find_on_audible"))
                .navigationBarTitleDisplayMode(.inline)
                .toolbar {
                    ToolbarItem(placement: .cancellationAction) {
                        Button(String(localized: "common.cancel"), action: onCancel)
                    }
                }
                .navigationSplitViewColumnWidth(min: 280, ideal: 340, max: 420)
        } detail: {
            NavigationStack(path: $path) {
                detailColumn
                    .navigationTitle(String(localized: "metadata.match_metadata"))
                    .navigationBarTitleDisplayMode(.inline)
                    .toolbar {
                        ToolbarItem(placement: .confirmationAction) {
                            Button {
                                observer.applyMatch()
                            } label: {
                                Label(String(localized: "metadata.apply_metadata"), systemImage: "checkmark")
                            }
                            .disabled(!applyEnabled)
                        }
                    }
                    .navigationDestination(for: MetadataStep.self, destination: destination)
            }
        }
        .navigationSplitViewStyle(.balanced)
        .onAppear { if queryDraft.isEmpty { queryDraft = observer.query } }
    }

    private var applyEnabled: Bool {
        guard case .preview(.ready(let preview)) = observer.phase else { return false }
        return preview.selectedCount > 0 && !preview.isApplying
    }

    // MARK: - Left rail

    private var searchRail: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 18) {
                VStack(alignment: .leading, spacing: 9) {
                    MetadataGroupHeader(text: String(localized: "metadata.find_on_audible")).padding(.leading, 4)
                    MetadataSearchField(text: $queryDraft) { submit() }
                }

                VStack(alignment: .leading, spacing: 9) {
                    MetadataGroupHeader(text: String(localized: "metadata.audible_region")).padding(.leading, 4)
                    FlowLayout(spacing: 8) {
                        ForEach(MetadataRegionOption.all) { region in
                            MetadataGenreChip(label: region.displayName, isOn: region == observer.region) {
                                observer.changeRegion(region)
                            }
                        }
                    }
                }

                railResults
            }
            .padding(20)
        }
    }

    @ViewBuilder
    private var railResults: some View {
        if case .search(let search) = observer.phase {
            switch search {
            case .loaded(let results) where !results.isEmpty:
                VStack(alignment: .leading, spacing: 8) {
                    MetadataGroupHeader(
                        text: String(format: String(localized: "metadata.matches_count"), results.count)
                    ).padding(.leading, 4)
                    FieldGroup(results, separatorInset: 77) { item in
                        MetadataSearchResultRow(item: item, isActive: selectedAsin == item.id) {
                            selectedAsin = item.id
                            observer.selectMatch(item.id)
                        }
                    }
                }
            case .inFlight:
                ProgressView().frame(maxWidth: .infinity).padding(.vertical, 24)
            default:
                EmptyView()
            }
        }
    }

    // MARK: - Right column

    @ViewBuilder
    private var detailColumn: some View {
        switch observer.phase {
        case .preview(.ready(let preview)):
            ScrollView {
                MetadataSelectBody(
                    preview: preview,
                    region: observer.region,
                    observer: observer,
                    onReviewChapters: onReviewChapters,
                    showChangeRow: false
                )
                .padding(24)
            }
        case .preview(.loading):
            LoadingStateView(label: String(localized: "metadata.loading_match"))
        default:
            ContentUnavailableView(
                String(localized: "metadata.select_metadata"),
                systemImage: "sparkles",
                description: Text(String(localized: "metadata.find_subtitle"))
            )
        }
    }

    private func submit() {
        observer.updateQuery(queryDraft)
        selectedAsin = nil
        observer.search()
    }
}
