import SwiftUI
import Shared

/// Per-type notification delivery toggles, reached from Settings › Account. Follows
/// `DevicesView`'s shape: observer built in `.onAppear`, phase-switched body, a readable single
/// column. Each known type gets a labelled card of two `ToggleRow`s (In-app, Push); the Push row
/// is disabled when the registry declares the type push-ineligible. Toggles apply optimistically;
/// the shared ViewModel reverts them if the server refuses.
struct NotificationPrefsView: View {
    @Environment(\.dependencies) private var deps
    @State private var observer: NotificationPrefsObserver?

    /// The two delivery channels a type's card renders, in row order.
    private enum Channel: Hashable {
        case inApp
        case push
    }

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
            ScrollView {
                VStack(alignment: .leading, spacing: 24) {
                    ForEach(rows) { row in
                        typeCard(row, observer: observer)
                    }
                }
                .padding(.horizontal, 20)
                .padding(.vertical, 16)
                .readableWidth()
            }
        }
    }

    // MARK: - Per-type card

    private func typeCard(_ row: NotificationPrefRowModel, observer: NotificationPrefsObserver) -> some View {
        VStack(alignment: .leading, spacing: 7) {
            Text(row.displayName)
                .font(.footnote.weight(.semibold))
                .foregroundStyle(Color.luLabel2)
                .padding(.leading, 14)
            FieldGroup([Channel.inApp, Channel.push], id: \.self, separatorInset: 56) { channel in
                switch channel {
                case .inApp:
                    ToggleRow(
                        systemImage: "app.badge",
                        title: String(localized: "notifications.settings_in_app"),
                        isOn: inAppBinding(row, observer: observer),
                        isBusy: observer.isBusy(type: row.type, push: false)
                    )
                    .haptic(.toggleOn, trigger: row.inApp)
                case .push:
                    // ToggleRow has no disabled affordance of its own (isBusy swaps in a spinner,
                    // nothing more), so ineligibility wraps the row: non-interactive and dimmed.
                    ToggleRow(
                        systemImage: "iphone.radiowaves.left.and.right",
                        title: String(localized: "notifications.settings_push"),
                        isOn: pushBinding(row, observer: observer),
                        isBusy: observer.isBusy(type: row.type, push: true)
                    )
                    .haptic(.toggleOn, trigger: row.push)
                    .disabled(!row.pushEligible)
                    .opacity(row.pushEligible ? 1 : 0.45)
                }
            }
        }
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
