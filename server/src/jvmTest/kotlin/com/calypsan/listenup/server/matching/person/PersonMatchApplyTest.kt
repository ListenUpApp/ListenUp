package com.calypsan.listenup.server.matching.person

import com.calypsan.listenup.api.dto.match.AppliedChange
import com.calypsan.listenup.api.dto.match.ExternalRef
import com.calypsan.listenup.api.dto.match.FieldChoice
import com.calypsan.listenup.api.dto.match.ImageChoice
import com.calypsan.listenup.api.dto.match.MetadataSource
import com.calypsan.listenup.api.dto.match.PersonCandidateKey
import com.calypsan.listenup.api.error.MetadataError
import com.calypsan.listenup.api.metadata.ContributorField
import com.calypsan.listenup.api.metadata.FieldProvenance
import com.calypsan.listenup.api.metadata.FieldSourceKind
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.api.sync.SyncDomains
import com.calypsan.listenup.server.sync.withCapturedFrames
import com.calypsan.listenup.server.testing.shouldSucceed
import com.calypsan.listenup.server.testing.withSqlDatabase
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldStartWith
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.test.runTest

private fun AppResult<*>.error() = (this as AppResult.Failure).error

private val AUDIBLE_SOURCE = MetadataSource("audible", "Audible")
private val HARDCOVER_SOURCE = MetadataSource("hardcover", "Hardcover")

/** Person Apply (spec, *Find and Review for people*): photo and biography, separately, in one transaction. */
class PersonMatchApplyTest :
    FunSpec({
        test("photo and biography come from the sources chosen, independently, with a receipt saying so") {
            withSqlDatabase {
                runTest {
                    val rig = PersonRig(this@withSqlDatabase)
                    rig.seedRay()
                    val receipt = rig.apply(rig.mixedRequest()).shouldSucceed()
                    val after = rig.person()
                    after.description shouldBe "Audible bio."
                    after.imagePath!! shouldStartWith "contributors/"
                    after.imagePath shouldNotBe "contributors/old.jpg"
                    receipt.changes shouldContainExactly
                        listOf(AppliedChange.Photo(HARDCOVER_SOURCE), AppliedChange.Biography(AUDIBLE_SOURCE))
                    receipt.undoable shouldBe true
                }
            }
        }

        test("keeping the photo writes only the biography") {
            withSqlDatabase {
                runTest {
                    val rig = PersonRig(this@withSqlDatabase)
                    rig.seedRay()
                    val receipt = rig.apply(rig.mixedRequest().copy(photo = ImageChoice.KeepCurrent)).shouldSucceed()
                    rig.person().imagePath shouldBe "contributors/old.jpg"
                    rig.person().description shouldBe "Audible bio."
                    receipt.changes shouldContainExactly listOf(AppliedChange.Biography(AUDIBLE_SOURCE))
                }
            }
        }

        test("keeping the biography writes only the photo") {
            withSqlDatabase {
                runTest {
                    val rig = PersonRig(this@withSqlDatabase)
                    rig.seedRay()
                    val receipt = rig.apply(rig.mixedRequest().copy(biography = FieldChoice.KeepCurrent)).shouldSucceed()
                    rig.person().description shouldBe "Old bio."
                    rig.person().imagePath shouldNotBe "contributors/old.jpg"
                    receipt.changes shouldContainExactly listOf(AppliedChange.Photo(HARDCOVER_SOURCE))
                }
            }
        }

        test("the name is never written, and the aliases are kept") {
            withSqlDatabase {
                runTest {
                    val rig = PersonRig(this@withSqlDatabase)
                    val before = rig.seedRay()
                    rig.hardcover.profiles = mapOf("250716" to ray("250716", bio = "Bio.", photo = HARDCOVER_PHOTO))
                    rig.apply(rig.defaultRequest()).shouldSucceed()
                    val after = rig.person()
                    after.name shouldBe before.name
                    after.sortName shouldBe before.sortName
                    after.aliases shouldBe before.aliases
                }
            }
        }

        test("written fields are stamped ENRICHMENT from their provider; untouched fields keep their provenance") {
            withSqlDatabase {
                runTest {
                    val rig = PersonRig(this@withSqlDatabase)
                    rig.seedRay()
                    val nameEdit = FieldProvenance(FieldSourceKind.USER, at = 5L, by = "u9")
                    rig.contributors
                        .upsert(rig.person().copy(fieldProvenance = mapOf(ContributorField.NAME to nameEdit)))
                        .shouldSucceed()
                    rig.apply(rig.mixedRequest()).shouldSucceed()
                    val provenance = rig.person().fieldProvenance
                    provenance[ContributorField.NAME] shouldBe nameEdit
                    provenance[ContributorField.BIOGRAPHY] shouldBe
                        FieldProvenance(FieldSourceKind.ENRICHMENT, provider = "audnexus", at = 1_000L)
                    provenance[ContributorField.PHOTO] shouldBe
                        FieldProvenance(FieldSourceKind.ENRICHMENT, provider = "hardcover", at = 1_000L)
                }
            }
        }

        test("the candidate's refs replace those providers' refs and leave the others; the asin column follows") {
            withSqlDatabase {
                runTest {
                    val rig = PersonRig(this@withSqlDatabase)
                    rig.seedRay()
                    rig.apply(rig.defaultRequest()).shouldSucceed()
                    val after = rig.person()
                    after.externalRefs shouldContainExactly
                        listOf(
                            ExternalRef("audible", "B0RAY"),
                            ExternalRef("custom:wiki", "ray"),
                            ExternalRef("hardcover", "250716"),
                        )
                    after.asin shouldBe "B0RAY"
                }
            }
        }

        test("a Hardcover-only match keeps the Audible link and adds Hardcover's") {
            withSqlDatabase {
                runTest {
                    val rig = PersonRig(this@withSqlDatabase)
                    rig.seedRay()
                    val key = PersonCandidateKey(listOf(ExternalRef("hardcover", "250716")))
                    rig.apply(rig.defaultRequest(key)).shouldSucceed()
                    val after = rig.person()
                    after.asin shouldBe "B0OLD"
                    after.externalRefs.map { it.provider to it.id } shouldContainExactly
                        listOf("audible" to "B0OLD", "custom:wiki" to "ray", "hardcover" to "250716")
                }
            }
        }

        test("one apply is one contributor revision and one contributor frame") {
            withSqlDatabase {
                runTest {
                    val rig = PersonRig(this@withSqlDatabase)
                    val before = rig.seedRay()
                    val applied = withCapturedFrames { rig.apply(rig.mixedRequest()) }.shouldSucceed()
                    applied.frames.count { it.domain == SyncDomains.CONTRIBUTORS.name } shouldBe 1
                    applied.frames.size shouldBe 1
                    val after = rig.person()
                    (after.revision > before.revision) shouldBe true
                    rig.receipts.find(applied.value.receiptId)!!.revisionAfter shouldBe after.revision
                }
            }
        }

        test("a person changed since the Review is ReviewOutdated, and nothing is written") {
            withSqlDatabase {
                runTest {
                    val rig = PersonRig(this@withSqlDatabase)
                    rig.seedRay()
                    val request = rig.mixedRequest()
                    rig.contributors.upsert(rig.person().copy(website = "https://ray.example")).shouldSucceed()
                    val moved = rig.person()
                    rig.apply(request).error().shouldBeInstanceOf<MetadataError.ReviewOutdated>()
                    rig.person() shouldBe moved
                }
            }
        }

        test("a person changed between the Review and the commit is still ReviewOutdated") {
            withSqlDatabase {
                runTest {
                    val rig = PersonRig(this@withSqlDatabase)
                    rig.seedRay()
                    val request = rig.mixedRequest()
                    val stale = rig.person()
                    rig.contributors.upsert(stale.copy(website = "https://ray.example")).shouldSucceed()
                    val moved = rig.person()
                    rig.applier
                        .apply(stale, request, PERSON_US, appliedBy = "u1")
                        .error()
                        .shouldBeInstanceOf<MetadataError.ReviewOutdated>()
                    rig.person() shouldBe moved
                }
            }
        }

        test("an option no longer offered is ReviewOutdated") {
            withSqlDatabase {
                runTest {
                    val rig = PersonRig(this@withSqlDatabase)
                    val before = rig.seedRay()
                    val request = rig.mixedRequest()
                    rig.audnexus.profiles = mapOf("B0RAY" to ray("B0RAY", bio = "A rewritten bio.", photo = AUDIBLE_PHOTO))
                    rig.apply(request).error().shouldBeInstanceOf<MetadataError.ReviewOutdated>()
                    rig.person() shouldBe before
                }
            }
        }

        test("a photo option no longer offered is ReviewOutdated") {
            withSqlDatabase {
                runTest {
                    val rig = PersonRig(this@withSqlDatabase)
                    val before = rig.seedRay()
                    val request = rig.mixedRequest()
                    rig.hardcover.profiles =
                        mapOf("250716" to ray("250716", bio = "Hardcover bio.", photo = "https://example.test/new.jpg"))
                    rig.apply(request).error().shouldBeInstanceOf<MetadataError.ReviewOutdated>()
                    rig.person() shouldBe before
                }
            }
        }

        test("a photo that can't be fetched is CoverDownloadFailed, and nothing is written") {
            withSqlDatabase {
                runTest {
                    val rig = PersonRig(this@withSqlDatabase)
                    rig.hardcover.profiles = mapOf("250716" to ray("250716", bio = "Bio.", photo = "https://broken.test/p.jpg"))
                    val before = rig.seedRay()
                    val review = rig.review()
                    val request =
                        rig.defaultRequest().copy(photo = ImageChoice.Candidate(review.photo.options.first { "broken" in it.url }.optionId))
                    rig.apply(request).error().shouldBeInstanceOf<MetadataError.CoverDownloadFailed>()
                    rig.person() shouldBe before
                    rig.receipts.pinnedPhotoPaths() shouldBe emptySet()
                }
            }
        }

        test("a fault at the last moment rolls back the person, the refs and the receipt") {
            withSqlDatabase {
                runTest {
                    val rig = PersonRig(this@withSqlDatabase)
                    val before = rig.seedRay()
                    rig.fault = { error("injected") }
                    runCatching { rig.apply(rig.mixedRequest()) }
                    rig.person() shouldBe before
                    rig.receipts.pinnedPhotoPaths() shouldBe emptySet()
                }
            }
        }

        test("the photo you already have is not a change") {
            withSqlDatabase {
                runTest {
                    val rig = PersonRig(this@withSqlDatabase)
                    rig.seedRay()
                    rig.apply(rig.mixedRequest().copy(biography = FieldChoice.KeepCurrent)).shouldSucceed()
                    val matched = rig.person().imagePath
                    val again = rig.apply(rig.mixedRequest().copy(biography = FieldChoice.KeepCurrent)).shouldSucceed()
                    again.changes shouldBe emptyList()
                    rig.person().imagePath shouldBe matched
                }
            }
        }

        test("a second match replaces the first receipt") {
            withSqlDatabase {
                runTest {
                    val rig = PersonRig(this@withSqlDatabase)
                    rig.seedRay()
                    val first = rig.apply(rig.mixedRequest()).shouldSucceed()
                    rig.apply(rig.defaultRequest()).shouldSucceed()
                    rig.receipts.find(first.receiptId) shouldBe null
                }
            }
        }
    })
