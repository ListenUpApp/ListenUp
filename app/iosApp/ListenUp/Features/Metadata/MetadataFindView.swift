import SwiftUI
import Shared

/// Step 1 (iPhone): search Audible and pick the matching edition. Query field, region picker,
/// and a results list; a sticky "Use This Match" tray advances once a result is chosen.
struct MetadataFindView: View {
    let observer: MetadataMatchObserver
    let onCancel: () -> Void
    let onUseMatch: () -> Void

    @State private var queryDraft: String = ""
    @State private var selectedAsin: String?

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 18) {
                MetadataFlowHeader(
                    badge: String(localized: "metadata.match_metadata"),
                    title: String(localized: "metadata.find_on_audible"),
                    subtitle: String(localized: "metadata.find_subtitle")
                )

                VStack(alignment: .leading, spacing: 7) {
                    MetadataSearchField(text: $queryDraft) { submit() }
                    Text(String(localized: "metadata.search_helper"))
                        .font(.caption).foregroundStyle(Color.luLabel3)
                        .padding(.leading, 4)
                }

                VStack(alignment: .leading, spacing: 9) {
                    MetadataGroupHeader(text: String(localized: "metadata.audible_region")).padding(.leading, 4)
                    RegionPicker(
                        options: MetadataRegionOption.all,
                        selection: observer.region,
                        label: \.displayName
                    ) { observer.changeRegion($0) }
                }

                resultsSection
            }
            .padding(.horizontal, 18)
            .padding(.vertical, 16)
            .readableWidth(680)
        }
        .background(Color.luSurface)
        .navigationTitle("")
        .navigationBarTitleDisplayMode(.inline)
        .safeAreaInset(edge: .bottom) { tray }
        .toolbar {
            ToolbarItem(placement: .topBarLeading) {
                Button(String(localized: "common.cancel"), action: onCancel)
            }
        }
        .onAppear { if queryDraft.isEmpty { queryDraft = observer.query } }
        // The seeded query (title + author, from `initForBook`) arrives asynchronously over the
        // flow bridge — usually AFTER `onAppear` — so adopt it when it lands, as long as the user
        // hasn't typed anything yet. Without this the search field stays blank on first open.
        .onChange(of: observer.query) { _, seeded in
            if queryDraft.isEmpty { queryDraft = seeded }
        }
    }

    @ViewBuilder
    private var resultsSection: some View {
        switch observer.phase {
        case .search(let search):
            switch search {
            case .idle:
                emptyState(
                    icon: "magnifyingglass",
                    title: String(localized: "metadata.find_on_audible"),
                    subtitle: String(localized: "metadata.start_subtitle")
                )
            case .inFlight:
                ProgressView().frame(maxWidth: .infinity).padding(.vertical, 40)
            case .loaded(let results):
                resultsList(results)
            case .failed(let message):
                emptyState(
                    icon: "exclamationmark.triangle",
                    title: String(localized: "common.error"),
                    subtitle: message
                )
            }
        default:
            ProgressView().frame(maxWidth: .infinity).padding(.vertical, 40)
        }
    }

    @ViewBuilder
    private func resultsList(_ results: [MetadataResultItem]) -> some View {
        if results.isEmpty {
            emptyState(
                icon: "magnifyingglass",
                title: String(localized: "metadata.no_matches_title"),
                subtitle: String(localized: "metadata.no_matches_subtitle")
            )
        } else {
            VStack(alignment: .leading, spacing: 8) {
                MetadataGroupHeader(
                    text: results.count == 1
                        ? String(format: String(localized: "metadata.match_count"), results.count)
                        : String(format: String(localized: "metadata.matches_count"), results.count)
                )
                .padding(.leading, 4)

                FieldGroup(results, separatorInset: 77) { item in
                    MetadataSearchResultRow(item: item, isActive: selectedAsin == item.id) {
                        selectedAsin = item.id
                        observer.selectMatch(item.id)
                    }
                }
            }
        }
    }

    private func emptyState(icon: String, title: String, subtitle: String) -> some View {
        ContentUnavailableView {
            Label(title, systemImage: icon)
        } description: {
            Text(subtitle)
        }
        .frame(maxWidth: .infinity)
        .padding(.vertical, 24)
    }

    @ViewBuilder
    private var tray: some View {
        if selectedAsin != nil {
            VStack(spacing: 0) {
                Divider()
                PrimaryButton(
                    title: String(localized: "metadata.use_this_match"),
                    icon: "arrow.right",
                    action: onUseMatch
                )
                .padding(16)
            }
            .background(.bar)
        }
    }

    private func submit() {
        observer.updateQuery(queryDraft)
        selectedAsin = nil
        observer.search()
    }
}

/// One Audible match in the results list: cover, title, author · narrator, duration, and an active
/// checkmark when chosen. Composes `BookCoverImage`-style remote loading via an async cover URL.
struct MetadataSearchResultRow: View {
    let item: MetadataResultItem
    let isActive: Bool
    let onTap: () -> Void

    var body: some View {
        Button(action: onTap) {
            HStack(spacing: 13) {
                MetadataRemoteCover(url: item.coverURL)
                    .frame(width: 50, height: 50)
                    .clipShape(RoundedRectangle(cornerRadius: 9, style: .continuous))

                VStack(alignment: .leading, spacing: 2) {
                    Text(item.title)
                        .font(.callout.weight(.semibold))
                        .foregroundStyle(.primary)
                        .lineLimit(2)
                    if !item.subtitleLine.isEmpty {
                        Text(item.subtitleLine)
                            .font(.footnote)
                            .foregroundStyle(Color.luLabel2)
                            .lineLimit(1)
                    }
                    if let runtime = item.runtimeMinutes, runtime > 0 {
                        Text(MetadataDuration.format(minutes: runtime))
                            .font(.caption)
                            .foregroundStyle(Color.luLabel3)
                    }
                }
                .frame(maxWidth: .infinity, alignment: .leading)

                if isActive {
                    Image(systemName: "checkmark")
                        .font(.body.weight(.bold))
                        .foregroundStyle(Color.luTint)
                }
            }
            .padding(.horizontal, 14)
            .padding(.vertical, 12)
            .contentShape(Rectangle())
        }
        .buttonStyle(PressScaleButtonStyle())
        .accessibilityAddTraits(isActive ? .isSelected : [])
    }
}

/// Formats a runtime in minutes as "Hh Mm" / "Mm". Pure; shared by result rows and the preview hero.
enum MetadataDuration {
    static func format(minutes: Int) -> String {
        let hours = minutes / 60
        let mins = minutes % 60
        return hours > 0 ? "\(hours)h \(mins)m" : "\(mins)m"
    }
}
