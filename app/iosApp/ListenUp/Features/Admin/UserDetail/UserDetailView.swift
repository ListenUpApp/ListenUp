import SwiftUI

/// Admin → a user's detail: read-only identity plus the editable **Can Share** permission — the
/// counterpart to Android's `UserDetailScreen`, and the only place an admin can grant/revoke a
/// user's sharing right. Protected (root/self) users show the toggle disabled with an explanation.
struct UserDetailView: View {
    let userId: String

    @Environment(\.dependencies) private var deps
    @State private var observer: UserDetailObserver?

    var body: some View {
        Group {
            switch observer?.phase {
            case .none, .loading:
                LoadingStateView()
            case .ready(let ready):
                content(ready)
            case .error(let message):
                ContentUnavailableView(
                    String(localized: "common.error"),
                    systemImage: "exclamationmark.triangle",
                    description: Text(message)
                )
            }
        }
        .navigationTitle(observerTitle)
        .navigationBarTitleDisplayMode(.inline)
        .onAppear {
            if observer == nil {
                observer = UserDetailObserver(viewModel: deps.createUserDetailViewModel(userId: userId))
            }
        }
    }

    private var observerTitle: String {
        if case .ready(let ready) = observer?.phase { return ready.displayName }
        return String(localized: "common.account")
    }

    @ViewBuilder
    private func content(_ ready: UserDetailReadyModel) -> some View {
        Form {
            Section(String(localized: "common.permissions")) {
                LabeledContent(String(localized: "common.display_name"), value: ready.displayName)
                LabeledContent(String(localized: "common.email_address"), value: ready.email)
                LabeledContent(String(localized: "common.role"), value: ready.role.capitalized)
            }

            Section {
                Toggle(
                    String(localized: "admin.can_share"),
                    isOn: Binding(
                        get: { ready.canShare },
                        set: { _ in observer?.toggleCanShare() }
                    )
                )
                .disabled(ready.isProtected || ready.isSaving)
            } footer: {
                if ready.isProtected {
                    Text(String(localized: "admin.this_users_permissions_cannot_be"))
                }
            }
        }
    }
}
