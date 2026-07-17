package com.calypsan.listenup.gradle

import java.io.File

/** Result of one patch transform: the rewritten source and how many declarations it touched. */
data class PatchOutcome(
    val content: String,
    val count: Int,
)

/**
 * Pure, Gradle-free post-processor for Swift Export 2.4.0 generated output. Mirrors
 * [LocalizationGenerator]: the transforms are deterministic `String -> String` functions so they
 * are unit-testable; a thin file-walking wrapper ([patchPackage]) does the I/O.
 *
 * The native iOS app consumes the shared Kotlin core through Swift Export, whose Alpha codegen
 * emits Swift that doesn't compile and isn't idiomatic. Each transform here is keyed to Swift
 * Export 2.4.0's exact emitted shape and is removable when the corresponding upstream bug is fixed
 * (each `fun`'s doc explains which bug it patches). The transforms are behavior-preserving rewrites
 * — golden-fixture tests pin the output byte-for-byte — except that they return a match count so
 * the build can assert loudly when a tool bump shifts the generated shape and a regex matches zero.
 */
object SwiftExportSourcePatcher {
    /**
     * Swift reserved words. A generated case identifier (or sealed-enum case) that collides with
     * one must be back-tick-escaped. Shared by the sealed-enum and camelCase passes (it was
     * copy-pasted into both before the extraction).
     */
    internal val swiftKeywords: Set<String> =
        setOf(
            "default",
            "case",
            "where",
            "class",
            "enum",
            "protocol",
            "struct",
            "init",
            "deinit",
            "self",
            "super",
            "import",
            "return",
            "func",
            "var",
            "let",
            "if",
            "else",
            "for",
            "in",
            "do",
            "try",
            "catch",
            "throw",
            "true",
            "false",
            "nil",
            "is",
            "as",
            "guard",
            "switch",
            "public",
            "private",
            "internal",
            "static",
            "extension",
            "associatedtype",
            "operator",
            "repeat",
            "while",
            "break",
            "continue",
            "fallthrough",
            "defer",
            "subscript",
            "typealias",
        )

    private const val FLAT_TYPEALIAS_MARKER = "// --- swift-export flat typealias layer (generated) ---"
    private const val SEALED_ENUM_MARKER = "// --- swift-export sealed-enum support (generated) ---"
    private const val APP_RESULT_MARKER = "// --- swift-export AppResult accessor (generated) ---"

    /** The generated subtype-class names + erased base type for `AppResult`. See [appendAppResultAccessor]. */
    private const val APP_RESULT_SUCCESS_CLASS = "_ExportedKotlinPackages_com_calypsan_listenup_api_result_AppResult_Success"
    private const val APP_RESULT_FAILURE_CLASS = "_ExportedKotlinPackages_com_calypsan_listenup_api_result_AppResult_Failure"
    private const val APP_RESULT_BASE_TYPE = "ExportedKotlinPackages.com.calypsan.listenup.api.result.AppResult"

    /**
     * Operator-unavailability + undefined-type + `description`/`release()` collision fixes for one
     * generated `.swift` file. `count` is 1 if the file changed, else 0 (matching the per-file
     * "patched files" tally of the original transform).
     *
     * Two bug classes are patched, both behavior-preserving and removable upstream:
     *
     *   Class 1 — unavailable operator calling an unavailable helper. An operator that maps to a
     *   Swift `@available(*, unavailable)` declaration (e.g. `CoroutineDispatcher.+`, whose Kotlin
     *   `@Deprecated(level = ERROR)` becomes `unavailable`) has its body call an equally-unavailable
     *   `_helper`; Swift rejects calling an unavailable symbol even from inside another. These decls
     *   are uncallable from Swift, so rewriting only those bodies to `fatalError()` is safe. The
     *   transform is annotation-scoped + brace-aware — available operators (Timestamp, Duration,
     *   Comparable, …) that legitimately delegate to `this._helper` are left intact.
     *
     *   Class 2 — function referencing an undefined same-module type. Swift export references a
     *   nested sealed-interface type it never emits (e.g. `LocalDate.Companion.Format(block:)`'s
     *   closure parameter `DateTimeFormatBuilder.WithDate`). Such a function can't compile and isn't
     *   part of any API we call from Swift, so the whole declaration is deleted (brace-aware).
     *
     *   Class 3 — rename a generated `description` Kotlin property to `description_`. Swift export
     *   emits `public var description: <T>` for any Kotlin `description` field, which collides with
     *   the inherited `KotlinRuntime.KotlinBase.description: String` (different type -> an illegal
     *   override). SKIE renamed the same field to `description_`; matching that keeps existing Swift
     *   call sites stable.
     *
     * @param module the file's parent directory name (the Swift module), used to scope the
     *   undefined-type reference regex exactly as the original walk did.
     */
    fun patchSource(
        content: String,
        module: String,
    ): PatchOutcome {
        val unavailable = Regex("""@available\(\*, *unavailable""")
        val signatureClose = Regex("""\)\s*(->[^{]*)?\{\s*$""")
        val helperCall = Regex("""^(\s*)this\._[A-Za-z]+\(.*""")
        val typeDef = Regex("""\b(?:class|protocol|struct|enum|typealias|extension)\s+([A-Za-z0-9_]+)""")
        val funcStart = Regex("""^\s*(?:public|package|open|final|static|\s)*func\s""")

        val lines = content.lines().let { if (it.isNotEmpty() && it.last().isEmpty()) it.dropLast(1) else it }
        val defined = lines.flatMap { line -> typeDef.findAll(line).map { it.groupValues[1] }.toList() }.toHashSet()
        val undefinedRef = Regex("""\Q$module\E\._ExportedKotlinPackages_([A-Za-z0-9_]+)""")
        val out = ArrayList<String>(lines.size)
        var changed = false
        var i = 0
        var armed = false
        var inBody = false
        var depth = 0
        while (i < lines.size) {
            val line = lines[i]

            // Class 2 — delete a whole function whose signature names an undefined same-module type.
            if (!inBody && funcStart.containsMatchIn(line)) {
                var j = i
                while (j < lines.size && !signatureClose.containsMatchIn(lines[j])) j++
                if (j < lines.size) {
                    val signature = lines.subList(i, j + 1).joinToString("\n")
                    val refsUndefined =
                        undefinedRef.findAll(signature).any { m ->
                            val name = m.groupValues[1]
                            "_ExportedKotlinPackages_$name" !in defined && name !in defined
                        }
                    if (refsUndefined) {
                        var d = lines[j].count { it == '{' } - lines[j].count { it == '}' }
                        var k = j + 1
                        while (k < lines.size && d > 0) {
                            d += lines[k].count { it == '{' } - lines[k].count { it == '}' }
                            k++
                        }
                        changed = true
                        i = k
                        continue
                    }
                }
            }

            // Class 1 — neutralize unavailable-operator bodies.
            when {
                !inBody && unavailable.containsMatchIn(line) -> {
                    armed = true
                    out.add(line)
                }

                armed && signatureClose.containsMatchIn(line) -> {
                    inBody = true
                    armed = false
                    depth = 1
                    out.add(line)
                }

                inBody -> {
                    val match = helperCall.matchEntire(line)
                    if (match != null) {
                        out.add("${match.groupValues[1]}fatalError(\"swift-export: unavailable operator\")")
                        changed = true
                    } else {
                        out.add(line)
                    }
                    depth += line.count { it == '{' } - line.count { it == '}' }
                    if (depth <= 0) inBody = false
                }

                else -> {
                    out.add(line)
                }
            }
            i++
        }

        // Class 3 — rename a generated `description` Kotlin property to `description_`. Guarded by
        // the property's own `_description_get` getter so framework `description`s are never touched.
        val descriptionProperty = Regex("""^(\s*)public var description: (.+)$""")
        for (idx in out.indices) {
            val match = descriptionProperty.matchEntire(out[idx]) ?: continue
            val getterNearby = (idx + 1..minOf(idx + 3, out.lastIndex)).any { out[it].contains("_description_get(") }
            if (getterNearby) {
                out[idx] = "${match.groupValues[1]}public var description_: ${match.groupValues[2]}"
                changed = true
            }
        }

        return if (changed) {
            PatchOutcome(out.joinToString("\n") + "\n", 1)
        } else {
            PatchOutcome(content, 0)
        }
    }

    /**
     * Flat top-level typealiases for nested `ExportedKotlinPackages` types, appended onto the
     * generated `Shared.swift`. `flattenPackage` does NOT actually flatten in Kotlin 2.4.0 — every
     * exported type is nested as `ExportedKotlinPackages.com.calypsan.listenup.<pkg>.<Type>`, so
     * `import Shared; Book` can't resolve. SKIE gave callers flat names; to match that, append a
     * top-level `public typealias` for every exported type. Idempotent via a marker. Name
     * collisions across packages resolve to the `client.domain.model` (then any `client.domain`)
     * variant; remaining ambiguous names are skipped and stay qualified.
     *
     * @param sharedContent the `Shared.swift` contents the aliases are appended to.
     * @param sourceContents the `Shared.swift` + `ListenupContract.swift` contents to harvest types
     *   from (both modules' types are in scope on `Shared.swift`).
     * @return the rewritten `Shared.swift` and the number of aliases emitted.
     */
    fun appendFlatTypealiases(
        sharedContent: String,
        sourceContents: List<String>,
    ): PatchOutcome {
        if (sharedContent.contains(FLAT_TYPEALIAS_MARKER)) return PatchOutcome(sharedContent, 0)
        val extensionRe = Regex("""^extension ExportedKotlinPackages\.([A-Za-z0-9_.]+?)(?:\s+where\b.*)?\s*\{""")
        val typeRe = Regex("""^\s+public (?:final class|class|enum|protocol|struct|actor) ([A-Za-z_][A-Za-z0-9_]*)""")
        val packagesByName = HashMap<String, MutableSet<String>>()
        for (fileContent in sourceContents) {
            var packageNamespace: String? = null
            var depth = 0
            for (line in fileContent.lineSequence()) {
                if (packageNamespace == null) {
                    val ext = extensionRe.find(line)
                    if (ext != null) {
                        val path = ext.groupValues[1]
                        // A package extension's last segment is lowercase; a type/conformance extension's is Capitalized.
                        if (path.substringAfterLast('.').first().isLowerCase()) {
                            packageNamespace = path
                            depth = line.count { it == '{' } - line.count { it == '}' }
                        }
                    }
                    continue
                }
                // Only alias types declared DIRECTLY in the package (depth 1) — not sealed subtypes or
                // other types nested inside a parent type (depth > 1), whose flat path would be wrong.
                if (depth == 1) {
                    typeRe
                        .find(
                            line,
                        )?.let { packagesByName.getOrPut(it.groupValues[1]) { HashSet() }.add(packageNamespace!!) }
                }
                depth += line.count { it == '{' } - line.count { it == '}' }
                if (depth <= 0) packageNamespace = null
            }
        }
        val builder = StringBuilder("\n$FLAT_TYPEALIAS_MARKER\n")
        var count = 0
        for ((name, namespaces) in packagesByName.toSortedMap()) {
            if (name == "Companion") continue
            val namespace =
                when {
                    namespaces.size == 1 -> {
                        namespaces.first()
                    }

                    else -> {
                        namespaces.firstOrNull { it.contains(".client.domain.model") }
                            ?: namespaces.firstOrNull { it.contains(".client.domain") }
                            ?: continue
                    }
                }
            builder.append("public typealias $name = ExportedKotlinPackages.$namespace.$name\n")
            count++
        }
        return PatchOutcome(sharedContent + builder.toString(), count)
    }

    /** A sealed parent type harvested from the generated Swift: its package [path] and [name]. */
    internal data class SealedParent(
        val path: String,
        val name: String,
    )

    // A sealed subtype: `public final class <Class>: KotlinRuntime.KotlinBase,
    // ExportedKotlinPackages.<path>.<Parent>, ExportedKotlinPackages.<path>._<Parent> {`
    private val subtypeRe =
        Regex(
            """^public final class (_ExportedKotlinPackages_\w+): KotlinRuntime\.KotlinBase, ExportedKotlinPackages\.([\w.]+)\.(\w+), ExportedKotlinPackages\.[\w.]+\._\3\b""",
        )

    /**
     * Harvest every sealed parent and its subtypes from the generated Swift, keyed by the same
     * [SealedParent] used to emit the `onEnum` support. Single source of truth shared by
     * [appendSealedEnumSupport] (the emitter) and [sealedSubtypeDrift] (the exact-count guard), so
     * the count the build asserts against is exactly the count the generator would emit.
     */
    internal fun harvestSealedSubtypes(sourceContents: List<String>): LinkedHashMap<SealedParent, MutableList<Pair<String, String>>> {
        val sealedTypes = LinkedHashMap<SealedParent, MutableList<Pair<String, String>>>()
        for (fileContent in sourceContents) {
            for (line in fileContent.lineSequence()) {
                val m = subtypeRe.find(line) ?: continue
                val (className, path, parent) = m.destructured
                val subtype = className.substringAfterLast("_${parent}_")
                if (subtype.isBlank() || subtype == className) continue
                sealedTypes.getOrPut(SealedParent(path, parent)) { mutableListOf() }.add(subtype to className)
            }
        }
        return sealedTypes
    }

    /**
     * Expected subtype count per sealed parent (`<package path>.<Name>` -> count). The exact-count
     * floor that [sealedSubtypeDrift] enforces. Plan 001's aggregate `count > 0` floor catches a
     * *total* harvest failure but not a *partial* drop — if a Kotlin/Swift-Export bump shifts one
     * subtype's emitted shape out of, say, `AppError`'s eight, the regex still matches the other
     * seven, the build stays green, and any value of the dropped subtype slips to the generated
     * `unknown` case (a silent degradation that used to be a `fatalError` crash). Pinning the exact
     * count makes that partial drop a red build naming the parent.
     *
     * **Maintenance:** when a Kotlin sealed type legitimately gains or loses a subtype, update its
     * entry here — that edit is the intended signal. A brand-new sealed type added to `:contract`
     * must also be declared here (with its subtype count): [sealedSubtypeDrift] fails the build on any
     * harvested parent absent from this map, so a new sealed type can't ship without its `onEnum(of:)`
     * support being considered. The guard fires both when a *known* parent shrinks and when an
     * *undeclared* parent is harvested.
     */
    internal val expectedSealedSubtypeCounts: Map<String, Int> =
        mapOf(
            "com.calypsan.listenup.client.data.repository.ShortcutAction" to 6,
            "com.calypsan.listenup.client.domain.chapter.DriftResult" to 1,
            "com.calypsan.listenup.client.domain.imagepicker.ImagePickerResult" to 3,
            "com.calypsan.listenup.client.domain.leaderboard.LeaderboardPeriod" to 4,
            "com.calypsan.listenup.client.domain.model.AdminEvent" to 4,
            "com.calypsan.listenup.client.domain.model.AuthState" to 8,
            "com.calypsan.listenup.client.domain.model.BookDownloadStatus" to 5,
            "com.calypsan.listenup.client.domain.model.BookEvent" to 1,
            "com.calypsan.listenup.client.domain.model.ContinueListeningItem" to 2,
            "com.calypsan.listenup.client.domain.model.DownloadOutcome" to 3,
            "com.calypsan.listenup.client.domain.model.SyncState" to 7,
            "com.calypsan.listenup.client.domain.readers.ReaderLineKind" to 2,
            "com.calypsan.listenup.client.domain.repository.PlaybackUpdate" to 11,
            "com.calypsan.listenup.client.domain.repository.PreferenceChangeEvent" to 1,
            "com.calypsan.listenup.client.domain.repository.Reachability" to 3,
            "com.calypsan.listenup.client.domain.repository.StreamedRegistrationStatus" to 3,
            "com.calypsan.listenup.client.playback.NowPlayingOverlay" to 6,
            "com.calypsan.listenup.client.playback.NowPlayingState" to 3,
            "com.calypsan.listenup.client.playback.PlaybackState" to 6,
            "com.calypsan.listenup.client.playback.SessionState" to 3,
            "com.calypsan.listenup.client.playback.SleepTimerMode" to 2,
            "com.calypsan.listenup.client.playback.SleepTimerState" to 3,
            "com.calypsan.listenup.client.presentation.nowplaying.PlaybackGuardEvent" to 2,
            "com.calypsan.listenup.client.presentation.admin.ABSImportListUiState" to 3,
            "com.calypsan.listenup.client.presentation.admin.AdminBackupUiState" to 3,
            "com.calypsan.listenup.client.presentation.admin.AdminCategoriesUiState" to 3,
            "com.calypsan.listenup.client.presentation.admin.AdminCollectionDetailUiState" to 3,
            "com.calypsan.listenup.client.presentation.admin.AdminCollectionsUiState" to 3,
            "com.calypsan.listenup.client.presentation.admin.AdminInboxUiState" to 3,
            "com.calypsan.listenup.client.presentation.admin.AdminSettingsUiState" to 3,
            "com.calypsan.listenup.client.presentation.admin.AdminUiState" to 2,
            "com.calypsan.listenup.client.presentation.admin.CreateInviteErrorType" to 4,
            "com.calypsan.listenup.client.presentation.admin.CreateInviteStatus" to 4,
            "com.calypsan.listenup.client.presentation.admin.CreateInviteUiState" to 3,
            "com.calypsan.listenup.client.presentation.admin.LibrarySettingsEvent" to 1,
            "com.calypsan.listenup.client.presentation.admin.LibrarySettingsUiState" to 3,
            "com.calypsan.listenup.client.presentation.admin.OrganizeSettingsUiState" to 3,
            "com.calypsan.listenup.client.presentation.admin.RestoreBackupUiState" to 4,
            "com.calypsan.listenup.client.presentation.admin.RestoreFromFileUiState" to 3,
            "com.calypsan.listenup.client.presentation.admin.UserDetailUiState" to 3,
            "com.calypsan.listenup.client.presentation.admin.imports.ImportFlowUiState" to 7,
            "com.calypsan.listenup.client.presentation.auth.LoginErrorType" to 4,
            "com.calypsan.listenup.client.presentation.auth.LoginUiState" to 4,
            "com.calypsan.listenup.client.presentation.auth.PendingApprovalUiState" to 3,
            "com.calypsan.listenup.client.presentation.auth.RegisterUiState" to 4,
            "com.calypsan.listenup.client.presentation.auth.SetupErrorType" to 4,
            "com.calypsan.listenup.client.presentation.auth.SetupUiState" to 4,
            "com.calypsan.listenup.client.presentation.bookdetail.BookDetailNavAction" to 2,
            "com.calypsan.listenup.client.presentation.bookdetail.BookDetailUiState" to 3,
            "com.calypsan.listenup.client.presentation.bookdetail.BookReadersUiState" to 4,
            "com.calypsan.listenup.client.presentation.bookedit.BookEditNavAction" to 2,
            "com.calypsan.listenup.client.presentation.bookedit.BookEditUiEvent" to 42,
            "com.calypsan.listenup.client.presentation.books.BookMultiSelectEvent" to 4,
            "com.calypsan.listenup.client.presentation.books.SelectionMode" to 2,
            "com.calypsan.listenup.client.presentation.browsefacet.BrowseFacetUiState" to 3,
            "com.calypsan.listenup.client.presentation.browsegenre.BrowseGenreUiState" to 3,
            "com.calypsan.listenup.client.presentation.campfire.CampfireCreateUiState" to 3,
            "com.calypsan.listenup.client.presentation.campfire.CampfireInviteUiState" to 4,
            "com.calypsan.listenup.client.presentation.campfire.CampfireScreenEvent" to 2,
            "com.calypsan.listenup.client.presentation.campfire.CampfireScreenUiState" to 6,
            "com.calypsan.listenup.client.presentation.chaptereditor.ChapterEditorEvent" to 3,
            "com.calypsan.listenup.client.presentation.chaptereditor.ChapterEditorUiState" to 3,
            "com.calypsan.listenup.client.presentation.chaptereditor.DriftPreview" to 2,
            "com.calypsan.listenup.client.presentation.connect.ServerConnectUiState" to 4,
            "com.calypsan.listenup.client.presentation.connect.ServerSelectUiEvent" to 6,
            "com.calypsan.listenup.client.presentation.connect.ServerSelectUiState" to 4,
            "com.calypsan.listenup.client.presentation.connect.ServerSelectViewModel.NavigationEvent" to 2,
            "com.calypsan.listenup.client.presentation.connection.ConnectionHealthUi" to 4,
            "com.calypsan.listenup.client.presentation.connection.ConnectionHealthViewModel.Event" to 1,
            "com.calypsan.listenup.client.presentation.contributordetail.ContributorBooksUiState" to 4,
            "com.calypsan.listenup.client.presentation.contributordetail.ContributorDetailNavAction" to 1,
            "com.calypsan.listenup.client.presentation.contributordetail.ContributorDetailUiState" to 4,
            "com.calypsan.listenup.client.presentation.contributoredit.ContributorEditNavAction" to 2,
            "com.calypsan.listenup.client.presentation.contributoredit.ContributorEditUiEvent" to 11,
            "com.calypsan.listenup.client.presentation.discover.ActivityFeedUiState" to 3,
            "com.calypsan.listenup.client.presentation.discover.CurrentlyListeningUiState" to 3,
            "com.calypsan.listenup.client.presentation.discover.DiscoverBooksUiState" to 3,
            "com.calypsan.listenup.client.presentation.discover.DiscoverShelvesUiState" to 3,
            "com.calypsan.listenup.client.presentation.discover.LeaderboardUiState" to 4,
            "com.calypsan.listenup.client.presentation.discover.RecentlyAddedUiState" to 3,
            "com.calypsan.listenup.client.presentation.home.HomeStatsUiState" to 4,
            "com.calypsan.listenup.client.presentation.home.HomeUiState" to 3,
            "com.calypsan.listenup.client.presentation.invite.ClaimInviteUiState" to 6,
            "com.calypsan.listenup.client.presentation.library.LibraryUiEvent" to 11,
            "com.calypsan.listenup.client.presentation.library.LibraryUiState" to 3,
            "com.calypsan.listenup.client.presentation.metadata.ChapterSuggestion" to 3,
            "com.calypsan.listenup.client.presentation.metadata.MetadataEvent" to 2,
            "com.calypsan.listenup.client.presentation.metadata.MetadataUiState" to 3,
            "com.calypsan.listenup.client.presentation.metadata.PreviewLoadState" to 3,
            "com.calypsan.listenup.client.presentation.metadata.SearchLoadState" to 4,
            "com.calypsan.listenup.client.presentation.nowplaying.NowPlayingNavAction" to 1,
            "com.calypsan.listenup.client.presentation.profile.AvatarChange" to 3,
            "com.calypsan.listenup.client.presentation.profile.EditProfileEvent" to 2,
            "com.calypsan.listenup.client.presentation.profile.EditProfileUiState" to 3,
            "com.calypsan.listenup.client.presentation.profile.UserProfileUiState" to 4,
            "com.calypsan.listenup.client.presentation.readingorder.ReadingOrderListUiState" to 3,
            "com.calypsan.listenup.client.presentation.search.SearchNavAction" to 4,
            "com.calypsan.listenup.client.presentation.search.SearchUiState" to 4,
            "com.calypsan.listenup.client.presentation.search.SeeAllSearchUiState" to 4,
            "com.calypsan.listenup.client.presentation.seriesdetail.SeriesDetailUiState" to 4,
            "com.calypsan.listenup.client.presentation.seriesedit.SeriesEditNavAction" to 1,
            "com.calypsan.listenup.client.presentation.seriesedit.SeriesEditUiEvent" to 8,
            "com.calypsan.listenup.client.presentation.settings.DevicesUiState" to 3,
            "com.calypsan.listenup.client.presentation.setup.LibrarySetupNavAction" to 1,
            "com.calypsan.listenup.client.presentation.shelf.CreateEditShelfNavAction" to 1,
            "com.calypsan.listenup.client.presentation.shelf.CreateEditShelfUiState" to 5,
            "com.calypsan.listenup.client.presentation.shelf.ShelfDetailUiState" to 4,
            "com.calypsan.listenup.client.presentation.readingorder.ReadingOrderDetailEvent" to 1,
            "com.calypsan.listenup.client.presentation.readingorder.ReadingOrderDetailUiState" to 4,
            "com.calypsan.listenup.client.presentation.readingorder.SeriesReadingOrdersEvent" to 1,
            "com.calypsan.listenup.client.presentation.readingorder.SeriesReadingOrdersUiState" to 3,
            "com.calypsan.listenup.client.presentation.startup.LibraryReadiness" to 5,
            "com.calypsan.listenup.client.presentation.storage.DeleteConfirmation" to 2,
            "com.calypsan.listenup.client.presentation.storyworld.AnchorLabel" to 5,
            "com.calypsan.listenup.client.presentation.storyworld.EntityDetailEvent" to 1,
            "com.calypsan.listenup.client.presentation.storyworld.EntityDetailUiState" to 4,
            "com.calypsan.listenup.client.presentation.storyworld.StorySoFarUiState" to 4,
            "com.calypsan.listenup.client.presentation.storyworld.StoryWorldEntityListUiState" to 3,
            "com.calypsan.listenup.client.presentation.storyworld.StoryWorldHubUiState" to 3,
            "com.calypsan.listenup.client.presentation.storyworld.composer.AnchorSelection" to 5,
            "com.calypsan.listenup.client.presentation.storyworld.composer.WorldComposerEvent" to 1,
            "com.calypsan.listenup.client.presentation.sync.SyncIndicatorUiEvent" to 4,
            "com.calypsan.listenup.client.presentation.tagdetail.TagDetailUiState" to 4,
            "com.calypsan.listenup.client.share.ShareResolution" to 5,
            "com.calypsan.listenup.client.share.ShareTarget" to 2,
            "com.calypsan.listenup.api.dto.backup.BackupEvent" to 10,
            "com.calypsan.listenup.api.dto.imports.ImportEvent" to 6,
            "com.calypsan.listenup.api.dto.organize.OrganizeRunEvent" to 4,
            "com.calypsan.listenup.api.error.DownloadError" to 2,
            "com.calypsan.listenup.api.error.ServerConnectError" to 5,
        )

    /**
     * Compares harvested per-parent subtype counts against [expectedSealedSubtypeCounts] and returns
     * one human-readable drift line per parent that drifts. Two ways a parent drifts:
     *  1. A *known* parent harvested fewer subtypes than recorded (a partial drop) — the exact-count
     *     guard; the dropped subtype would silently fall to the generated `unknown` case.
     *  2. A harvested parent that is *not declared* in [expectedSealedSubtypeCounts] — a brand-new
     *     sealed type that would ship with no recorded baseline, so its `onEnum(of:)` support is never
     *     considered. Declaring it (with its subtype count) is the forcing function that guarantees a
     *     reviewer signs off on its exhaustive Swift switch.
     * A *known* parent that grew (legitimately gained a subtype, with its map entry not yet bumped)
     * still fails via case 1's shrink check only when it shrank; a grow surfaces at the consumer
     * compile. The returned list is empty when there's no drift.
     */
    internal fun sealedSubtypeDrift(sourceContents: List<String>): List<String> {
        val harvested = harvestSealedSubtypes(sourceContents).mapKeys { (parent, _) -> "${parent.path}.${parent.name}" }
        val shrinkOrMissing =
            expectedSealedSubtypeCounts.mapNotNull { (parent, expected) ->
                val actual = harvested[parent]?.size ?: 0
                if (actual < expected) {
                    "$parent: expected $expected subtype(s), harvested $actual " +
                        "(a Swift-Export/Kotlin bump likely shifted a subtype's emitted shape; the dropped " +
                        "subtype would silently fall to the generated `unknown` case)"
                } else {
                    null
                }
            }
        val undeclared =
            harvested.keys.filter { it !in expectedSealedSubtypeCounts }.map { parent ->
                "$parent: harvested sealed type is not declared in expectedSealedSubtypeCounts — add it " +
                    "(with its subtype count) so its onEnum(of:) support is guaranteed; a new sealed type " +
                    "without an entry would ship with no exhaustive Swift switch."
            }
        return shrinkOrMissing + undeclared
    }

    /**
     * Sealed-class enum support (the `onEnum(of:)` exhaustive-switch helper), appended onto the
     * generated `Shared.swift`. Swift export maps a Kotlin sealed class to `protocol <Name>` +
     * marker `package protocol _<Name>` + one
     * `public final class _ExportedKotlinPackages_<path>_<Name>_<Subtype>` per subtype (each
     * conforming to both). That gives no exhaustive Swift `switch`. SKIE gave callers `onEnum(of:)`
     * returning a Swift enum; this regenerates that. Per sealed type, appended onto Shared.swift:
     *   • a flat alias `<Name><Subtype>` for each subtype (SKIE's nested-subtype name), and
     *   • `enum` (case = lowercased subtype, associated value = the subtype) + a generated `unknown`
     *     case carrying the base type + an `onEnum(of: <Name>)` overload that `as?`-casts to each
     *     subtype and returns `.unknown(value)` for anything that matches none. Idempotent via a
     *     marker.
     *
     * @param sharedContent the `Shared.swift` contents the support is appended to.
     * @param sourceContents the `Shared.swift` + `ListenupContract.swift` contents to harvest
     *   subtypes from.
     * @return the rewritten `Shared.swift` and `count` = number of sealed parents emitted.
     */
    fun appendSealedEnumSupport(
        sharedContent: String,
        sourceContents: List<String>,
    ): PatchOutcome {
        if (sharedContent.contains(SEALED_ENUM_MARKER)) return PatchOutcome(sharedContent, 0)
        val sealedTypes = harvestSealedSubtypes(sourceContents)
        if (sealedTypes.isEmpty()) return PatchOutcome(sharedContent, 0)
        val builder = StringBuilder("\n$SEALED_ENUM_MARKER\n")
        val emittedAlias = HashSet<String>()
        var count = 0
        for ((parent, subtypes) in sealedTypes) {
            val enumName = "OnEnum_${parent.path.replace('.', '_')}_${parent.name}"
            // The synthetic catch-all case name. Normally `unknown`, but a real Kotlin subtype named
            // `Unknown` already maps to `case unknown` (e.g. `Reachability.Unknown`); emitting another
            // `unknown` is an invalid Swift redeclaration. So pick the first name that doesn't collide
            // with any real subtype's lowercased-first-char case — keeps `.unknown` for the 99% case,
            // sidesteps the collision for the rare real-`Unknown` enum without consumer churn.
            val existingCases = subtypes.mapTo(HashSet()) { (subtype, _) -> subtype.replaceFirstChar { it.lowercase() } }
            val catchAll = generateSequence(0) { it + 1 }
                .map { if (it == 0) "unknown" else "unknownCatchAll$it" }
                .first { it !in existingCases }
            // subtype flat aliases (SKIE's `<Parent><Subtype>` name)
            for ((subtype, className) in subtypes) {
                val alias = "${parent.name}$subtype"
                if (emittedAlias.add(alias)) builder.append("public typealias $alias = $className\n")
            }
            // enum — one case per subtype, plus a synthetic catch-all carrying the base type so a value
            // matching no known subtype degrades gracefully (the consumer `switch` is forced to handle
            // it) instead of hitting a runtime `fatalError`. See Plan 004.
            builder.append("public enum $enumName {\n")
            for ((subtype, className) in subtypes) {
                val raw = subtype.replaceFirstChar { it.lowercase() }
                val case = if (raw in swiftKeywords) "`$raw`" else raw
                builder.append("    case $case($className)\n")
            }
            builder.append("    case $catchAll(ExportedKotlinPackages.${parent.path}.${parent.name})\n")
            builder.append("}\n")
            // onEnum overload
            builder.append(
                "public func onEnum(of value: ExportedKotlinPackages.${parent.path}.${parent.name}) -> $enumName {\n",
            )
            for ((subtype, className) in subtypes) {
                val raw = subtype.replaceFirstChar { it.lowercase() }
                val case = if (raw in swiftKeywords) "`$raw`" else raw
                builder.append("    if let value = value as? $className { return .$case(value) }\n")
            }
            builder.append("    return .$catchAll(value)\n}\n")
            count++
        }
        return PatchOutcome(sharedContent + builder.toString(), count)
    }

    /**
     * Typed `AppResult` accessor, appended onto the generated `Shared.swift`. `AppResult<T>` is the
     * success/failure envelope every fallible repository call returns — but because it's *generic*,
     * Swift Export erases it to `any AppResult` and its subtypes are emitted as plain
     * `_ExportedKotlinPackages_…_AppResult_Success` / `…_Failure` classes that do *not* conform to the
     * `AppResult` protocol, so [appendSealedEnumSupport]'s [subtypeRe] never matches them and no
     * `onEnum` is generated. Consumers were left hand-casting a 60-char mangled symbol — and the two
     * sites diverged (one folded the failure, one silently dropped it).
     *
     * This emits a clean, compiler-forced accessor (idempotent, behind a marker): flat typealiases for
     * the two subtype classes, an `AppResultCase` enum (`success` / `failure` / a defensive `unknown`
     * tail — never a silent success), and an `appResultCase(_:)` fold over the erased base type. The
     * success payload stays accessible (`success.data as? T`) via the clean `AppResultSuccess` alias.
     *
     * Generated only when the `_AppResult_Success`/`_Failure` classes are actually present in
     * [sourceContents] (scoped to `AppResult` — the one generic sealed type that matters; a general
     * generic-sealed solution is out of scope). Removable when Swift Export gains generic-sealed
     * support upstream (Plan 013).
     *
     * @param sharedContent the `Shared.swift` contents the accessor is appended to.
     * @param sourceContents the `Shared.swift` + `ListenupContract.swift` contents to detect the
     *   `AppResult` subtype classes in (they live in the `:contract` module's `ListenupContract.swift`).
     * @return the rewritten `Shared.swift` and `count` = 1 if the accessor was emitted, else 0.
     */
    fun appendAppResultAccessor(
        sharedContent: String,
        sourceContents: List<String>,
    ): PatchOutcome {
        if (sharedContent.contains(APP_RESULT_MARKER)) return PatchOutcome(sharedContent, 0)
        val hasSuccess = sourceContents.any { it.contains("public final class $APP_RESULT_SUCCESS_CLASS:") }
        val hasFailure = sourceContents.any { it.contains("public final class $APP_RESULT_FAILURE_CLASS:") }
        if (!hasSuccess || !hasFailure) return PatchOutcome(sharedContent, 0)

        val block =
            """

            $APP_RESULT_MARKER
            public typealias AppResultSuccess = $APP_RESULT_SUCCESS_CLASS
            public typealias AppResultFailure = $APP_RESULT_FAILURE_CLASS
            public enum AppResultCase {
                case success(AppResultSuccess)
                case failure(AppResultFailure)
                case unknown(any $APP_RESULT_BASE_TYPE)
            }
            public func appResultCase(_ value: any $APP_RESULT_BASE_TYPE) -> AppResultCase {
                if let failure = value as? AppResultFailure { return .failure(failure) }
                if let success = value as? AppResultSuccess { return .success(success) }
                return .unknown(value)
            }
            """.trimIndent() + "\n"
        return PatchOutcome(sharedContent + block, 1)
    }

    /**
     * `SCREAMING_SNAKE` generated enum cases -> camelCase for one generated `.swift` file. Swift
     * export emits Kotlin enum entries with their raw `SCREAMING_SNAKE` names (`.SYSTEM`,
     * `.FIRST_NAME`), which is jarring in Swift. Rewrite the generated case identifiers to camelCase
     * (`.system`, `.firstName`) so call sites read idiomatically. Only the `case X` declarations and
     * `.X` case references inside the enum are touched — the `"X"` string literals in the generated
     * `description`/`init?(_:)` (which round-trip the Kotlin name on the wire) and the `com_…_X()` C
     * bridge functions are left exactly as-is, so behavior is identical. Scoped per enum: only `.X`
     * where `X` is a declared case of that same enum is rewritten. Idempotent (already-camelCased
     * cases don't match the `SCREAMING_SNAKE` shape).
     *
     * @return the rewritten file and `count` = 1 if the file changed, else 0.
     */
    fun camelCaseEnumCases(content: String): PatchOutcome {
        val enumStart = Regex("""^(\s*)public enum (\w+):""")
        val caseDecl = Regex("""^(\s*)case ([A-Z][A-Z0-9_]+)\s*$""")

        fun toCamel(name: String): String {
            val camel =
                name
                    .split("_")
                    .mapIndexed { i, part ->
                        if (i == 0) part.lowercase() else part.lowercase().replaceFirstChar { it.uppercase() }
                    }.joinToString("")
            return if (camel in swiftKeywords) "`$camel`" else camel
        }

        val lines = content.lines().let { if (it.isNotEmpty() && it.last().isEmpty()) it.dropLast(1) else it }
        val out = ArrayList<String>(lines.size)
        var changed = false
        var inEnum = false
        var enumDepth = 0
        var depth = 0
        val caseMap = HashMap<String, String>()
        for (line in lines) {
            if (!inEnum) {
                if (enumStart.containsMatchIn(line)) {
                    inEnum = true
                    enumDepth = depth
                    caseMap.clear()
                    depth += line.count { it == '{' } - line.count { it == '}' }
                    out.add(line)
                    continue
                }
                out.add(line)
                depth += line.count { it == '{' } - line.count { it == '}' }
                continue
            }
            // Inside an enum body.
            var rewritten = line
            val decl = caseDecl.matchEntire(line)
            if (decl != null) {
                val camel = toCamel(decl.groupValues[2])
                if (camel != decl.groupValues[2]) {
                    caseMap[decl.groupValues[2]] = camel
                    rewritten = "${decl.groupValues[1]}case $camel"
                    changed = true
                }
            } else if (caseMap.isNotEmpty()) {
                for ((raw, camel) in caseMap) {
                    if (rewritten.contains(".$raw")) {
                        rewritten = rewritten.replace(Regex("""\.$raw\b"""), ".$camel")
                        changed = true
                    }
                }
            }
            out.add(rewritten)
            depth += line.count { it == '{' } - line.count { it == '}' }
            if (depth <= enumDepth) inEnum = false
        }
        return if (changed) {
            PatchOutcome(out.joinToString("\n") + "\n", 1)
        } else {
            PatchOutcome(content, 0)
        }
    }

    /**
     * Walks a generated SPM package dir, applies every transform to the right files, returns
     * per-pass counts keyed `patchSource` / `camelCase` / `flatTypealias` / `sealedEnum`. The only
     * File-touching code; mirrors [LocalizationArtifacts]'s role around [LocalizationGenerator].
     *
     * Order matches the original `doLast`: per-file passes first (`patchSource`, `camelCase`), then
     * the Shared.swift-append passes (`flatTypealias`, `sealedEnum`, `appResult`). A missing root
     * yields all-zero counts (the original returned 0 from each transform).
     *
     * Before emitting the sealed-enum support, it enforces the exact-count guard
     * ([sealedSubtypeDrift]): a *partial* subtype drop on a known sealed type fails the build loudly
     * here (naming the parent), rather than slipping the dropped subtype to the `unknown` case
     * silently. The aggregate `> 0` floor in `build.gradle.kts` stays as the *total*-failure net.
     */
    fun patchPackage(root: File): Map<String, Int> {
        if (!root.exists()) {
            return mapOf(
                "patchSource" to 0,
                "camelCase" to 0,
                "flatTypealias" to 0,
                "sealedEnum" to 0,
                "appResult" to 0,
            )
        }

        var patchSourceCount = 0
        root.walkTopDown().filter { it.isFile && it.extension == "swift" }.forEach { file ->
            val outcome = patchSource(file.readText(), file.parentFile.name)
            if (outcome.count > 0) {
                file.writeText(outcome.content)
                patchSourceCount++
            }
        }

        var camelCaseCount = 0
        for (file in moduleSourceFiles(root)) {
            val outcome = camelCaseEnumCases(file.readText())
            if (outcome.count > 0) {
                file.writeText(outcome.content)
                camelCaseCount++
            }
        }

        val sharedFile = sharedSwiftFile(root)
        val sourceContents = moduleSourceFiles(root).map { it.readText() }

        val flatTypealiasCount: Int
        if (sharedFile != null) {
            val outcome = appendFlatTypealiases(sharedFile.readText(), sourceContents)
            if (outcome.content != sharedFile.readText()) sharedFile.writeText(outcome.content)
            flatTypealiasCount = outcome.count
        } else {
            flatTypealiasCount = 0
        }

        val sealedEnumCount: Int
        if (sharedFile != null) {
            // Re-read source contents: the flat-typealias pass mutated Shared.swift above.
            val sealedSources = moduleSourceFiles(root).map { it.readText() }
            val drift = sealedSubtypeDrift(sealedSources)
            check(drift.isEmpty()) {
                "Swift Export patcher: sealed-subtype exact-count drift — a known sealed type harvested " +
                    "fewer subtypes than expected, so the dropped subtype(s) would silently fall to the " +
                    "generated `unknown` case. Update SwiftExportSourcePatcher.expectedSealedSubtypeCounts " +
                    "only if the Kotlin type legitimately changed.\n  - " + drift.joinToString("\n  - ")
            }
            val outcome = appendSealedEnumSupport(sharedFile.readText(), sealedSources)
            if (outcome.content != sharedFile.readText()) sharedFile.writeText(outcome.content)
            sealedEnumCount = outcome.count
        } else {
            sealedEnumCount = 0
        }

        val appResultCount: Int
        if (sharedFile != null) {
            val appResultSources = moduleSourceFiles(root).map { it.readText() }
            val outcome = appendAppResultAccessor(sharedFile.readText(), appResultSources)
            if (outcome.content != sharedFile.readText()) sharedFile.writeText(outcome.content)
            appResultCount = outcome.count
        } else {
            appResultCount = 0
        }

        return mapOf(
            "patchSource" to patchSourceCount,
            "camelCase" to camelCaseCount,
            "flatTypealias" to flatTypealiasCount,
            "sealedEnum" to sealedEnumCount,
            "appResult" to appResultCount,
        )
    }

    /** The generated `Shared/Shared.swift`, or null if the package hasn't generated it. */
    private fun sharedSwiftFile(root: File): File? =
        root.walkTopDown().firstOrNull { it.name == "Shared.swift" && it.parentFile.name == "Shared" }

    /** The `Shared.swift` + `ListenupContract.swift` module roots the harvest passes read. */
    private fun moduleSourceFiles(root: File): List<File> =
        listOf("Shared", "ListenupContract").mapNotNull { module ->
            root.walkTopDown().firstOrNull { it.name == "$module.swift" && it.parentFile.name == module }
        }
}
