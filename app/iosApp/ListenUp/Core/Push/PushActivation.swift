/// Whether the person has allowed notifications, as the system reports it.
enum PushAuthorization: Equatable {
    case notDetermined
    case denied
    case granted
}

/// One thing `PushCoordinator` does to bring push up.
enum PushActivationStep: Equatable {
    case requestAuthorization
    case registerWithAPNs
    case syncRegistration
}

/// Decides what each sign-in must do for push.
///
/// Process-level setup — the permission prompt and APNs registration — happens once. The
/// server-side registration sync belongs to the *session*: a second sign-in in the same process has
/// new credentials and no push token on the server yet, so it syncs again.
struct PushActivation {
    private var promptRequested = false
    private var registeredWithAPNs = false

    /// The steps to take now, given the current [authorization]. Call on every sign-in, and again
    /// with `.granted` when the permission prompt is answered yes.
    mutating func steps(for authorization: PushAuthorization) -> [PushActivationStep] {
        switch authorization {
        case .notDetermined:
            guard !promptRequested else { return [] }
            promptRequested = true
            return [.requestAuthorization]
        case .denied:
            return []
        case .granted:
            guard registeredWithAPNs else {
                registeredWithAPNs = true
                return [.registerWithAPNs, .syncRegistration]
            }
            return [.syncRegistration]
        }
    }
}
