package com.calypsan.listenup.gradle

import java.io.File

/**
 * Pure post-processor for the Kotlin glue Swift Export generates (`build/SwiftExport/<target>/<config>/files`),
 * the Kotlin-side twin of [SwiftExportSourcePatcher]. Same contract: deterministic `String -> String`
 * transforms keyed to the exact emitted shape, each removable when its upstream bug is fixed, each
 * returning a count so the build can log what it did.
 */
object SwiftExportGluePatcher {
    private val reverseBinding = Regex("""^@BindReverseBridgeToMethod\(([\w.]+)::class, "(\w+)"\)\s*$""")
    private val importedBridge = Regex("""^@ImportedBridge\("\w+_reverse_swift"\)\s*$""")
    private val exportedBridge = Regex("""@ExportedBridge\("(\w+?)__TypesOfArguments__(\w*?)(?:_direct)?"""")

    /**
     * Drops every reverse bridge — the pair Kotlin 2.4.20 emits so a Swift subclass can override a
     * Kotlin member: an `@ImportedBridge` external plus a `@BindReverseBridgeToMethod(C::class, "m")`
     * function — whose target `m` is overloaded on `C`.
     *
     * The binding is by bare name, so it is only well-defined when the name is unique. On an
     * overloaded name the Kotlin/Native binder resolves it to an arbitrary overload; for
     * `androidx.lifecycle.ViewModel.addCloseable` that is the *final* two-argument one, and the
     * link dies with `... is not found in vtable of CLASS ViewModel`. Overloads are counted from
     * the `@ExportedBridge` forward bridges for the same `C_m` prefix (their `___direct` twins
     * collapse onto the same overload). Nothing on the Swift side references the dropped pair: its
     * `@_cdecl` callback simply goes uncalled.
     *
     * @return the rewritten glue and `count` = reverse bridges dropped.
     */
    fun dropAmbiguousReverseBridges(content: String): PatchOutcome {
        val lines = content.lines()
        val overloads = HashMap<String, MutableSet<String>>()
        for (line in lines) {
            val bridge = exportedBridge.find(line) ?: continue
            overloads.getOrPut(bridge.groupValues[1]) { HashSet() }.add(bridge.groupValues[2].trimEnd('_'))
        }
        val dropped = HashSet<Int>()
        var count = 0
        lines.forEachIndexed { index, line ->
            val binding = reverseBinding.matchEntire(line) ?: return@forEachIndexed
            val prefix = binding.groupValues[1].replace('.', '_') + "_" + binding.groupValues[2]
            if ((overloads[prefix]?.size ?: 0) < 2) return@forEachIndexed
            // Back to the imported twin (attribute, external fun, blank line) directly above.
            var start = index
            while (start > 0 && index - start < 4 && !importedBridge.matches(lines[start - 1])) start--
            if (start > 0 && importedBridge.matches(lines[start - 1])) start--
            // Forward over the reverse function's brace-balanced body.
            var end = index + 1
            while (end < lines.size && !lines[end].contains('{')) end++
            var depth = 0
            while (end < lines.size) {
                depth += lines[end].count { it == '{' } - lines[end].count { it == '}' }
                end++
                if (depth <= 0) break
            }
            if (end < lines.size && lines[end].isBlank()) end++
            dropped.addAll(start until end)
            count++
        }
        if (count == 0) return PatchOutcome(content, 0)
        return PatchOutcome(lines.filterIndexed { index, _ -> index !in dropped }.joinToString("\n"), count)
    }

    /** Applies every transform to each `.kt` under [filesRoot]; returns the total reverse bridges dropped. */
    fun patchGlue(filesRoot: File): Int {
        if (!filesRoot.isDirectory) return 0
        var total = 0
        filesRoot.walkTopDown().filter { it.isFile && it.extension == "kt" }.forEach { file ->
            val outcome = dropAmbiguousReverseBridges(file.readText())
            if (outcome.count > 0) {
                file.writeText(outcome.content)
                total += outcome.count
            }
        }
        return total
    }
}
