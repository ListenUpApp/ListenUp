import SwiftUI

/// The featured Continue-Listening item: a larger, glass-backed card that leads the
/// section. A prominent cover, the title/author, time-left, and a percent-labelled
/// progress ring. Tapping navigates to the book's detail screen.
///
/// A dedicated "resume playback" entry point doesn't exist yet, so the hero routes to
/// `BookDestination` like the rows. When a resume hook lands, wire it here (the card is
/// the natural home for a play button overlay).
struct ContinueHeroCard: View {
    let item: ContinueItem

    private let coverSize: CGFloat = 96

    var body: some View {
        NavigationLink(value: BookDestination(id: item.id)) {
            content
        }
        .buttonStyle(.plain)
    }

    private var content: some View {
        HStack(spacing: 16) {
            BookCoverImage(bookId: item.id, coverPath: item.coverPath, blurHash: item.blurHash)
                .frame(width: coverSize, height: coverSize)
                .clipShape(RoundedRectangle(cornerRadius: 12))
                .shadow(color: .black.opacity(0.2), radius: 10, x: 0, y: 5)

            VStack(alignment: .leading, spacing: 6) {
                Text(item.title)
                    .font(.headline)
                    .lineLimit(2)
                    .foregroundStyle(.primary)

                Text(item.author)
                    .font(.subheadline)
                    .lineLimit(1)
                    .foregroundStyle(.secondary)

                if !item.timeLeft.isEmpty {
                    Text(item.timeLeft)
                        .font(.caption)
                        .foregroundStyle(.tertiary)
                        .padding(.top, 2)
                }
            }

            Spacer(minLength: 8)

            CircularProgressRing(progress: item.progress, lineWidth: 6, showPercentLabel: true)
                .frame(width: 56, height: 56)
        }
        .padding(16)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(Color.luSurface2, in: RoundedRectangle(cornerRadius: 20))
        .overlay {
            RoundedRectangle(cornerRadius: 20)
                .strokeBorder(Color.luSeparator, lineWidth: 0.5)
        }
        .contentShape(RoundedRectangle(cornerRadius: 20))
        .accessibilityElement(children: .combine)
        .accessibilityLabel("\(item.title), \(item.author)")
        .accessibilityValue(String(format: String(localized: "home.progress_percent"), item.progressPercent))
        .accessibilityHint(String(localized: "home.opens_book"))
    }
}

// MARK: - Preview

#Preview("Hero") {
    ContinueHeroCard(item: ContinueItem(
        id: "1",
        title: "The Way of Kings",
        author: "Brandon Sanderson",
        coverPath: nil,
        blurHash: "LEHV6nWB2yk8pyo0adR*.7kCMdnj",
        progress: 0.72,
        progressPercent: 72,
        timeLeft: "12h 40m left",
        isLoading: false
    ))
    .padding()
}
