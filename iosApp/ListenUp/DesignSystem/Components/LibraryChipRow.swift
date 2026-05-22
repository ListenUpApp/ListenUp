import SwiftUI
import UIKit

/// Horizontal row of selectable glass-styled chips for library tab navigation.
///
/// Features:
/// - Liquid Glass aesthetic with translucent materials
/// - Spring animations on selection
/// - Haptic feedback on tap
/// - Auto-scrolls to keep selected tab visible
/// - Syncs with TabView selection
struct LibraryChipRow: View {
    @Binding var selectedTab: LibraryTab

    var body: some View {
        ScrollViewReader { proxy in
            ScrollView(.horizontal, showsIndicators: false) {
                HStack(spacing: 8) {
                    ForEach(LibraryTab.allCases) { tab in
                        LibraryChip(
                            tab: tab,
                            isSelected: selectedTab == tab
                        ) {
                            selectedTab = tab
                        }
                        .id(tab)
                    }
                }
                .padding(.horizontal, 16)
                .padding(.vertical, 8)
            }
            .scrollIndicators(.hidden)
            .onChange(of: selectedTab) { _, newTab in
                withAnimation(.easeInOut(duration: 0.25)) {
                    proxy.scrollTo(newTab, anchor: .center)
                }
            }
        }
    }
}

/// Individual selectable chip with glass styling.
///
/// Design tokens:
/// - Unselected: `.ultraThinMaterial` background, `.primary` text
/// - Selected: `Color.listenUpOrange` background, `.white` text
/// - Pill shape (`.infinity` corner radius)
/// - Spring animation on state change
private struct LibraryChip: View {
    let tab: LibraryTab
    let isSelected: Bool
    let onTap: () -> Void

    private let feedbackGenerator = UISelectionFeedbackGenerator()

    var body: some View {
        Button {
            feedbackGenerator.selectionChanged()
            onTap()
        } label: {
            HStack(spacing: 6) {
                Image(systemName: tab.icon)
                    .font(.subheadline)
                Text(tab.title)
                    .font(.subheadline.weight(.medium))
            }
            .foregroundStyle(isSelected ? .white : .primary)
            .padding(.horizontal, 14)
            .padding(.vertical, 10)
            .background {
                Capsule()
                    .fill(isSelected ? AnyShapeStyle(Color.listenUpOrange) : AnyShapeStyle(.ultraThinMaterial))
                    .overlay {
                        if !isSelected {
                            Capsule()
                                .strokeBorder(
                                    LinearGradient(
                                        colors: [.white.opacity(0.3), .white.opacity(0.1)],
                                        startPoint: .topLeading,
                                        endPoint: .bottomTrailing
                                    ),
                                    lineWidth: 0.5
                                )
                        }
                    }
            }
        }
        .buttonStyle(ChipButtonStyle())
        .animation(.spring(response: 0.3, dampingFraction: 0.7), value: isSelected)
        .accessibilityLabel(String(format: String(localized: "common.tab_label"), tab.title))
        .accessibilityHint(isSelected ? String(localized: "common.currently_selected") : String(localized: "common.double_tap_select"))
        .accessibilityAddTraits(isSelected ? .isSelected : [])
    }
}

/// Custom button style with scale animation that doesn't interfere with scroll gestures.
private struct ChipButtonStyle: ButtonStyle {
    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .scaleEffect(configuration.isPressed ? 0.95 : 1.0)
            .animation(.spring(response: 0.2, dampingFraction: 0.6), value: configuration.isPressed)
    }
}

// MARK: - Preview

#Preview("Library Chips") {
    struct PreviewWrapper: View {
        @State private var selectedTab: LibraryTab = .books

        var body: some View {
            VStack(spacing: 20) {
                LibraryChipRow(selectedTab: $selectedTab)

                Text("Selected: \(selectedTab.title)")
                    .font(.headline)

                Spacer()
            }
            .padding(.top)
            .background(Color(.systemBackground))
        }
    }

    return PreviewWrapper()
}

#Preview("Dark Mode") {
    struct PreviewWrapper: View {
        @State private var selectedTab: LibraryTab = .series

        var body: some View {
            VStack {
                LibraryChipRow(selectedTab: $selectedTab)
                Spacer()
            }
            .padding(.top)
            .background(Color(.systemBackground))
        }
    }

    return PreviewWrapper()
        .preferredColorScheme(.dark)
}
