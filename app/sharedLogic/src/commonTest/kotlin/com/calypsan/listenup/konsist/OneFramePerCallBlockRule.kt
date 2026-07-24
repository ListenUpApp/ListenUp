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
        val afterToken = dotCall + ".call".length
        // Reject `.callback`, `.calledBack`, etc. — `.call` must be a whole token.
        if (source.getOrNull(afterToken)?.let { it.isLetterOrDigit() || it == '_' } == true) {
            i = afterToken
            continue
        }
        var j = source.skipWhitespace(afterToken)
        // Optional argument group, e.g. `.call(timeout = MERGE_TIMEOUT) { ... }`.
        if (source.getOrNull(j) == '(') {
            j = source.matchDelimited(j, '(', ')')
            if (j < 0) {
                i = afterToken
                continue
            }
            j = source.skipWhitespace(j)
        }
        if (source.getOrNull(j) != '{') {
            i = afterToken
            continue
        }
        val blockEnd = source.matchDelimited(j, '{', '}')
        if (blockEnd < 0) {
            i = afterToken
            continue
        }
        val block = source.substring(j + 1, blockEnd - 1)
        val frames = block.countServiceFrames()
        if (frames > MAX_FRAMES_PER_CALL_BLOCK) {
            offenders += "a call { } block issuing $frames RPC frames"
        }
        i = blockEnd
    }
    return offenders
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
private fun String.countServiceFrames(): Int {
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
        val c = this[k]
        val next = if (k + 1 < length) this[k + 1] else ' '
        when {
            c == '/' && next == '/' -> {
                while (k < length && this[k] != '\n') k++
            }

            c == '/' && next == '*' -> {
                k += 2
                while (k + 1 < length && !(this[k] == '*' && this[k + 1] == '/')) k++
                k += 2
            }

            c == '"' && next == '"' && k + 2 < length && this[k + 2] == '"' -> {
                k += 3
                while (k + 2 < length && !(this[k] == '"' && this[k + 1] == '"' && this[k + 2] == '"')) k++
                k += 3
            }

            c == '"' -> {
                k++
                while (k < length && this[k] != '"') {
                    if (this[k] == '\\') k++
                    k++
                }
                k++
            }

            c == '\'' -> {
                k++
                while (k < length && this[k] != '\'') {
                    if (this[k] == '\\') k++
                    k++
                }
                k++
            }

            else -> {
                out.append(c)
                k++
            }
        }
    }
    return out.toString()
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
