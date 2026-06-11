import SwiftUI
@preconcurrency import Shared
import UIKit

/// Book detail screen showing audiobook information.
///
/// Features:
/// - Hero cover with gradient background
/// - Title, subtitle, and series info
/// - Rating, duration, year metadata
/// - Progress indicator for in-progress books
/// - Authors and narrators (clickable to navigate)
/// - Genres as tags
/// - Expandable description
/// - Chapter list
struct BookDetailView: View {
    let bookId: String

    @Environment(\.dependencies) private var deps
    @State private var observer: BookDetailObserver?
    @State private var showAllChapters = false

    private let maxChaptersPreview = 5

    var body: some View {
        Group {
            if let observer, !observer.isLoading {
                content(observer: observer)
            } else {
                loadingView
            }
        }
        .background(Color(.systemBackground))
        .navigationTitle(String(localized: "common.about"))
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .topBarTrailing) {
                Menu {
                    Button(action: {}) {
                        Label(String(localized: "common.edit"), systemImage: "pencil")
                    }
                    Button(action: {}) {
                        Label(String(localized: "common.share"), systemImage: "square.and.arrow.up")
                    }
                } label: {
                    Image(systemName: "ellipsis.circle")
                }
            }
        }
        .task(id: bookId) {
            guard observer == nil else { return }
            let vm = deps.createBookDetailViewModel()
            let obs = BookDetailObserver(viewModel: vm, playerCoordinator: deps.playerCoordinator, downloadService: deps.downloadService)
            observer = obs
            obs.loadBook(bookId: bookId)
        }
    }

    // MARK: - Content

    private func content(observer: BookDetailObserver) -> some View {
        ScrollView {
            VStack(spacing: 24) {
                // Hero section with cover
                heroSection(observer: observer)

                // Metadata and actions
                VStack(spacing: 20) {
                    // Action buttons
                    actionButtons

                    // Contributors (Authors & Narrators)
                    contributorsSection(observer: observer)

                    // Genres
                    if !observer.genres.isEmpty {
                        genresSection(observer: observer)
                    }

                    // Description
                    if !observer.bookDescription.isEmpty {
                        descriptionSection(observer: observer)
                    }

                    // Chapters
                    if !observer.chapters.isEmpty {
                        chaptersSection(observer: observer)
                    }
                }
                .padding(.horizontal)
            }
            .padding(.bottom, 32)
        }
    }

    // MARK: - Hero Section

    private func heroSection(observer: BookDetailObserver) -> some View {
        VStack(spacing: 16) {
            // Cover image
            coverImage(observer: observer)
                .frame(width: 200, height: 200)
                .clipShape(RoundedRectangle(cornerRadius: 12, style: .continuous))
                .shadow(color: .black.opacity(0.3), radius: 20, x: 0, y: 10)

            // Title and subtitle
            VStack(spacing: 6) {
                Text(observer.title)
                    .font(.title2.bold())
                    .multilineTextAlignment(.center)

                if let subtitle = observer.subtitle {
                    Text(subtitle)
                        .font(.subheadline)
                        .foregroundStyle(.secondary)
                        .multilineTextAlignment(.center)
                }

                if let series = observer.series {
                    NavigationLink(value: SeriesDestination(id: observer.book?.seriesId ?? "")) {
                        Text(series)
                            .font(.subheadline)
                            .foregroundStyle(Color.listenUpOrange)
                    }
                }
            }
            .padding(.horizontal)

            // Metadata row
            metadataRow(observer: observer)

            // Progress indicator
            if let progress = observer.progress {
                progressIndicator(progress: progress, timeRemaining: observer.timeRemaining)
            } else if observer.isComplete {
                completedBadge
            }
        }
        .padding(.top, 16)
    }

    private func coverImage(observer: BookDetailObserver) -> some View {
        BookCoverImage(coverPath: observer.coverPath, blurHash: observer.coverBlurHash)
    }

    private func metadataRow(observer: BookDetailObserver) -> some View {
        HStack(spacing: 16) {
            if let rating = observer.rating {
                Label(String(format: "%.1f", rating), systemImage: "star.fill")
                    .foregroundStyle(Color.listenUpOrange)
            }

            Label(observer.duration, systemImage: "clock")
                .foregroundStyle(.secondary)

            if let year = observer.year {
                Text(String(year))
                    .foregroundStyle(.secondary)
            }
        }
        .font(.caption)
    }

    private func progressIndicator(progress: Float, timeRemaining: String?) -> some View {
        ProgressBar(progress: progress, label: timeRemaining)
            .frame(height: 6)
            .padding(.horizontal, 40)
    }

    private var completedBadge: some View {
        HStack(spacing: 4) {
            Image(systemName: "checkmark.circle.fill")
            Text(String(localized: "common.completed"))
        }
        .font(.caption.weight(.medium))
        .foregroundStyle(.green)
    }

    // MARK: - Action Buttons

    private var actionButtons: some View {
        HStack(spacing: 16) {
            Button(action: { observer?.play() }) {
                Label(String(localized: "book.detail_stream_now"), systemImage: "play.fill")
                    .font(.headline)
                    .frame(maxWidth: .infinity)
                    .padding(.vertical, 14)
            }
            .buttonStyle(.borderedProminent)

            if let observer {
                DownloadButton(
                    state: observer.downloadState,
                    progress: observer.downloadProgress,
                    onDownload: { observer.downloadBook() },
                    onCancel: { observer.cancelDownload() },
                    onDelete: { observer.deleteDownload() }
                )
            }
        }
    }

    // MARK: - Contributors Section

    private func contributorsSection(observer: BookDetailObserver) -> some View {
        VStack(alignment: .leading, spacing: 12) {
            if !observer.authors.isEmpty {
                contributorRow(
                    label: String(localized: "book.detail_written_by"),
                    value: observer.authors,
                    contributors: observer.book?.authors ?? []
                )
            }

            if !observer.narrators.isEmpty {
                contributorRow(
                    label: String(localized: "book.detail_narrated_by"),
                    value: observer.narrators,
                    contributors: observer.book?.narrators ?? []
                )
            }
        }
        .padding(16)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background {
            RoundedRectangle(cornerRadius: 12, style: .continuous)
                .fill(.ultraThinMaterial)
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
        }
    }

    private func contributorRow(label: String, value: String, contributors: [BookContributor]) -> some View {
        VStack(alignment: .leading, spacing: 4) {
            Text(label)
                .font(.caption)
                .foregroundStyle(.secondary)

            // Wrapping layout with tappable contributor names
            FlowLayout(spacing: 0) {
                ForEach(Array(contributors.enumerated()), id: \.element.id) { index, contributor in
                    HStack(spacing: 0) {
                        if index > 0 {
                            Text(", ")
                                .foregroundStyle(Color.listenUpOrange)
                        }
                        NavigationLink(value: ContributorDestination(id: contributor.id)) {
                            Text(contributor.name)
                                .foregroundStyle(Color.listenUpOrange)
                        }
                    }
                }
            }
            .font(.subheadline.weight(.medium))
        }
    }

    // MARK: - Genres Section

    private func genresSection(observer: BookDetailObserver) -> some View {
        VStack(alignment: .leading, spacing: 8) {
            Text(String(localized: "book.genres"))
                .font(.headline)

            FlowLayout(spacing: 8) {
                ForEach(observer.genres, id: \.self) { genre in
                    Text(genre)
                        .font(.caption)
                        .padding(.horizontal, 12)
                        .padding(.vertical, 6)
                        .background(Color.listenUpOrange.opacity(0.15), in: Capsule())
                        .foregroundStyle(Color.listenUpOrange)
                }
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }

    // MARK: - Description Section

    private func descriptionSection(observer: BookDetailObserver) -> some View {
        ExpandableText(title: String(localized: "book.detail_synopsis"), text: observer.bookDescription, lineLimit: 4)
    }

    // MARK: - Chapters Section

    private func chaptersSection(observer: BookDetailObserver) -> some View {
        VStack(alignment: .leading, spacing: 12) {
            HStack {
                Text(String(localized: "book.detail_chapters"))
                    .font(.headline)

                Text("\(observer.chapters.count)")
                    .font(.caption.weight(.medium))
                    .padding(.horizontal, 8)
                    .padding(.vertical, 4)
                    .background(Color.listenUpOrange.opacity(0.2), in: Capsule())
                    .foregroundStyle(Color.listenUpOrange)
            }

            let chaptersToShow = showAllChapters ? observer.chapters : Array(observer.chapters.prefix(maxChaptersPreview))

            ForEach(Array(chaptersToShow.enumerated()), id: \.element.id) { index, chapter in
                chapterRow(index: index + 1, chapter: chapter)
            }

            if observer.chapters.count > maxChaptersPreview {
                Button(showAllChapters ? String(localized: "book.detail_show_less") : String(format: String(localized: "book.detail_see_all_chapters"), observer.chapters.count)) {
                    withAnimation(.easeInOut(duration: 0.2)) {
                        showAllChapters.toggle()
                    }
                }
                .font(.subheadline.weight(.medium))
                .frame(maxWidth: .infinity)
                .padding(.vertical, 12)
                .background(Color(.secondarySystemBackground), in: RoundedRectangle(cornerRadius: 8))
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }

    private func chapterRow(index: Int, chapter: ChapterUiModel) -> some View {
        HStack(spacing: 12) {
            Text("\(index)")
                .font(.caption.weight(.semibold))
                .foregroundStyle(.secondary)
                .frame(width: 24)

            VStack(alignment: .leading, spacing: 2) {
                Text(chapter.title)
                    .font(.subheadline.weight(.medium))
                    .lineLimit(1)

                Text(chapter.duration)
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }

            Spacer()

            Button(action: {}) {
                Image(systemName: "play.circle")
                    .font(.title2)
                    .foregroundStyle(.secondary)
            }
        }
        .padding(.vertical, 4)
    }

    // MARK: - Loading View

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

// MARK: - Preview

#Preview {
    NavigationStack {
        BookDetailView(bookId: "preview-book-id")
    }
}
