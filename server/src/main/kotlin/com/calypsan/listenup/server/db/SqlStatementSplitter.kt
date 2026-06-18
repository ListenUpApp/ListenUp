package com.calypsan.listenup.server.db

/**
 * Splits a multi-statement SQLite migration script into individual executable statements.
 *
 * Aware of `--` line comments, slash-star block comments, single- and double-quoted
 * literals/identifiers (with doubled-quote escapes), and `CREATE TRIGGER … BEGIN … END;`
 * blocks whose internal semicolons must NOT terminate the statement. Terminating semicolons
 * are dropped; comments and internal semicolons are preserved verbatim.
 */
internal object SqlStatementSplitter {
    fun split(sql: String): List<String> {
        val statements = mutableListOf<String>()
        val current = StringBuilder()
        var beginDepth = 0
        var i = 0
        val n = sql.length
        while (i < n) {
            val c = sql[i]
            when {
                c == '-' && i + 1 < n && sql[i + 1] == '-' -> {
                    while (i < n && sql[i] != '\n') {
                        current.append(sql[i]); i++
                    }
                }
                c == '/' && i + 1 < n && sql[i + 1] == '*' -> {
                    current.append("/*"); i += 2
                    while (i < n && !(sql[i] == '*' && i + 1 < n && sql[i + 1] == '/')) {
                        current.append(sql[i]); i++
                    }
                    if (i < n) {
                        current.append("*/"); i += 2
                    }
                }
                c == '\'' || c == '"' -> {
                    val quote = c
                    current.append(c); i++
                    while (i < n) {
                        current.append(sql[i])
                        if (sql[i] == quote) {
                            if (i + 1 < n && sql[i + 1] == quote) {
                                current.append(sql[i + 1]); i += 2; continue
                            }
                            i++; break
                        }
                        i++
                    }
                }
                c.isLetter() -> {
                    val start = i
                    while (i < n && (sql[i].isLetterOrDigit() || sql[i] == '_')) i++
                    val word = sql.substring(start, i)
                    current.append(word)
                    when (word.uppercase()) {
                        "BEGIN" -> beginDepth++
                        "END" -> if (beginDepth > 0) beginDepth--
                    }
                }
                c == ';' && beginDepth == 0 -> {
                    val stmt = current.toString().trim()
                    if (stmt.isNotEmpty()) statements.add(stmt)
                    current.clear()
                    i++
                }
                else -> {
                    current.append(c); i++
                }
            }
        }
        val tail = current.toString().trim()
        if (tail.isNotEmpty()) statements.add(tail)
        return statements
    }
}
