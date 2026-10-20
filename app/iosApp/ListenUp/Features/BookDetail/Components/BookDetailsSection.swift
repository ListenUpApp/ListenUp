import SwiftUI

/// Book Detail's "Details" (spec §3.2.1 D31): a heading over the shared rule's rows — Publisher, Released, Genre,
/// Language, Length, Added, ISBN, ASIN — then three links: All Credits (Cast & Credits), Files & Audio (its sheet) and
/// Maps & Illustrations (one document opens at once, more open a list). A row whose datum is absent is not in `rows`.
/// Keys sit left in secondary text, values right in medium weight, with hairline dividers between rows; a link row is
/// a button with a chevron.
struct BookDetailsSection: View {
    let rows: [BookDetailRowValue]
    let onLink: (BookDetailRowLink) -> Void

    var body: some View {
        if !rows.isEmpty {
            VStack(alignment: .leading, spacing: 0) {
                Text(String(localized: "book.detail_details"))
                    .font(.headline)
                    .accessibilityAddTraits(.isHeader)
                    .padding(.bottom, Spacing.xs)

                ForEach(Array(rows.enumerated()), id: \.offset) { index, row in
                    if index > 0 { Divider() }
                    let cell = row.cell
                    if let link = cell.link {
                        Button { onLink(link) } label: { detailRow(cell, isLink: true) }
                            .buttonStyle(.plain)
                    } else {
                        detailRow(cell, isLink: false)
                    }
                }
            }
            .frame(maxWidth: .infinity, alignment: .leading)
        }
    }

    private func detailRow(_ cell: BookDetailCell, isLink: Bool) -> some View {
        HStack(alignment: .firstTextBaseline, spacing: 12) {
            Text(cell.label)
                .font(.subheadline)
                .foregroundStyle(.secondary)
            Spacer(minLength: 12)
            Text(cell.value)
                .font(.subheadline.weight(.medium))
                .foregroundStyle(isLink ? AnyShapeStyle(.tint) : AnyShapeStyle(.primary))
                .multilineTextAlignment(.trailing)
            if isLink {
                Image(systemName: "chevron.right")
                    .font(.caption.weight(.semibold))
                    .foregroundStyle(.tint)
                    .accessibilityHidden(true)
            }
        }
        .padding(.vertical, Spacing.s)
        .frame(minHeight: 44)
        .contentShape(Rectangle())
        .accessibilityElement(children: .combine)
    }
}

// MARK: - Preview

#Preview("Details") {
    BookDetailsSection(
        rows: [
            .publisher("Macmillan Audio"),
            .released(2010),
            .genre(name: "Epic fantasy", abridged: false),
            .language("English"),
            .length(durationMs: 163_800_000, chapterCount: 75),
            .isbn("9781427209979"),
            .allCredits(count: 6),
            .filesAndAudio(container: "M4B", bitrate: "64 kbps"),
            .maps(count: 1, onlyDocumentId: "d1", format: "PDF", totalBytes: 18_874_368)
        ],
        onLink: { _ in }
    )
    .padding()
}
