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
    /// Comma-joined author names; empty when none.
    let authors: String
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
    let format: String?
    let bitrate: String?
    let sampleRate: String?
    let channels: String?
    /// Opens the Cast & Credits sheet (from the "Credits" header link and the "Narrated by" row).
    let onOpenCast: () -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            HStack(alignment: .firstTextBaseline) {
                Text(String(localized: "book.detail_details"))
                    .font(.headline)
                Spacer()
                Button(String(localized: "book.detail_credits"), action: onOpenCast)
                    .font(.subheadline.weight(.medium))
                    .foregroundStyle(.tint)
                    .accessibilityHint(Text(String(localized: "book.detail_credits_hint")))
            }
            .padding(.bottom, 8)

            ForEach(Array(rows.enumerated()), id: \.offset) { index, row in
                if index > 0 { Divider() }
                if row.isCast {
                    Button(action: onOpenCast) {
                        detailRow(key: row.key, value: row.value, isLink: true)
                    }
                    .buttonStyle(.plain)
                    .accessibilityHint(Text(String(localized: "book.detail_credits_hint")))
                } else {
                    detailRow(key: row.key, value: row.value, isLink: false)
                }
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }

    // MARK: - Rows

    /// The present rows, in display order. Absent data is dropped here so the
    /// body never has to reason about empty values.
    private var rows: [(key: String, value: String, isCast: Bool)] {
        var result: [(String, String, Bool)] = []
        if !authors.isEmpty {
            result.append((String(localized: "book.detail_written_by"), authors, false))
        }
        if !narrators.isEmpty {
            result.append((String(localized: "book.detail_narrated_by"), narrators, true))
        }
        if let length = lengthValue {
            result.append((String(localized: "book.detail_length"), length, false))
        }
        if let publisher, !publisher.isEmpty {
            result.append((String(localized: "book.detail_publisher"), publisher, false))
        }
        if let released, !released.isEmpty {
            result.append((String(localized: "book.detail_released"), released, false))
        }
        if let language, !language.isEmpty {
            result.append((String(localized: "common.language"), language, false))
        }
        if let format, !format.isEmpty {
            result.append((String(localized: "book.detail_format"), format, false))
        }
        if let bitrate, !bitrate.isEmpty {
            result.append((String(localized: "book.detail_bitrate"), bitrate, false))
        }
        if let sampleRate, !sampleRate.isEmpty {
            result.append((String(localized: "book.detail_sample_rate"), sampleRate, false))
        }
        if let channels, !channels.isEmpty {
            result.append((String(localized: "book.detail_channels"), channels, false))
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

    private func detailRow(key: String, value: String, isLink: Bool) -> some View {
        HStack(alignment: .firstTextBaseline, spacing: 12) {
            Text(key)
                .font(.subheadline)
                .foregroundStyle(.secondary)
            Spacer(minLength: 12)
            Text(value)
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
        .padding(.vertical, 11)
        .accessibilityElement(children: .combine)
    }
}

// MARK: - Preview

#Preview("Details — full") {
    BookDetailsSection(
        authors: "George R. R. Martin",
        narrators: "Roy Dotrice",
        lengthLabel: "33h 50m",
        chapterCount: 23,
        publisher: "Random House Audio",
        released: "2003",
        language: "English",
        format: "Dolby Atmos",
        bitrate: "320 kbps",
        sampleRate: "48 kHz",
        channels: "5.1",
        onOpenCast: {}
    )
    .padding()
}

#Preview("Details — sparse (rows omitted)") {
    BookDetailsSection(
        authors: "Brandon Sanderson",
        narrators: "Kate Reading, Michael Kramer",
        lengthLabel: "45h 30m",
        chapterCount: 0,
        publisher: nil,
        released: nil,
        language: nil,
        format: nil,
        bitrate: nil,
        sampleRate: nil,
        channels: nil,
        onOpenCast: {}
    )
    .padding()
}
