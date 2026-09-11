package com.calypsan.listenup.web.features.admin

/**
 * Every way into an admin surface, as one value.
 *
 * ⛔ A bundle rather than twelve parameters, and the reason is measured rather than aesthetic:
 * these openers were threaded individually through [com.calypsan.listenup.web.WebAppRoot],
 * `RouteContent`, `AccountRouteContent` and `AdminRouteContent` — four signatures, so each new
 * admin screen cost eight parameter lines and pushed the two outer functions past detekt's length
 * budget. Four slices in a row ended with lines shaved off those functions to fit, which is the
 * shape of a problem being paid for rather than fixed.
 *
 * The admin family is bundled here because this is the family that grew; the rest of the app's
 * openers are still threaded one by one and want the same treatment.
 */
data class AdminSessions(
    val librarySettings: OpenLibrarySettings,
    val inbox: OpenAdminInbox,
    val serverSettings: OpenServerSettings,
    val categories: OpenCategories,
    val collections: OpenCollections,
    val collectionDetail: OpenCollectionDetail,
    val backups: OpenBackups,
    val restore: OpenRestore,
    val imports: OpenImports,
    val importFlow: OpenImportFlow,
    val createInvite: OpenCreateInvite,
    val userDetail: OpenUserDetail,
)
