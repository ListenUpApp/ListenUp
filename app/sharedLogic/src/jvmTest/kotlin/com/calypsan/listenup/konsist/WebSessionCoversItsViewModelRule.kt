package com.calypsan.listenup.konsist

import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty

/**
 * Konsist guard pinning the rule that a web session factory covers the ViewModel it resolves.
 *
 * ⛔ **The regression this exists to stop.** `:app:webApp`'s feature seam is
 * `XSession(state, callbacks…, close)` built by a `graphX(koin)` factory. State is one obvious
 * field; the callbacks are an open-ended list. So a ViewModel action nobody wired is not a
 * compile error, not a test failure, and not visible on screen — it is simply a control the
 * browser never grew. That is not hypothetical: `BookDetailSession` shipped as `(state, close)`
 * with every one of its ViewModel's actions missing, and it took a six-domain audit to notice.
 *
 * So: for every `graph*` factory, every public function of the ViewModel it resolves must appear
 * somewhere in that factory's file — or be named in [EXCUSED] with a reason.
 *
 * **Comments are stripped before matching.** A KDoc explaining why an action is *not* wired would
 * otherwise satisfy the rule by naming it — the exact false negative this guard exists to prevent.
 *
 * **What this does NOT check.** That the *right* method is wired, or that a wired callback reaches
 * the UI.
 *
 * ⛔ **An offender is a question, not a verdict.** Four shapes have turned out not to be gaps:
 * a **convenience overload** (`onResultClicked` *is* `onResultSelected`), a **different route to
 * the same capability** (`SettingsViewModel.signOut` vs `AuthGraph.signOut()`), a **function the
 * ViewModel calls itself** (`loadScanIssues`, from `init`), and a **building block covered by
 * wrappers** (`setBookOverride`, behind `selectBook`/`skipBook`). Plus the big one:
 *
 * ⛔ **it cannot see a capability that lives on a different ViewModel.** An offender here means
 * "this session does not reference this function" — NOT "web cannot do this". Book Detail's whole
 * tag API reads as a gap and is not one: tag editing lives on `BookEditViewModel`, which web wires
 * in full. Before filing an entry as a GAP, look for the capability elsewhere; an unwired function
 * that *no* client wires is dead code on the ViewModel, not a web gap. It catches "nobody thought about this action",
 * which is the failure that actually happened, and it catches it the day the action is added to a
 * shared ViewModel rather than at the next audit.
 *
 * **Overrides are skipped** — `onCleared` is androidx's lifecycle hook, reached by `store.clear()`,
 * not an action any client wires.
 *
 * Entries in [EXCUSED] marked `GAP` are **not blessed**. They are the honest inventory of what web
 * still owes, kept here so it cannot be lost and so a *new* omission fails this test by name.
 * Derived 2026-09-18; the full working is in the docs sibling.
 */
class WebSessionCoversItsViewModelRule :
    FunSpec({
        test("every web session factory covers its ViewModel's actions, or excuses them by name") {
            val scope = productionScope()

            val factoryFiles =
                scope
                    .files
                    .filter { "/webApp/" in it.path }
                    .filter { file -> file.functions().any { it.name.startsWith("graph") } }
                    .associateWith { it.text.withoutComments() }

            assertScopeNotEmpty(
                factoryFiles.keys,
                expectedMin = 30,
                why = "web session factories — if discovery breaks, this rule polices nothing",
            )

            val viewModelFunctions =
                scope
                    .classes()
                    .filter { it.name.endsWith("ViewModel") }
                    .associate { vm ->
                        vm.name to
                            vm
                                .functions()
                                .filter { it.hasPublicOrDefaultModifier }
                                .filterNot { it.hasOverrideModifier }
                                // Commands, not queries. A session wires actions; a function that
                                // returns a value is a display helper the UI calls where it renders
                                // (`roleToDisplayName`, `secondaryOf`), and demanding the factory
                                // mention it would teach the reader to allowlist noise.
                                .filter { it.returnType == null || it.returnType?.name == "Unit" }
                                .map { it.name }
                    }

            val offenders =
                factoryFiles.flatMap { (file, code) ->
                    RESOLVED_VIEW_MODEL
                        .findAll(code)
                        .map { it.groupValues[1] }
                        .distinct()
                        .flatMap { viewModel ->
                            viewModelFunctions[viewModel]
                                .orEmpty()
                                .filterNot { fn -> Regex("\\b${Regex.escape(fn)}\\b").containsMatchIn(code) }
                                .filterNot { fn -> "$viewModel.$fn" in EXCUSED }
                                .map { fn -> "$viewModel.$fn never referenced in ${file.name}" }
                        }
                }

            // The clue carries every offender: a rule that names one of four sends the reader
            // back to run it again three times.
            withClue(offenders.joinToString("\n", prefix = "\n")) { offenders.shouldBeEmpty() }
        }

        test("no excused action has quietly been wired since it was excused") {
            // ⛔ Without this, EXCUSED only ever grows. An entry that has been closed stops matching
            // anything, sits there reading like a live gap, and the next reader either trusts a
            // stale inventory or re-verifies all of it by hand. Closing a gap must delete its line.
            val scope = productionScope()
            val factoryText =
                scope
                    .files
                    .filter { "/webApp/" in it.path }
                    .filter { file -> file.functions().any { it.name.startsWith("graph") } }
                    .associate { it.name to it.text.withoutComments() }

            val stale =
                EXCUSED.filter { entry ->
                    val viewModel = entry.substringBefore('.')
                    val function = entry.substringAfter('.')
                    factoryText.any { (_, text) ->
                        RESOLVED_VIEW_MODEL.findAll(text).any { it.groupValues[1] == viewModel } &&
                            Regex("\\b${Regex.escape(function)}\\b").containsMatchIn(text)
                    }
                }

            withClue(stale.joinToString("\n", prefix = "\nWired now — delete these lines:\n")) {
                stale.shouldBeEmpty()
            }
        }
    })

/** `koin.get<SomeViewModel>()` — how a factory names the ViewModel it is the seam for. */
private val RESOLVED_VIEW_MODEL = Regex("""koin\.get<(\w+ViewModel)>\(\)""")

/**
 * Actions a web session deliberately does not wire, or that it reaches by another route.
 *
 * Three kinds, and the distinction matters:
 *  - **DELIBERATE** — web answers this differently on purpose. Deleting the entry should break
 *    the build, because re-wiring it would be a bug.
 *  - **FALSE POSITIVE** — web has the capability through a different name. The entry documents
 *    the route so the next reader does not "fix" a non-problem.
 *  - **GAP** — web owes this. Not blessed; listed so it cannot be lost. Close it, delete the line.
 */
private val EXCUSED =
    setOf(
        // ── DELIBERATE ────────────────────────────────────────────────────────────────────────
        // Resolves a local file path for a platform PDF renderer. Web links at the cookie-authed
        // document route and lets the browser stream it — see DocumentsPanel's KDoc.
        "BookDetailViewModel.onOpenDocument",
        // Emits navigate-to-sign-in for clients whose sign-in is a screen. Web answers a lapse
        // with a sheet over the page the reader is already on — see ConnectionHealthStore.
        "ConnectionHealthViewModel.signIn",
        // The Universal-Link entry point: `start` stores the LINK's server for confirmation, and
        // onConfirmServer/onCancelServer answer that prompt. A browser already knows its server, so
        // `pendingServerUrl` is never set and all three are unreachable here — see ClaimInviteSession.
        "ClaimInviteViewModel.start",
        "ClaimInviteViewModel.onConfirmServer",
        "ClaimInviteViewModel.onCancelServer",
        // Material You sources colours from the Android wallpaper. Web has its own theme switch
        // (ThemeMode), so there is no equivalent knob to wire.
        "SettingsViewModel.setDynamicColorsEnabled",
        // ⛔ The Settings four. `SettingsPage`/`SettingsSession` already carry the reasoning in
        // prose — "eight controls, not twelve" — and this rule strips comments, so a carefully
        // made decision read exactly like an oversight. Recorded here so it reads as a decision:
        //  - wifi-only downloads: web cannot download at all (`supportsDownloads` is false), so the
        //    toggle would govern a capability this device does not have.
        //  - haptics: needs hardware a browser tab does not have.
        //  - default sleep timer: a stored preference NOTHING anywhere reads yet — web has the
        //    sleep timer, but nothing starts one from this number, so the control would change
        //    nothing. Revisit when something honours it, exactly as default boost was revisited
        //    once `WebGainStage` could act on it.
        //  - sendTestNotification: pushes "back to THIS device", and this tab cannot receive a push
        //    (no service worker). It would report "sent" while nothing ever arrived — destroying
        //    the one diagnostic the button exists for.
        "SettingsViewModel.setWifiOnlyDownloads",
        "SettingsViewModel.setHapticFeedbackEnabled",
        "SettingsViewModel.setDefaultSleepTimerMin",
        "SettingsViewModel.sendTestNotification",
        // ── FALSE POSITIVE (capability present under another name) ────────────────────────────
        // Reached via onResultClicked, which IS onResultSelected(hit.id, hit.type, hit.name).
        "SearchViewModel.onResultSelected",
        "SeeAllSearchViewModel.onResultSelected",
        // iOS-only: its scope picker is single-select (a Swift Export bridging choice). Android
        // and web both use toggleTypeFilter.
        "SearchViewModel.setTypeFilter",
        // Web signs out through AuthGraph.signOut() — see AuthGate.
        "SettingsViewModel.signOut",
        // ⛔ CORRECTION (2026-09-18). These five were filed as a GAP — "a book cannot be tagged
        // from the browser" — and that was WRONG. No client wires BookDetailViewModel's tag API:
        // not Android, not iOS, not web. It is dead code on the ViewModel. Tag editing lives on
        // the Book EDIT screen, through BookEditViewModel, which web has in full (search, attach,
        // detach, invent — see BookEditPage's RelationField). Wiring these on web would build a
        // detail-page picker no other platform has AND give web a second tag surface. The
        // derivation that found them cannot see a capability that lives on a different ViewModel.
        "BookDetailViewModel.addTag",
        "BookDetailViewModel.addNewTag",
        "BookDetailViewModel.removeTag",
        "BookDetailViewModel.showTagPicker",
        "BookDetailViewModel.hideTagPicker",
        // Loaded by the ViewModel itself — from `init`, and again when an admin event says the
        // inbox changed. No client wires it because none needs to; it is public by accident.
        "AdminInboxViewModel.loadScanIssues",
        // A building block. Clients drive the two wrappers that cover it — `selectBook` and
        // `skipBook` — and web wires both.
        "ImportFlowViewModel.setBookOverride",
        // ── UNREVIEWED — an offender nobody has triaged yet. NOT a to-do list. ────────────────
        //
        // ⛔ Do not build from this section. Three times now a cluster here has turned out to be a
        // decision web already made (tags, the Settings four) or a function no client wires at all.
        // Before treating an entry as work: check the other clients call it, and check whether
        // web's own KDoc already explains the omission — this rule strips comments, so a documented
        // decision is indistinguishable from an oversight until a human looks. Then move it up to a
        // labelled section or close it and delete the line.
        "BookDetailViewModel.retryConnection",
        // Delete Book is parked on the `next` branch by an explicit product decision.
        "BookDetailViewModel.deleteBook",
        "BookDetailViewModel.clearDeleteError",
        // Metadata wizards.
        "ContributorMetadataViewModel.reset",
        "ContributorMetadataViewModel.selectAsin",
        "MetadataViewModel.reset",
        // Library setup.
        "LibrarySetupViewModel.checkLibraryStatus",
        "LibrarySetupViewModel.clearSelection",
        "LibrarySetupViewModel.selectPath",
        // Admin.
        "OrganizeSettingsViewModel.clearError",
        "SyncIndicatorViewModel.toggleExpanded",
        // Manual refresh: check whether web refreshes on navigation before building a control.
        "HomeViewModel.refresh",
        "DiscoverViewModel.refresh",
        "ActivityFeedViewModel.refresh",
        "UserProfileViewModel.refresh",
    )

/**
 * Code with comments removed, so prose about an action cannot pass for wiring it.
 *
 * Deliberately crude — block comments then line comments. It runs over Kotlin this repo formats
 * with spotless, and the cost of the edge it misses (a `//` inside a string literal) is a name
 * that stays matched, which is the pre-existing behaviour rather than a new hole.
 */
private fun String.withoutComments(): String =
    replace(Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL), " ")
        .replace(Regex("""//[^\n]*"""), " ")
