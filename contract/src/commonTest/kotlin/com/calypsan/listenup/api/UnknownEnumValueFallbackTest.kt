package com.calypsan.listenup.api

import com.calypsan.listenup.api.dto.ServerInfo
import com.calypsan.listenup.api.dto.SharePermission
import com.calypsan.listenup.api.dto.auth.RegistrationPolicy
import com.calypsan.listenup.api.dto.auth.User
import com.calypsan.listenup.api.dto.auth.UserId
import com.calypsan.listenup.api.dto.auth.UserRole
import com.calypsan.listenup.api.dto.auth.UserStatus
import com.calypsan.listenup.api.dto.invite.InviteDto
import com.calypsan.listenup.api.dto.invite.InviteId
import com.calypsan.listenup.api.dto.scan.ScanIssue
import com.calypsan.listenup.api.dto.scan.ScanIssueReason
import com.calypsan.listenup.api.dto.scanner.FileEntry
import com.calypsan.listenup.api.dto.scanner.FileType
import com.calypsan.listenup.api.metadata.BookField
import com.calypsan.listenup.api.metadata.FieldProvenance
import com.calypsan.listenup.api.metadata.FieldProvenanceMapSerializer
import com.calypsan.listenup.api.metadata.FieldSourceKind
import com.calypsan.listenup.api.sync.CollectionShareSyncPayload
import com.calypsan.listenup.api.sync.CoverPayload
import com.calypsan.listenup.api.sync.CoverSource
import com.calypsan.listenup.core.appJson
import io.kotest.assertions.throwables.shouldNotThrowAny
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/**
 * The additive-wire guarantee on the **enum-value** axis.
 *
 * `ignoreUnknownKeys` makes an old build tolerate new *fields*, and the polymorphic default makes it
 * tolerate new *subtypes*. Neither covers a new enum *literal*: an unrecognised member name throws
 * `SerializationException`. That made every additive enum member wire-breaking — one new member on a
 * newer server froze the affected sync domain on every older client, because a catch-up page that
 * cannot decode never advances its cursor and the next pass re-fetches the identical page.
 *
 * `coerceInputValues` closes it, but only where the author wrote down a fallback: coercion can
 * substitute a property's declared default and nothing else. These tests pin both halves — the
 * fallback fires for an unknown literal, and a known literal is still decoded as itself.
 */
class UnknownEnumValueFallbackTest :
    FunSpec({
        // Payloads from a hypothetical newer server: the shape is right, one enum literal names a
        // member this build has never heard of.
        val futureScanIssue =
            """{"id":"i1","rootRelPath":"Author/Book","reason":"QUANTUM_TAG_COLLAPSE","firstSeenAt":1,"lastSeenAt":2}"""
        val futureFileEntry =
            """{"relPath":"a/b.holo","name":"b.holo","ext":"holo","size":10,"mtimeMs":20,"fileType":"HOLOGRAM"}"""
        val futureShare =
            """
            {"id":"s1","collectionId":"c1","sharedWithUserId":"u1","sharedByUserId":"u2",
             "permission":"curate","revision":1,"updatedAt":2}
            """.trimIndent()
        val futureCover = """{"source":"HOLOGRAPHIC","hash":"abc123"}"""

        test("an unknown ScanIssue reason lands on the declared UNKNOWN fallback instead of throwing") {
            shouldNotThrowAny {
                contractJson.decodeFromString<ScanIssue>(futureScanIssue)
            }

            contractJson.decodeFromString<ScanIssue>(futureScanIssue).reason shouldBe ScanIssueReason.UNKNOWN
        }

        test("an unknown FileEntry fileType lands on the declared UNKNOWN fallback") {
            contractJson.decodeFromString<FileEntry>(futureFileEntry).fileType shouldBe FileType.UNKNOWN
        }

        test("an unknown share permission lands on Read — least privilege, never an accidental grant") {
            contractJson.decodeFromString<CollectionShareSyncPayload>(futureShare).permission shouldBe SharePermission.Read
        }

        test("an unknown cover source lands on null — CoverSource has no honest fallback member") {
            contractJson.decodeFromString<CoverPayload>(futureCover).source shouldBe null
        }

        test("a KNOWN literal still decodes to its own member — the fallback is not swallowing valid input") {
            val knownScanIssue = futureScanIssue.replace("QUANTUM_TAG_COLLAPSE", "FILE_UNREADABLE")
            val knownFileEntry = futureFileEntry.replace("HOLOGRAM", "AUDIO")
            val knownShare = futureShare.replace("curate", "write")
            val knownCover = futureCover.replace("HOLOGRAPHIC", "UPLOADED")

            contractJson.decodeFromString<ScanIssue>(knownScanIssue).reason shouldBe ScanIssueReason.FILE_UNREADABLE
            contractJson.decodeFromString<FileEntry>(knownFileEntry).fileType shouldBe FileType.AUDIO
            contractJson.decodeFromString<CollectionShareSyncPayload>(knownShare).permission shouldBe SharePermission.Write
            contractJson.decodeFromString<CoverPayload>(knownCover).source shouldBe CoverSource.UPLOADED
        }

        test("a value equal to its new default round-trips even though encodeDefaults omits it from the wire") {
            // `contractJson` does not set `encodeDefaults`, so giving these properties a default makes
            // a default-valued one VANISH from the encoded bytes. That is a real wire change, and it is
            // safe in both directions precisely because the decoder now supplies the same default.
            val issue = ScanIssue("i1", "Author/Book", ScanIssueReason.UNKNOWN, firstSeenAt = 1, lastSeenAt = 2)
            val encoded = contractJson.encodeToString(ScanIssue.serializer(), issue)

            encoded shouldNotContain "reason"
            contractJson.decodeFromString(ScanIssue.serializer(), encoded) shouldBe issue

            val entry = FileEntry("a/b", "b", "", size = 1, mtimeMs = 2, fileType = FileType.UNKNOWN)
            contractJson.decodeFromString(
                FileEntry.serializer(),
                contractJson.encodeToString(FileEntry.serializer(), entry),
            ) shouldBe entry

            val cover = CoverPayload(source = CoverSource.EMBEDDED, hash = "abc123")
            contractJson.decodeFromString(
                CoverPayload.serializer(),
                contractJson.encodeToString(CoverPayload.serializer(), cover),
            ) shouldBe cover
        }

        // Control probe: a tolerance setting must be shown to fail when unset, or the tests above
        // would pass with or without it and prove nothing.
        test("WITHOUT coerceInputValues the same payloads throw — the setting is load-bearing") {
            val strictJson =
                Json {
                    ignoreUnknownKeys = true
                    isLenient = true
                }

            shouldThrow<SerializationException> { strictJson.decodeFromString<ScanIssue>(futureScanIssue) }
            shouldThrow<SerializationException> { strictJson.decodeFromString<FileEntry>(futureFileEntry) }
            shouldThrow<SerializationException> { strictJson.decodeFromString<CollectionShareSyncPayload>(futureShare) }
            shouldThrow<SerializationException> { strictJson.decodeFromString<CoverPayload>(futureCover) }
        }

        // The connect, sign-in and book-sync paths: enum-typed properties an app must survive a newer
        // server extending. `contractJson` and `appJson` are configured separately, so each is exercised.
        listOf("contractJson" to contractJson, "appJson" to appJson).forEach { (jsonName, json) ->
            val serverInfo =
                ServerInfo("ListenUp", "1.0", "v1", setupRequired = false, registrationPolicy = RegistrationPolicy.OPEN, instanceId = "i1")
            val user = User(UserId("u1"), "a@b.c", "A", UserRole.ADMIN, UserStatus.PENDING_APPROVAL, createdAt = 1)
            val invite = InviteDto(InviteId("i1"), "code", "a@b.c", "A", UserRole.ADMIN, "admin1", expiresAt = 2, createdAt = 1)
            val provenance = mapOf(BookField.entries.first() to FieldProvenance(FieldSourceKind.ENRICHMENT))

            fun encodeProvenance(map: Map<BookField, FieldProvenance>) = json.encodeToString(FieldProvenanceMapSerializer, map)

            fun decodeProvenance(text: String) = json.decodeFromString(FieldProvenanceMapSerializer, text)

            test("$jsonName: an unknown registration policy lands on CLOSED") {
                val future = json.encodeToString(ServerInfo.serializer(), serverInfo).replace("\"OPEN\"", "\"INVITE_ONLY\"")

                json.decodeFromString(ServerInfo.serializer(), future).registrationPolicy shouldBe RegistrationPolicy.CLOSED
            }

            test("$jsonName: an unknown user role lands on MEMBER") {
                val future = json.encodeToString(User.serializer(), user).replace("\"ADMIN\"", "\"SUPERUSER\"")

                json.decodeFromString(User.serializer(), future).role shouldBe UserRole.MEMBER
            }

            test("$jsonName: an unknown user status lands on ACTIVE") {
                val future = json.encodeToString(User.serializer(), user).replace("\"PENDING_APPROVAL\"", "\"SUSPENDED\"")

                json.decodeFromString(User.serializer(), future).status shouldBe UserStatus.ACTIVE
            }

            test("$jsonName: an unknown invite role lands on MEMBER") {
                val future = json.encodeToString(InviteDto.serializer(), invite).replace("\"ADMIN\"", "\"SUPERUSER\"")

                json.decodeFromString(InviteDto.serializer(), future).role shouldBe UserRole.MEMBER
            }

            test("$jsonName: an unknown provenance kind inside a provenance map lands on USER and the entry survives") {
                val future = encodeProvenance(provenance).replace("\"ENRICHMENT\"", "\"QUANTUM_ORACLE\"")

                decodeProvenance(future) shouldBe mapOf(BookField.entries.first() to FieldProvenance(FieldSourceKind.USER))
            }

            test("$jsonName: a known literal still decodes as itself") {
                json.decodeFromString(ServerInfo.serializer(), json.encodeToString(ServerInfo.serializer(), serverInfo)) shouldBe serverInfo
                json.decodeFromString(User.serializer(), json.encodeToString(User.serializer(), user)) shouldBe user
                json.decodeFromString(InviteDto.serializer(), json.encodeToString(InviteDto.serializer(), invite)) shouldBe invite
                decodeProvenance(encodeProvenance(provenance)) shouldBe provenance
            }

            // Without EncodeDefault a value equal to its default vanishes from the bytes, and an app
            // built before the default existed then fails on the missing key.
            test("$jsonName: a value equal to its default is still written to the wire") {
                val closed = serverInfo.copy(registrationPolicy = RegistrationPolicy.CLOSED)
                json.encodeToString(ServerInfo.serializer(), closed) shouldContain """"registrationPolicy":"CLOSED""""

                val member = user.copy(role = UserRole.MEMBER, status = UserStatus.ACTIVE)
                val encodedUser = json.encodeToString(User.serializer(), member)
                encodedUser shouldContain """"role":"MEMBER""""
                encodedUser shouldContain """"status":"ACTIVE""""

                json.encodeToString(InviteDto.serializer(), invite.copy(role = UserRole.MEMBER)) shouldContain """"role":"MEMBER""""

                encodeProvenance(mapOf(BookField.entries.first() to FieldProvenance(FieldSourceKind.USER))) shouldContain
                    """"kind":"USER""""

                val readShare =
                    CollectionShareSyncPayload(
                        id = "s1",
                        collectionId = "c1",
                        sharedWithUserId = "u1",
                        sharedByUserId = "u2",
                        permission = SharePermission.Read,
                        revision = 1,
                        updatedAt = 2,
                    )
                json.encodeToString(CollectionShareSyncPayload.serializer(), readShare) shouldContain """"permission":"read""""
            }
        }
    })
