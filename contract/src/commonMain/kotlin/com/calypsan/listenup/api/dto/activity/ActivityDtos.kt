package com.calypsan.listenup.api.dto.activity

/** Canonical activity-type strings (wire-stable). */
object ActivityType {
    const val STARTED_BOOK = "started_book"
    const val FINISHED_BOOK = "finished_book"
    const val LISTENING_SESSION = "listening_session"
    const val STREAK_MILESTONE = "streak_milestone"
    const val LISTENING_MILESTONE = "listening_milestone"
    const val SHELF_CREATED = "shelf_created"
    const val USER_JOINED = "user_joined"
}
