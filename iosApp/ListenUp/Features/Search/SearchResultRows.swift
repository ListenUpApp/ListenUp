import SwiftUI
import Shared

// Row views for each search-hit kind. Pure presentation over a native `SearchRow`; the tap
// closure lets the row work in both the compact list and the regular-width columns.

/// A book hit: cover thumbnail + title + "author · duration" subtitle.
struct SearchBookRow: View {
    let row: SearchRow
    let onTap: () -> Void

    var body: some View {
        Button(action: onTap) {
            HStack(spacing: 12) {
                BookCoverImage(bookId: row.id, coverPath: row.coverPath, coverHash: row.coverHash)
                    .frame(width: 52, height: 52)
                    .clipShape(RoundedRectangle(cornerRadius: 8))
                SearchRowText(title: row.name, subtitle: row.subtitle)
            }
        }
        .buttonStyle(.plain)
    }
}

/// A person hit: circular initials avatar + name + role + book count.
struct SearchPersonRow: View {
    let row: SearchRow
    let onTap: () -> Void

    var body: some View {
        Button(action: onTap) {
            HStack(spacing: 12) {
                ContributorAvatar(name: row.name, imagePath: row.coverPath, id: row.id, streamsContributorPhoto: true)
                    .frame(width: 52, height: 52)
                SearchRowText(title: row.name, subtitle: row.subtitle)
            }
        }
        .buttonStyle(.plain)
    }
}

/// A series hit: SF Symbol tile + name + "author · N books".
struct SearchSeriesRow: View {
    let row: SearchRow
    let onTap: () -> Void

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
                SearchRowText(title: row.name, subtitle: row.subtitle)
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
    let tags: [SearchRow]
    let onTap: (SearchRow) -> Void

    var body: some View {
        FlowLayout(spacing: 8) {
            ForEach(tags) { tag in
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
