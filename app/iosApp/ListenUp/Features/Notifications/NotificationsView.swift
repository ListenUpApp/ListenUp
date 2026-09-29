import SwiftUI
import Shared

/// The notifications inbox — the list behind the toolbar bell. Follows `DevicesView`'s
/// shape: observer built in `.onAppear`, phase-switched body, a system inset-grouped `List`.
/// Tapping a row marks it read and routes its resolved `NotificationTapOutcome` through
/// `route` (provided by `MainTabView`, where the tab's `NavigationPath` lives).
struct NotificationsView: View {
    /// Where a tapped row's resolved outcome lands — appends onto the owning tab's path.
    let route: (NotificationTapOutcome) -> Void

    @Environment(\.dependencies) private var deps
    @State private var observer: NotificationsObserver?

    /// One formatter for every row — creating one per row is measurable list-scroll work.
    @MainActor private static let relativeFormatter = RelativeDateTimeFormatter()

    var body: some View {
        Group {
            if let observer {
                content(observer: observer)
            } else {
                LoadingStateView()
            }
        }
        .background(Color.luSurface)
        .navigationTitle(String(localized: "notifications.title"))
        .navigationBarTitleDisplayMode(.large)
        .onAppear {
            if observer == nil {
                observer = NotificationsObserver(viewModel: deps.createNotificationsViewModel())
            }
        }
    }

    // MARK: - Phase routing

    @ViewBuilder
    private func content(observer: NotificationsObserver) -> some View {
        switch observer.phase {
        case .loading:
            LoadingStateView()
        case .empty:
            ContentUnavailableView {
                Label(String(localized: "notifications.empty_title"), systemImage: "bell.slash")
            } description: {
                Text(String(localized: "notifications.empty_subtitle"))
            }
        case .ready(let rows):
            // Each notification is a list row: it highlights on tap and the list draws the separators.
            // HIG, Lists and tables.
            List(rows) { row in
                rowButton(row, observer: observer)
            }
            .listStyle(.insetGrouped)
            .readableListWidth()
        }
    }

    // MARK: - Rows

    private func rowButton(_ row: NotificationRowModel, observer: NotificationsObserver) -> some View {
        Button {
            observer.markRead(row.id)
            route(row.target)
        } label: {
            rowLabel(row)
        }
        // A list button tints its label with the accent; the notification's words stay label-coloured.
        .foregroundStyle(Color.primary)
    }

    private func rowLabel(_ row: NotificationRowModel) -> some View {
        HStack(alignment: .top, spacing: 14) {
            IconTile(systemImage: row.systemImage, size: 44)
            VStack(alignment: .leading, spacing: 3) {
                Text(row.title)
                    .font(row.isUnread ? .body.weight(.semibold) : .body)
                    .foregroundStyle(.primary)
                Text(row.body)
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }
            Spacer(minLength: 8)
            VStack(alignment: .trailing, spacing: 6) {
                Text(Self.relativeFormatter.localizedString(
                    for: Date(timeIntervalSince1970: Double(row.createdAtMs) / 1_000),
                    relativeTo: Date()
                ))
                .font(.footnote)
                .foregroundStyle(.secondary)
                if row.isUnread {
                    Circle()
                        .fill(Color.luTint)
                        .frame(width: 8, height: 8)   // decorative fixed size
                }
            }
        }
        .padding(.vertical, 2)
        .contentShape(Rectangle())
    }
}

// MARK: - Preview

#Preview {
    NavigationStack {
        NotificationsView { _ in }
    }
}
