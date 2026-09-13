package com.tiarebalbi.comprehensioncoverage.git

import java.util.concurrent.ConcurrentHashMap
import java.util.regex.Pattern

/**
 * Translates a Python `fnmatch`-style glob into a [Pattern], mirroring
 * `fnmatch.fnmatch`'s semantics: case-sensitive (POSIX `normcase` is a
 * no-op, and both this project's repos and CI run on case-sensitive
 * filesystems -- SPEC §2.1 / C6), and matched against the *whole* path as
 * one flat character sequence -- unlike gitignore-style globs, `*` here
 * matches across `/`, so `lib/router*` matches `lib/router/index.js`.
 *
 * Supports `*`, `?`, and basic `[...]`/`[!...]` character classes (single
 * chars and simple ranges, e.g. `[0-9]`). Does not replicate
 * `fnmatch.translate`'s adjacent-range-merging edge case for overlapping
 * `[a-c1-3]`-style classes -- irrelevant to this project's module globs,
 * which are plain path prefixes/suffixes with `*`.
 */
private val patternCache = ConcurrentHashMap<String, Pattern>()

private fun translate(glob: String): Pattern = patternCache.getOrPut(glob) {
    val sb = StringBuilder()
    var i = 0
    val n = glob.length
    while (i < n) {
        val c = glob[i]
        i++
        when (c) {
            '*' -> sb.append(".*")
            '?' -> sb.append(".")
            '[' -> {
                var j = i
                if (j < n && glob[j] == '!') j++
                if (j < n && glob[j] == ']') j++
                while (j < n && glob[j] != ']') j++
                if (j >= n) {
                    sb.append("\\[")
                } else {
                    // Escape class-content specials FIRST, then decide the
                    // negation/leading-bracket prefix on the escaped
                    // string -- doing it in the other order double-escapes
                    // a prepended '^' or '\'.
                    var stuff = glob.substring(i, j)
                        .replace("\\", "\\\\")
                        .replace("&", "\\&")
                    i = j + 1
                    when {
                        stuff.isEmpty() -> sb.append("(?!)")
                        stuff == "!" -> sb.append(".")
                        else -> {
                            if (stuff.startsWith("!")) {
                                stuff = "^" + stuff.substring(1)
                            } else if (stuff.startsWith("^") || stuff.startsWith("[")) {
                                stuff = "\\$stuff"
                            }
                            sb.append('[').append(stuff).append(']')
                        }
                    }
                }
            }
            else -> sb.append(Pattern.quote(c.toString()))
        }
    }
    Pattern.compile(sb.toString(), Pattern.DOTALL)
}

/** Full-string match, mirroring `fnmatch.fnmatch(name, pattern)`. */
fun fnmatch(name: String, pattern: String): Boolean = translate(pattern).matcher(name).matches()
