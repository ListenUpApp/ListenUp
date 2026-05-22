import SwiftUI

/// Primary CTA button with ListenUp brand styling.
///
/// Features:
/// - Brand orange gradient background
/// - Loading state with spinner
/// - Full-width layout
/// - Accessible by default
///
/// Usage:
/// ```swift
/// ListenUpButton(
///     title: "Connect",
///     isLoading: viewModel.isLoading,
///     action: { viewModel.connect() }
/// )
/// ```
struct ListenUpButton: View {
    let title: String
    let isLoading: Bool
    let action: () -> Void

    @State private var isPressed: Bool = false

    init(
        title: String,
        isLoading: Bool = false,
        action: @escaping () -> Void
    ) {
        self.title = title
        self.isLoading = isLoading
        self.action = action
    }

    var body: some View {
        Button(action: action) {
            Group {
                if isLoading {
                    ProgressView()
                        .tint(.white)
                } else {
                    Text(title)
                        .font(.system(size: 17, weight: .semibold, design: .rounded))
                        .foregroundStyle(.white)
                }
            }
            .frame(maxWidth: .infinity)
            .frame(height: 56)
            .background {
                RoundedRectangle(cornerRadius: 16)
                    .fill(Color.listenUpOrange)
            }
        }
        .buttonStyle(ScaleButtonStyle())
        .disabled(isLoading)
        // Accessibility
        .accessibilityLabel(isLoading ? String(localized: "common.accessibility_loading") : title)
        .accessibilityHint(isLoading ? String(localized: "common.please_wait") : "Double tap to \(title.lowercased())")
        .accessibilityAddTraits(isLoading ? [.updatesFrequently] : [])
        .accessibilityRemoveTraits(isLoading ? [.isButton] : [])
    }
}

// MARK: - Tactile Scale Effect

private struct ScaleButtonStyle: ButtonStyle {
    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .scaleEffect(configuration.isPressed ? 0.96 : 1.0)
            .animation(.easeInOut(duration: 0.1), value: configuration.isPressed)
    }
}

// MARK: - Previews

#Preview("Button - Default") {
    VStack(spacing: 20) {
        ListenUpButton(title: "Connect") {
            print("Tapped")
        }

        ListenUpButton(title: "Sign In") {
            print("Tapped")
        }

        ListenUpButton(title: "Continue") {
            print("Tapped")
        }
    }
    .padding()
}

#Preview("Button - Loading") {
    VStack(spacing: 20) {
        ListenUpButton(title: "Connect", isLoading: true) {
            print("Should not execute")
        }

        ListenUpButton(title: "Loading", isLoading: true) {
            print("Should not execute")
        }
    }
    .padding()
}

#Preview("Button - In Context") {
    ZStack {
        // Gradient background
        LinearGradient(
            colors: [
                Color(hex: "8B3A3A"),
                Color(hex: "E8704A")
            ],
            startPoint: .topLeading,
            endPoint: .bottomTrailing
        )
        .ignoresSafeArea()

        GlassCard {
            VStack(spacing: 24) {
                Text("Connect to Server")
                    .font(.largeTitle.bold())

                Text("Enter your server URL to continue")
                    .font(.subheadline)
                    .foregroundStyle(.secondary)

                Spacer()

                ListenUpButton(title: "Connect") {
                    print("Connect tapped")
                }
            }
            .frame(height: 400)
        }
        .padding()
    }
}

#Preview("Button - Dark Mode") {
    VStack(spacing: 20) {
        ListenUpButton(title: "Connect") {
            print("Tapped")
        }

        ListenUpButton(title: "Loading", isLoading: true) {
            print("Tapped")
        }
    }
    .padding()
    .preferredColorScheme(.dark)
}
