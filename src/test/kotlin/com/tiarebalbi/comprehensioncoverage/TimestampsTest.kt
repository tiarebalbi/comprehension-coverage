package com.tiarebalbi.comprehensioncoverage

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class TimestampsTest {
    @Test
    fun `tz-naive instant is rejected`() {
        // C6 / issue #3: no 'Z', no offset -- must not be silently resolved
        // against the invoking machine's local timezone.
        assertFailsWith<IllegalArgumentException> { parseAsOf("2026-09-13T00:00:00") }
    }

    @Test
    fun `Z suffix and explicit zero offset parse to the same instant`() {
        assertEquals(1789257600L, parseAsOf("2026-09-13T00:00:00Z"))
        assertEquals(1789257600L, parseAsOf("2026-09-13T00:00:00+00:00"))
    }
}
