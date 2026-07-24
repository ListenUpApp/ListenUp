import SwiftUI

/// Button style that provides tactile press feedback via scale animation.
///
/// Uses spring animation for natural feel. Scale values:
/// - `.card` (0.96) - For larger card-like elements
/// - `.row` (0.98) - For list rows and smaller items
/// - `.chip` (0.95) - For small interactive chips
///
/// Usage:
/// ```swift
/// Button("Tap me") { ... }
///     .buttonStyle(PressScaleButtonStyle())
///
/// // Custom scale
/// Button("Tap me") { ... }
///     .buttonStyle(PressScaleButtonStyle(scale: .chip))
/// ```
struct PressScaleButtonStyle: ButtonStyle {
    var scale: Scale = .row

    enum Scale {
        case card    // 0.96 - Large cards
        case row     // 0.98 - List rows
        case chip    // 0.95 - Small chips/tags

        var value: CGFloat {
            switch self {
            case .card: 0.96
            case .row: 0.98
            case .chip: 0.95
            }
        }
    }

    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    /// The scale to apply: full size (1.0) when not pressed, or when Reduce Motion is on.
    static func effectiveScale(pressed: Bool, base: CGFloat, reduceMotion: Bool) -> CGFloat {
        guard pressed, !reduceMotion else { return 1.0 }
        return base
    }

    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .scaleEffect(Self.effectiveScale(pressed: configuration.isPressed, base: scale.value, reduceMotion: reduceMotion))
            .animation(
                reduceMotion ? nil : .spring(response: 0.2, dampingFraction: 0.7),
                value: configuration.isPressed
            )
    }
}

// MARK: - Convenience Extension

extension ButtonStyle where Self == PressScaleButtonStyle {
    /// Card-style press animation (scale: 0.96)
    static var pressScaleCard: PressScaleButtonStyle {
        PressScaleButtonStyle(scale: .card)
    }

    /// Row-style press animation (scale: 0.98)
    static var pressScaleRow: PressScaleButtonStyle {
        PressScaleButtonStyle(scale: .row)
    }

    /// Chip-style press animation (scale: 0.95)
    static var pressScaleChip: PressScaleButtonStyle {
        PressScaleButtonStyle(scale: .chip)
    }
}

// MARK: - Preview

#Preview("Press Scale Styles") {
    VStack(spacing: 20) {
        Button {
            print("Card tapped")
        } label: {
            RoundedRectangle(cornerRadius: 12)
                .fill(Color.blue)
                .frame(height: 100)
                .overlay {
                    Text("Card Scale (0.96)")
                        .foregroundStyle(.white)
                }
        }
        .buttonStyle(.pressScaleCard)

        Button {
            print("Row tapped")
        } label: {
            RoundedRectangle(cornerRadius: 8)
                .fill(Color.green)
                .frame(height: 60)
                .overlay {
                    Text("Row Scale (0.98)")
                        .foregroundStyle(.white)
                }
        }
        .buttonStyle(.pressScaleRow)

        Button {
            print("Chip tapped")
        } label: {
            Text("Chip Scale (0.95)")
                .padding(.horizontal, 16)
                .padding(.vertical, 8)
                .background(Color.orange, in: Capsule())
                .foregroundStyle(.white)
        }
        .buttonStyle(.pressScaleChip)
    }
    .padding()
}
