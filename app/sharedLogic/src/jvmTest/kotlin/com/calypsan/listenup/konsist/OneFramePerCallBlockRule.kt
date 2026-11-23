package com.calypsan.listenup.konsist

import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.ints.shouldBeGreaterThanOrEqual

/**
 * Pins the "one RPC frame per `channel.call { }` block" invariant — on every `call { }` and
 * `callMutation { }` block anywhere in `:app:sharedLogic` production code.
 *
 * The engine ([com.calypsan.listenup.client.data.remote.RpcProxyCache]) retries a `call { }` block
 * **whole** on a provably pre-delivery transport fault — that single at-most-once retry is what makes
 * a dropped socket heal invisibly. The retry is only safe because the block is assumed to issue
 * exactly ONE service method (one RPC frame): re-running a one-frame block re-sends one frame, which
 * a pre-delivery classification proves was never delivered. But a block that issues *two* service
 * methods (a mutate-then-read, or two mutates composed inside the lambda) re-runs BOTH on retry — so
 * a non-idempotent mutation in that block can fire twice. Mutate-then-read is the dangerous order:
 * the read failing pre-delivery would re-fire the already-committed mutation.
 *
 * The canonical shape composes multiple frames with `.flatMap { }` BETWEEN separate `call { }` blocks
 * (see [com.calypsan.listenup.client.data.repository.AdminRepositoryImpl.approveUser] /
 * `addScanPath` / `denyUser`), so each frame retries independently and only the frame that actually
 * dropped is re-sent.
 *
 * `callMutation { }` rides the same whole-block retry (`client/data/remote/RpcChannel.kt`), and the offline
 * outbox's push lambdas in `client/data/sync/OutboxBindings.kt` are call blocks too — the place a double-fired
 * frame is least welcome. The rule once scanned only the `…Impl.kt` files under `data/repository` and
 * skipped any `.call` followed by a letter, which dropped `.callMutation` along with `.callback`; it now
 * scans every production file and asserts it reached both shapes.
 *
 * **This is a text heuristic, and its limits are deliberate.** It brace-matches each `.call { }` /
 * `.callMutation { }` lambda and counts how many times the lambda's service receiver
 * is invoked. For a named receiver (`{ service -> ... }`) it counts every reference minus the
 * declaration, at any depth (a named param is not shadowed, so a second frame can legitimately hide
 * inside a `.flatMap { service.other() }`). For the implicit-`it` receiver it counts only depth-0
 * usages — trading one accepted false-negative for zero (build-breaking) false-positives:
 *  - **No false positives:** a nested *implicit*-`it` lambda rebinds `it` to its own element
 *    (`channel.call { it.getUsers().map { it.toDomain() } }` — the inner `it` is a User, not the
 *    service), so counting nested `it` would wrongly flag a legitimate one-frame block.
 *  - **One accepted false-negative:** a second frame hidden in a *named-param* nested lambda —
 *    `channel.call { it.addFolder(p).flatMap { folder -> it.scanFolder(folder.id) } }` — is a real
 *    two-frame block (the named param does NOT shadow the outer `it`) that this rule does NOT catch.
 *    Telling that apart from the false-positive case needs real shadowing analysis, not a text
 *    heuristic, so that exotic shape relies on the reviewer's eye.
 * It also keys off the `.call` / `.callMutation` tokens — a differently-named dispatch method would slip past. Good enough
 * to catch the mutate-then-read regression class (which uses depth-0 `it`) and block its reintroduction.
 */
class OneFramePerCallBlockRule :
    FunSpec({
        test("each call { } / callMutation { } block in :app:sharedLogic issues exactly one RPC frame") {
            val files =
                productionScope()
                    .files
                    .filter { "/app/sharedLogic/src/" in it.path }

            assertScopeNotEmpty(
                files,
                expectedMin = 500,
                why = ":app:sharedLogic production files — every file whose call blocks this heuristic scans",
            )

            val blocksByPath = files.associate { file -> file.path to file.text.callBlocks() }
            val blocks = blocksByPath.values.flatten()

            // Non-vacuous: the real count is ~200; a scanner that silently stopped matching would read zero.
            withClue("call blocks scanned") { blocks.size shouldBeGreaterThanOrEqual MIN_CALL_BLOCKS }
            withClue("callMutation blocks scanned") { blocks.filter { it.token == CALL_MUTATION }.shouldNotBeEmpty() }
            withClue("call blocks scanned in OutboxBindings.kt (the outbox's push lambdas)") {
                blocksByPath
                    .filterKeys { it.endsWith("/OutboxBindings.kt") }
                    .values
                    .flatten()
                    .shouldNotBeEmpty()
            }

            val offenders =
                blocksByPath.flatMap { (path, fileBlocks) ->
                    fileBlocks
                        .filter { it.frames > MAX_FRAMES_PER_CALL_BLOCK }
                        .map { "a ${it.token} { } block issuing ${it.frames} RPC frames in $path" }
                }

            offenders.shouldBeEmpty()
        }
    })

/** The most frames a single `call { }` block may issue before it is a double-apply hazard on retry. */
private const val MAX_FRAMES_PER_CALL_BLOCK = 1

/** The real block count (about 200) minus a margin, so a scanner that stops matching fails loudly. */
private const val MIN_CALL_BLOCKS = 180

private const val CALL = "call"
private const val CALL_MUTATION = "callMutation"

/** One `.call { }` / `.callMutation { }` block: which token opened it, and how many RPC frames its lambda issues. */
internal data class CallBlock(
    val token: String,
    val frames: Int,
)

/** Every `.call { }` and `.callMutation { }` block in [this] source, with its frame count. */
internal fun String.callBlocks(): List<CallBlock> {
    val blocks = mutableListOf<CallBlock>()
    var i = 0
    while (true) {
        val dotCall = indexOf(".$CALL", i)
        if (dotCall < 0) break
        val scan = scanCallBlockAt(dotCall)
        scan.block?.let { blocks += it }
        i = scan.resumeAt
    }
    return blocks
}

/** One resolved `.call…` occurrence: where scanning resumes, and the block found there, if any. */
private data class CallBlockScan(
    val resumeAt: Int,
    val block: CallBlock?,
)

/**
 * Resolve the `.call` token at [dotCall] — as `.call` or `.callMutation`, skipping it when it is any other
 * identifier (`.callback`) or not followed by a block — and count its lambda's frames.
 *
 * Handing back a resume index instead of jumping is what keeps the caller's loop to a single exit.
 */
private fun String.scanCallBlockAt(dotCall: Int): CallBlockScan {
    val token = if (startsWith(".$CALL_MUTATION", dotCall)) CALL_MUTATION else CALL
    val afterToken = dotCall + 1 + token.length
    val skip = CallBlockScan(afterToken, null)
    // Reject `.callback`, `.calledBack`, `.callMutations`, etc. — the token must be whole.
    if (getOrNull(afterToken)?.let { it.isLetterOrDigit() || it == '_' } == true) return skip

    var j = skipWhitespace(afterToken)
    // Optional argument group, e.g. `.call(timeout = MERGE_TIMEOUT) { ... }`.
    if (getOrNull(j) == '(') {
        j = matchDelimited(j, '(', ')')
        if (j < 0) return skip
        j = skipWhitespace(j)
    }
    if (getOrNull(j) != '{') return skip

    val blockEnd = matchDelimited(j, '{', '}')
    if (blockEnd < 0) return skip

    return CallBlockScan(blockEnd, CallBlock(token, substring(j + 1, blockEnd - 1).countServiceFrames()))
}

private val NAMED_RECEIVER = Regex("""^\s*([A-Za-z_][A-Za-z0-9_]*)\s*->""")

/**
 * How many times the lambda's service receiver is invoked inside [this] `call { }` block body.
 *
 * - Named receiver (`{ service -> ... }`): every `\bservice\b` reference at any depth, minus the
 *   parameter declaration. A named param is not shadowed, so a second frame can hide in a nested
 *   `.flatMap { service.other() }`.
 * - Implicit `it`: only `\bit\b` at brace-depth 0. A nested lambda's implicit `it` shadows the
 *   outer, so it can never name the service — counting it would be a false positive.
 *
 * Comments and string literals are stripped first, so a comment that happens to contain the word
 * "it" (or the receiver's name) never inflates the frame count.
 */
internal fun String.countServiceFrames(): Int {
    val code = stripCommentsAndStringLiterals()
    val named = NAMED_RECEIVER.find(code)?.run { groupValues[1] }
    return if (named != null) {
        Regex("""\b${Regex.escape(named)}\b""").findAll(code).count() - 1
    } else {
        code.countTokenAtTopLevel("it")
    }
}

/**
 * Replace every comment (line + block) and string/char literal in [this] with nothing, so downstream
 * token-counting and brace-depth tracking see only real code. Not a full lexer, but it handles the
 * cases repository lambdas actually contain: `// ...`, block comments, `"..."`, `"""..."""`, `'x'`.
 */
private fun String.stripCommentsAndStringLiterals(): String {
    val out = StringBuilder(length)
    var k = 0
    while (k < length) {
        val skipTo = skipNonCodeAt(k)
        if (skipTo > k) {
            k = skipTo
        } else {
            out.append(this[k])
            k++
        }
    }
    return out.toString()
}

/**
 * If a comment or literal opens at [start], the index just past its close; otherwise [start] itself,
 * meaning "this is real code, keep it". Every branch advances by at least one, so the caller's loop
 * cannot stall.
 */
private fun String.skipNonCodeAt(start: Int): Int {
    val c = this[start]
    val next = if (start + 1 < length) this[start + 1] else ' '
    return when {
        c == '/' && next == '/' -> skipLineComment(start)
        c == '/' && next == '*' -> skipBlockComment(start)
        c == '"' && next == '"' && start + 2 < length && this[start + 2] == '"' -> skipRawString(start)
        c == '"' -> skipQuotedLiteral(start, '"')
        c == '\'' -> skipQuotedLiteral(start, '\'')
        else -> start
    }
}

internal fun String.skipLineComment(start: Int): Int {
    var k = start
    while (k < length && this[k] != '\n') k++
    return k
}

internal fun String.skipBlockComment(start: Int): Int {
    var k = start + 2
    while (k + 1 < length && !(this[k] == '*' && this[k + 1] == '/')) k++
    return k + 2
}

internal fun String.skipRawString(start: Int): Int {
    var k = start + 3
    while (k + 2 < length && !(this[k] == '"' && this[k + 1] == '"' && this[k + 2] == '"')) k++
    return k + 3
}

/** Skip a `"..."` or `'x'` literal from its opening [quote], honouring backslash escapes. */
internal fun String.skipQuotedLiteral(
    start: Int,
    quote: Char,
): Int {
    var k = start + 1
    while (k < length && this[k] != quote) {
        if (this[k] == '\\') k++
        k++
    }
    return k + 1
}

/** Count `\btoken\b` occurrences that sit at brace-depth 0 within [this] (nested lambdas excluded). */
private fun String.countTokenAtTopLevel(token: String): Int {
    val matcher = Regex("""\b${Regex.escape(token)}\b""")
    var depth = 0
    var count = 0
    var idx = 0
    while (idx < length) {
        when (this[idx]) {
            '{' -> depth++
            '}' -> if (depth > 0) depth--
        }
        if (depth == 0 && matcher.matchesAt(this, idx)) {
            count++
            idx += token.length
            continue
        }
        idx++
    }
    return count
}

private fun String.skipWhitespace(from: Int): Int {
    var k = from
    while (k < length && this[k].isWhitespace()) k++
    return k
}

/**
 * Given [this][String] and the index of an opening [open] delimiter, return the index JUST PAST its
 * matching [close], or -1 if unbalanced. Handles nesting; does not special-case string literals.
 */
private fun String.matchDelimited(
    openIndex: Int,
    open: Char,
    close: Char,
): Int {
    var depth = 0
    var k = openIndex
    while (k < length) {
        when (this[k]) {
            open -> {
                depth++
            }

            close -> {
                depth--
                if (depth == 0) return k + 1
            }
        }
        k++
    }
    return -1
}
