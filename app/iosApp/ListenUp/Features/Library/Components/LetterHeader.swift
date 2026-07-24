import SwiftUI

/// Alphabet section header for the contributor list: a bold letter and a trailing
/// hairline rule.
struct LetterHeader: View {
    let letter: String

    @Environment(\.displayScale) private var displayScale
    private var hairline: CGFloat { 1 / max(displayScale, 1) }

    var body: some View {
        HStack(spacing: 12) {
            Text(letter)
                .font(.title3.weight(.bold))
                .foregroundStyle(.primary)
            Rectangle()
                .fill(Color.luSeparator)
                .frame(height: hairline)
        }
        .padding(.top, 16)
        .padding(.bottom, 8)
        .accessibilityAddTraits(.isHeader)
    }
}

#Preview("LetterHeader") {
    VStack(alignment: .leading) {
        LetterHeader(letter: "A")
        LetterHeader(letter: "#")
    }
    .padding(.horizontal)
    .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .top)
    .background(Color.luSurface)
}
