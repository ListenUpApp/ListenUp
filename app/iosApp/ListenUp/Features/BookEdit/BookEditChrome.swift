import SwiftUI

/// Renders either an empty-state hint or a wrapping flow of removable chips for a Book Edit
/// relation list.
struct RelationChipGroup: View {
    let items: [EditableRelation]
    let emptyText: String
    var roleKind: RoleChip.Kind?
    let onRemove: (EditableRelation) -> Void

    var body: some View {
        if items.isEmpty {
            EmptyRelationHint(text: emptyText)
        } else {
            ChipFlow {
                ForEach(items) { item in
                    RemovableChip(
                        label: item.label,
                        roleKind: roleKind,
                        removeLabel: String(localized: .commonRemoveName(item.label)),
                        onRemove: { onRemove(item) }
                    )
                }
            }
        }
    }
}

struct EmptyRelationHint: View {
    let text: String

    var body: some View {
        Text(text)
            .font(.subheadline)
            .foregroundStyle(.tertiary)
    }
}

/// A removable token: an optional `RoleChip`-style icon, a label, and an `xmark`
/// remove button. Used for authors, narrators, series, genres, tags, moods, and collections.
struct RemovableChip: View {
    let label: String
    let roleKind: RoleChip.Kind?
    let removeLabel: String
    let onRemove: () -> Void

    var body: some View {
        HStack(spacing: 6) {
            if let roleKind {
                Image(systemName: roleKind.icon)
                    .font(.caption2.weight(.semibold))
                    .foregroundStyle(.secondary)
            }
            Text(label)
                .font(.subheadline)
                .foregroundStyle(.primary)
                .lineLimit(1)
            Button(action: onRemove) {
                Image(systemName: "xmark")
                    .font(.system(size: 10, weight: .bold)) // decorative fixed size
                    .foregroundStyle(Color.secondary)
                    .frame(width: 20, height: 20)
                    .background(Circle().fill(Color.luFill))
                    .minimumTapTarget(visualSize: 20)
            }
            .buttonStyle(.plain)
            .accessibilityLabel(removeLabel)
        }
        .padding(.leading, Spacing.s)
        .padding(.trailing, Spacing.xxs)
        .padding(.vertical, Spacing.xxs)
        .background(Capsule().fill(Color.luFill.opacity(0.6)))
    }
}

/// A width-responsive wrapping flow for chips. `Layout` lets the chips reflow to as
/// many rows as the available width needs — right at every point on the size
/// continuum (full-screen iPad through narrow Split View).
struct ChipFlow: Layout {
    var spacing: CGFloat = 8

    func sizeThatFits(proposal: ProposedViewSize, subviews: Subviews, cache: inout Void) -> CGSize {
        let maxWidth = proposal.width ?? .infinity
        return layout(subviews: subviews, maxWidth: maxWidth).size
    }

    func placeSubviews(
        in bounds: CGRect,
        proposal: ProposedViewSize,
        subviews: Subviews,
        cache: inout Void
    ) {
        let result = layout(subviews: subviews, maxWidth: bounds.width)
        for (index, offset) in result.offsets.enumerated() {
            subviews[index].place(
                at: CGPoint(x: bounds.minX + offset.x, y: bounds.minY + offset.y),
                proposal: .unspecified
            )
        }
    }

    private func layout(subviews: Subviews, maxWidth: CGFloat) -> (size: CGSize, offsets: [CGPoint]) {
        var offsets: [CGPoint] = []
        var x: CGFloat = 0
        var y: CGFloat = 0
        var rowHeight: CGFloat = 0
        var totalWidth: CGFloat = 0

        for subview in subviews {
            let size = subview.sizeThatFits(.unspecified)
            if x > 0, x + size.width > maxWidth {
                x = 0
                y += rowHeight + spacing
                rowHeight = 0
            }
            offsets.append(CGPoint(x: x, y: y))
            x += size.width + spacing
            rowHeight = max(rowHeight, size.height)
            totalWidth = max(totalWidth, x - spacing)
        }

        return (CGSize(width: totalWidth, height: y + rowHeight), offsets)
    }
}
