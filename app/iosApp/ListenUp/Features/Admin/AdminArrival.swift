import Shared

/// What Administration does on arrival when it was opened with a `focus`: wait for its content, scroll
/// to the focused section, or stay at the top. Decided once — a later refresh never scrolls again.
enum AdminArrival: Equatable {
    /// The sections above the focus are still loading; scrolling now would be undone when they land.
    case waiting
    /// Nothing to scroll to — no focus, or the focused section is hidden.
    case stay
    /// Scroll the focused section to the top.
    case scroll(to: AdminFocus)

    /// The pending registrations are only drawn under an approval queue; any other policy hides them.
    static func decide(
        focus: AdminFocus?,
        registrationPolicy: RegistrationPolicy,
        isSettingsLoaded: Bool
    ) -> AdminArrival {
        guard let focus else { return .stay }
        guard isSettingsLoaded else { return .waiting }
        switch focus {
        case .pendingRegistrations:
            return registrationPolicy == .approvalQueue ? .scroll(to: focus) : .stay
        }
    }
}
