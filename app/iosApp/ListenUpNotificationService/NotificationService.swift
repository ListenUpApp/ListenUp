import UserNotifications

/// Rewrites an incoming push with typed, localized text before iOS displays it.
///
/// Without this extension every ListenUp notification on iOS reads "ListenUp / Something happened
/// on your server." — the constant loc-keys the relay puts in the `aps` envelope. It cannot do
/// better: the relay forwards an opaque payload and deliberately knows nothing about what is
/// inside it, and the payload itself carries IDs only, never display text, so that a third-party
/// relay never learns who is being notified about what. Turning IDs into words is therefore the
/// client's job, and on iOS this is the only place with a chance to do it before the banner
/// appears. Android does the equivalent in `PushNotificationRenderer`.
///
/// The relay sets `mutable-content: 1` on every send specifically so this runs.
///
/// **Every path must call `contentHandler`.** iOS gives an extension a few seconds and then
/// displays whatever it was given; a path that returns without calling it, or that throws, shows
/// the untouched generic copy. That is a survivable failure — the notification still arrives — and
/// it is why every branch below degrades to "leave the text alone" instead of trying harder.
final class NotificationService: UNNotificationServiceExtension {
    private var contentHandler: ((UNNotificationContent) -> Void)?
    private var mutableContent: UNMutableNotificationContent?

    override func didReceive(
        _ request: UNNotificationRequest,
        withContentHandler contentHandler: @escaping (UNNotificationContent) -> Void
    ) {
        self.contentHandler = contentHandler
        let content = request.content.mutableCopy() as? UNMutableNotificationContent
        mutableContent = content

        guard let content else {
            contentHandler(request.content)
            return
        }

        if let payload = PushPayloadContent.from(userInfo: request.content.userInfo),
           let text = NotificationCopy.forPayload(payload) {
            content.title = text.title
            content.body = text.body
        }
        // No payload, or one this build does not recognise: the generic copy the relay supplied
        // stands. A newer server must never produce a blank notification on an older app.
        contentHandler(content)
    }

    /// Called when the few seconds are nearly up. Hand back whatever has been built so far —
    /// partially-rewritten copy still beats iOS silently substituting the original.
    override func serviceExtensionTimeWillExpire() {
        if let contentHandler, let mutableContent {
            contentHandler(mutableContent)
        }
    }
}
