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
    let progress: Float?
    /// Whether the grid is in multi-select mode (shows a selection circle on every cover).
    let isSelecting: Bool
    /// Whether this book is currently selected (filled vs. empty circle).
    let isSelected: Bool
    /// When non-nil this is a Library card (spec §2.6): the state drives the progress bar, the
    /// finished badge and the last line, and replaces `progress`'s time-left capsule.
    let libraryState: LibraryCardState?
    /// Whether the card may add a "Read by …" line. It does so only at 160 pt or wider (spec §2.6).
    let showsNarrator: Bool

    @Environment(\.restrictedBooks) private var restrictedBooks
    @State private var isWideEnoughForNarrator = false

    init(
        book: BookRow,
        progress: Float? = nil,
        isSelecting: Bool = false,
        isSelected: Bool = false,
        libraryState: LibraryCardState? = nil,
        showsNarrator: Bool = false
    ) {
        self.book = book
        self.progress = progress
        self.isSelecting = isSelecting
        self.isSelected = isSelected
        self.libraryState = libraryState
        self.showsNarrator = showsNarrator
    }

    /// The progress the cover's bar draws: a Library card's own fraction, else the caller's.
    private var barProgress: Float? {
        if let libraryState { return libraryState.fraction }
        return progress
    }

    private var accessibilityText: String {
        let base = CoverAccessibility.label(title: book.title, author: book.authorNames) ?? book.title
        guard let libraryState else { return base }
        return "\(base), \(libraryState.lastLine)"
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
                .overlay(alignment: .topTrailing) {
                    // Finished outranks the documents badge, and steps aside while selecting, as
                    // Android's CompletionBadge does.
                    if libraryState?.isFinished == true && !isSelecting {
                        Image(systemName: "checkmark.circle.fill")
                            .font(.system(size: 22)) // decorative fixed size
                            .symbolRenderingMode(.palette)
                            .foregroundStyle(.white, Color.listenUpOrange)
                            .background(Circle().fill(.white).padding(2))
                            .padding(Spacing.xs)
                            .accessibilityHidden(true)
                    } else if book.hasDocuments {
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
                    if libraryState == nil, let progress, progress > 0, progress < 1, book.duration > 0 {
                        Text(timeLeftLabel(progress: progress))
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

    /// "{Xh Ym} left" — remaining time derived from the book's total duration and listen progress.
    private func timeLeftLabel(progress: Float) -> String {
        let remaining = Int64(Double(book.duration) * Double(1 - progress))
        return String(format: String(localized: "book.time_left"), DurationFormatting.hoursMinutes(ms: remaining))
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
                Text(String(format: String(localized: "library.card_read_by"), book.narratorNames))
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
