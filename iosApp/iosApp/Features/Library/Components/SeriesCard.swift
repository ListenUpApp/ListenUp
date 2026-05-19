import SwiftUI
@preconcurrency import Shared

/// Floating series card with editorial design.
///
/// Features:
/// - Animated cover stack as the hero (no container boxing)
/// - Series name and book count below
/// - Press scale animation for tactile feedback
/// - Navigation to series detail view
///
/// Design: Matches Android's floating card approach where covers have their own shadows.
struct SeriesCard: View {
    let series: SeriesWithBooks_

    /// Books from the series (uses order from data source)
    private var seriesBooks: [BookListItem] {
        Array(series.books)
    }

    /// Series ID as string for navigation
    private var seriesId: String {
        String(describing: series.series.id)
    }

    var body: some View {
        NavigationLink(value: SeriesDestination(id: seriesId)) {
            cardContent
        }
        .buttonStyle(.pressScaleCard)
        .accessibilityElement(children: .combine)
        .accessibilityLabel(series.series.name)
        .accessibilityValue(bookCountLabel)
        .accessibilityHint("Double tap to view series details")
    }

    private var cardContent: some View {
        VStack(alignment: .leading, spacing: 8) {
            // Animated cover stack - the hero element
            AnimatedCoverStack(books: seriesBooks)
                .frame(height: 140)

            // Metadata
            VStack(alignment: .leading, spacing: 2) {
                Text(series.series.name)
                    .font(.subheadline.weight(.semibold))
                    .foregroundStyle(.primary)
                    .lineLimit(2)

                Text(bookCountLabel)
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }
            .padding(.horizontal, 4)
        }
    }

    private var bookCountLabel: String {
        let count = Int(series.books.count)
        return "\(count) \(count == 1 ? "book" : "books")"
    }
}

// MARK: - Preview

#Preview("Series Card") {
    NavigationStack {
        ScrollView {
            LazyVGrid(columns: [GridItem(.adaptive(minimum: 160))], spacing: 16) {
                ForEach(0 ..< 6, id: \.self) { index in
                    SeriesCardPreview(
                        name: ["The Stormlight Archive", "The Kingkiller Chronicle", "Mistborn", "Red Rising", "The Expanse", "Dune"][index],
                        bookCount: [4, 2, 7, 6, 9, 8][index]
                    )
                }
            }
            .padding()
        }
        .background(Color(.systemBackground))
        .navigationTitle("Series")
    }
}

/// Preview helper since we can't create Kotlin objects
private struct SeriesCardPreview: View {
    let name: String
    let bookCount: Int

    @State private var isPressed = false

    var body: some View {
        cardContent
            .scaleEffect(isPressed ? 0.96 : 1.0)
            .animation(.spring(response: 0.2, dampingFraction: 0.7), value: isPressed)
            .onTapGesture {}
            .simultaneousGesture(
                DragGesture(minimumDistance: 0)
                    .onChanged { _ in isPressed = true }
                    .onEnded { _ in isPressed = false }
            )
    }

    private let coverAspectRatio: CGFloat = 1.0

    private var cardContent: some View {
        VStack(alignment: .leading, spacing: 8) {
            // Mock cover stack
            GeometryReader { geo in
                let visibleCount = CGFloat(min(bookCount, 5))
                let containerWidth = geo.size.width
                let coverHeight = geo.size.height
                let coverWidth = coverHeight * coverAspectRatio

                // Calculate offset to always fill full width
                let stackOffset: CGFloat = visibleCount > 1
                    ? (containerWidth - coverWidth) / (visibleCount - 1)
                    : 0

                ZStack {
                    ForEach((0 ..< min(bookCount, 5)).reversed(), id: \.self) { index in
                        RoundedRectangle(cornerRadius: 8, style: .continuous)
                            .fill(
                                LinearGradient(
                                    colors: [
                                        Color(hue: Double(name.hashValue.magnitude % 360) / 360.0 + Double(index) * 0.05, saturation: 0.6, brightness: 0.8),
                                        Color(hue: Double(name.hashValue.magnitude % 360) / 360.0 + Double(index) * 0.05 + 0.1, saturation: 0.5, brightness: 0.6)
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
            .frame(height: 140)

            VStack(alignment: .leading, spacing: 2) {
                Text(name)
                    .font(.subheadline.weight(.semibold))
                    .foregroundStyle(.primary)
                    .lineLimit(2)

                Text("\(bookCount) \(bookCount == 1 ? "book" : "books")")
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }
            .padding(.horizontal, 4)
        }
    }
}
