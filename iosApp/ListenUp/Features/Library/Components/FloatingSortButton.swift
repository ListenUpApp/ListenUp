import SwiftUI
@preconcurrency import Shared

/// Floating sort button that shows current sort and expands to menu on tap.
///
/// Features:
/// - Glass pill styling with current sort label
/// - Menu showing available sort categories
/// - Direction toggle (A→Z / Z→A)
/// - Haptic feedback on interactions
struct FloatingSortButton: View {
    let sortState: SortState
    let categories: [SortCategory]
    let onCategorySelected: (SortCategory) -> Void
    let onDirectionToggle: () -> Void

    @State private var isExpanded = false

    var body: some View {
        Menu {
            // Category options
            ForEach(categories, id: \.rawValue) { category in
                Button {
                    onCategorySelected(category)
                } label: {
                    HStack {
                        Text(category.label)
                        if category == sortState.category {
                            Image(systemName: "checkmark")
                        }
                    }
                }
            }

            Divider()

            // Direction toggle
            Button {
                onDirectionToggle()
            } label: {
                HStack {
                    Text(String(localized: "common.direction"))
                    Spacer()
                    Text(sortState.directionLabel)
                        .foregroundStyle(.secondary)
                }
            }
        } label: {
            HStack(spacing: 6) {
                Image(systemName: sortIcon)
                    .font(.caption.weight(.semibold))
                Text(sortState.category.label)
                    .font(.caption.weight(.medium))
                Image(systemName: "chevron.down")
                    .font(.caption2.weight(.bold))
            }
            .foregroundStyle(.primary)
            .padding(.horizontal, 12)
            .padding(.vertical, 8)
            .glassControl(in: .capsule)
        }
        .accessibilityLabel("Sort by \(sortState.category.label)")
        .accessibilityHint("Double tap to change sort options")
        .haptic(.selectionTick, trigger: sortState)
    }

    private var sortIcon: String {
        switch sortState.direction {
        case .ascending:
            "arrow.up"
        case .descending:
            "arrow.down"
        }
    }
}

// MARK: - Preview

#Preview("Floating Sort Button") {
    VStack(spacing: 32) {
        // Mock previews since we can't create Kotlin objects
        FloatingSortButtonPreview(
            categoryLabel: "Title",
            directionLabel: "A → Z",
            icon: "arrow.up"
        )

        FloatingSortButtonPreview(
            categoryLabel: "Name",
            directionLabel: "Z → A",
            icon: "arrow.down"
        )

        FloatingSortButtonPreview(
            categoryLabel: "Books",
            directionLabel: "Most",
            icon: "arrow.down"
        )

        Spacer()
    }
    .padding(.top, 32)
    .frame(maxWidth: .infinity, alignment: .leading)
    .padding(.horizontal)
    .background(Color(.systemBackground))
}

/// Preview helper
private struct FloatingSortButtonPreview: View {
    let categoryLabel: String
    let directionLabel: String
    let icon: String

    var body: some View {
        Menu {
            Button("Option 1") {}
            Button("Option 2") {}
        } label: {
            HStack(spacing: 6) {
                Image(systemName: icon)
                    .font(.caption.weight(.semibold))
                Text(categoryLabel)
                    .font(.caption.weight(.medium))
                Image(systemName: "chevron.down")
                    .font(.caption2.weight(.bold))
            }
            .foregroundStyle(.primary)
            .padding(.horizontal, 12)
            .padding(.vertical, 8)
            .glassControl(in: .capsule)
        }
    }
}
