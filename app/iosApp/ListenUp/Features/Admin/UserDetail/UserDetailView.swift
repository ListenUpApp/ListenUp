import SwiftUI

/// Admin → a user's detail: read-only identity — the counterpart to Android's `UserDetailScreen`.
/// A protected (root) user gets the note saying why their access is locked.
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
            Section(String(format: String(localized: "common.entity_information"), "User")) {
                LabeledContent(String(localized: "common.display_name"), value: ready.displayName)
                LabeledContent(String(localized: "common.email_address"), value: ready.email)
                LabeledContent(String(localized: "common.role"), value: ready.role.capitalized)
            }

            if ready.isProtected {
                Section {
                } footer: {
                    Text(String(localized: "admin.this_users_permissions_cannot_be"))
                }
            }
        }
    }
}
