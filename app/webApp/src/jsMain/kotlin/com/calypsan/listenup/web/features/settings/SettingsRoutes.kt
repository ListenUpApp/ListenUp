package com.calypsan.listenup.web.features.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.calypsan.listenup.core.currentEpochMilliseconds
import com.calypsan.listenup.web.design.ShowActionToast
import com.calypsan.listenup.web.features.admin.AdminRoute
import com.calypsan.listenup.web.features.admin.AdminRouteContent
import com.calypsan.listenup.web.features.admin.AdminSessions
import com.calypsan.listenup.web.features.admin.CategoriesRoute
import com.calypsan.listenup.web.features.admin.OpenAdmin
import com.calypsan.listenup.web.features.devices.DevicesPage
import com.calypsan.listenup.web.features.devices.OpenDevices
import com.calypsan.listenup.web.features.hardcover.HardcoverRoute
import com.calypsan.listenup.web.features.hardcover.OpenHardcover
import com.calypsan.listenup.web.features.licences.LicencesPage
import com.calypsan.listenup.web.features.licences.OpenLicences
import com.calypsan.listenup.web.features.notifications.NotificationPrefsRoute
import com.calypsan.listenup.web.features.notifications.OpenNotificationPrefs
import com.calypsan.listenup.web.nav.ADMIN_KEY
import com.calypsan.listenup.web.nav.CATEGORIES_KEY
import com.calypsan.listenup.web.nav.DEVICES_KEY
import com.calypsan.listenup.web.nav.HARDCOVER_KEY
import com.calypsan.listenup.web.nav.LICENCES_KEY
import com.calypsan.listenup.web.nav.NOTIFICATIONS_KEY
import com.calypsan.listenup.web.nav.PENDING_SECTION
import com.calypsan.listenup.web.nav.Route
import com.calypsan.listenup.web.nav.Router
import com.calypsan.listenup.web.nav.Routes
import com.calypsan.listenup.web.nav.SECTION_QUERY_KEY
import com.calypsan.listenup.web.nav.SETTINGS_KEY
import com.calypsan.listenup.web.nav.rememberSession
import com.calypsan.listenup.web.shell.NotFoundPage
import com.calypsan.listenup.web.shell.RouteContent
import com.calypsan.listenup.web.shell.nowMs

/**
 * The `/settings` branch.
 *
 * No router: nothing on this page navigates. Every control writes a preference and the effect is
 * the page itself changing — the theme most visibly, since it repaints the whole shell.
 */
@Composable
private fun SettingsRoute(
    router: Router,
    openSettings: OpenSettings,
) {
    val session = rememberSession { openSettings() }

    SettingsPage(
        state = session.state.collectAsState().value,
        onThemeMode = session.onThemeMode,
        onDefaultSpeed = session.onDefaultSpeed,
        onDefaultBoost = session.onDefaultBoost,
        onSkipForward = session.onSkipForward,
        onSkipBackward = session.onSkipBackward,
        onAutoRewind = session.onAutoRewind,
        onHideSingleBookSeries = session.onHideSingleBookSeries,
        onOpenDevices = { router.navigate(Routes.settingsDevices) },
        onOpenNotifications = { router.navigate(Routes.settingsNotifications) },
        onOpenLicences = { router.navigate(Routes.settingsLicences) },
        hardcoverRow = session.hardcoverRow.collectAsState().value,
        onOpenHardcover = { router.navigate(Routes.settingsHardcover) },
        canCurateLibrary = session.canCurateLibrary.collectAsState().value,
        onOpenCategories = { router.navigate(Routes.settingsCategories) },
        onDownloadLogs = session.downloadLogs,
    )
}

/**
 * `/settings/licences` — what the web client is built from.
 *
 * Opened once for the route's life: the manifest is a static build artifact, so there is nothing to
 * re-fetch while the page is up.
 */
@Composable
private fun LicencesRoute(
    router: Router,
    openLicences: OpenLicences,
) {
    val session = rememberSession { openLicences() }

    LicencesPage(
        state = session.state.collectAsState().value,
        onOpenSettings = { router.navigate(Routes.settings) },
    )
}

/**
 * The `/settings/devices` branch.
 *
 * `nowMs` is read once per visit, as on Discover: every timestamp here is at least a minute old, so
 * a ticking clock would re-render the list each second to change nothing anyone is watching.
 */
@Composable
internal fun DevicesRoute(openDevices: OpenDevices) {
    val session = rememberSession { openDevices() }
    val nowMs = remember { currentEpochMilliseconds() }

    DevicesPage(
        state = session.state.collectAsState().value,
        nowMs = nowMs,
        onRevoke = session.onRevoke,
        onSignOutOthers = session.onSignOutOthers,
        onRetry = session.onRetry,
    )
}

/**
 * Whether these segments belong to the account family — settings, devices, admin.
 *
 * Grouped for the same reason the shelf routes were: [RouteContent]'s chain grows a branch per
 * screen and tripped its complexity limit on the third of these. One condition here, one `when`
 * below, and the shell's chain stops caring how many account screens exist.
 */
internal fun isAccountRoute(
    segments: List<String>,
    active: String,
): Boolean = active == SETTINGS_KEY || active == ADMIN_KEY || segments.firstOrNull() == SETTINGS_KEY

/** The account family: settings, the devices beneath it, and admin. */
@Composable
internal fun AccountRouteContent(
    route: Route,
    active: String,
    router: Router,
    openSettings: OpenSettings,
    openDevices: OpenDevices,
    openHardcover: OpenHardcover,
    openAdmin: OpenAdmin,
    openNotificationPrefs: OpenNotificationPrefs,
    openLicences: OpenLicences,
    admin: AdminSessions,
    onToast: (String) -> Unit,
    onActionToast: ShowActionToast,
) {
    val segments = route.segments
    when {
        segments.firstOrNull() == ADMIN_KEY && segments.size > 1 -> {
            AdminRouteContent(
                sub = segments[1],
                id = segments.getOrNull(2),
                router = router,
                admin = admin,
                onToast = onToast,
            )
        }

        segments.firstOrNull() == SETTINGS_KEY && segments.getOrNull(1) == DEVICES_KEY -> {
            DevicesRoute(openDevices = openDevices)
        }

        segments.firstOrNull() == SETTINGS_KEY && segments.getOrNull(1) == NOTIFICATIONS_KEY -> {
            NotificationPrefsRoute(router = router, openNotificationPrefs = openNotificationPrefs)
        }

        segments.firstOrNull() == SETTINGS_KEY && segments.getOrNull(1) == HARDCOVER_KEY -> {
            HardcoverRoute(
                router = router,
                openHardcover = openHardcover,
                onToast = onToast,
                onActionToast = onActionToast,
                subPage = segments.getOrNull(2),
            )
        }

        segments.firstOrNull() == SETTINGS_KEY && segments.getOrNull(1) == LICENCES_KEY -> {
            LicencesRoute(router = router, openLicences = openLicences)
        }

        segments.firstOrNull() == SETTINGS_KEY && segments.getOrNull(1) == CATEGORIES_KEY -> {
            // `/settings/categories` — the same screen, reached by a curator who has no admin area.
            CategoriesRoute(
                router = router,
                openCategories = admin.categories,
                parentCrumb = "Settings",
                parent = Routes.settings,
            )
        }

        // `size <= 1` for the reason the Settings branch needs it: `active` is ADMIN_KEY for
        // every `/admin/*` URL, so without it this swallows `/admin/nonsense` and shows Admin.
        active == ADMIN_KEY && segments.size <= 1 -> {
            AdminRoute(
                openAdmin = openAdmin,
                onOpenLibrarySettings = { router.navigate(Routes.Admin.library) },
                onOpenInbox = { router.navigate(Routes.Admin.inbox) },
                onOpenServerSettings = { router.navigate(Routes.Admin.serverSettings) },
                onOpenCategories = { router.navigate(Routes.Admin.categories) },
                onOpenCollections = { router.navigate(Routes.Admin.collections) },
                onOpenBackups = { router.navigate(Routes.Admin.backups) },
                onOpenImports = { router.navigate(Routes.Admin.imports) },
                onOpenInvite = { router.navigate(Routes.Admin.invite) },
                onOpenUpload = { router.navigate(Routes.Admin.upload) },
                onOpenOrganize = { router.navigate(Routes.Admin.organize) },
                onOpenUser = { id -> router.navigate(Routes.Admin.user(id)) },
                landOnPending = route.query[SECTION_QUERY_KEY] == PENDING_SECTION,
            )
        }

        // `size <= 1` is load-bearing, not defensive. `active` is SETTINGS_KEY for every
        // `/settings/*` URL, so without it this branch swallows `/settings/nonsense` and renders
        // Settings — which is exactly what the comment below has always said it does not do.
        active == SETTINGS_KEY && segments.size <= 1 -> {
            SettingsRoute(router = router, openSettings = openSettings)
        }

        // A `/settings/anything-else` URL. Falls through to the shell's own not-found rather than
        // silently showing Settings, so a mistyped path says so.
        else -> {
            NotFoundPage(onGoHome = { router.navigate(Routes.home) })
        }
    }
}
