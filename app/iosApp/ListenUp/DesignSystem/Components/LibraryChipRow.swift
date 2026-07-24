import SwiftUI

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
        .haptic(.selectionTick, trigger: selectedTab)
    }
}

/// Individual selectable chip with clean-coral capsule styling.
///
/// Design tokens:
/// - Unselected: `Color.luFill` background, `.primary` text
/// - Selected: `Color.luTint` background, `Color.luOnTint` text
/// - Pill shape (`.infinity` corner radius)
/// - Spring animation on state change
private struct LibraryChip: View {
    let tab: LibraryTab
    let isSelected: Bool
    let onTap: () -> Void

    var body: some View {
        Button {
            onTap()
        } label: {
            HStack(spacing: 6) {
                Image(systemName: tab.icon)
                    .font(.subheadline)
                Text(tab.title)
                    .font(.subheadline.weight(isSelected ? .semibold : .medium))
            }
            .foregroundStyle(isSelected ? Color.luOnTint : Color.primary)
            .padding(.horizontal, 14)
            .padding(.vertical, 10)
            .frame(minHeight: 34)
            .background {
                Capsule()
                    .fill(isSelected ? AnyShapeStyle(Color.luTint) : AnyShapeStyle(Color.luFill))
            }
        }
        .buttonStyle(ChipButtonStyle())
        .animation(.spring(response: 0.3, dampingFraction: 0.7), value: isSelected)
        .accessibilityLabel(String(format: String(localized: "common.tab_label"), tab.title))
        .accessibilityHint(
            isSelected
                ? String(localized: "common.currently_selected")
                : String(localized: "common.double_tap_select")
        )
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
