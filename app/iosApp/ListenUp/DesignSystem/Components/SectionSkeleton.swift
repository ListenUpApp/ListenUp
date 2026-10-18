import SwiftUI

/// A rail of square cards with their caption lines, standing in for a section's cards while it loads.
///
/// Spec §3.8: a section keeps its place while loading and says plainly when it failed; Home's sections
/// use the same pieces. Hidden from VoiceOver: the section heading above it already says what is coming.
struct RailSkeleton: View {
    let cardWidth: CGFloat
    var count = 4
    var captionLines = 2
    var horizontalInset: CGFloat = 0

    var body: some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(alignment: .top, spacing: Spacing.m) {
                ForEach(0..<count, id: \.self) { _ in
                    VStack(alignment: .leading, spacing: Spacing.xs) {
                        RoundedRectangle(cornerRadius: Radius.s)
                            .fill(Color.gray.opacity(0.2))
                            .frame(width: cardWidth, height: cardWidth)
                            .shimmer()
                        ForEach(0..<captionLines, id: \.self) { line in
                            RoundedRectangle(cornerRadius: Radius.xs)
                                .fill(Color.gray.opacity(0.2))
                                .frame(width: line == 0 ? cardWidth : cardWidth * 0.6, height: 12)
                                .shimmer()
                        }
                    }
                }
            }
            .padding(.horizontal, horizontalInset)
        }
        .scrollDisabled(true)
        .accessibilityHidden(true)
    }
}

/// Rows of an avatar and two text lines, standing in for the leaderboard or the activity feed while
/// they load. Hidden from VoiceOver, like `RailSkeleton`.
struct RowsSkeleton: View {
    let rows: Int
    var avatarSize: CGFloat = 40

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            ForEach(0..<rows, id: \.self) { _ in
                HStack(spacing: Spacing.s) {
                    Circle()
                        .fill(Color.gray.opacity(0.2))
                        .frame(width: avatarSize, height: avatarSize)
                        .shimmer()
                    VStack(alignment: .leading, spacing: Spacing.xs) {
                        RoundedRectangle(cornerRadius: Radius.xs)
                            .fill(Color.gray.opacity(0.2))
                            .frame(height: 12)
                            .shimmer()
                        RoundedRectangle(cornerRadius: Radius.xs)
                            .fill(Color.gray.opacity(0.2))
                            .frame(height: 12)
                            .padding(.trailing, 80)
                            .shimmer()
                    }
                }
                .frame(minHeight: 56)
            }
        }
        .accessibilityHidden(true)
    }
}
