package com.calypsan.listenup.server.services

import com.calypsan.listenup.api.dto.match.ExternalRef
import com.calypsan.listenup.api.metadata.ContributorField
import com.calypsan.listenup.api.metadata.FieldProvenance
import com.calypsan.listenup.api.metadata.FieldSourceKind
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.server.sync.ChangeBus
import com.calypsan.listenup.server.sync.SyncRegistry
import com.calypsan.listenup.server.testing.withSqlDatabase
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.test.runTest

private val HC = ExternalRef("hardcover", "250716")

/**
 * A contributor's refs and provenance ride every contributor write, and the legacy `asin` column stays the
 * truth for the ref its value names (spec, *ExternalRef → backward compatibility*): a plain value is the
 * Audible ref, the legacy Hardcover apply's `hardcover:author:<id>` is the Hardcover ref.
 */
class ContributorRepositoryRefsTest :
    FunSpec({
        context("reconcileContributorRefs") {
            test("a plain asin is the audible ref, keeping a store already recorded for it") {
                ContributorIdentity.reconcileRefs("B001", emptyList()) shouldBe listOf(ExternalRef("audible", "B001"))
                ContributorIdentity.reconcileRefs("B001", listOf(ExternalRef("audible", "B001", "uk"))) shouldBe
                    listOf(ExternalRef("audible", "B001", "uk"))
            }

            test("the column wins over a stale audible ref, and other providers pass through sorted") {
                ContributorIdentity.reconcileRefs("B002", listOf(HC, ExternalRef("audible", "B001"))) shouldBe
                    listOf(ExternalRef("audible", "B002"), HC)
            }

            test("a blank column drops the audible ref but keeps the others") {
                ContributorIdentity.reconcileRefs("  ", listOf(ExternalRef("audible", "B001"), HC)) shouldBe listOf(HC)
                ContributorIdentity.reconcileRefs(null, listOf(ExternalRef("audible", "B001"))) shouldBe emptyList()
            }

            test("a hardcover-shaped column is the hardcover ref, and no audible ref") {
                ContributorIdentity.reconcileRefs(
                    "hardcover:author:99",
                    listOf(HC, ExternalRef("audible", "B001")),
                ) shouldBe listOf(ExternalRef("hardcover", "99"))
            }

            test("one ref per provider, the first wins") {
                ContributorIdentity.reconcileRefs(null, listOf(HC, ExternalRef("hardcover", "1"))) shouldBe listOf(HC)
            }
        }

        test("refs and provenance written with a contributor read back") {
            withSqlDatabase {
                val repo = ContributorRepository(db = sql, bus = ChangeBus(), registry = SyncRegistry())
                runTest {
                    val id = repo.resolveOrCreate("Ray Porter", sortName = null)
                    val current = repo.findById(id.value)!!
                    val provenance =
                        mapOf(ContributorField.BIOGRAPHY to FieldProvenance(FieldSourceKind.USER, at = 7, by = "u1"))
                    repo.upsert(current.copy(asin = "B001", externalRefs = listOf(HC), fieldProvenance = provenance))

                    val stored = repo.findById(id.value)!!
                    stored.externalRefs shouldBe listOf(ExternalRef("audible", "B001"), HC)
                    stored.fieldProvenance shouldBe provenance
                    repo.readPayloadsForTest(listOf(id.value)).single() shouldBe stored
                }
            }
        }

        test("an older client's asin edit moves the audible ref, in one revision") {
            withSqlDatabase {
                val repo = ContributorRepository(db = sql, bus = ChangeBus(), registry = SyncRegistry())
                runTest {
                    val id = repo.resolveOrCreate("Andy Weir", sortName = null)
                    repo.upsert(repo.findById(id.value)!!.copy(asin = "B001"))
                    val before = repo.findById(id.value)!!

                    // An older client edits the ASIN alone (a ContributorUpdate patch); refs come along unchanged.
                    val result = repo.upsert(before.copy(asin = "B002"))

                    result.shouldBeInstanceOf<AppResult.Success<*>>()
                    val after = repo.findById(id.value)!!
                    after.externalRefs shouldBe listOf(ExternalRef("audible", "B002"))
                    after.revision shouldBe before.revision + 1
                }
            }
        }
    })
