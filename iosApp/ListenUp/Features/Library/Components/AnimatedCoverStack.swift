import SwiftUI
@preconcurrency import Shared

/// Displays overlapping book covers that cycle through all books in a series.
///
/// Features:
/// - Shows up to 5 overlapping covers with depth shadows
/// - Covers fill full width with proper book aspect ratio
/// - Timer-based cycling animation (3 second interval)
/// - BlurHash placeholders for missing covers
/// - Smooth spring transitions between cycles
struct AnimatedCoverStack: View {
    let books: [BookListItem]
    var cycleDurationSeconds: Double = 3.0
    var maxVisibleCovers: Int = 5

    /// Book cover aspect ratio (width:height) - square for audiobook covers
    private let coverAspectRatio: CGFloat = 1.0

    @State private var cycleOffset = 0
    @State private var timer: Timer?

    /// Books to display, limited and offset for cycling
    private var visibleBooks: [BookListItem] {
        guard !books.isEmpty else { return [] }
        let count = min(books.count, maxVisibleCovers)
        var result: [BookListItem] = []
        for i in 0 ..< count {
            let index = (cycleOffset + i) % books.count
            result.append(books[index])
        }
        return result
    }

    var body: some View {
        GeometryReader { geo in
            let coverCount = CGFloat(min(books.count, maxVisibleCovers))
            let containerWidth = geo.size.width
            let coverHeight = geo.size.height

            // For 2-book series, scale covers to fill width with overlap
            // For 3+ books, use standard square covers
            let coverWidth: CGFloat = if books.count == 2 {
                // Two covers with ~30% overlap fills the width
                containerWidth * 0.65
            } else {
                coverHeight * coverAspectRatio
            }

            // Calculate offset to fill full width
            // Front cover at x=0, last cover's right edge at containerWidth
            let stackOffset: CGFloat = coverCount > 1
                ? (containerWidth - coverWidth) / (coverCount - 1)
                : 0

            ZStack {
                ForEach(Array(visibleBooks.enumerated().reversed()), id: \.element.idString) { index, book in
                    BookCoverImage(book: book)
                        .frame(width: coverWidth, height: coverHeight)
                        .clipShape(RoundedRectangle(cornerRadius: 8, style: .continuous))
                        .shadow(
                            color: .black.opacity(0.2 - Double(index) * 0.03),
                            radius: 10 - CGFloat(index) * 1.5,
                            x: 2,
                            y: 4 - CGFloat(index)
                        )
                        .offset(x: CGFloat(index) * stackOffset)
                        .zIndex(Double(visibleBooks.count - index))
                }
            }
            .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .leading)
        }
        .onAppear {
            startCycling()
        }
        .onDisappear {
            stopCycling()
        }
    }

    // MARK: - Cycling

    private func startCycling() {
        // Animate series with 3+ books (cycle through them)
        guard books.count >= 3 else { return }

        timer = Timer.scheduledTimer(withTimeInterval: cycleDurationSeconds, repeats: true) { _ in
            // The timer is scheduled on the main run loop (`startCycling` runs from
            // `.onAppear`), so its block fires on the main actor — assert that to
            // mutate the `@MainActor`-isolated `@State` from this `@Sendable` block.
            MainActor.assumeIsolated {
                withAnimation(.spring(response: 0.5, dampingFraction: 0.8)) {
                    cycleOffset = (cycleOffset + 1) % books.count
                }
            }
        }
    }

    private func stopCycling() {
        timer?.invalidate()
        timer = nil
    }
}

// MARK: - Preview

#Preview("Animated Cover Stack") {
    VStack(spacing: 32) {
        // Preview with mock data
        CoverStackPreview(coverCount: 8)
            .frame(height: 160)
            .padding(.horizontal)

        CoverStackPreview(coverCount: 3)
            .frame(height: 160)
            .padding(.horizontal)

        CoverStackPreview(coverCount: 1)
            .frame(height: 160)
            .padding(.horizontal)

        Spacer()
    }
    .padding(.top, 32)
    .background(Color(.systemBackground))
}

/// Preview helper for cover stack
private struct CoverStackPreview: View {
    let coverCount: Int
    private let coverAspectRatio: CGFloat = 1.0

    var body: some View {
        GeometryReader { geo in
            let visibleCount = CGFloat(min(coverCount, 5))
            let containerWidth = geo.size.width
            let coverHeight = geo.size.height
            let coverWidth = coverHeight * coverAspectRatio

            // Calculate offset to always fill full width
            let stackOffset: CGFloat = visibleCount > 1
                ? (containerWidth - coverWidth) / (visibleCount - 1)
                : 0

            ZStack {
                ForEach((0 ..< min(coverCount, 5)).reversed(), id: \.self) { index in
                    RoundedRectangle(cornerRadius: 8, style: .continuous)
                        .fill(
                            LinearGradient(
                                colors: [
                                    Color(hue: Double(index) * 0.15, saturation: 0.6, brightness: 0.8),
                                    Color(hue: Double(index) * 0.15 + 0.05, saturation: 0.5, brightness: 0.6)
                                ],
                                startPoint: .topLeading,
                                endPoint: .bottomTrailing
                            )
                        )
                        .frame(width: coverWidth, height: coverHeight)
                        .shadow(
                            color: .black.opacity(0.2 - Double(index) * 0.03),
                            radius: 10 - CGFloat(index) * 1.5,
                            x: 2,
                            y: 4 - CGFloat(index)
                        )
                        .offset(x: CGFloat(index) * stackOffset)
                }
            }
            .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .leading)
        }
    }
}
