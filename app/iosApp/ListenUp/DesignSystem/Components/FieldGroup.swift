import SwiftUI

/// Grouped inset-list container — the clean-coral surface for stacked rows (series book
/// lists, contributor person rows). Renders each item via `row`, draws a hairline
/// `luSeparator` between non-last rows (inset `separatorInset` from the leading edge),
/// and wraps the stack in a rounded `luSurface2` card.
///
/// The primary initializer takes an explicit `id` KeyPath (mirroring SwiftUI's
/// `ForEach(_:id:)`), so it works with the Swift Export-bridged Kotlin domain types that
/// cannot conform to `Identifiable`. A convenience initializer defaults `id` to
/// `\.id` for native `Identifiable` items.
struct FieldGroup<Item, ID: Hashable, Row: View>: View {
    let items: [Item]
    let id: KeyPath<Item, ID>
    var separatorInset: CGFloat = 0
    @ViewBuilder var row: (Item) -> Row

    init(
        _ items: [Item],
        id: KeyPath<Item, ID>,
        separatorInset: CGFloat = 0,
        @ViewBuilder row: @escaping (Item) -> Row
    ) {
        self.items = items
        self.id = id
        self.separatorInset = separatorInset
        self.row = row
    }

    @Environment(\.displayScale) private var displayScale
    private var hairline: CGFloat { 1 / max(displayScale, 1) }

    var body: some View {
        VStack(spacing: 0) {
            ForEach(items, id: id) { item in
                row(item)
                if item[keyPath: id] != items.last?[keyPath: id] {
                    Rectangle()
                        .fill(Color.luSeparator)
                        .frame(height: hairline)
                        .padding(.leading, separatorInset)
                }
            }
        }
        .background(Color.luSurface2)
        .clipShape(RoundedRectangle(cornerRadius: 12, style: .continuous))
        .overlay(
            RoundedRectangle(cornerRadius: 12, style: .continuous)
                .stroke(Color.luSeparator, lineWidth: hairline)
        )
        .shadow(color: .black.opacity(0.05), radius: 3, x: 0, y: 1)
    }
}

extension FieldGroup where ID == Item.ID, Item: Identifiable {
    /// Convenience for `Identifiable` items: keys on `\.id`.
    init(_ items: [Item], separatorInset: CGFloat = 0, @ViewBuilder row: @escaping (Item) -> Row) {
        self.init(items, id: \.id, separatorInset: separatorInset, row: row)
    }
}

// MARK: - Preview

private struct DemoRow: Identifiable {
    let id: Int
    let title: String
    let subtitle: String
}

#Preview("FieldGroup") {
    let rows = [
        DemoRow(id: 1, title: "The Way of Kings", subtitle: "Book 1 · 45h 13m"),
        DemoRow(id: 2, title: "Words of Radiance", subtitle: "Book 2 · 48h 02m"),
        DemoRow(id: 3, title: "Oathbringer", subtitle: "Book 3 · 55h 06m")
    ]
    FieldGroup(rows, separatorInset: 68) { row in
        HStack(spacing: 14) {
            RoundedRectangle(cornerRadius: 10, style: .continuous)
                .fill(Color.luFill)
                .frame(width: 54, height: 54)
            VStack(alignment: .leading, spacing: 2) {
                Text(row.title).foregroundStyle(.primary)
                Text(row.subtitle).font(.footnote).foregroundStyle(Color.luLabel2)
            }
            Spacer()
            Image(systemName: "chevron.right").font(.footnote).foregroundStyle(Color.luLabel3)
        }
        .padding(.horizontal, 14)
        .padding(.vertical, 11)
    }
    .padding()
    .frame(maxWidth: .infinity, maxHeight: .infinity)
    .background(Color.luSurface)
}
