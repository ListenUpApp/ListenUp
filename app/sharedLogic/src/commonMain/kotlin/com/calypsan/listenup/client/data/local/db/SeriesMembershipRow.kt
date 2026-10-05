package com.calypsan.listenup.client.data.local.db

/** One book's membership of one series, ids only — a row of [SeriesDao.observeVisibleMemberships]. */
internal data class SeriesMembershipRow(
    val seriesId: String,
    val bookId: String,
)
