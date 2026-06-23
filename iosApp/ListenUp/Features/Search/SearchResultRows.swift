import SwiftUI
@preconcurrency import Shared

// Row views for each search-hit kind. Pure presentation over a `SearchHit`; the tap
// closure lets the row work in both the compact list and the regular-width columns.

/// A book hit: cover thumbnail + title + "author · duration" subtitle.
struct SearchBookRow: View {
    let hit: SearchHit
    let onTap: () -> Void

    private var subtitle: String? {
        [hit.author, hit.formatDuration()]
            .compactMap { $0 }
            .joined(separator: " · ")
            .nilIfEmpty
    }

    var body: some View {
        Button(action: onTap) {
            HStack(spacing: 12) {
                BookCoverImage(bookId: hit.id, coverPath: hit.coverPath, blurHash: nil)
                    .frame(width: 52, height: 52)
                    .clipShape(RoundedRectangle(cornerRadius: 8))
                SearchRowText(title: hit.name, subtitle: subtitle)
            }
        }
        .buttonStyle(.plain)
    }
}

/// A person hit: circular initials avatar + name + role + book count.
struct SearchPersonRow: View {
    let hit: SearchHit
    let onTap: () -> Void

    private var subtitle: String? {
        var parts: [String] = []
        if let role = hit.subtitle?.nilIfEmpty { parts.append(role) }
        if let count = hit.bookCount?.intValue {
            parts.append(String(format: String(localized: "search.count_books"), String(count)))
        }
        return parts.joined(separator: " · ").nilIfEmpty
    }

    var body: some View {
        Button(action: onTap) {
            HStack(spacing: 12) {
                ContributorAvatar(name: hit.name, imagePath: hit.coverPath, id: hit.id, streamsContributorPhoto: true)
                    .frame(width: 52, height: 52)
                SearchRowText(title: hit.name, subtitle: subtitle)
            }
        }
        .buttonStyle(.plain)
    }
}

/// A series hit: SF Symbol tile + name + "author · N books".
struct SearchSeriesRow: View {
    let hit: SearchHit
    let onTap: () -> Void

    private var subtitle: String? {
        var parts: [String] = []
        if let author = hit.author?.nilIfEmpty { parts.append(author) }
        if let count = hit.bookCount?.intValue {
            parts.append(String(format: String(localized: "search.count_books"), String(count)))
        }
        return parts.joined(separator: " · ").nilIfEmpty
    }

    var body: some View {
        Button(action: onTap) {
            HStack(spacing: 12) {
                RoundedRectangle(cornerRadius: 8)
                    .fill(Color.luTint.opacity(0.15))
                    .frame(width: 52, height: 52)
                    .overlay {
                        Image(systemName: "list.bullet")
                            .font(.title3)
                            .foregroundStyle(Color.luTint)
                    }
                SearchRowText(title: hit.name, subtitle: subtitle)
            }
        }
        .buttonStyle(.plain)
    }
}

/// Shared two-line title/subtitle stack for the icon-led rows.
private struct SearchRowText: View {
    let title: String
    let subtitle: String?

    var body: some View {
        VStack(alignment: .leading, spacing: 2) {
            Text(title)
                .font(.body)
                .foregroundStyle(.primary)
                .lineLimit(1)
            if let subtitle {
                Text(subtitle)
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
                    .lineLimit(1)
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }
}

/// A wrapped row of tag capsules; tapping a capsule selects that tag hit.
struct SearchTagsFlow: View {
    let tags: [SearchHit]
    let onTap: (SearchHit) -> Void

    var body: some View {
        FlowLayout(spacing: 8) {
            ForEach(tags, id: \.id) { tag in
                Button { onTap(tag) } label: {
                    Text(tag.name)
                        .font(.subheadline)
                        .foregroundStyle(Color.luTint)
                        .padding(.horizontal, 12)
                        .padding(.vertical, 6)
                        .background(Color.luTint.opacity(0.12), in: Capsule())
                }
                .buttonStyle(.plain)
            }
        }
    }
}

private extension String {
    var nilIfEmpty: String? { isEmpty ? nil : self }
}
