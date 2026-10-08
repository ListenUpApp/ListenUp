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
 * So: for every `graph*` factory, every public function of the ViewModel it resolves must be
 * referenced on that ViewModel in that factory's file — or be named in [EXCUSED] with a reason.
 *
 * **Per receiver, not per file.** `DiscoverStore` resolves three ViewModels, two of which have a
 * `refresh`; wiring `discover::refresh` once read as wiring both. A reference now counts only when
 * it is made through the binding that holds that ViewModel — see [actionCoverage].
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
 * ViewModel calls itself** (`loadScanIssues`, from Retry), and a **building block covered by
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
                    actionCoverage(code, viewModelFunctions)
                        .filterNot { it.isWired }
                        .filterNot { it.qualifiedName in EXCUSED }
                        .map { "${it.qualifiedName} never referenced in ${file.name}" }
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

            val wired =
                factoryText.values
                    .flatMap { text -> actionCoverage(text, excusedActionsByViewModel()) }
                    .filter { it.isWired }
                    .map { it.qualifiedName }
                    .toSet()
            val stale = EXCUSED.filter { it in wired }

            withClue(stale.joinToString("\n", prefix = "\nWired now — delete these lines:\n")) {
                stale.shouldBeEmpty()
            }
        }
    })

/**
 * `koin.get<SomeViewModel>()`, or `koin.get<SomeViewModel> { parametersOf(id) }` — how a factory
 * names the ViewModel it is the seam for. The lambda form is how every book- or id-scoped
 * ViewModel is resolved; matching only `()` left all seven of those factories unpoliced.
 */
private val RESOLVED_VIEW_MODEL = Regex("""koin\.get<(\w+ViewModel)>\s*(?:\(\)|\{)""")

/** One public action of a ViewModel a factory file resolves, and whether that file wires it. */
internal data class ActionCoverage(
    val viewModel: String,
    val action: String,
    val isWired: Boolean,
) {
    val qualifiedName: String get() = "$viewModel.$action"
}

/**
 * Every action of every ViewModel [code] resolves, each marked wired or not. [actionsByViewModel]
 * names each ViewModel's public actions; a ViewModel it does not know contributes nothing.
 *
 * An action is credited to the ViewModel whose binding it is called on — `discover::refresh` or
 * `discover.refresh(…)`, where `val discover = koin.get<DiscoverViewModel>()` — so a file that
 * resolves two ViewModels sharing an action name cannot wire one and pass for both. A binding's
 * reach runs from its declaration to the next declaration of the same name, which is how one file
 * can call every factory's ViewModel `viewModel` and still be charged correctly.
 *
 * ⛔ If any resolution in the file binds no local name (`koin.get<X>().state` inline), the file
 * falls back to the old, coarser match: the action's name anywhere in the file. No web factory
 * takes that shape today.
 */
internal fun actionCoverage(
    code: String,
    actionsByViewModel: Map<String, List<String>>,
): List<ActionCoverage> {
    val resolved =
        RESOLVED_VIEW_MODEL
            .findAll(code)
            .map { it.groupValues[1] }
            .distinct()
            .toList()
    val bindings = BOUND_VIEW_MODEL.findAll(code).toList()
    val everyResolutionIsBound = bindings.size == RESOLVED_VIEW_MODEL.findAll(code).count()

    fun isWired(
        viewModel: String,
        action: String,
    ): Boolean {
        if (!everyResolutionIsBound) return Regex("\\b${Regex.escape(action)}\\b").containsMatchIn(code)
        return bindings
            .filter { it.groupValues[2] == viewModel }
            .any { binding ->
                val name = binding.groupValues[1]
                val reachEnd =
                    Regex("""\b(?:val|var)\s+${Regex.escape(name)}\b""")
                        .find(code, binding.range.last + 1)
                        ?.range
                        ?.first ?: code.length
                Regex("""\b${Regex.escape(name)}\s*(?:\?\.|\.|::)\s*${Regex.escape(action)}\b""")
                    .containsMatchIn(code.substring(binding.range.first, reachEnd))
            }
    }

    return resolved.flatMap { viewModel ->
        actionsByViewModel[viewModel].orEmpty().map { action ->
            ActionCoverage(viewModel, action, isWired(viewModel, action))
        }
    }
}

/** `val discover = koin.get<DiscoverViewModel>()` — a resolution bound to a name: (name, ViewModel). */
private val BOUND_VIEW_MODEL = Regex("""\b(?:val|var)\s+(\w+)\s*=\s*koin\.get<(\w+ViewModel)>\s*(?:\(\)|\{)""")

/** [EXCUSED], regrouped as the action map [actionCoverage] takes. */
private fun excusedActionsByViewModel(): Map<String, List<String>> =
    EXCUSED.groupBy({ it.substringBefore('.') }, { it.substringAfter('.') })

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
        // Organize's error is an inline `role="alert"` line under the form (OrganizePage), and every
        // action the ViewModel takes clears it before it runs. A dismiss control would be a second,
        // redundant way to make the same message go away.
        "OrganizeSettingsViewModel.clearError",
        // Expands the pending-operations panel. Declined for web 2026-09-09: web carries no such
        // panel — in-flight sync is chrome the reader has nothing to decide about — and wires only
        // the dead-letter half of this ViewModel (see DeadLetterStore).
        "SyncIndicatorViewModel.toggleExpanded",
        // Snackbar acknowledgement. Android and iOS show a failed permission save as a snackbar and
        // call this once it is dismissed; web shows `Ready.error` as an inline alert, which has no
        // dismissal. The ViewModel clears `error` whenever a later save succeeds (2026-09-30, after
        // per-receiver matching showed nothing on web ever cleared the alert), so web needs no call.
        "UserDetailViewModel.clearError",
        // ── FALSE POSITIVE (capability present under another name) ────────────────────────────
        // Reached through the session's `close = store::clear`: clearing the ViewModelStore runs
        // `onCleared`, which calls `close()`. `close` exists for iOS, which has no store to clear.
        "BulkEditViewModel.close",
        "ChapterEditorViewModel.close",
        "HomeViewModel.close",
        "InboxBadgeViewModel.close",
        "LibraryViewModel.close",
        "LibrarySetupViewModel.close",
        "NotificationBellViewModel.close",
        "RestrictedBooksViewModel.close",
        // Reached via onResultClicked, which IS onResultSelected(hit.id, hit.type, hit.name).
        "SearchViewModel.onResultSelected",
        "SeeAllSearchViewModel.onResultSelected",
        // iOS-only: its scope picker is single-select (a Swift Export bridging choice). Android
        // and web both use toggleTypeFilter.
        "SearchViewModel.setTypeFilter",
        // Web signs out through AuthGraph.signOut() — see AuthGate.
        "SettingsViewModel.signOut",
        // Loaded by the ViewModel itself — when first observed, from Retry (`loadInboxBooks`, which web
        // wires), and again when an admin event says a scan ran. No client wires it directly.
        "AdminInboxViewModel.loadScanIssues",
        // A building block. Clients drive the two wrappers that cover it — `selectBook` and
        // `skipBook` — and web wires both.
        "ImportFlowViewModel.setBookOverride",
        // Web retries a profile through `loadProfile(userId, forceRefresh = true)` — see ProfileStore.
        // `refresh` re-requests the CURRENT profile, which is the same request by another name.
        "UserProfileViewModel.refresh",
        // Called by `showAddMemberSheet`, which web wires: opening the sheet is what loads the users.
        "AdminCollectionDetailViewModel.loadUsersForSharing",
        // ── UNREVIEWED — an offender nobody has triaged yet. NOT a to-do list. ────────────────
        //
        // ⛔ Do not build from this section. Three times now a cluster here has turned out to be a
        // decision web already made (tags, the Settings four) or a function no client wires at all.
        // Before treating an entry as work: check the other clients call it, and check whether
        // web's own KDoc already explains the omission — this rule strips comments, so a documented
        // decision is indistinguishable from an oversight until a human looks. Then move it up to a
        // labelled section or close it and delete the line.
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
