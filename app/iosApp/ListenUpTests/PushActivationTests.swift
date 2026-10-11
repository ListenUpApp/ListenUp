import Testing
@testable import ListenUp

/// Push setup that belongs to the process (the permission prompt, APNs registration) happens once;
/// the server-side registration sync belongs to the session and repeats on every sign-in. The bug:
/// all of it sat behind one `activated` guard, so signing out and back in within one process left
/// the new session with no push token on the server.
struct PushActivationTests {
    @Test("the first sign-in with notifications allowed registers with APNs and syncs")
    func firstSignInAuthorized() {
        var activation = PushActivation()

        #expect(activation.steps(for: .granted) == [.registerWithAPNs, .syncRegistration])
    }

    @Test("a second sign-in in the same process syncs again and registers nothing twice")
    func secondSignInSyncsAgain() {
        var activation = PushActivation()
        _ = activation.steps(for: .granted)

        #expect(activation.steps(for: .granted) == [.syncRegistration])
    }

    @Test("the permission prompt is asked once, and a grant then registers and syncs")
    func promptThenGrant() {
        var activation = PushActivation()

        #expect(activation.steps(for: .notDetermined) == [.requestAuthorization])
        #expect(activation.steps(for: .notDetermined).isEmpty)
        #expect(activation.steps(for: .granted) == [.registerWithAPNs, .syncRegistration])
    }

    @Test("denied notifications never register or sync, on any sign-in")
    func deniedNeverSyncs() {
        var activation = PushActivation()

        #expect(activation.steps(for: .denied).isEmpty)
        #expect(activation.steps(for: .denied).isEmpty)
    }
}
