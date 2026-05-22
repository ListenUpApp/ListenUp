import SwiftUI

/// Reusable layout wrapper for authentication screens.
/// Provides centered content with proper keyboard handling and scroll support.
struct AuthScreenLayout<Content: View>: View {
    @ViewBuilder let content: () -> Content

    var body: some View {
        ZStack(alignment: .bottom) {
            // Gradient fills entire screen
            Color.brandGradient
                .ignoresSafeArea()

            VStack(spacing: 0) {
                // Logo area - flexible, takes remaining space
                VStack {
                    Spacer()
                    Image("listenup_logo_white")
                        .resizable()
                        .scaledToFit()
                        .frame(width: 190, height: 190)
                    Spacer()
                }
                .frame(maxHeight: .infinity)

                // Content card - sized to content, max 70%
                ScrollView {
                    content()
                        .padding(24)
                        .padding(.bottom, 16)
                }
                .frame(maxHeight: UIScreen.main.bounds.height * 0.7)
                .background(
                    UnevenRoundedRectangle(topLeadingRadius: 28, topTrailingRadius: 28)
                        .fill(Color(.systemBackground))
                        .shadow(color: .black.opacity(0.1), radius: 20, y: -10)
                        .ignoresSafeArea(edges: .bottom)
                )
            }
        }
    }
}
