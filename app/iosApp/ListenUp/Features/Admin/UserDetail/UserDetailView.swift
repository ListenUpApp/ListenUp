import SwiftUI
import Shared

/// Admin → a user's detail: who they are, and a row naming their access that opens the permissions
/// screen. A protected (owner) user gets the note saying why their access is locked.
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

    private func content(_ ready: UserDetailReadyModel) -> some View {
        Form {
            Section(String(format: String(localized: "common.entity_information"), "User")) {
                LabeledContent(String(localized: "common.display_name"), value: ready.displayName)
                LabeledContent(String(localized: "common.email_address"), value: ready.email)
            }

            Section {
                NavigationLink(value: UserPermissionsDestination(userId: ready.userId)) {
                    LabeledContent(
                        String(localized: "common.permissions"),
                        value: PermissionLabels.title(ready.access)
                    )
                }
            } header: {
                Text(String(localized: "admin.role_and_permissions"))
            } footer: {
                if ready.isProtected {
                    Text(String(localized: "admin.this_users_permissions_cannot_be"))
                }
            }
        }
    }
}
