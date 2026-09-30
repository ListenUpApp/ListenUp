import SwiftUI

/// A grouped-list `Section` header for the admin screens, with an optional trailing accessory — a
/// count and/or an inline action (the Users header's "Invite" button). The title takes the system
/// header style (and its heading trait); the accessory keeps its own case, since it's an action,
/// not a label.
struct AdminSectionHeader<Trailing: View>: View {
    let title: String
    @ViewBuilder var trailing: () -> Trailing

    init(_ title: String, @ViewBuilder trailing: @escaping () -> Trailing = { EmptyView() }) {
        self.title = title
        self.trailing = trailing
    }

    var body: some View {
        HStack(alignment: .firstTextBaseline) {
            Text(title)
            Spacer(minLength: 8)
            trailing()
                .font(.subheadline.weight(.medium))
                .textCase(nil)
        }
    }
}

#Preview("AdminSectionHeader") {
    Form {
        Section {
            Text("Row")
        } header: {
            AdminSectionHeader("Users · 3") {
                Button {} label: { Label("Invite", systemImage: "plus") }
            }
        }
    }
}
