import SwiftUI
import Shared

/// iPad / regular-width layout: a focused full-screen modal with a fixed left search rail beside a
/// flexible right column showing the matched edition and its field checklists. The work gets the
/// whole width (no app sidebar). The right column reuses `MetadataSelectBody` verbatim, so the
/// iPhone push screen and this master–detail stay in lockstep.
///
/// Width-responsive: the left rail is a fixed, comfortable column; the right column flows and its
/// field lists are full-width. In a narrow Split View (where an iPad reports `.compact`) the root
/// falls back to the iPhone push flow, so this only renders when there's genuine width to use.
struct MetadataMatchPadView: View {
    let observer: MetadataMatchObserver
    let onCancel: () -> Void
    let onReviewChapters: () -> Void

    @State private var queryDraft: String = ""
    @State private var selectedAsin: String?

    var body: some View {
        VStack(spacing: 0) {
            modalBar
            Divider()
            HStack(spacing: 0) {
                searchRail
                    .frame(width: 360)
                Divider()
                detailColumn
                    .frame(maxWidth: .infinity)
            }
        }
        .background(Color.luSurface)
        .navigationTitle("")
        .toolbar(.hidden, for: .navigationBar)
        .onAppear { if queryDraft.isEmpty { queryDraft = observer.query } }
    }

    // MARK: - Top bar

    private var modalBar: some View {
        HStack(spacing: 16) {
            Button(String(localized: "common.cancel"), action: onCancel)
                .foregroundStyle(Color.luTint)
            Spacer()
            HStack(spacing: 8) {
                Image(systemName: "sparkles").font(.subheadline)
                Text(String(localized: "metadata.match_metadata")).font(.headline)
            }
            .foregroundStyle(.primary)
            Spacer()
            Button(action: { observer.applyMatch() }) {
                Label(String(localized: "metadata.apply_metadata"), systemImage: "checkmark")
                    .font(.subheadline.weight(.semibold))
                    .foregroundStyle(Color.luOnTint)
                    .padding(.horizontal, 18).frame(height: 40)
                    .background(Capsule().fill(applyEnabled ? Color.luTint : Color.luTint.opacity(0.4)))
            }
            .buttonStyle(PressScaleButtonStyle())
            .disabled(!applyEnabled)
        }
        .padding(.horizontal, 24)
        .frame(height: 64)
        .background(Color.luSurface2)
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
        .background(Color.luSurface2)
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
