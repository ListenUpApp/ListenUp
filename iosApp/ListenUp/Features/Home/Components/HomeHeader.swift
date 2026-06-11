import SwiftUI

/// The Home ("Listen Now") header — a muted time-of-day greeting over the user's name as a
/// large-title hero. Both strings arrive pre-formatted from `HomeReady` (the ViewModel owns the
/// time-of-day logic), so this view is pure presentation.
///
/// The name hero leans on the system large title and scales up at regular width to anchor the
/// wider iPad layout. Dynamic Type is honored throughout; the name truncates rather than clipping.
struct HomeHeader: View {
    let greeting: String
    let userName: String

    @Environment(\.horizontalSizeClass) private var horizontalSizeClass

    private var isRegularWidth: Bool { horizontalSizeClass == .regular }

    var body: some View {
        VStack(alignment: .leading, spacing: 2) {
            if !greeting.isEmpty {
                Text(greeting)
                    .font(.title3)
                    .foregroundStyle(.secondary)
            }

            if !userName.isEmpty {
                Text(userName)
                    .font(isRegularWidth ? .largeTitle.weight(.bold) : .title.weight(.bold))
                    .foregroundStyle(.primary)
                    .lineLimit(1)
                    .truncationMode(.tail)
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .accessibilityElement(children: .combine)
    }
}

// MARK: - Preview

#Preview("Header") {
    VStack(spacing: 32) {
        HomeHeader(greeting: "Good evening", userName: "Simon")
        HomeHeader(greeting: "Good morning", userName: "A Very Long Display Name That Truncates")
        HomeHeader(greeting: "Welcome back", userName: "")
    }
    .padding()
}

#Preview("Header Dark") {
    HomeHeader(greeting: "Good evening", userName: "Simon")
        .padding()
        .preferredColorScheme(.dark)
}
