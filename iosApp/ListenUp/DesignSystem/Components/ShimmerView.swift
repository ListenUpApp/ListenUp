import SwiftUI

/// Animated shimmer effect for loading placeholders.
///
/// Usage:
/// ```swift
/// RoundedRectangle(cornerRadius: 8)
///     .fill(Color.gray.opacity(0.3))
///     .shimmer()
/// ```
struct ShimmerModifier: ViewModifier {
    @State private var phase: CGFloat = 0

    func body(content: Content) -> some View {
        content
            .overlay {
                GeometryReader { geo in
                    LinearGradient(
                        colors: [
                            .clear,
                            .white.opacity(0.4),
                            .clear,
                        ],
                        startPoint: .leading,
                        endPoint: .trailing
                    )
                    .frame(width: geo.size.width * 2)
                    .offset(x: -geo.size.width + phase * geo.size.width * 2)
                }
                .mask(content)
            }
            .onAppear {
                withAnimation(.linear(duration: 1.5).repeatForever(autoreverses: false)) {
                    phase = 1
                }
            }
    }
}

extension View {
    /// Adds an animated shimmer effect to the view.
    func shimmer() -> some View {
        modifier(ShimmerModifier())
    }
}

// MARK: - Book Cover Shimmer Placeholder

/// Shimmer placeholder matching BookCoverCard layout.
struct BookCoverShimmer: View {
    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            // Cover placeholder (square)
            RoundedRectangle(cornerRadius: 8)
                .fill(Color.gray.opacity(0.2))
                .aspectRatio(1, contentMode: .fit)
                .shimmer()

            // Title placeholder (single line)
            RoundedRectangle(cornerRadius: 4)
                .fill(Color.gray.opacity(0.2))
                .frame(height: 14)
                .shimmer()

            // Author placeholder (single line)
            RoundedRectangle(cornerRadius: 4)
                .fill(Color.gray.opacity(0.2))
                .frame(height: 12)
                .shimmer()
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }
}

// MARK: - Preview

#Preview("Shimmer Effect") {
    VStack(spacing: 24) {
        // Single shimmer
        RoundedRectangle(cornerRadius: 8)
            .fill(Color.gray.opacity(0.2))
            .frame(width: 150, height: 200)
            .shimmer()

        // Book cover shimmer
        BookCoverShimmer()
            .frame(width: 150)
    }
    .padding()
}

#Preview("Shimmer Grid") {
    ScrollView {
        LazyVGrid(columns: [GridItem(.adaptive(minimum: 150))], spacing: 16) {
            ForEach(0 ..< 6, id: \.self) { _ in
                BookCoverShimmer()
            }
        }
        .padding()
    }
}
