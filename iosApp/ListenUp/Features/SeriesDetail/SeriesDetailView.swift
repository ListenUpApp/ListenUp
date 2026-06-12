import SwiftUI
@preconcurrency import Shared

/// Series detail screen — clean-coral design.
///
/// Layout (iPhone, scrolling):
/// 1. Hero — `CoverStack` + "SERIES" eyebrow + title + author + narrator
/// 2. `StatStrip` — Books / Finished / Total
/// 3. Full-width Continue CTA
/// 4. Optional expandable description
/// 5. "Books in Series" header + order toggle
/// 6. `FieldGroup` of `SeriesBookRow` entries
///
/// iPad (`horizontalSizeClass == .regular`) splits hero + meta into a fixed left
/// column beside a right column with the book list.
struct SeriesDetailView: View {
    let seriesId: String

    @Environment(\.dependencies) private var deps
    @Environment(\.horizontalSizeClass) private var hSize
    @Environment(\.displayScale) private var displayScale
    @State private var observer: SeriesDetailObserver?
    @State private var reversed: Bool = false

    var body: some View {
        Group {
            if let observer, !observer.isLoading {
                if let errorMessage = observer.error {
                    errorView(message: errorMessage)
                } else {
                    content(observer: observer)
                }
            } else {
                loadingView
            }
        }
        .background(Color.luSurface)
        .navigationTitle(observer?.seriesName ?? String(localized: "common.series"))
        .navigationBarTitleDisplayMode(.inline)
        .task(id: seriesId) {
            let vm = deps.createSeriesDetailViewModel()
            let obs = SeriesDetailObserver(viewModel: vm, playerCoordinator: deps.playerCoordinator)
            observer = obs
            obs.loadSeries(seriesId: seriesId)
        }
        .onDisappear {
            observer?.stopObserving()
            observer = nil
        }
    }

    // MARK: - Content

    @ViewBuilder
    private func content(observer: SeriesDetailObserver) -> some View {
        if hSize == .regular {
            iPadLayout(observer: observer)
        } else {
            iPhoneLayout(observer: observer)
        }
    }

    private func iPhoneLayout(observer: SeriesDetailObserver) -> some View {
        ScrollView {
            VStack(spacing: 0) {
                heroSection(observer: observer)
                    .padding(.top, 16)
                statStripSection(observer: observer)
                    .padding(.vertical, 20)
                continueButton(observer: observer)
                    .padding(.horizontal)
                if let description = observer.seriesDescription, !description.isEmpty {
                    ExpandableText(
                        title: String(localized: "common.about"),
                        text: description,
                        lineLimit: 3
                    )
                    .padding(.horizontal)
                    .padding(.top, 20)
                }
                booksSection(observer: observer)
                    .padding(.top, 20)
            }
            .padding(.bottom, 32)
        }
    }

    private func iPadLayout(observer: SeriesDetailObserver) -> some View {
        ScrollView {
            HStack(alignment: .top, spacing: 40) {
                // Left column — hero + stats + CTA
                VStack(spacing: 0) {
                    heroSection(observer: observer)
                    statStripSection(observer: observer)
                        .padding(.vertical, 20)
                    continueButton(observer: observer)
                    if let description = observer.seriesDescription, !description.isEmpty {
                        ExpandableText(
                            title: String(localized: "common.about"),
                            text: description,
                            lineLimit: 3
                        )
                        .padding(.top, 20)
                    }
                }
                .frame(width: 340, alignment: .top)
                // Right column — books list
                VStack(spacing: 0) {
                    booksSection(observer: observer)
                }
                .frame(maxWidth: .infinity, alignment: .leading)
            }
            .padding(.horizontal)
            .padding(.vertical, 24)
        }
    }

    // MARK: - Hero

    private func heroSection(observer: SeriesDetailObserver) -> some View {
        VStack(spacing: 8) {
            CoverStack(books: observer.books, size: 150, peek: 34)
                .accessibilityHidden(true)
            Text(String(localized: "series.eyebrow"))
                .font(.caption.weight(.semibold))
                .kerning(0.6)
                .textCase(.uppercase)
                .foregroundStyle(Color.luTint)
            Text(observer.seriesName)
                .font(.title.bold())
                .multilineTextAlignment(.center)
            if let author = observer.seriesAuthor {
                Text(author)
                    .font(.callout)
                    .foregroundStyle(.primary)
            }
            if let narrator = observer.seriesNarrator {
                Text(verbatim: String(format: String(localized: "series.narrated_by"), narrator))
                    .font(.subheadline)
                    .foregroundStyle(Color.luLabel2)
            }
        }
        .padding(.horizontal)
    }

    // MARK: - Stat strip

    private func statStripSection(observer: SeriesDetailObserver) -> some View {
        StatStrip(stats: [
            .init(value: "\(observer.bookCount)", label: String(localized: "series.stat_books")),
            .init(value: "\(observer.finishedCount)", label: String(localized: "series.stat_finished")),
            .init(value: observer.totalDuration, label: String(localized: "series.stat_total")),
        ])
    }

    // MARK: - Continue CTA

    private func continueButton(observer: SeriesDetailObserver) -> some View {
        Button(action: { observer.continueSeries() }) {
            HStack(spacing: 9) {
                Image(systemName: "play.fill")
                Text(observer.continueButtonTitle).fontWeight(.semibold)
            }
            .frame(maxWidth: .infinity, minHeight: 52)
            .foregroundStyle(Color.luOnTint)
            .background(
                RoundedRectangle(cornerRadius: 14, style: .continuous).fill(Color.luTint)
            )
        }
        .buttonStyle(.plain)
        .disabled(observer.books.isEmpty)
    }

    // MARK: - Books section

    private func booksSection(observer: SeriesDetailObserver) -> some View {
        VStack(alignment: .leading, spacing: 12) {
            booksHeader(observer: observer)
                .padding(.horizontal)
            let displayedBooks = reversed
                ? Array(observer.books.reversed())
                : observer.books
            booksList(books: displayedBooks, observer: observer, scale: displayScale)
                .padding(.horizontal)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }

    private func booksHeader(observer: SeriesDetailObserver) -> some View {
        HStack {
            Text(String(localized: "series.books_header"))
                .font(.title2.bold())
            Text("(\(observer.bookCount))")
                .font(.title2)
                .foregroundStyle(Color.luLabel2)
            Spacer()
            Button(action: { reversed.toggle() }) {
                Image(systemName: reversed ? "arrow.up" : "arrow.down")
                    .font(.subheadline.weight(.semibold))
                    .foregroundStyle(Color.luTint)
            }
            .buttonStyle(.plain)
            .accessibilityLabel(reversed ? "Sort ascending" : "Sort descending")
        }
    }

    private func booksList(books: [BookListItem], observer: SeriesDetailObserver, scale: CGFloat) -> some View {
        // Use a VStack replicating FieldGroup's visual — books are not Identifiable so we
        // can't pass them directly to FieldGroup. The visual result is identical.
        let hairline = 1 / max(scale, 1)
        return VStack(spacing: 0) {
            ForEach(Array(books.enumerated()), id: \.element.idString) { index, book in
                NavigationLink(value: BookDestination(id: book.idString)) {
                    SeriesBookRow(
                        book: book,
                        sequence: book.series.first?.sequence,
                        progress: observer.progress(for: book.idString),
                        isFinished: observer.isFinished(book.idString),
                        isPlaying: observer.isPlaying(book.idString),
                        onPlayTapped: { observer.playBook(book.idString) }
                    )
                }
                .buttonStyle(.plain)
                if index < books.count - 1 {
                    Rectangle()
                        .fill(Color.luSeparator)
                        .frame(height: hairline)
                        .padding(.leading, 76)
                }
            }
        }
        .background(Color.luSurface2)
        .clipShape(RoundedRectangle(cornerRadius: 12, style: .continuous))
        .overlay(
            RoundedRectangle(cornerRadius: 12, style: .continuous)
                .stroke(Color.luSeparator, lineWidth: hairline)
        )
        .shadow(color: .black.opacity(0.05), radius: 3, x: 0, y: 1)
    }

    // MARK: - Error

    private func errorView(message: String) -> some View {
        VStack(spacing: 12) {
            Image(systemName: "exclamationmark.triangle")
                .font(.largeTitle)
                .foregroundStyle(Color.luLabel2)
            Text(message)
                .font(.subheadline)
                .foregroundStyle(Color.luLabel2)
                .multilineTextAlignment(.center)
        }
        .padding()
        .frame(maxWidth: .infinity, maxHeight: .infinity)
    }

    // MARK: - Loading

    private var loadingView: some View {
        VStack(spacing: 16) {
            ProgressView()
            Text(String(localized: "common.loading"))
                .font(.subheadline)
                .foregroundStyle(.secondary)
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
    }
}

#Preview {
    NavigationStack {
        SeriesDetailView(seriesId: "preview")
    }
}
