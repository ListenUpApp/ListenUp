import SwiftUI

/// Horizontal progress bar with customizable styling.
///
/// Variants:
/// - `.standard` - Rounded, gray background, orange fill
/// - `.overlay` - Flat, dark background, orange fill (for overlaying on images)
///
/// Usage:
/// ```swift
/// ProgressBar(progress: 0.65)
///     .frame(height: 6)
///
/// // With label
/// ProgressBar(progress: 0.65, label: "2h 15m left")
///
/// // Overlay style
/// ProgressBar(progress: 0.65, style: .overlay)
///     .frame(height: 4)
/// ```
struct ProgressBar: View {
    let progress: Float
    var label: String?
    var style: Style = .standard

    enum Style {
        case standard
        case overlay
    }

    var body: some View {
        VStack(spacing: 6) {
            GeometryReader { geo in
                ZStack(alignment: .leading) {
                    // Background track
                    RoundedRectangle(cornerRadius: cornerRadius)
                        .fill(backgroundColor)

                    // Progress fill
                    RoundedRectangle(cornerRadius: cornerRadius)
                        .fill(Color.listenUpOrange)
                        .frame(width: geo.size.width * CGFloat(progress.clamped(to: 0...1)))
                }
            }

            if let label {
                Text(label)
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }
        }
    }

    private var cornerRadius: CGFloat {
        switch style {
        case .standard: 4
        case .overlay: 0
        }
    }

    private var backgroundColor: Color {
        switch style {
        case .standard: Color(.systemGray5)
        case .overlay: .black.opacity(0.5)
        }
    }
}

// MARK: - Float Clamping Extension

private extension Float {
    func clamped(to range: ClosedRange<Float>) -> Float {
        min(max(self, range.lowerBound), range.upperBound)
    }
}

// MARK: - Preview

#Preview("Standard") {
    VStack(spacing: 20) {
        ProgressBar(progress: 0.0)
            .frame(height: 6)

        ProgressBar(progress: 0.25)
            .frame(height: 6)

        ProgressBar(progress: 0.65)
            .frame(height: 6)

        ProgressBar(progress: 1.0)
            .frame(height: 6)

        ProgressBar(progress: 0.65, label: "2h 15m left")
            .frame(height: 6)
    }
    .padding()
}

#Preview("Overlay Style") {
    ZStack {
        RoundedRectangle(cornerRadius: 8)
            .fill(Color.blue)
            .frame(width: 100, height: 100)

        VStack {
            Spacer()
            ProgressBar(progress: 0.65, style: .overlay)
                .frame(height: 4)
        }
        .frame(width: 100, height: 100)
    }
    .padding()
}
