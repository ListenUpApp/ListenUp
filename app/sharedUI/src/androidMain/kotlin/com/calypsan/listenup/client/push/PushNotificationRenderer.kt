package com.calypsan.listenup.client.push

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.calypsan.listenup.api.push.PushPayload
import com.calypsan.listenup.client.MainActivity
import com.calypsan.listenup.api.contractJson
import com.calypsan.listenup.client.notifications.NotificationChannels
import com.calypsan.listenup.client.shortcuts.ShortcutActions
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import listenup.composeapp.generated.resources.Res
import listenup.composeapp.generated.resources.push_campfire_invite_body
import listenup.composeapp.generated.resources.push_campfire_invite_title
import listenup.composeapp.generated.resources.push_campfire_invite_title_unknown
import listenup.composeapp.generated.resources.push_generic_body
import listenup.composeapp.generated.resources.push_registration_action_approve
import listenup.composeapp.generated.resources.push_registration_action_deny
import listenup.composeapp.generated.resources.push_registration_approved_body
import listenup.composeapp.generated.resources.push_registration_approved_title
import listenup.composeapp.generated.resources.push_registration_denied_body
import listenup.composeapp.generated.resources.push_registration_denied_title
import listenup.composeapp.generated.resources.push_registration_request_body
import listenup.composeapp.generated.resources.push_registration_request_body_unknown
import listenup.composeapp.generated.resources.push_registration_request_title
import listenup.composeapp.generated.resources.push_generic_title
import listenup.composeapp.generated.resources.push_test_body
import listenup.composeapp.generated.resources.push_test_title
import org.jetbrains.compose.resources.getString

private const val RES_TYPE_DRAWABLE = "drawable"

/** Title + body of a rendered local notification, pre-enrichment. */
private data class NotificationContent(
    val title: String,
    val body: String,
)

/**
 * Decodes a [PushPayload] into an enriched, localized local notification and posts it on the
 * [NotificationChannels.SOCIAL] channel.
 *
 * Push payloads carry IDs only (never names or titles — see [PushPayload]'s KDoc), so enrichment
 * resolves display data locally: [bookTitleLookup] and [inviterNameLookup] are best-effort suspend
 * lookups wired at the DI site to the client's own repositories (local Room first, the
 * repository's own server fallback second). Both lookups are `runCatching`-wrapped here — any
 * failure degrades to the generic/unknown-inviter copy rather than losing the notification.
 */
class PushNotificationRenderer(
    private val context: Context,
    private val bookTitleLookup: suspend (String) -> String?,
    private val inviterNameLookup: suspend (String) -> String?,
    private val pendingUserNameLookup: suspend (String) -> String?,
) {
    private val smallIcon: Int by lazy {
        context.resources
            .getIdentifier("ic_notification", RES_TYPE_DRAWABLE, context.packageName)
            .takeIf { it != 0 }
            ?: android.R.drawable.ic_dialog_info
    }

    /** Decodes, enriches, and posts a local notification for [payload]. `null` renders generic copy. */
    suspend fun render(payload: PushPayload?) {
        val content =
            when (payload) {
                is PushPayload.TestNotification -> {
                    NotificationContent(
                        title = getString(Res.string.push_test_title),
                        body = getString(Res.string.push_test_body),
                    )
                }

                is PushPayload.CampfireInvite -> {
                    val inviter = runCatching { inviterNameLookup(payload.inviterUserId) }.getOrNull()
                    val book = runCatching { bookTitleLookup(payload.bookId) }.getOrNull()
                    NotificationContent(
                        title =
                            inviter?.let { getString(Res.string.push_campfire_invite_title, it) }
                                ?: getString(Res.string.push_campfire_invite_title_unknown),
                        body = book?.let { getString(Res.string.push_campfire_invite_body, it) } ?: "",
                    )
                }

                is PushPayload.RegistrationDecision -> {
                    // Static per-outcome copy — no enrichment: the recipient is the pre-auth
                    // registrant themselves; there is nothing local to look up (#1068).
                    if (payload.approved) {
                        NotificationContent(
                            title = getString(Res.string.push_registration_approved_title),
                            body = getString(Res.string.push_registration_approved_body),
                        )
                    } else {
                        NotificationContent(
                            title = getString(Res.string.push_registration_denied_title),
                            body = getString(Res.string.push_registration_denied_body),
                        )
                    }
                }

                is PushPayload.RegistrationApproval -> {
                    // Enriched, unlike RegistrationDecision: the recipient here is an ADMIN, and
                    // an admin's client already mirrors the pending user in its synced roster. The
                    // name is resolved locally precisely so it never has to cross the relay — a
                    // push naming everyone who requests access to a private server would leak
                    // exactly what a self-hosted install exists to keep private.
                    val name = runCatching { pendingUserNameLookup(payload.userId) }.getOrNull()
                    NotificationContent(
                        title = getString(Res.string.push_registration_request_title),
                        body =
                            name?.let { getString(Res.string.push_registration_request_body, it) }
                                ?: getString(Res.string.push_registration_request_body_unknown),
                    )
                }

                null -> {
                    NotificationContent(
                        title = getString(Res.string.push_generic_title),
                        body = getString(Res.string.push_generic_body),
                    )
                }
            }

        val tapIntent =
            PendingIntent.getActivity(
                context,
                payload.hashCode(),
                Intent(context, MainActivity::class.java).apply {
                    // An explicit action, so MainActivity dispatches this through the same
                    // `when (intent.action)` as every shortcut rather than sniffing extras.
                    action = ShortcutActions.PUSH_TAP
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                    // The STABLE wire discriminator, not simpleName: R8 renames classes in a
                    // release build, so a simpleName match would work in debug and quietly stop
                    // matching in the build users actually run.
                    payload?.let { putExtra(ShortcutActions.EXTRA_PUSH_TYPE, it.wireType()) }
                    (payload as? PushPayload.RegistrationApproval)?.let {
                        putExtra(ShortcutActions.EXTRA_PUSH_SUBJECT_ID, it.userId)
                    }
                    // Campfire deep-link target lands with the Campfire arc; the single seam
                    // for per-type actions/routing is actionsFor() + this intent.
                },
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )

        val notification =
            NotificationCompat
                .Builder(context, NotificationChannels.SOCIAL)
                .setSmallIcon(smallIcon)
                .setContentTitle(content.title)
                .setContentText(content.body)
                .setAutoCancel(true)
                .setContentIntent(tapIntent)
                .apply { actionsFor(payload).forEach(::addAction) }
                .build()

        // POST_NOTIFICATIONS may be denied (Android 13+): notify() is then a silent no-op — acceptable,
        // the in-app SSE-fed surface still carries the same event.
        NotificationManagerCompat.from(context).notify(notificationId(payload), notification)
    }

    /**
     * THE per-type action seam. Registration approvals carry Approve/Deny; everything else is
     * tap-only. The Campfire arc adds its "Join" deep-link here rather than at a call site.
     */
    private suspend fun actionsFor(payload: PushPayload?): List<NotificationCompat.Action> =
        when (payload) {
            is PushPayload.RegistrationApproval -> {
                listOf(
                    decisionAction(
                        payload.userId,
                        approve = true,
                        label = getString(Res.string.push_registration_action_approve),
                    ),
                    decisionAction(
                        payload.userId,
                        approve = false,
                        label = getString(Res.string.push_registration_action_deny),
                    ),
                )
            }

            else -> {
                emptyList()
            }
        }

    /**
     * One Approve/Deny button, broadcast to [PushActionReceiver] and applied by
     * [RegistrationDecisionWorker].
     *
     * `setAuthenticationRequired(true)` is the load-bearing line. Granting someone access to a
     * private server is a privileged mutation, and without this it could be performed from the
     * lock screen of an unattended phone by whoever picked it up — no app open, no unlock, no
     * trace until an admin noticed a stranger in the roster. The OS demands a device unlock before
     * it will fire the intent, which is the same bar the in-app path effectively has. minSdk is 33,
     * so this is always available; there is no older path to fall back to.
     *
     * `requestCode` mixes the user id with the decision so Approve and Deny cannot collide into one
     * PendingIntent — `FLAG_UPDATE_CURRENT` on a shared request code would quietly make both
     * buttons do whatever the last one registered.
     */
    private fun decisionAction(
        userId: String,
        approve: Boolean,
        label: String,
    ): NotificationCompat.Action {
        val intent =
            Intent(context, PushActionReceiver::class.java).apply {
                action = PushActionReceiver.ACTION_DECIDE_REGISTRATION
                putExtra(PushActionReceiver.EXTRA_USER_ID, userId)
                putExtra(PushActionReceiver.EXTRA_APPROVE, approve)
                putExtra(PushActionReceiver.EXTRA_NOTIFICATION_ID, registrationNotificationId(userId))
            }
        val pending =
            PendingIntent.getBroadcast(
                context,
                (userId + approve).hashCode(),
                intent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        return NotificationCompat.Action
            .Builder(smallIcon, label, pending)
            .setAuthenticationRequired(true)
            .build()
    }

    /** Stable per-pending-user id, so the receiver can dismiss the exact notification it acted on. */
    private fun registrationNotificationId(userId: String): Int = userId.hashCode()

    /**
     * The payload's stable `@SerialName`, read off its own serializer rather than restated here —
     * a second hand-maintained copy of the discriminator is a copy that drifts.
     */
    private fun PushPayload.wireType(): String =
        contractJson.encodeToString(PushPayload.serializer(), this).let { encoded ->
            Json
                .parseToJsonElement(encoded)
                .jsonObject["type"]
                ?.jsonPrimitive
                ?.content
                .orEmpty()
        }

    private fun notificationId(payload: PushPayload?): Int =
        when (payload) {
            is PushPayload.CampfireInvite -> payload.campfireId.hashCode()

            // Keyed on the waiting user, matching the relay's collapse key and the id the action
            // buttons dismiss — a re-sent request replaces its notification instead of stacking.
            is PushPayload.RegistrationApproval -> registrationNotificationId(payload.userId)

            else -> payload.hashCode()
        }
}
