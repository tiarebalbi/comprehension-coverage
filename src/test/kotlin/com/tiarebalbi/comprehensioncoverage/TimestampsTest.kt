package com.tiarebalbi.comprehensioncoverage

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class TimestampsTest {
    @Test
    fun `tz-naive instant is rejected with the C6 offset message`() {
        // C6 / issue #3: no 'Z', no offset -- must not be silently resolved
        // against the invoking machine's local timezone.
        val error = assertFailsWith<IllegalArgumentException> { parseAsOf("2026-09-13T00:00:00") }
        assertTrue(error.message!!.contains("UTC offset"), error.message)
    }

    @Test
    fun `Z suffix and explicit zero offset parse to the same instant`() {
        assertEquals(1789257600L, parseAsOf("2026-09-13T00:00:00Z"))
        assertEquals(1789257600L, parseAsOf("2026-09-13T00:00:00+00:00"))
    }

    @Test
    fun `genuinely malformed input is reported as unparseable, not as a missing offset`() {
        // Review note from #23: a garbled string isn't just "missing an
        // offset" -- the two failure modes should read differently.
        val error = assertFailsWith<IllegalArgumentException> { parseAsOf("not-a-timestamp") }
        assertTrue(error.message!!.contains("unparseable"), error.message)
        assertTrue(!error.message!!.contains("UTC offset"), error.message)
    }
}
