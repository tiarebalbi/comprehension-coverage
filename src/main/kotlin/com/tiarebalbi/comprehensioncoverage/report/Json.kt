package com.tiarebalbi.comprehensioncoverage.report

import com.tiarebalbi.comprehensioncoverage.scoring.ModuleReport

/** A minimal JSON value tree -- only the shapes this project's output actually needs (no arrays, bools, or null). */
sealed class JsonValue {
    data class Obj(val entries: Map<String, JsonValue>) : JsonValue()
    data class Str(val value: String) : JsonValue()
    data class Num(val value: Double) : JsonValue()
    data class IntNum(val value: Long) : JsonValue()
}

/**
 * Hand-rolled writer byte-matching Python's `json.dump(obj, indent=2,
 * sort_keys=True)` (CLAUDE.md: "JSON: read with a library, write by
 * hand" -- no off-the-shelf pretty-printer reproduces Python's exact
 * separator conventions or float repr, so both are ported here rather
 * than trusted to a library default). No trailing newline, matching
 * `json.dump` writing to a file handle directly.
 */
fun dumpJson(value: JsonValue): String {
    val sb = StringBuilder()
    writeValue(sb, value, 0)
    return sb.toString()
}

private fun writeValue(sb: StringBuilder, value: JsonValue, depth: Int) {
    when (value) {
        is JsonValue.Obj -> writeObject(sb, value, depth)
        is JsonValue.Str -> sb.append(jsonStringLiteral(value.value))
        is JsonValue.Num -> sb.append(pythonRepr(value.value))
        is JsonValue.IntNum -> sb.append(value.value)
    }
}

private fun writeObject(sb: StringBuilder, obj: JsonValue.Obj, depth: Int) {
    if (obj.entries.isEmpty()) {
        sb.append("{}")
        return
    }
    sb.append("{\n")
    val childIndent = "  ".repeat(depth + 1)
    // sort_keys=True in Python sorts every nested object independently, by
    // key string -- not by any tuple this project's keys might conceptually
    // represent (see PLAN-REVIEW.md's "scores" key note).
    val sortedEntries = obj.entries.entries.sortedBy { it.key }
    sortedEntries.forEachIndexed { i, (key, v) ->
        sb.append(childIndent).append(jsonStringLiteral(key)).append(": ")
        writeValue(sb, v, depth + 1)
        if (i != sortedEntries.lastIndex) sb.append(",")
        sb.append("\n")
    }
    sb.append("  ".repeat(depth)).append("}")
}

/**
 * Mirrors Python's default `json.dumps` string escaping
 * (`ensure_ascii=True`, the prototype's default): quotes, backslash and
 * control-character escapes, and non-ASCII characters as lowercase
 * `\uXXXX`. Not exercised by the golden fixture (all names are ASCII) but
 * implemented for correctness rather than narrowed to what the fixture
 * happens to cover.
 */
private fun jsonStringLiteral(s: String): String {
    val sb = StringBuilder("\"")
    for (c in s) {
        when {
            c == '"' -> sb.append("\\\"")
            c == '\\' -> sb.append("\\\\")
            c == '\n' -> sb.append("\\n")
            c == '\r' -> sb.append("\\r")
            c == '\t' -> sb.append("\\t")
            c == '\b' -> sb.append("\\b")
            c.code == 0x0C -> sb.append("\\f")
            c.code < 0x20 || c.code > 0x7E -> sb.append("\\u%04x".format(c.code))
            else -> sb.append(c)
        }
    }
    sb.append("\"")
    return sb.toString()
}

/**
 * Mirrors `main()`'s `public` dict plus its outer `{"as_of":...,
 * "modules":...}` wrapper -- `people` is never serialized here (C5: module
 * aggregates are public, individual scores are not the CLI's default
 * output). Distinct from the golden fixture's JSON shape, which also
 * carries a top-level `scores` section -- that shape is test-only,
 * produced by `test_comprehension.py`'s own fixture writer, never by
 * `main()`'s real `--json` output (see GoldenFixtureJsonTest's KDoc).
 */
fun publicJson(asOf: Long, modules: Map<String, ModuleReport>): JsonValue.Obj = JsonValue.Obj(
    mapOf(
        "as_of" to JsonValue.IntNum(asOf),
        "modules" to JsonValue.Obj(
            modules.mapValues { (_, report) ->
                JsonValue.Obj(
                    mapOf(
                        "comprehenders" to JsonValue.IntNum(report.comprehenders.toLong()),
                        "status" to JsonValue.Str(report.status.name),
                    ),
                )
            },
        ),
    ),
)
