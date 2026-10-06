package com.calypsan.listenup.server.api

import com.calypsan.listenup.api.dto.ContributorUpdate
import com.calypsan.listenup.api.metadata.ContributorField
import com.calypsan.listenup.api.metadata.FieldProvenance
import com.calypsan.listenup.api.metadata.FieldSourceKind
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.core.ContributorId
import com.calypsan.listenup.server.services.BookRepository
import com.calypsan.listenup.server.services.ContributorRepository
import com.calypsan.listenup.server.services.GenreRepository
import com.calypsan.listenup.server.services.SeriesRepository
import com.calypsan.listenup.server.sync.ChangeBus
import com.calypsan.listenup.server.sync.SyncRegistry
import com.calypsan.listenup.server.testing.SqlTestDatabases
import com.calypsan.listenup.server.testing.rootPrincipal
import com.calypsan.listenup.server.testing.withSqlDatabase
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.longs.shouldBeGreaterThan
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.test.runTest

private class StampRig(
    db: SqlTestDatabases,
) {
    val contributors = ContributorRepository(db = db.sql, bus = ChangeBus(), registry = SyncRegistry())
    val service =
        ContributorServiceImpl(
            contributorRepo = contributors,
            bookRepo =
                BookRepository(
                    db = db.sql,
                    driver = db.driver,
                    bus = ChangeBus(),
                    registry = SyncRegistry(),
                    contributorRepository = contributors,
                    seriesRepository = SeriesRepository(db.sql, ChangeBus(), SyncRegistry()),
                    genreRepository = GenreRepository(db.sql, ChangeBus(), SyncRegistry()),
                ),
            sqlDb = db.sql,
            accessPolicy = BookAccessPolicy(db.sql, db.driver),
            principal = rootPrincipal("editor-1"),
        )
}

/**
 * A contributor field you edit by hand is recorded as yours — who and when — so a person match leaves it alone
 * by default (decision 2) and Review can say "Edited by you". Only the fields the edit touched are stamped.
 */
class ContributorProvenanceStampingTest :
    FunSpec({
        test("editing the biography stamps BIOGRAPHY as yours, and nothing else") {
            withSqlDatabase {
                val rig = StampRig(this)
                runTest {
                    val id = rig.contributors.resolveOrCreate("Ray Porter", sortName = null)

                    rig.service
                        .updateContributor(id, ContributorUpdate(description = "Narrator of the Bobiverse."))
                        .shouldBeInstanceOf<AppResult.Success<Unit>>()

                    val provenance =
                        rig.contributors
                            .findById(id.value)
                            .shouldNotBeNull()
                            .fieldProvenance
                    provenance.keys shouldBe setOf(ContributorField.BIOGRAPHY)
                    val stamp = provenance.getValue(ContributorField.BIOGRAPHY)
                    stamp.kind shouldBe FieldSourceKind.USER
                    stamp.by shouldBe "editor-1"
                    stamp.at shouldBeGreaterThan 0L
                }
            }
        }

        test("a photo (the upload route's patch) stamps PHOTO; a rename stamps NAME and SORT_NAME") {
            withSqlDatabase {
                val rig = StampRig(this)
                runTest {
                    val id = rig.contributors.resolveOrCreate("Ray Porter", sortName = null)
                    rig.service.updateContributor(id, ContributorUpdate(imagePath = "contributors/abc.jpg"))
                    rig.service.updateContributor(id, ContributorUpdate(name = "R. Porter", sortName = "Porter, R."))

                    rig.contributors
                        .findById(id.value)
                        .shouldNotBeNull()
                        .fieldProvenance
                        .keys
                        .shouldContainExactlyInAnyOrder(
                            ContributorField.PHOTO,
                            ContributorField.NAME,
                            ContributorField.SORT_NAME,
                        )
                }
            }
        }

        test("an edit keeps the provenance of fields it didn't touch") {
            withSqlDatabase {
                val rig = StampRig(this)
                runTest {
                    val id = rig.contributors.resolveOrCreate("Ray Porter", sortName = null)
                    val matched = FieldProvenance(FieldSourceKind.ENRICHMENT, provider = "hardcover", at = 1)
                    rig.contributors.upsert(
                        rig.contributors.findById(id.value)!!.copy(
                            fieldProvenance = mapOf(ContributorField.PHOTO to matched),
                        ),
                    )

                    rig.service.updateContributor(ContributorId(id.value), ContributorUpdate(description = "Bio."))

                    val provenance = rig.contributors.findById(id.value)!!.fieldProvenance
                    provenance[ContributorField.PHOTO] shouldBe matched
                    provenance.getValue(ContributorField.BIOGRAPHY).kind shouldBe FieldSourceKind.USER
                }
            }
        }
    })
