import XCTest

/// Captures the App Store screenshot set against a seeded screenshot-rig server.
///
/// Not part of CI: the target is detached from the app so the `ListenUp` scheme never builds it, only its
/// own `ListenUpScreenshots` scheme does, and the test skips itself unless the rig is named in the
/// environment. `tools/scripts/store-screenshots-ios.sh` creates a fresh simulator, sets a 9:41 status bar,
/// installs the app, passes the rig through `TEST_RUNNER_`-prefixed variables (the prefix is stripped on the
/// way in) and exports the attachments as PNGs.
///
/// The password never passes through `typeText`: XCUITest logs typed text, so the account rides in the
/// app's launch environment instead and a DEBUG-only hook in `LoginView` pre-fills the form.
final class StoreScreenshots: XCTestCase {

    private var app: XCUIApplication!
    private var rig: Rig!

    override func setUpWithError() throws {
        guard let rig = Rig.fromEnvironment() else {
            throw XCTSkip("No screenshot rig in the environment; run tools/scripts/store-screenshots-ios.sh")
        }
        self.rig = rig
        // The script installs the app and the runner addresses it by bundle identifier.
        app = XCUIApplication(bundleIdentifier: rig.appBundleId)
        app.launchArguments += ["-AppleLanguages", "(en)", "-AppleLocale", "en_US"]
        app.launchEnvironment["LISTENUP_SCREENSHOTS_EMAIL"] = rig.email
        app.launchEnvironment["LISTENUP_SCREENSHOTS_PASSWORD"] = rig.password
    }

    @MainActor
    func testCaptureStoreScreenshots() throws {
        continueAfterFailure = false
        app.launch()

        // Signed out, on a fresh simulator: the first thing a new listener sees.
        let addServer = app.buttons["Add server manually"]
        XCTAssert(addServer.waitForExistence(timeout: 60), "Connect screen never appeared")
        // The picker exists in the tree a beat before it is on screen; a first launch can still show the
        // boot spinner.
        for _ in 0..<20 where !addServer.isHittable { settle(1) }
        settle(4)
        snap("07-connect")

        signIn(addServer: addServer)
        XCTAssert(tab("Home").waitForExistence(timeout: 60), "Sign-in never reached Home")
        dismissSystemAlerts()

        // Every screen from here is captured independently: one moved label costs one screenshot, not the
        // rest of the set. The misses are reported together at the end.
        continueAfterFailure = true
        let missed = [
            screen("home", captureHome),
            screen("library", captureLibrary),
            screen("discover", captureDiscover),
            screen("book detail", captureBookDetail),
            // Downloads before Now Playing: starting playback also starts a download of the whole book.
            screen("downloads", captureDownloads),
            screen("now playing", captureNowPlaying)
        ].compactMap { $0 }
        app.terminate()
        XCTAssert(missed.isEmpty, "Store screenshots missed: \(missed.joined(separator: ", "))")
    }

    // MARK: - Screens

    @MainActor
    private func captureHome() -> Bool {
        guard element(containing: "Sherlock").waitForExistence(timeout: 90) else { return false }
        settle(6)
        snap("01-home")
        return true
    }

    @MainActor
    private func captureLibrary() -> Bool {
        tab("Library").tap()
        settle(5)
        snap("04-library")
        return true
    }

    @MainActor
    private func captureDiscover() -> Bool {
        tab("Discover").tap()
        settle(8)
        snap("05-discover")
        return true
    }

    /// The top of Sherlock Holmes, then one swipe down to its chapters and readers.
    @MainActor
    private func captureBookDetail() -> Bool {
        guard openSherlock() else { return false }
        snap("03-book-detail")
        app.swipeUp()
        settle(2)
        snap("03b-book-detail-readers")
        backToRoot()
        return true
    }

    /// Playback starts and is paused at once (the rig is silent anyway), then the full player opens.
    @MainActor
    private func captureNowPlaying() -> Bool {
        guard openSherlock() else { return false }
        let resume = app.buttons["Resume"].firstMatch
        guard resume.waitForExistence(timeout: 10) else { return false }
        resume.tap()
        let pause = app.buttons["Pause"].firstMatch
        guard pause.waitForExistence(timeout: 30) else { return false }
        pause.tap()
        // Starting playback also starts a download of the whole book; the set has no use for it.
        let cancel = app.buttons["Cancel download"].firstMatch
        if cancel.waitForExistence(timeout: 3) { cancel.tap() }
        let miniPlayer = app.buttons.matching(NSPredicate(
            format: "label == %@ OR label BEGINSWITH %@",
            "The Adventures of Sherlock Holmes", "The Adventures of Sherlock Holmes, Paused"
        )).firstMatch
        guard miniPlayer.waitForExistence(timeout: 10) else { return false }
        miniPlayer.tap()
        settle(4)
        // A tap that lands while playback is still settling can be swallowed: pause until Play shows.
        let play = app.buttons["Play"].firstMatch
        for _ in 0..<5 where !play.exists {
            if app.buttons["Pause"].firstMatch.exists { app.buttons["Pause"].firstMatch.tap() }
            settle(2)
        }
        guard play.exists else { return false }
        snap("02-now-playing")
        app.swipeDown()
        settle(2)
        return true
    }

    /// Downloads The Art of War (72 minutes, seconds to fetch), then opens the downloads manager.
    @MainActor
    private func captureDownloads() -> Bool {
        tab("Search").tap()
        let field = app.searchFields.firstMatch
        guard field.waitForExistence(timeout: 10) else { return false }
        field.tap()
        field.typeText("Art of War")
        let hit = app.buttons.matching(NSPredicate(format: "label BEGINSWITH %@", "The Art of War")).firstMatch
        guard hit.waitForExistence(timeout: 15) else { return false }
        hit.tap()
        let download = app.buttons.matching(NSPredicate(format: "label BEGINSWITH %@", "Download")).firstMatch
        guard download.waitForExistence(timeout: 15) else { return false }
        download.tap()
        guard app.buttons["Delete download"].firstMatch.waitForExistence(timeout: 180) else { return false }

        tab("Home").tap()
        settle(2)
        let account = app.buttons["MO"].firstMatch
        guard account.waitForExistence(timeout: 10) else { return false }
        account.tap()
        // Profile > Settings > Manage storage. (The profile's own Downloads row is not wired to anything yet.)
        // On iPad a sidebar-only Settings tab shares the label; take the one on screen.
        guard app.buttons["Settings"].firstMatch.waitForExistence(timeout: 10),
              let settings = app.buttons.matching(identifier: "Settings").allElementsBoundByIndex
                  .first(where: \.isHittable)
        else { return false }
        settings.tap()
        let manageStorage = app.buttons.matching(NSPredicate(format: "label BEGINSWITH %@", "Manage storage"))
            .firstMatch
        for _ in 0..<6 where !manageStorage.isHittable { app.swipeUp() }
        guard manageStorage.isHittable else { return false }
        manageStorage.tap()
        settle(4)
        guard app.state == .runningForeground else { return false }
        snap("06-downloads")
        return true
    }

    // MARK: - Flow

    @MainActor
    private func signIn(addServer: XCUIElement) {
        addServer.tap()
        let url = app.textFields["example.com_or_192.168.1.100:8080_field"]
        XCTAssert(url.waitForExistence(timeout: 10), "Server URL field missing")
        url.tap()
        url.typeText(rig.serverUrl)
        app.buttons["Connect"].firstMatch.tap()

        let signIn = app.buttons.matching(identifier: "Sign in").firstMatch
        XCTAssert(signIn.waitForExistence(timeout: 60), "Sign-in screen never appeared")
        dismissSystemAlerts()
        signIn.tap()
    }

    @MainActor
    private func openSherlock() -> Bool {
        backToRoot()
        tab("Home").tap()
        let sherlock = app.buttons
            .matching(NSPredicate(format: "label BEGINSWITH %@", "The Adventures of Sherlock Holmes, by"))
            .firstMatch
        guard sherlock.waitForExistence(timeout: 10) else { return false }
        sherlock.tap()
        guard app.buttons["Resume"].firstMatch.waitForExistence(timeout: 15) else { return false }
        settle(4)
        return true
    }

    // MARK: - Helpers

    /// Runs one screen's capture. A miss leaves its accessibility tree behind and returns to Home.
    @MainActor
    private func screen(_ name: String, _ capture: () -> Bool) -> String? {
        if capture() { return nil }
        // A screen that crashes the app leaves nothing to dump; relaunch (still signed in) and carry on.
        guard app.state == .runningForeground else {
            app.launch()
            _ = tab("Home").waitForExistence(timeout: 60)
            return "\(name) (the app crashed)"
        }
        dumpHierarchy(name)
        backToRoot()
        tab("Home").tap()
        return name
    }

    /// Pops any pushed screens off the current tab.
    @MainActor
    private func backToRoot() {
        let back = app.buttons["BackButton"].firstMatch
        for _ in 0..<4 where back.exists {
            back.tap()
            settle(1)
        }
    }

    /// A tab-bar (iPhone) or sidebar/top-bar (iPad) item.
    @MainActor
    private func tab(_ title: String) -> XCUIElement {
        let inTabBar = app.tabBars.buttons[title]
        return inTabBar.exists ? inTabBar : app.buttons[title].firstMatch
    }

    @MainActor
    private func element(containing text: String) -> XCUIElement {
        app.descendants(matching: .any)
            .matching(NSPredicate(format: "label CONTAINS[c] %@", text))
            .firstMatch
    }

    /// Notification and Local Network prompts are SpringBoard's, not the app's.
    @MainActor
    private func dismissSystemAlerts() {
        let springboard = XCUIApplication(bundleIdentifier: "com.apple.springboard")
        for _ in 0..<3 {
            let alert = springboard.alerts.firstMatch
            guard alert.waitForExistence(timeout: 2) else { return }
            let allow = alert.buttons["Allow"]
            (allow.exists ? allow : alert.buttons.element(boundBy: 0)).tap()
        }
    }

    /// Lets covers load and transitions finish before a capture.
    private func settle(_ seconds: TimeInterval) {
        Thread.sleep(forTimeInterval: seconds)
    }

    @MainActor
    private func snap(_ name: String) {
        let attachment = XCTAttachment(screenshot: XCUIScreen.main.screenshot())
        attachment.name = name
        attachment.lifetime = .keepAlways
        add(attachment)
    }

    /// The accessibility tree of a screen that could not be captured, to show what moved.
    @MainActor
    private func dumpHierarchy(_ name: String) {
        let attachment = XCTAttachment(string: app.debugDescription)
        attachment.name = "missed-\(name.replacingOccurrences(of: " ", with: "-"))"
        attachment.lifetime = .keepAlways
        add(attachment)
    }
}

/// The screenshot-rig server and account, from the test runner's environment.
private struct Rig {
    let serverUrl: String
    let appBundleId: String
    let email: String
    let password: String

    static func fromEnvironment() -> Rig? {
        let environment = ProcessInfo.processInfo.environment
        guard let serverUrl = environment["SCREENSHOTS_SERVER_URL"], !serverUrl.isEmpty,
              let email = environment["SCREENSHOTS_EMAIL"], !email.isEmpty,
              let password = environment["SCREENSHOTS_PASSWORD"], !password.isEmpty
        else { return nil }
        let appBundleId = environment["SCREENSHOTS_APP_BUNDLE_ID"] ?? "com.calypsan.listenup.client.ios"
        return Rig(serverUrl: serverUrl, appBundleId: appBundleId, email: email, password: password)
    }
}
