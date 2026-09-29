import SwiftUI
import Shared

/// Per-type notification delivery toggles, reached from Settings › Account. Follows
/// `DevicesView`'s shape: observer built in `.onAppear`, phase-switched body, a grouped `Form`.
/// Each known type is a section of two switches (In-app, Push); the Push switch is disabled when
/// the registry declares the type push-ineligible. Toggles apply optimistically; the shared
/// ViewModel reverts them if the server refuses.
struct NotificationPrefsView: View {
    @Environment(\.dependencies) private var deps
    @State private var observer: NotificationPrefsObserver?

    var body: some View {
        Group {
            if let observer {
                content(observer: observer)
            } else {
                LoadingStateView()
            }
        }
        .background(Color.luSurface)
        .navigationTitle(String(localized: "notifications.settings_row_title"))
        .navigationBarTitleDisplayMode(.large)
        .onAppear {
            if observer == nil {
                observer = NotificationPrefsObserver(viewModel: deps.createNotificationPrefsViewModel())
            }
        }
    }

    // MARK: - Phase routing

    @ViewBuilder
    private func content(observer: NotificationPrefsObserver) -> some View {
        switch observer.phase {
        case .loading:
            LoadingStateView()
        case .error(let message):
            ContentUnavailableView {
                Label(String(localized: "common.something_went_wrong"), systemImage: "exclamationmark.triangle")
            } description: {
                Text(message)
            } actions: {
                Button(String(localized: "common.retry")) { observer.refresh() }
            }
        case .ready(let rows):
            // A system grouped form: the section header names the type, and each switch is a real
            // list row with the system's insets, separators and Dynamic Type metrics. HIG, Lists and
            // tables: "the grouped style uses headers, footers, and additional space to separate
            // groups of data".
            Form {
                ForEach(rows) { row in
                    Section(row.displayName) {
                        channelToggles(row, observer: observer)
                    }
                }
            }
            .readableListWidth()
        }
    }

    // MARK: - Per-type section

    @ViewBuilder
    private func channelToggles(_ row: NotificationPrefRowModel, observer: NotificationPrefsObserver) -> some View {
        ToggleRow(
            systemImage: "app.badge",
            title: String(localized: "notifications.settings_in_app"),
            isOn: inAppBinding(row, observer: observer)
        )
        .haptic(row.inApp ? .toggleOn : .toggleOff, trigger: row.inApp)
        ToggleRow(
            systemImage: "iphone.radiowaves.left.and.right",
            title: String(localized: "notifications.settings_push"),
            isOn: pushBinding(row, observer: observer)
        )
        .haptic(row.push ? .toggleOn : .toggleOff, trigger: row.push)
        // A disabled system switch dims itself, so ineligibility needs nothing drawn on top.
        .disabled(!row.pushEligible)
    }

    // MARK: - Bindings (read the row's flat state, write through the observer's forwarders)

    private func inAppBinding(_ row: NotificationPrefRowModel, observer: NotificationPrefsObserver) -> Binding<Bool> {
        Binding(get: { row.inApp }, set: { observer.setInApp(type: row.type, isOn: $0) })
    }

    private func pushBinding(_ row: NotificationPrefRowModel, observer: NotificationPrefsObserver) -> Binding<Bool> {
        Binding(get: { row.push }, set: { observer.setPush(type: row.type, isOn: $0) })
    }
}

// MARK: - Preview

#Preview {
    NavigationStack {
        NotificationPrefsView()
    }
}
