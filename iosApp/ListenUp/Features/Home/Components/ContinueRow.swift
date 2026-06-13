import SwiftUI

/// A single Continue-Listening row: cover art, title/author/time-left, and a circular
/// progress ring. The whole row navigates to the book's detail screen.
///
/// When `item.isLoading` is true the row renders a shimmer skeleton instead of real
/// content — a sync is in flight and the book's data hasn't landed yet.
struct ContinueRow: View {
    let item: ContinueItem

    private let coverSize: CGFloat = 60

    var body: some View {
        if item.isLoading {
            skeleton
        } else {
            NavigationLink(value: BookDestination(id: item.id)) {
                content
            }
            .buttonStyle(.plain)
        }
    }

    // MARK: - Content

    private var content: some View {
        HStack(spacing: 12) {
            BookCoverImage(coverPath: item.coverPath, blurHash: item.blurHash)
                .frame(width: coverSize, height: coverSize)
                .clipShape(RoundedRectangle(cornerRadius: 8))
                .shadow(color: .black.opacity(0.15), radius: 4, x: 0, y: 2)

            VStack(alignment: .leading, spacing: 3) {
                Text(item.title)
                    .font(.subheadline.weight(.medium))
                    .lineLimit(2)
                    .foregroundStyle(.primary)

                Text(item.author)
                    .font(.caption)
                    .lineLimit(1)
                    .foregroundStyle(.secondary)

                if !item.timeLeft.isEmpty {
                    Text(item.timeLeft)
                        .font(.caption2)
                        .foregroundStyle(.tertiary)
                }
            }

            Spacer(minLength: 8)

            CircularProgressRing(progress: item.progress)
                .frame(width: 32, height: 32)
        }
        .padding(.vertical, 6)
        .contentShape(Rectangle())
        .accessibilityElement(children: .combine)
        .accessibilityLabel("\(item.title), \(item.author)")
        .accessibilityValue(String(format: String(localized: "home.progress_percent"), item.progressPercent))
        .accessibilityHint(String(localized: "home.opens_book"))
    }

    // MARK: - Skeleton

    private var skeleton: some View {
        HStack(spacing: 12) {
            RoundedRectangle(cornerRadius: 8)
                .fill(Color.gray.opacity(0.2))
                .frame(width: coverSize, height: coverSize)
                .shimmer()

            VStack(alignment: .leading, spacing: 6) {
                RoundedRectangle(cornerRadius: 4)
                    .fill(Color.gray.opacity(0.2))
                    .frame(height: 14)
                    .frame(maxWidth: .infinity)
                    .shimmer()

                RoundedRectangle(cornerRadius: 4)
                    .fill(Color.gray.opacity(0.2))
                    .frame(width: 120, height: 12)
                    .shimmer()
            }

            Spacer(minLength: 8)

            Circle()
                .fill(Color.gray.opacity(0.2))
                .frame(width: 32, height: 32)
                .shimmer()
        }
        .padding(.vertical, 6)
        .accessibilityHidden(true)
    }
}

// MARK: - Preview

#Preview("Rows") {
    NavigationStack {
        List {
            ContinueRow(item: ContinueItem(
                id: "1",
                title: "The Name of the Wind",
                author: "Patrick Rothfuss",
                coverPath: nil,
                blurHash: "LEHV6nWB2yk8pyo0adR*.7kCMdnj",
                progress: 0.65,
                progressPercent: 65,
                timeLeft: "4h 12m left",
                isLoading: false
            ))

            ContinueRow(item: ContinueItem(
                id: "2",
                title: "A Very Long Audiobook Title That Wraps Across Two Lines",
                author: "Brandon Sanderson",
                coverPath: nil,
                blurHash: nil,
                progress: 0.12,
                progressPercent: 12,
                timeLeft: "18h 03m left",
                isLoading: false
            ))

            ContinueRow(item: ContinueItem(
                id: "3",
                title: "",
                author: "",
                coverPath: nil,
                blurHash: nil,
                progress: 0,
                progressPercent: 0,
                timeLeft: "",
                isLoading: true
            ))
        }
        .listStyle(.plain)
    }
}
