import SwiftUI

/// The "Readers" block on Book Detail — a social surface showing who is reading or has
/// finished this book.
///
/// A heading with a "{N} listening now" subtitle leads, then flat display-only rows: a
/// tinted initials avatar (with a coral ring when the reader is listening now), the name,
/// and either a progress bar + percent (reading) or a "Finished {date}" line. The current
/// user's row gets a "(You)" suffix. Tapping a row opens that reader's profile.
///
/// Pure/presentational: it takes the projected rows. Renders nothing when empty (the
/// observer's `.empty` phase keeps it out of the layout entirely).
struct BookReadersSection: View {
    let readers: [BookReaderRow]

    private var listeningCount: Int {
        readers.lazy.filter(\.isReading).count
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            header
                .padding(.bottom, 4)

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

            if listeningCount > 0 {
                Text(String(format: String(localized: "book.detail_readers_listening_now"), listeningCount))
                    .font(.footnote)
                    .foregroundStyle(.secondary)
            }
        }
    }

    // MARK: - Row

    private func readerRow(_ reader: BookReaderRow) -> some View {
        HStack(spacing: 13) {
            avatar(reader)

            VStack(alignment: .leading, spacing: 4) {
                Text(name(for: reader))
                    .font(.body.weight(.medium))
                    .foregroundStyle(.primary)
                    .lineLimit(1)

                if reader.isReading {
                    progress(reader)
                } else if let finished = reader.lastFinished {
                    Text(String(
                        format: String(localized: "book.detail_readers_finished"),
                        finished.formatted(date: .abbreviated, time: .omitted)
                    ))
                    .font(.footnote)
                    .foregroundStyle(.secondary)
                }
            }

            Spacer(minLength: 8)

            trailingGlyph(reader)
        }
        .padding(.vertical, 11)
        .accessibilityElement(children: .combine)
        .accessibilityLabel(accessibilityLabel(for: reader))
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

    @ViewBuilder
    private func trailingGlyph(_ reader: BookReaderRow) -> some View {
        if reader.isReading {
            Image(systemName: "sparkles")
                .font(.body)
                .foregroundStyle(Color.listenUpOrange)
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

    private func accessibilityLabel(for reader: BookReaderRow) -> String {
        if let pct = reader.progressPercent {
            return String(
                format: String(localized: "book.detail_readers_a11y_reading"),
                name(for: reader),
                pct
            )
        }
        if let finished = reader.lastFinished {
            return String(
                format: String(localized: "book.detail_readers_a11y_finished"),
                name(for: reader),
                finished.formatted(date: .abbreviated, time: .omitted)
            )
        }
        return name(for: reader)
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
                lastFinished: Date(timeIntervalSince1970: 1_712_000_000)
            )
        ]
    )
    .padding()
}
