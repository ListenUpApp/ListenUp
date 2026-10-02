package com.calypsan.listenup.web

import com.calypsan.listenup.web.features.admin.fixedCollectionDetail
import com.calypsan.listenup.web.features.admin.fixedRestrictedBooks
import com.calypsan.listenup.web.features.admin.readyDetail
import com.calypsan.listenup.web.features.contributordetail.fixedContributorDetail
import com.calypsan.listenup.web.features.contributordetail.readyContributor
import com.calypsan.listenup.web.features.seriesdetail.fixedSeriesDetail
import com.calypsan.listenup.web.features.seriesdetail.readySeries
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import org.w3c.dom.HTMLElement

/**
 * Beyond the library grid and search: the other book lists spec §7 names — series, author and
 * collection — wear the lock on the cover of each restricted book, compact on the small row covers.
 */
class CardLockSitesTest :
    FunSpec({
        test("a series' book rows: the compact lock on the restricted book only") {
            val (host, router, composition) =
                mountAt(
                    "/series/s-stormlight",
                    openSeriesDetail = fixedSeriesDetail(readySeries()),
                    openRestrictedBooks = fixedRestrictedBooks(setOf("b1")),
                )
            try {
                host.querySelectorAll(".sd-book-frame .cover > .lu-lock.sm").length shouldBe 1
                host.querySelectorAll(".lu-lock").length shouldBe 1
            } finally {
                composition.dispose()
                router.dispose()
            }
        }

        test("an author's tiles: the full-size lock") {
            val (host, router, composition) =
                mountAt(
                    "/contributor/c-king",
                    openContributorDetail = fixedContributorDetail(readyContributor()),
                    openRestrictedBooks = fixedRestrictedBooks(setOf("b1")),
                )
            try {
                host.querySelectorAll(".cd-tile-frame .cover > .lu-lock").length shouldBe 1
                host.querySelectorAll(".lu-lock.sm").length shouldBe 0
            } finally {
                composition.dispose()
                router.dispose()
            }
        }

        test("a collection's own book rows: the compact lock") {
            val (host, router, composition) =
                mountAt(
                    "/admin/collections/c7",
                    openCollectionDetail = { fixedCollectionDetail(readyDetail())(it) },
                    openRestrictedBooks = fixedRestrictedBooks(setOf("b1")),
                )
            try {
                host.querySelectorAll(".cdet-book .cover > .lu-lock.sm").length shouldBe 1
            } finally {
                composition.dispose()
                router.dispose()
            }
        }
    })
