import SwiftUI

/// The "Readers" block on Book Detail — a social surface showing who is reading or has
/// finished this book.
///
/// A heading with a "{N} listening now" subtitle leads, then flat display-only rows: a
/// tinted initials avatar (with a coral ring when the reader is listening now), the name,
/// and either a progress bar + percent (reading) or a "Finished {date}" line. The current
/// user's row gets a "(You)" suffix. A reader's rating sits beside their name as small stars, with
/// their note in quotes beneath (two lines at most); someone who rated the book without reading it
/// here reads "Rated". A reader whose newest read was logged on Hardcover reads "Read {date}" with a
/// Hardcover badge; one whose newest ListenUp finish was logged on Hardcover too reads "Finished {date}"
/// with an "Also on Hardcover" badge. Tapping a row opens that reader's profile.
///
/// Pure/presentational: it takes the projected rows. Renders nothing when empty (the
/// observer's `.empty` phase keeps it out of the layout entirely).
struct BookReadersSection: View {
    let readers: [BookReaderRow]
    @Environment(\.dynamicTypeSize) private var dynamicTypeSize

    /// How a reader row lays out at a text size. At the accessibility sizes a one-line name beside the
    /// stars and a body-sized trailing glyph truncated to "Rig Rea…", or to nothing at all; there the name
    /// wraps, the stars take their own line, and the glyph — which repeats what the row already says —
    /// steps aside. HIG, Typography: let text wrap rather than truncate at large sizes.
    struct RowLayout: Equatable {
        let starsBelowName: Bool
        let nameLineLimit: Int?
        let showsTrailingGlyph: Bool

        init(_ size: DynamicTypeSize) {
            let isLarge = size.isAccessibilitySize
            starsBelowName = isLarge
            nameLineLimit = isLarge ? nil : 1
            showsTrailingGlyph = !isLarge
        }
    }

    private var listeningCount: Int {
        readers.lazy.filter(\.isReading).count
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            header
                .padding(.bottom, Spacing.xxs)

            ForEach(Array(readers.enumerated()), id: \.element.id) { index, reader in
                if index > 0 {
                    Divider()
                        .padding(.leading, 57)
                }
                NavigationLink(value: ProfileDestination(userId: reader.id)) {
                    readerRow(reader)
                }
                .buttonStyle(.pressScaleRow)
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }

    // MARK: - Header

    private var header: some View {
        VStack(alignment: .leading, spacing: 2) {
            Text(String(localized: "book.detail_readers"))
                .font(.headline)
                .accessibilityAddTraits(.isHeader)

            if listeningCount > 0 {
                Text(String(format: String(localized: "book.detail_readers_listening_now"), listeningCount))
                    .font(.footnote)
                    .foregroundStyle(.secondary)
            }
        }
    }

    // MARK: - Row

    private func readerRow(_ reader: BookReaderRow) -> some View {
        let layout = RowLayout(dynamicTypeSize)
        return HStack(spacing: 13) {
            avatar(reader)

            VStack(alignment: .leading, spacing: 4) {
                let nameText = Text(name(for: reader))
                    .font(.body.weight(.medium))
                    .foregroundStyle(.primary)
                    .lineLimit(layout.nameLineLimit)
                if layout.starsBelowName {
                    nameText
                    if let halfStars = reader.halfStars {
                        RatingStarsView(halfStars: halfStars, starSize: 11)
                    }
                } else {
                    HStack(spacing: 8) {
                        nameText
                        if let halfStars = reader.halfStars {
                            RatingStarsView(halfStars: halfStars, starSize: 11)
                        }
                    }
                }

                if reader.isReading {
                    progress(reader)
                } else if let finished = reader.lastFinished, reader.lastFinishedOnHardcover {
                    readOnHardcover(finished)
                } else if let finished = reader.lastFinished, reader.lastFinishedAlsoOnHardcover {
                    finishedAlsoOnHardcover(finished)
                } else if let finished = reader.lastFinished {
                    Text(String(
                        format: String(localized: "book.detail_readers_finished"),
                        finished.formatted(date: .abbreviated, time: .omitted)
                    ))
                    .font(.footnote)
                    .foregroundStyle(.secondary)
                } else if reader.isRatedOnly {
                    Text(String(localized: "book.detail_readers_rated"))
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                }

                if let note = reader.note {
                    Text(String(format: String(localized: "book.detail_readers_note"), note))
                        .font(.footnote.italic())
                        .foregroundStyle(.secondary)
                        .lineLimit(2)
                }
            }

            Spacer(minLength: 8)

            if layout.showsTrailingGlyph {
                trailingGlyph(reader)
            }
        }
        .padding(.vertical, Spacing.s)
        .accessibilityElement(children: .combine)
        .accessibilityLabel(accessibilityLabel(for: reader))
    }

    /// "Read {date}" beside a Hardcover badge — or beneath it when the text size leaves no room, so a
    /// large Dynamic Type size wraps the badge rather than crushing the date.
    private func readOnHardcover(_ finished: Date) -> some View {
        let read = Text(String(
            format: String(localized: "book.detail_readers_read"),
            finished.formatted(date: .abbreviated, time: .omitted)
        ))
        .font(.footnote)
        .foregroundStyle(.secondary)
        let badge = SourceBadge(label: String(localized: "book.detail_readers_hardcover"))
        return ViewThatFits(in: .horizontal) {
            HStack(spacing: Spacing.xs) {
                read.fixedSize()
                badge
            }
            VStack(alignment: .leading, spacing: Spacing.xxs) {
                // Wraps rather than truncating to "Read Apr 30,…" at the accessibility sizes.
                read.fixedSize(horizontal: false, vertical: true)
                badge
            }
        }
    }

    /// "Finished {date}" beside an "Also on Hardcover" badge — one listen logged in both places. Wraps
    /// the badge beneath the date at large Dynamic Type sizes, like `readOnHardcover`.
    private func finishedAlsoOnHardcover(_ finished: Date) -> some View {
        let date = Text(String(
            format: String(localized: "book.detail_readers_finished"),
            finished.formatted(date: .abbreviated, time: .omitted)
        ))
        .font(.footnote)
        .foregroundStyle(.secondary)
        let badge = SourceBadge(label: String(localized: "book.detail_readers_also_on_hardcover"))
        return ViewThatFits(in: .horizontal) {
            HStack(spacing: Spacing.xs) {
                date.fixedSize()
                badge
            }
            VStack(alignment: .leading, spacing: Spacing.xxs) {
                date
                badge
            }
        }
    }

    private func avatar(_ reader: BookReaderRow) -> some View {
        UserAvatarView(userId: reader.id, fallbackName: reader.displayName, size: 44)
            .overlay {
                // A coral ring marks the readers listening right now.
                if reader.isReading {
                    Circle()
                        .strokeBorder(Color.listenUpOrange, lineWidth: 2.5)
                }
            }
    }

    @ViewBuilder
    private func progress(_ reader: BookReaderRow) -> some View {
        if let pct = reader.progressPercent {
            HStack(spacing: 8) {
                GeometryReader { geo in
                    ZStack(alignment: .leading) {
                        Capsule()
                            .fill(Color.luFill)
                        Capsule()
                            .fill(Color.listenUpOrange)
                            .frame(width: geo.size.width * CGFloat(pct) / 100)
                    }
                }
                .frame(height: 5)
                .frame(maxWidth: 150)

                Text("\(pct)%")
                    .font(.caption.weight(.semibold).monospacedDigit())
                    .foregroundStyle(Color.listenUpOrange)
            }
        }
    }

    /// Decorative: the row's words already say reading, rated or finished. Hidden, so the combined row
    /// doesn't take on the `checkmark` symbol's implicit Selected trait — "selected" on a navigation row with
    /// no selection. HIG, VoiceOver: traits describe the element truthfully.
    private func trailingGlyph(_ reader: BookReaderRow) -> some View {
        trailingSymbol(reader)
            .accessibilityHidden(true)
    }

    @ViewBuilder
    private func trailingSymbol(_ reader: BookReaderRow) -> some View {
        if reader.isReading {
            Image(systemName: "sparkles")
                .font(.body)
                .foregroundStyle(Color.listenUpOrange)
        } else if reader.isRatedOnly {
            Image(systemName: "star")
                .font(.body.weight(.semibold))
                .foregroundStyle(.tertiary)
        } else {
            Image(systemName: "checkmark")
                .font(.body.weight(.semibold))
                .foregroundStyle(.tertiary)
        }
    }

    // MARK: - Text helpers

    private func name(for reader: BookReaderRow) -> String {
        reader.isYou
            ? String(format: String(localized: "book.detail_readers_you"), reader.displayName)
            : reader.displayName
    }

    /// The row's one VoiceOver label — its children are combined, so everything the row shows is here.
    func accessibilityLabel(for reader: BookReaderRow) -> String {
        [activityLabel(for: reader), ratingLabel(for: reader)]
            .compactMap { $0 }
            .joined(separator: ", ")
    }

    private func activityLabel(for reader: BookReaderRow) -> String {
        if let pct = reader.progressPercent {
            return String(
                format: String(localized: "book.detail_readers_a11y_reading"),
                name(for: reader),
                pct
            )
        }
        if let finished = reader.lastFinished, reader.lastFinishedOnHardcover {
            return String(
                format: String(localized: "book.detail_readers_a11y_read_on_hardcover"),
                name(for: reader),
                finished.formatted(date: .abbreviated, time: .omitted)
            )
        }
        if let finished = reader.lastFinished, reader.lastFinishedAlsoOnHardcover {
            return String(
                format: String(localized: "book.detail_readers_a11y_finished_also_on_hardcover"),
                name(for: reader),
                finished.formatted(date: .abbreviated, time: .omitted)
            )
        }
        if let finished = reader.lastFinished {
            return String(
                format: String(localized: "book.detail_readers_a11y_finished"),
                name(for: reader),
                finished.formatted(date: .abbreviated, time: .omitted)
            )
        }
        if reader.isRatedOnly {
            return "\(name(for: reader)), \(String(localized: "book.detail_readers_rated"))"
        }
        return name(for: reader)
    }

    /// "3.5 out of 5 stars, “Loved it”" — the row's combined label replaces its children, so the
    /// stars and the note are spoken here.
    private func ratingLabel(for reader: BookReaderRow) -> String? {
        guard let halfStars = reader.halfStars else { return nil }
        let stars = RatingStarsView.starsA11y(halfStars: halfStars)
        guard let note = reader.note else { return stars }
        return "\(stars), \(String(format: String(localized: "book.detail_readers_note"), note))"
    }
}

// MARK: - Preview

#Preview("Readers") {
    BookReadersSection(
        readers: [
            BookReaderRow(
                id: "u1", displayName: "Marcus Lee", initials: "ML",
                isYou: false, progressPercent: 62, lastFinished: nil
            ),
            BookReaderRow(
                id: "u2", displayName: "Priya Shah", initials: "PS",
                isYou: true, progressPercent: 38, lastFinished: nil
            ),
            BookReaderRow(
                id: "u3", displayName: "David Warren", initials: "DW",
                isYou: false, progressPercent: nil,
                lastFinished: Date(timeIntervalSince1970: 1_712_000_000),
                halfStars: 9, note: "The last hour is worth the whole thing."
            ),
            BookReaderRow(
                id: "u4", displayName: "Ana Ruiz", initials: "AR",
                isYou: false, progressPercent: nil, lastFinished: nil,
                halfStars: 6
            ),
            BookReaderRow(
                id: "u5", displayName: "Lena Ortiz", initials: "LO",
                isYou: false, progressPercent: nil,
                lastFinished: Date(timeIntervalSince1970: 1_488_369_600),
                lastFinishedOnHardcover: true
            )
        ]
    )
    .padding()
}
