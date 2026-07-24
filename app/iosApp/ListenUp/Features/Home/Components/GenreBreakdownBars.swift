import SwiftUI

/// Top-genres breakdown: one row per genre, each a name plus a coral bar whose width is
/// proportional to that genre's listening time relative to the heaviest genre.
///
/// Shown only when there is genre data. Each row is a single VoiceOver element reading the
/// genre name and a human duration.
struct GenreBreakdownBars: View {
    let genres: [GenreBar]

    /// Heaviest genre's seconds, floored at 1 so the proportion math never divides by zero.
    private var maxSeconds: Int {
        max(genres.map(\.seconds).max() ?? 0, 1)
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            ForEach(genres) { genre in
                row(for: genre)
            }
        }
    }

    private func row(for genre: GenreBar) -> some View {
        let fraction = min(max(Double(genre.seconds) / Double(maxSeconds), 0), 1)

        return HStack(spacing: 12) {
            Text(genre.name)
                .font(.subheadline)
                .foregroundStyle(.primary)
                .lineLimit(1)
                .frame(width: 96, alignment: .leading)

            GeometryReader { geo in
                ZStack(alignment: .leading) {
                    Capsule()
                        .fill(Color.listenUpOrange.opacity(0.12))

                    Capsule()
                        .fill(Color.listenUpOrange)
                        .frame(width: max(geo.size.width * fraction, 4))
                }
            }
            .frame(height: 10)
        }
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(genre.name)
        .accessibilityValue(DurationFormatting.accessibleHoursMinutes(seconds: genre.seconds))
    }
}

// MARK: - Preview

#Preview("Genre Bars") {
    GenreBreakdownBars(genres: [
        GenreBar(name: "Fantasy", seconds: 18_000),
        GenreBar(name: "Science Fiction", seconds: 9_000),
        GenreBar(name: "History", seconds: 3_600)
    ])
    .padding()
}
