package com.tiarebalbi.comprehensioncoverage.report

import com.tiarebalbi.comprehensioncoverage.scoring.ModuleReport
import com.tiarebalbi.comprehensioncoverage.scoring.ModuleStatus
import kotlin.test.Test
import kotlin.test.assertEquals

class JsonTest {

    @Test
    fun `keys are sorted regardless of insertion order`() {
        val obj = JsonValue.Obj(linkedMapOf("b" to JsonValue.IntNum(2), "a" to JsonValue.IntNum(1)))
        assertEquals("{\n  \"a\": 1,\n  \"b\": 2\n}", dumpJson(obj))
    }

    @Test
    fun `nesting increases indent by 2 spaces per level and has no trailing newline`() {
        val obj = JsonValue.Obj(
            mapOf("outer" to JsonValue.Obj(mapOf("inner" to JsonValue.Str("x")))),
        )
        val expected = "{\n  \"outer\": {\n    \"inner\": \"x\"\n  }\n}"
        assertEquals(expected, dumpJson(obj))
        assertEquals('}', dumpJson(obj).last())
    }

    @Test
    fun `empty object renders inline`() {
        assertEquals("{}", dumpJson(JsonValue.Obj(emptyMap())))
    }

    @Test
    fun `integers never carry a trailing decimal point, unlike doubles`() {
        // as_of must serialize as 1728512000, never 1728512000.0.
        val intField = dumpJson(JsonValue.Obj(mapOf("a" to JsonValue.IntNum(1728512000L))))
        assertEquals("1728512000", intField.substringAfter(": ").substringBefore("\n"))

        val doubleField = dumpJson(JsonValue.Obj(mapOf("a" to JsonValue.Num(1.0))))
        assertEquals("1.0", doubleField.substringAfter(": ").substringBefore("\n"))
    }

    @Test
    fun `string escaping matches Python's default ensure_ascii behavior`() {
        val obj = JsonValue.Obj(mapOf("k" to JsonValue.Str("a\"b\\c\ndé")))
        // quote and backslash escaped, newline escaped, non-ASCII 'é' (U+00E9) as lowercase é
        assertEquals("{\n  \"k\": \"a\\\"b\\\\c\\nd\\u00e9\"\n}", dumpJson(obj))
    }

    @Test
    fun `publicJson omits people, matching main()'s public dict (C5)`() {
        val modules = linkedMapOf(
            "core" to ModuleReport(ModuleStatus.DARK, 0, listOf("Alice" to 0.1)),
        )
        val json = dumpJson(publicJson(1728512000L, modules))
        assertEquals(
            "{\n  \"as_of\": 1728512000,\n  \"modules\": {\n    \"core\": {\n      \"comprehenders\": 0,\n      \"status\": \"DARK\"\n    }\n  }\n}",
            json,
        )
    }
}
