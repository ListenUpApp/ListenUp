package com.calypsan.listenup.client.push

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.calypsan.listenup.api.push.PushPayload
import com.calypsan.listenup.client.MainActivity
import com.calypsan.listenup.client.notifications.NotificationChannels
import listenup.composeapp.generated.resources.Res
import listenup.composeapp.generated.resources.push_campfire_invite_body
import listenup.composeapp.generated.resources.push_campfire_invite_title
import listenup.composeapp.generated.resources.push_campfire_invite_title_unknown
import listenup.composeapp.generated.resources.push_generic_body
import listenup.composeapp.generated.resources.push_generic_title
import listenup.composeapp.generated.resources.push_test_body
import listenup.composeapp.generated.resources.push_test_title
import org.jetbrains.compose.resources.getString

private const val RES_TYPE_DRAWABLE = "drawable"
private const val EXTRA_PUSH_TYPE = "push_type"

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
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                    payload?.let { putExtra(EXTRA_PUSH_TYPE, it::class.simpleName) }
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
     * THE per-type action seam. v1 always returns an empty list — no notification carries action
     * buttons yet. The Campfire arc adds a "Join" action (deep-link); registration approvals add
     * "Approve"/"Deny" (background API calls via WorkManager). New actions are added here, not
     * scattered across call sites — [ignored] is the future dispatch key.
     */
    private fun actionsFor(ignored: PushPayload?): List<NotificationCompat.Action> = emptyList()

    private fun notificationId(payload: PushPayload?): Int =
        if (payload is PushPayload.CampfireInvite) payload.campfireId.hashCode() else payload.hashCode()
}
