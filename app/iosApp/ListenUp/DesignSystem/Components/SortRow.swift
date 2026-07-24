import SwiftUI

/// Inline count + sort control for list screens (replaces the floating sort button in
/// Phase B). Leading: a muted count. Trailing: a coral sort affordance backed by a
/// native `Menu` whose items the caller supplies (sort categories + direction toggle).
///
/// Caller contracts:
/// - `count` is a fully-formatted, **already-localized** string (e.g. `"24 series"`) —
///   pluralization and number formatting belong to the screen's localized catalog.
/// - The `menu` actions fire their own selection haptic
///   (`UISelectionFeedbackGenerator`); `SortRow` only presents the menu.
struct SortRow<MenuContent: View>: View {
    let count: String
    let sortLabel: String
    var icon: String = "arrow.up.arrow.down"
    @ViewBuilder var menu: () -> MenuContent

    var body: some View {
        HStack {
            Text(count)
                .font(.footnote)
                .foregroundStyle(Color.luLabel2)
            Spacer()
            Menu {
                menu()
            } label: {
                HStack(spacing: 4) {
                    Image(systemName: icon)
                    Text(sortLabel)
                }
                .font(.footnote.weight(.semibold))
                .foregroundStyle(Color.luTint)
            }
        }
    }
}

// MARK: - Preview

#Preview("SortRow") {
    VStack(spacing: 24) {
        SortRow(count: "24 series", sortLabel: "Name") {
            Button("Name") {}
            Button("Recently added") {}
            Divider()
            Button("Ascending") {}
            Button("Descending") {}
        }
        SortRow(count: "176 authors", sortLabel: "Books", icon: "arrow.down") {
            Button("Name") {}
            Button("Books") {}
        }
    }
    .padding()
    .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .top)
    .background(Color.luSurface)
}
