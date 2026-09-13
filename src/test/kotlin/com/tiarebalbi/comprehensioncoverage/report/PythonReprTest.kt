package com.tiarebalbi.comprehensioncoverage.report

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Every expected string below was verified against a live
 * `python3 -c 'import json; print(json.dumps(...))'` run on 2026-09-13 (see
 * PR description for the exact transcript) -- not derived from reading
 * CPython's source, so a misremembered threshold or exponent format would
 * have been caught rather than propagated.
 */
class PythonReprTest {

    @Test
    fun `issue's required boundary values`() {
        assertEquals("1.0", pythonRepr(1.0))
        assertEquals("0.05", pythonRepr(0.05))
        assertEquals("0.0023", pythonRepr(0.0023))
        assertEquals("0.0001", pythonRepr(0.0001)) // 1e-4: stays fixed (Python's lower threshold is exclusive)
        assertEquals("1e-05", pythonRepr(0.00001)) // just below 1e-4: switches to scientific
        assertEquals("1e+16", pythonRepr(1e16)) // upper threshold: switches to scientific
    }

    @Test
    fun `values just inside Java's own scientific-notation thresholds, which differ from Python's`() {
        // Java's Double.toString switches to scientific below 1e-3 and at/above
        // 1e7 -- both inside Python's [1e-4, 1e16) fixed-notation band, so these
        // exercise the reformatting, not just a passthrough of Java's own choice.
        assertEquals("0.0009", pythonRepr(0.0009))
        assertEquals("9999999999999998.0", pythonRepr(9999999999999998.0)) // just under 1e16: stays fixed
        assertEquals("1000000000000000.0", pythonRepr(1e15))
    }

    @Test
    fun `negative values`() {
        assertEquals("-1e-05", pythonRepr(-0.00001))
        assertEquals("-1e+16", pythonRepr(-1e16))
        assertEquals("-0.05", pythonRepr(-0.05))
    }

    @Test
    fun `zero preserves sign, matching Python's repr(-0_0)`() {
        assertEquals("0.0", pythonRepr(0.0))
        assertEquals("-0.0", pythonRepr(-0.0))
    }

    @Test
    fun `multi-digit mantissas in scientific notation keep their extra digits`() {
        assertEquals("1.5e+20", pythonRepr(1.5e20))
        assertEquals("2.5e-10", pythonRepr(2.5e-10))
        assertEquals("5e-05", pythonRepr(5e-05))
    }

    @Test
    fun `trailing zero is dropped, matching round(0_26727002, 6)'s json serialization`() {
        // PLAN-REVIEW.md's cited case: json.dumps(round(0.26727002, 6)) -> "0.26727", not "0.267270".
        assertEquals("0.26727", pythonRepr(0.26727))
    }

    @Test
    fun `a value already at the golden fixture's scale round-trips unchanged`() {
        assertEquals("0.596162", pythonRepr(0.596162))
        assertEquals("0.288667", pythonRepr(0.288667))
    }
}
