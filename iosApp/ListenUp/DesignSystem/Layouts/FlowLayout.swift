import SwiftUI

/// A layout that arranges subviews in a flowing, wrapping horizontal layout.
/// Used for genre chips, tags, and other variable-width items.
///
/// Rows are left-aligned by default. Pass `alignment: .center` to center each row's content
/// within the available width — used by the centered Book Detail hero (author / "Narrated by"
/// lines) so wrapping contributor links stay centered under the cover.
struct FlowLayout: Layout {
    var spacing: CGFloat = 8
    var alignment: HorizontalAlignment = .leading

    func sizeThatFits(proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) -> CGSize {
        let width = proposal.width ?? .infinity
        let rows = computeRows(maxWidth: width, subviews: subviews)
        let height = rows.reduce(into: CGFloat(0)) { total, row in total += row.height }
            + spacing * CGFloat(max(0, rows.count - 1))
        return CGSize(width: width, height: height)
    }

    func placeSubviews(in bounds: CGRect, proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) {
        let rows = computeRows(maxWidth: bounds.width, subviews: subviews)
        var y = bounds.minY

        for row in rows {
            var x: CGFloat = {
                switch alignment {
                case .center: return bounds.minX + (bounds.width - row.width) / 2
                case .trailing: return bounds.maxX - row.width
                default: return bounds.minX
                }
            }()

            for index in row.indices {
                let size = subviews[index].sizeThatFits(.unspecified)
                subviews[index].place(
                    at: CGPoint(x: x, y: y + (row.height - size.height) / 2),
                    proposal: .unspecified
                )
                x += size.width + spacing
            }
            y += row.height + spacing
        }
    }

    // MARK: - Row grouping

    /// One wrapped line: which subviews it holds, its content width (no trailing spacing), and its height.
    private struct Row {
        var indices: [Int] = []
        var width: CGFloat = 0
        var height: CGFloat = 0
    }

    /// Greedily pack subviews into rows that fit within [maxWidth].
    private func computeRows(maxWidth: CGFloat, subviews: Subviews) -> [Row] {
        var rows: [Row] = []
        var row = Row()
        var x: CGFloat = 0

        for index in subviews.indices {
            let size = subviews[index].sizeThatFits(.unspecified)
            // Wrap when the next subview would overflow — but never leave a row empty.
            if !row.indices.isEmpty, x + size.width > maxWidth {
                row.width = x - spacing // drop the trailing spacing added after the last item
                rows.append(row)
                row = Row()
                x = 0
            }
            row.indices.append(index)
            row.height = max(row.height, size.height)
            x += size.width + spacing
        }

        if !row.indices.isEmpty {
            row.width = x - spacing
            rows.append(row)
        }
        return rows
    }
}
