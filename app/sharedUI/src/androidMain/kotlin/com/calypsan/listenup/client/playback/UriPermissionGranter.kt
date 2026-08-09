package com.calypsan.listenup.client.playback

import android.content.Context
import android.content.Intent
import android.net.Uri

/**
 * Seam over [Context.grantUriPermission] so a grant's arguments — which package receives
 * access, and to which URI — are assertable in a test.
 *
 * This exists because the direct call is otherwise untestable: `Context.grantUriPermission`
 * is not shadowed or recorded by Robolectric, so swapping the grantee package and the URI
 * produces no crash, no compile error, and no test failure — just silently blank cover art in
 * a car. Routing the call through this interface lets a test substitute a fake that records
 * what was actually granted.
 */
fun interface UriPermissionGranter {
    /** Grants [toPackage] read access to [uri] (and, for a prefix URI, everything under it). */
    fun grantRead(
        toPackage: String,
        uri: Uri,
    )
}

/** Production [UriPermissionGranter], backed by the real [Context.grantUriPermission]. */
internal class ContextUriPermissionGranter(
    private val context: Context,
) : UriPermissionGranter {
    override fun grantRead(
        toPackage: String,
        uri: Uri,
    ) {
        context.grantUriPermission(
            toPackage,
            uri,
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PREFIX_URI_PERMISSION,
        )
    }
}
