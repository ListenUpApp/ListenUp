import SwiftUI

/// The uppercase grouped-list section overline used across the admin screen ("SERVER",
/// "MANAGEMENT", "WHO'S JOINING"), with an optional trailing accessory — a count and/or an
/// inline action (the USERS header's "Invite" button).
struct AdminSectionHeader<Trailing: View>: View {
    let title: String
    @ViewBuilder var trailing: () -> Trailing

    init(_ title: String, @ViewBuilder trailing: @escaping () -> Trailing = { EmptyView() }) {
        self.title = title
        self.trailing = trailing
    }

    var body: some View {
        HStack(alignment: .firstTextBaseline) {
            Text(title.uppercased())
                .font(.footnote.weight(.semibold))
                .foregroundStyle(Color.luLabel2)
                .tracking(0.4)
            Spacer(minLength: 8)
            trailing()
        }
        .padding(.horizontal, 6)
        .padding(.bottom, 7)
    }
}

#Preview("AdminSectionHeader") {
    VStack(alignment: .leading, spacing: 18) {
        AdminSectionHeader("Server")
        AdminSectionHeader("Users · 3") {
            Button {} label: {
                Label("Invite", systemImage: "plus")
                    .font(.subheadline.weight(.medium))
                    .foregroundStyle(Color.luTint)
            }
        }
    }
    .padding()
    .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
    .background(Color.luSurface)
}
