import SwiftUI

/// Displays a book cover with title and author below.
///
/// Features:
/// - Authenticated cover loading via `BookCoverImage` (local file → signed server URL →
///   gradient placeholder), so covers appear on the library grid even before the book is
///   downloaded — the local file is not yet present there.
/// - Soft shadow on the cover image
/// - Title and author (single line, truncated)
/// - Optional progress bar at bottom of cover
struct BookCoverCard: View {
    let book: BookRow
    /// A started book's shared progress: the bar draws its fraction and the capsule reads its time left.
    let progress: ListeningProgress?
    /// Whether the grid is in multi-select mode (shows a selection circle on every cover).
    let isSelecting: Bool
    /// Whether this book is currently selected (filled vs. empty circle).
    let isSelected: Bool
    /// When non-nil this is a Library card (spec §2.6): the state drives the progress bar, the
    /// finished badge and the last line, and replaces `progress`'s time-left capsule.
    let libraryState: LibraryCardState?
    /// Whether the card may add a "Read by …" line. It does so only at 160 pt or wider (spec §2.6).
    let showsNarrator: Bool
    /// The book's download, as a badge on the art (canvas I-Library); nil for a book with none.
    let downloadBadge: DownloadBadge?

    @Environment(\.restrictedBooks) private var restrictedBooks
    @State private var isWideEnoughForNarrator = false

    init(
        book: BookRow,
        progress: ListeningProgress? = nil,
        isSelecting: Bool = false,
        isSelected: Bool = false,
        libraryState: LibraryCardState? = nil,
        showsNarrator: Bool = false,
        downloadBadge: DownloadBadge? = nil
    ) {
        self.book = book
        self.progress = progress
        self.isSelecting = isSelecting
        self.isSelected = isSelected
        self.libraryState = libraryState
        self.showsNarrator = showsNarrator
        self.downloadBadge = downloadBadge
    }

    /// The progress the cover's bar draws: a Library card's own fraction, else the caller's.
    private var barProgress: Float? {
        if let libraryState { return libraryState.fraction }
        return progress?.fraction
    }

    private var accessibilityText: String {
        let base = CoverAccessibility.label(title: book.title, author: book.authorNames) ?? book.title
        let withDownload = downloadBadge.map { "\(base), \($0.accessibilityPhrase)" } ?? base
        guard let libraryState else { return withDownload }
        return "\(withDownload), \(libraryState.lastLine)"
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            coverImage
            bookInfo
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .onGeometryChange(for: Bool.self) { $0.size.width >= 160 } action: { isWideEnoughForNarrator = $0 }
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(
            RestrictedMarker.label(
                accessibilityText,
                isRestricted: restrictedBooks?.isRestricted(book.id) == true
            )
        )
    }

    // MARK: - Cover Image

    private var coverImage: some View {
        ZStack(alignment: .bottom) {
            // Square cover — local file, signed server URL, or gradient placeholder,
            // all resolved by BookCoverImage.
            BookCoverImage(book: book)
                .aspectRatio(1, contentMode: .fit)
                .clipShape(RoundedRectangle(cornerRadius: Radius.s))
                .shadow(color: .black.opacity(0.15), radius: 8, x: 0, y: 4)
                // The finished badge sits at the art's bottom-trailing corner (boards Library-iPhone and
                // -iPad), clear of the documents badge and the restricted lock; a finished book has no
                // progress bar to share the bottom edge with. It steps aside while selecting.
                // The download badge sits beside it, inboard (canvas I-Library).
                .overlay(alignment: .bottomTrailing) {
                    if !isSelecting {
                        HStack(spacing: Spacing.xs) {
                            if let downloadBadge {
                                DownloadBadgeView(badge: downloadBadge)
                            }
                            if libraryState?.isFinished == true {
                                Image(systemName: "checkmark.circle.fill")
                                    .font(.system(size: 22)) // decorative fixed size
                                    .symbolRenderingMode(.palette)
                                    .foregroundStyle(.white, Color.listenUpOrange)
                                    .background(Circle().fill(.white).padding(2))
                                    .accessibilityHidden(true)
                            }
                        }
                        .padding(Spacing.xs)
                    }
                }
                .overlay(alignment: .topTrailing) {
                    if book.hasDocuments {
                        Image(systemName: "book.closed.fill")
                            .font(.system(size: 11, weight: .semibold)) // decorative fixed size
                            .foregroundStyle(Color.listenUpOrange)
                            .padding(Spacing.xs)
                            .background(.ultraThinMaterial, in: RoundedRectangle(cornerRadius: Radius.s, style: .continuous))
                            .padding(Spacing.xs)
                            .accessibilityLabel(String(localized: "library.has_documents_badge"))
                    }
                }
                .restrictedMarker(bookId: book.id, isSelecting: isSelecting)
                // Selection circle — top-leading so it never clashes with the top-trailing
                // documents badge. Shown only while the grid is in multi-select mode.
                .overlay(alignment: .topLeading) {
                    if isSelecting {
                        Image(systemName: isSelected ? "checkmark.circle.fill" : "circle")
                            .font(.system(size: 22)) // decorative fixed size
                            .symbolRenderingMode(.palette)
                            .foregroundStyle(
                                isSelected ? Color.luOnTint : .white,
                                isSelected ? Color.listenUpOrange : Color.black.opacity(0.35)
                            )
                            .padding(Spacing.xs)
                            .accessibilityLabel(Text(isSelected
                                ? String(localized: "common.selected")
                                : String(localized: "common.not_selected")))
                    }
                }
                // Time remaining for in-progress books — a small capsule above the progress bar.
                .overlay(alignment: .bottomLeading) {
                    if libraryState == nil, let progress {
                        Text(Self.timeLeftLabel(remainingMs: progress.remainingMs))
                            .font(.caption2.weight(.semibold))
                            .foregroundStyle(.white)
                            .padding(.horizontal, Spacing.xs)
                            .padding(.vertical, 3)
                            .background(.black.opacity(0.55), in: Capsule())
                            .padding(.horizontal, Spacing.xs)
                            .padding(.bottom, Spacing.xs)
                    }
                }

            // Progress bar overlay
            if let barProgress, barProgress > 0 {
                progressOverlay(progress: barProgress)
            }
        }
        // On the whole cover stack, so its badges and progress bar lift with the artwork.
        .coverHoverEffect()
    }

    private func progressOverlay(progress: Float) -> some View {
        GeometryReader { geo in
            VStack {
                Spacer()
                ZStack(alignment: .leading) {
                    // Background track
                    Rectangle()
                        .fill(Color.black.opacity(0.3))
                        .frame(height: 4)

                    // Progress fill
                    Rectangle()
                        .fill(Color.listenUpOrange)
                        .frame(width: geo.size.width * CGFloat(progress), height: 4)
                }
            }
        }
        .clipShape(RoundedRectangle(cornerRadius: Radius.s))
    }

    /// "{Xh Ym} left", from the shared time left: never rebuilt from the bar's fraction.
    static func timeLeftLabel(remainingMs: Int64) -> String {
        String(localized: .bookTimeLeft(DurationFormatting.hoursMinutes(ms: remainingMs)))
    }

    // MARK: - Book Info

    private var bookInfo: some View {
        VStack(alignment: .leading, spacing: 2) {
            Text(book.title)
                .font(.subheadline.weight(.medium))
                .lineLimit(1)
                .truncationMode(.tail)
                .foregroundStyle(.primary)

            Text(book.authorNames)
                .font(.caption)
                .lineLimit(1)
                .truncationMode(.tail)
                .foregroundStyle(.secondary)

            if showsNarrator, isWideEnoughForNarrator, !book.narratorNames.isEmpty {
                Text(String(localized: .libraryCardReadBy(book.narratorNames)))
                    .font(.caption)
                    .lineLimit(1)
                    .truncationMode(.tail)
                    .foregroundStyle(.secondary)
            }

            if let libraryState {
                Text(libraryState.lastLine)
                    .font(libraryState.fraction != nil ? .caption.weight(.semibold) : .caption)
                    .lineLimit(1)
                    .foregroundStyle(libraryState.fraction != nil ? Color.listenUpOrange : Color.secondary)
            }
        }
    }
}

// MARK: - Drag

extension View {
    /// Makes a grid cover draggable as `BookDragItem`, lifted as its own artwork.
    func draggableBookCover(_ book: BookRow) -> some View {
        bookCoverDraggable(title: book.title, author: book.authorNames) {
            BookCoverImage(book: book)
                .frame(width: 120, height: 120)
                .clipShape(RoundedRectangle(cornerRadius: Radius.s))
        }
    }
}

// MARK: - Preview

#Preview("With Progress") {
    ScrollView {
        LazyVGrid(columns: [GridItem(.adaptive(minimum: 150))], spacing: 16) {
            // Can't create real BookListItem in preview, use placeholders
            ForEach(0 ..< 6, id: \.self) { _ in
                BookCoverCardPreview()
            }
        }
        .padding()
    }
}

/// Preview helper since we can't easily create Kotlin BookListItem objects
private struct BookCoverCardPreview: View {
    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            ZStack(alignment: .bottom) {
                RoundedRectangle(cornerRadius: Radius.s)
                    .fill(Color.gray.opacity(0.3))
                    .aspectRatio(2 / 3, contentMode: .fit)
                    .shadow(color: .black.opacity(0.15), radius: 8, x: 0, y: 4)

                // Sample progress
                GeometryReader { geo in
                    VStack {
                        Spacer()
                        Rectangle()
                            .fill(Color.listenUpOrange)
                            .frame(width: geo.size.width * 0.6, height: 4)
                            .frame(maxWidth: .infinity, alignment: .leading)
                    }
                }
                .clipShape(RoundedRectangle(cornerRadius: Radius.s))
            }

            VStack(alignment: .leading, spacing: 2) {
                Text("The Name of the Wind")
                    .font(.subheadline.weight(.medium))
                    .lineLimit(1)

                Text("Patrick Rothfuss")
                    .font(.caption)
                    .lineLimit(1)
                    .foregroundStyle(.secondary)
            }
        }
    }
}

/// A Library cover's download badge: a 24 pt light circle holding an SF Symbol, or the downloading ring; a failure
/// is the system red (canvas I-Library). Decorative — the cover's label says it in words.
struct DownloadBadgeView: View {
    let badge: DownloadBadge

    var body: some View {
        ZStack {
            Circle().fill(badge == .failed ? Color.red : Color.white.opacity(0.94))
            if case .downloading(let progress) = badge {
                ZStack {
                    Circle().stroke(Color.listenUpOrange.opacity(0.25), lineWidth: 2.4)
                    Circle()
                        .trim(from: 0, to: CGFloat(min(max(progress, 0), 1)))
                        .stroke(Color.listenUpOrange, style: StrokeStyle(lineWidth: 2.4, lineCap: .round))
                        .rotationEffect(.degrees(-90))
                }
                .frame(width: 14, height: 14)
            } else if let symbol = badge.systemImage {
                Image(systemName: symbol)
                    .font(.system(size: 12, weight: .bold)) // decorative fixed size, inside a fixed 24 pt badge
                    .foregroundStyle(badge == .failed ? Color.white : Color.black.opacity(0.85))
            }
        }
        .frame(width: 24, height: 24)
        .shadow(color: .black.opacity(0.22), radius: 1.5, y: 1)
        .accessibilityHidden(true)
    }
}
