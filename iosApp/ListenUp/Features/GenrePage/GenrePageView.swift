import SwiftUI
import Shared

/// Genre destination page — the standalone landing screen for a single genre, reached by tapping a
/// genre chip on Book Detail (or a sub-genre/breadcrumb crumb on this very screen).
///
/// The iOS idiom is Cupertino, not Android's color-blocked hero: a tinted icon **tile** (the
/// genre's accent hue at low opacity, not a full-bleed gradient) + a tappable breadcrumb + large
/// title, an optional curator blurb, a hairline `StatStrip`, an inset "Include sub-genres" toggle
/// card with a contextual subtitle, capsule sub-genre chips (hue dot + name + count), and a
/// width-responsive cover grid. A leaf genre (no sub-genres) drops the toggle/chip section but
/// still shows its full ancestor breadcrumb.
struct GenrePageView: View {
    let genreId: String
    let genreName: String

    @Environment(\.dependencies) private var deps
    @State private var observer: GenrePageObserver?

    var body: some View {
        Group {
            if let observer {
                content(observer)
            } else {
                LoadingStateView()
            }
        }
        .background(Color.luSurface)
        .navigationTitle(observer?.name ?? genreName)
        .navigationBarTitleDisplayMode(.inline)
        .task(id: genreId) {
            let obs = GenrePageObserver(
                viewModel: deps.createGenreDestinationViewModel(),
                fallbackName: genreName
            )
            observer = obs
            obs.load(genreId: genreId)
        }
        .onDisappear {
            // Release the observer; its deinit cancels the FlowBridge subscriptions.
            observer = nil
        }
    }

    // MARK: - Content by phase

    @ViewBuilder
    private func content(_ observer: GenrePageObserver) -> some View {
        switch observer.phase {
        case .loading:
            LoadingStateView()
        case .notFound:
            ContentUnavailableView(
                String(format: String(localized: "common.not_found"), "Genre"),
                systemImage: "exclamationmark.triangle"
            )
        case .ready:
            grid(observer)
        }
    }

    // MARK: - Grid

    private func grid(_ observer: GenrePageObserver) -> some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 20) {
                header(observer)

                if observer.hasSubs {
                    GenreSubtreeSection(observer: observer)
                }

                Text(audiobookCountLabel(observer.bookCount))
                    .font(.title3.weight(.bold))
                    .foregroundStyle(.primary)

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

    // MARK: - Header (tinted tile + breadcrumb + title + blurb + stat strip)

    private func header(_ observer: GenrePageObserver) -> some View {
        VStack(alignment: .leading, spacing: 16) {
            HStack(alignment: .top, spacing: 16) {
                identityTile(observer)

                VStack(alignment: .leading, spacing: 4) {
                    if !observer.breadcrumb.isEmpty {
                        GenreBreadcrumbRow(crumbs: observer.breadcrumb)
                    }
                    Text(observer.name)
                        .font(.title.weight(.bold))
                        .foregroundStyle(.primary)
                        .lineLimit(2)
                    if let blurb = observer.blurb {
                        Text(blurb)
                            .font(.subheadline)
                            .foregroundStyle(Color.luLabel2)
                            .lineLimit(3)
                    }
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

    /// The tinted, rounded icon tile: the genre's accent hue at low-opacity fill + a faint hue
    /// border + the auto-mapped SF Symbol tinted the same hue. The clean-coral idiom's identity
    /// mark — never a full-bleed color-block hero.
    private func identityTile(_ observer: GenrePageObserver) -> some View {
        RoundedRectangle(cornerRadius: 17, style: .continuous)
            .fill(observer.hue.opacity(0.16))
            .frame(width: 60, height: 60)
            .overlay {
                RoundedRectangle(cornerRadius: 17, style: .continuous)
                    .strokeBorder(observer.hue.opacity(0.3), lineWidth: 1)
            }
            .overlay {
                Image(systemName: observer.symbolName)
                    .font(.system(size: 26, weight: .semibold)) // decorative fixed size
                    .foregroundStyle(observer.hue)
            }
            .accessibilityHidden(true)
    }

    // MARK: - Copy

    private func audiobookCountLabel(_ count: Int) -> String {
        let key = count == 1 ? "genre_destination.audiobook_count" : "genre_destination.audiobooks_count"
        return String(format: String(localized: String.LocalizationValue(key)), count)
    }
}

// MARK: - Breadcrumb

/// Tappable ancestor chain, root-first, chevron-separated — the coral accent, matching the
/// clean-coral idiom's "coral for actions" rule.
private struct GenreBreadcrumbRow: View {
    let crumbs: [GenreCrumbRow]

    var body: some View {
        HStack(spacing: 4) {
            ForEach(Array(crumbs.enumerated()), id: \.element.id) { index, crumb in
                if index > 0 {
                    Image(systemName: "chevron.right")
                        .font(.caption2.weight(.bold))
                        .foregroundStyle(Color.listenUpOrange.opacity(0.6))
                        .accessibilityHidden(true)
                }
                NavigationLink(value: GenreDestination(genreId: crumb.id, genreName: crumb.name)) {
                    Text(crumb.name)
                        .font(.caption.weight(.bold))
                        .foregroundStyle(Color.listenUpOrange)
                }
                .buttonStyle(.plain)
            }
        }
        .accessibilityElement(children: .combine)
    }
}

// MARK: - Preview

#Preview {
    NavigationStack {
        GenrePageView(genreId: "preview", genreName: "Epic Fantasy")
    }
}
