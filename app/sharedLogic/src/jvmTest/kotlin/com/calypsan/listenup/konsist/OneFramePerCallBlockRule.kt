package com.calypsan.listenup.konsist

import com.lemonappdev.konsist.api.declaration.KoFileDeclaration
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty

/**
 * Pins the "one RPC frame per `channel.call { }` block" invariant on the repository layer.
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
 * **This is a text heuristic, and its limits are deliberate.** It brace-matches each `.call { }`
 * lambda in a repository `…Impl.kt` file and counts how many times the lambda's service receiver
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
 * It also keys off the `.call` token — a differently-named dispatch method would slip past. Good enough
 * to catch the mutate-then-read regression class (which uses depth-0 `it`) and block its reintroduction.
 */
class OneFramePerCallBlockRule :
    FunSpec({
        test("each channel.call { } block in data/repository/*Impl issues exactly one RPC frame") {
            val offenders =
                productionScope()
                    .files
                    .filter { it.path.contains("/data/repository/") && it.path.endsWith("Impl.kt") }
                    .flatMap { file -> file.multiFrameCallBlocks().map { "$it in ${file.path}" } }

            offenders.shouldBeEmpty()
        }
    })

/** The most frames a single `call { }` block may issue before it is a double-apply hazard on retry. */
private const val MAX_FRAMES_PER_CALL_BLOCK = 1

/**
 * Descriptions of every `.call { }` block in this file whose lambda invokes the service receiver more
 * than [MAX_FRAMES_PER_CALL_BLOCK] times — i.e. more than one RPC frame per block.
 */
private fun KoFileDeclaration.multiFrameCallBlocks(): List<String> {
    val source = text
    val offenders = mutableListOf<String>()
    var i = 0
    while (true) {
        val dotCall = source.indexOf(".call", i)
        if (dotCall < 0) break
        val scan = source.scanCallBlockAt(dotCall)
        scan.offender?.let { offenders += it }
        i = scan.resumeAt
    }
    return offenders
}

/** One resolved `.call` occurrence: where scanning resumes, and the offence found there, if any. */
private data class CallBlockScan(
    val resumeAt: Int,
    val offender: String?,
)

/**
 * Resolve the `.call` token at [dotCall] — skipping it when it is not really a `call { }` block, and
 * otherwise reporting whether its lambda issues more than [MAX_FRAMES_PER_CALL_BLOCK] frames.
 *
 * Handing back a resume index instead of jumping is what keeps the caller's loop to a single exit.
 */
private fun String.scanCallBlockAt(dotCall: Int): CallBlockScan {
    val afterToken = dotCall + ".call".length
    val skip = CallBlockScan(afterToken, null)
    // Reject `.callback`, `.calledBack`, etc. — `.call` must be a whole token.
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

    val frames = substring(j + 1, blockEnd - 1).countServiceFrames()
    val offender =
        if (frames > MAX_FRAMES_PER_CALL_BLOCK) "a call { } block issuing $frames RPC frames" else null
    return CallBlockScan(blockEnd, offender)
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
    val named = NAMED_RECEIVER.find(code)?.groupValues?.get(1)
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

private fun String.skipLineComment(start: Int): Int {
    var k = start
    while (k < length && this[k] != '\n') k++
    return k
}

private fun String.skipBlockComment(start: Int): Int {
    var k = start + 2
    while (k + 1 < length && !(this[k] == '*' && this[k + 1] == '/')) k++
    return k + 2
}

private fun String.skipRawString(start: Int): Int {
    var k = start + 3
    while (k + 2 < length && !(this[k] == '"' && this[k + 1] == '"' && this[k + 2] == '"')) k++
    return k + 3
}

/** Skip a `"..."` or `'x'` literal from its opening [quote], honouring backslash escapes. */
private fun String.skipQuotedLiteral(
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
