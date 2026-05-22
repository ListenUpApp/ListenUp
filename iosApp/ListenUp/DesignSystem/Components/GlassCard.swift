import SwiftUI

/// Liquid Glass card with translucent material background.
///
/// This is the foundational component of the Liquid Glass design system.
/// Uses SwiftUI's `.regularMaterial` for authentic glass translucency
/// with subtle depth through shadows and highlight stroke.
///
/// Usage:
/// ```swift
/// GlassCard {
///     VStack {
///         Text("Content goes here")
///     }
/// }
/// ```
///
/// Customization:
/// ```swift
/// GlassCard(padding: 32, cornerRadius: 20, material: .thickMaterial) {
///     // content
/// }
/// ```
struct GlassCard<Content: View>: View {
    let content: Content
    var padding: CGFloat
    var cornerRadius: CGFloat
    var material: Material

    init(
        padding: CGFloat = 24,
        cornerRadius: CGFloat = 20,
        material: Material = .regularMaterial,
        @ViewBuilder content: () -> Content
    ) {
        self.padding = padding
        self.cornerRadius = cornerRadius
        self.material = material
        self.content = content()
    }

    var body: some View {
        content
            .padding(padding)
            .background {
                RoundedRectangle(cornerRadius: cornerRadius, style: .continuous)
                    .fill(material)
                    .overlay {
                        RoundedRectangle(cornerRadius: cornerRadius, style: .continuous)
                            .strokeBorder(
                                LinearGradient(
                                    colors: [.white.opacity(0.3), .white.opacity(0.1)],
                                    startPoint: .topLeading,
                                    endPoint: .bottomTrailing
                                ),
                                lineWidth: 0.5
                            )
                    }
                    .shadow(color: .black.opacity(0.12), radius: 16, x: 0, y: 8)
            }
    }
}

// MARK: - Previews

#Preview("Glass Card - Light") {
    ZStack {
        // Gradient background to show translucency
        LinearGradient(
            colors: [.blue, .purple],
            startPoint: .topLeading,
            endPoint: .bottomTrailing
        )
        .ignoresSafeArea()

        GlassCard {
            VStack(alignment: .leading, spacing: 16) {
                Text("Liquid Glass")
                    .font(.title.bold())

                Text("This card demonstrates the ultra-modern frosted glass aesthetic with translucent background and depth.")
                    .font(.body)
                    .foregroundStyle(.secondary)

                HStack {
                    Image(systemName: "checkmark.circle.fill")
                        .foregroundStyle(.green)
                    Text("Ultra-thin material")
                }

                HStack {
                    Image(systemName: "checkmark.circle.fill")
                        .foregroundStyle(.green)
                    Text("Adaptive to theme")
                }
            }
        }
        .padding()
    }
}

#Preview("Glass Card - Dark") {
    ZStack {
        // Brand gradient background to show translucency
        Color.brandGradient
            .ignoresSafeArea()

        GlassCard {
            VStack(alignment: .leading, spacing: 12) {
                Text("Server Connection")
                    .font(.largeTitle.bold())

                Text("Enter your ListenUp server URL")
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
            }
        }
        .padding()
    }
    .preferredColorScheme(.dark)
}

#Preview("Glass Card - Compact") {
    ZStack {
        LinearGradient(colors: [.blue, .cyan], startPoint: .topLeading, endPoint: .bottomTrailing)
            .ignoresSafeArea()

        GlassCard(padding: 16, cornerRadius: 16) {
            HStack {
                Image(systemName: "info.circle")
                Text("Compact glass card")
                Spacer()
            }
        }
        .padding()
    }
}
