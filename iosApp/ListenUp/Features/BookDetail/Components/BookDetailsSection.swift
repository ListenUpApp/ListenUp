import SwiftUI

/// The "Details" block on the redesigned Book Detail screen: a heading over a set
/// of key/value rows — Narrated by, Length, Publisher, Released, Language.
///
/// Every row is conditional: a row is omitted entirely when its datum is absent
/// (no "Publisher: —" placeholders). The length row folds the chapter count in
/// when there is one. Keys sit left in secondary text, values right in medium
/// weight, with hairline dividers between rows.
///
/// Pure/presentational: the assembly screen supplies each already-formatted value
/// from the observer / `BookDetail`. Fields `BookDetail` doesn't expose simply
/// aren't passed.
struct BookDetailsSection: View {
    /// Comma-joined narrator names; empty when none.
    let narrators: String
    /// Pre-formatted total length (e.g. "33h 50m"); empty when unknown.
    let lengthLabel: String
    /// Chapter count; folded into the length row when > 0.
    let chapterCount: Int
    let publisher: String?
    /// Pre-formatted release label (e.g. a year or date); `nil` when unknown.
    let released: String?
    let language: String?

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            Text(String(localized: "book.detail_details"))
                .font(.title3.bold())
                .padding(.bottom, 8)

            ForEach(Array(rows.enumerated()), id: \.offset) { index, row in
                if index > 0 {
                    Divider()
                }
                detailRow(key: row.key, value: row.value)
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }

    // MARK: - Rows

    /// The present rows, in display order. Absent data is dropped here so the
    /// body never has to reason about empty values.
    private var rows: [(key: String, value: String)] {
        var result: [(String, String)] = []

        if !narrators.isEmpty {
            result.append((String(localized: "book.detail_narrated_by"), narrators))
        }
        if let length = lengthValue {
            result.append((String(localized: "book.detail_length"), length))
        }
        if let publisher, !publisher.isEmpty {
            result.append((String(localized: "book.detail_publisher"), publisher))
        }
        if let released, !released.isEmpty {
            result.append((String(localized: "book.detail_released"), released))
        }
        if let language, !language.isEmpty {
            result.append((String(localized: "common.language"), language))
        }
        return result
    }

    /// "{length} · {N} chapters" when both present; either alone otherwise; `nil` when neither.
    private var lengthValue: String? {
        let hasLength = !lengthLabel.isEmpty
        let hasChapters = chapterCount > 0
        switch (hasLength, hasChapters) {
        case (true, true):
            let chapters = String(format: String(localized: "book.detail_chapter_count"), chapterCount)
            return "\(lengthLabel) · \(chapters)"
        case (true, false):
            return lengthLabel
        case (false, true):
            return String(format: String(localized: "book.detail_chapter_count"), chapterCount)
        case (false, false):
            return nil
        }
    }

    private func detailRow(key: String, value: String) -> some View {
        HStack(alignment: .firstTextBaseline, spacing: 12) {
            Text(key)
                .font(.subheadline)
                .foregroundStyle(.secondary)

            Spacer(minLength: 12)

            Text(value)
                .font(.subheadline.weight(.medium))
                .multilineTextAlignment(.trailing)
        }
        .padding(.vertical, 11)
        .accessibilityElement(children: .combine)
    }
}

// MARK: - Preview

#Preview("Details — full") {
    BookDetailsSection(
        narrators: "Roy Dotrice",
        lengthLabel: "33h 50m",
        chapterCount: 23,
        publisher: "Random House Audio",
        released: "2003",
        language: "English"
    )
    .padding()
}

#Preview("Details — sparse (rows omitted)") {
    BookDetailsSection(
        narrators: "Kate Reading, Michael Kramer",
        lengthLabel: "45h 30m",
        chapterCount: 0,
        publisher: nil,
        released: nil,
        language: nil
    )
    .padding()
}
