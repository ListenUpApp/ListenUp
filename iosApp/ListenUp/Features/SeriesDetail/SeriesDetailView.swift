import SwiftUI
@preconcurrency import Shared
import UIKit

/// Series detail screen — Liquid Glass design.
///
/// Layout:
/// - Hero cover with shadow (no card container)
/// - Title + stats on glass panel
/// - Expandable description
/// - Ordered book list with glass row cards
struct SeriesDetailView: View {
    let seriesId: String

    @Environment(\.dependencies) private var deps
    @State private var observer: SeriesDetailObserver?

    var body: some View {
        Group {
            if let observer, !observer.isLoading {
                content(observer: observer)
            } else {
                loadingView
            }
        }
        .background(Color(.systemBackground))
        .navigationTitle(observer?.seriesName ?? String(localized: "common.series"))
        .navigationBarTitleDisplayMode(.inline)
        .task(id: seriesId) {
            let vm = deps.createSeriesDetailViewModel()
            let obs = SeriesDetailObserver(viewModel: vm)
            observer = obs
            obs.loadSeries(seriesId: seriesId)
        }
        .onDisappear {
            observer?.stopObserving()
            observer = nil
        }
    }

    // MARK: - Content

    private func content(observer: SeriesDetailObserver) -> some View {
        ScrollView {
            VStack(spacing: 24) {
                headerSection(observer: observer)

                if let description = observer.seriesDescription, !description.isEmpty {
                    ExpandableText(
                        title: String(localized: "common.about"),
                        text: description,
                        lineLimit: 3
                    )
                    .padding(.horizontal)
                }

                booksSection(observer: observer)
            }
            .padding(.bottom, 32)
        }
    }

    // MARK: - Header

    private func headerSection(observer: SeriesDetailObserver) -> some View {
        VStack(spacing: 16) {
            // Cover — floating with shadow, no card
            BookCoverImage(coverPath: observer.coverPath, blurHash: nil)
                .frame(width: 200, height: 200)
                .clipShape(RoundedRectangle(cornerRadius: 8, style: .continuous))
                .shadow(color: .black.opacity(0.2), radius: 16, x: 0, y: 8)

            VStack(spacing: 8) {
                Text(observer.seriesName)
                    .font(.title2.bold())
                    .multilineTextAlignment(.center)

                HStack(spacing: 16) {
                    Label(
                        "\(observer.bookCount) \(observer.bookCount == 1 ? String(localized: "contributor.audiobook_count") : String(localized: "contributor.audiobooks_count"))",
                        systemImage: "books.vertical"
                    )
                    Label(observer.totalDuration, systemImage: "clock")
                }
                .font(.subheadline)
                .foregroundStyle(.secondary)
            }
            .padding(.horizontal)
        }
        .padding(.top, 16)
    }

    // MARK: - Books

    private func booksSection(observer: SeriesDetailObserver) -> some View {
        VStack(alignment: .leading, spacing: 12) {
            Text(String(localized: "series.books_in_series"))
                .font(.headline)
                .padding(.horizontal)

            ForEach(Array(observer.books.enumerated()), id: \.element.idString) { index, book in
                bookRow(book: book, sequence: book.seriesSequence ?? "\(index + 1)")
                    .padding(.horizontal)
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }

    private func bookRow(book: BookListItem, sequence: String) -> some View {
        NavigationLink(value: BookDestination(id: book.idString)) {
            HStack(spacing: 16) {
                // Sequence badge
                Text("#\(sequence)")
                    .font(.caption.bold())
                    .foregroundStyle(Color.listenUpOrange)
                    .frame(width: 36)

                // Cover thumbnail
                BookCoverImage(book: book)
                    .frame(width: 50, height: 50)
                    .clipShape(RoundedRectangle(cornerRadius: 6, style: .continuous))
                    .shadow(color: .black.opacity(0.1), radius: 4, x: 0, y: 2)

                // Info
                VStack(alignment: .leading, spacing: 4) {
                    Text(book.title)
                        .font(.subheadline.weight(.medium))
                        .foregroundStyle(.primary)
                        .lineLimit(2)

                    HStack(spacing: 8) {
                        Text(book.authorNames)
                            .font(.caption)
                            .foregroundStyle(.secondary)
                            .lineLimit(1)

                        Text("•")
                            .foregroundStyle(.tertiary)

                        Text(book.formatDuration())
                            .font(.caption)
                            .foregroundStyle(.secondary)
                    }
                }

                Spacer()

                Image(systemName: "chevron.right")
                    .font(.caption.weight(.semibold))
                    .foregroundStyle(.tertiary)
            }
            .padding(12)
            .background {
                RoundedRectangle(cornerRadius: 12, style: .continuous)
                    .fill(.regularMaterial)
                    .overlay {
                        RoundedRectangle(cornerRadius: 12, style: .continuous)
                            .strokeBorder(
                                LinearGradient(
                                    colors: [.white.opacity(0.3), .white.opacity(0.1)],
                                    startPoint: .topLeading,
                                    endPoint: .bottomTrailing
                                ),
                                lineWidth: 0.5
                            )
                    }
                    .shadow(color: .black.opacity(0.08), radius: 8, x: 0, y: 4)
            }
        }
        .buttonStyle(.plain)
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
