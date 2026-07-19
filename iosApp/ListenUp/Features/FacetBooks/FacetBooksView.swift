import SwiftUI
import Shared

/// Facet-browse screen — every book carrying a tapped Tag or Mood chip.
///
/// The iOS idiom is Cupertino, not the Android color-blocked hero: the same `FacetIconTile` +
/// facet eyebrow + large title, a hairline `StatStrip` (Books · Length), then a width-responsive
/// cover grid where the art carries the colour — the same tinted-tile identity mark as the genre
/// destination page, so a genre, a tag, and a mood read as one visual family. The tile's hue and
/// icon are derived from the facet's name via the shared `FacetIdentity.hue(name:)`/`.icon(name:)`
/// (`GenrePageView`'s identity derivation), not a hand-picked tag/mood glyph. Columns flow
/// continuously from the available width (`GridItem(.adaptive`), so it stays right from a narrow
/// Split View up to full-screen iPad.
struct FacetBooksView: View {
    let kind: FacetBrowseKind
    let facetId: String
    let facetName: String

    @Environment(\.dependencies) private var deps
    @State private var observer: FacetBooksObserver?

    var body: some View {
        Group {
            if let observer {
                content(observer)
            } else {
                LoadingStateView()
            }
        }
        .background(Color.luSurface)
        .navigationTitle(observer?.facetName ?? facetName)
        .navigationBarTitleDisplayMode(.inline)
        .task(id: facetId) {
            let obs = FacetBooksObserver(
                viewModel: deps.createBrowseFacetViewModel(),
                fallbackName: facetName
            )
            observer = obs
            obs.load(kind: kind.shared, facetId: facetId)
        }
        .onDisappear {
            // Release the observer; its deinit cancels the FlowBridge subscriptions.
            observer = nil
        }
    }

    // MARK: - Content by phase

    @ViewBuilder
    private func content(_ observer: FacetBooksObserver) -> some View {
        switch observer.phase {
        case .loading:
            LoadingStateView()
        case .notFound:
            ContentUnavailableView(
                unavailableTitle,
                systemImage: "exclamationmark.triangle"
            )
        case .ready:
            if observer.books.isEmpty {
                ContentUnavailableView(
                    noBooksTitle,
                    systemImage: kind.glyphName
                )
            } else {
                grid(observer)
            }
        }
    }

    // MARK: - Grid

    private func grid(_ observer: FacetBooksObserver) -> some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 18) {
                header(observer)

                Divider()

                LazyVGrid(
                    columns: [GridItem(.adaptive(minimum: 150), spacing: 16)],
                    spacing: 20
                ) {
                    ForEach(observer.books) { book in
                        NavigationLink(value: BookDestination(id: book.id)) {
                            BookCoverCard(book: book, progress: nil)
                        }
                        .buttonStyle(.plain)
                    }
                }
            }
            .readableWidth()
            .padding(.horizontal)
            .padding(.top, 4)
            .padding(.bottom, 100)
        }
    }

    // MARK: - Header (tinted tile + eyebrow + title + stat strip)

    private func header(_ observer: FacetBooksObserver) -> some View {
        VStack(alignment: .leading, spacing: 16) {
            HStack(alignment: .top, spacing: 16) {
                FacetIconTile(symbolName: observer.symbolName, hue: observer.hue)

                VStack(alignment: .leading, spacing: 2) {
                    Text(kind.eyebrow)
                        .font(.caption.weight(.bold))
                        .tracking(0.6)
                        .textCase(.uppercase)
                        .foregroundStyle(kind == .mood ? Color.listenUpOrange : Color.luLabel2)
                    Text(observer.facetName)
                        .font(.title.weight(.bold))
                        .foregroundStyle(.primary)
                        .lineLimit(2)
                }
            }

            StatStrip(
                stats: [
                    .init(value: "\(observer.bookCount)", label: String(localized: "browse_facet.books_label")),
                    .init(
                        value: DurationFormatting.hoursMinutes(ms: observer.totalDurationMs),
                        label: String(localized: "browse_facet.total_label")
                    )
                ],
                centered: false
            )
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .accessibilityElement(children: .contain)
    }

    // MARK: - Localized copy by kind

    private var unavailableTitle: String {
        switch kind {
        case .tag: String(localized: "browse_facet.unavailable_tag")
        case .mood: String(localized: "browse_facet.unavailable_mood")
        }
    }

    private var noBooksTitle: String {
        switch kind {
        case .tag: String(localized: "browse_facet.no_books_tag")
        case .mood: String(localized: "browse_facet.no_books_mood")
        }
    }
}

// MARK: - FacetBrowseKind presentation

private extension FacetBrowseKind {
    /// The facet's SF Symbol — tag glyph vs the mood sparkles, matching the detail chips.
    var glyphName: String {
        switch self {
        case .tag: "tag"
        case .mood: "sparkles"
        }
    }

    /// The uppercase eyebrow above the title.
    var eyebrow: String {
        switch self {
        case .tag: String(localized: "browse_facet.kind_tag")
        case .mood: String(localized: "browse_facet.kind_mood")
        }
    }
}

#Preview {
    NavigationStack {
        FacetBooksView(kind: .mood, facetId: "preview", facetName: "Atmospheric")
    }
}
