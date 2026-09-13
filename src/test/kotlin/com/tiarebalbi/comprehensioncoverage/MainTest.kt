package com.tiarebalbi.comprehensioncoverage

import kotlin.test.Test
import kotlin.test.assertEquals

class MainTest {
    @Test
    fun `scaffold identity is stable`() {
        assertEquals("comprehension-coverage kotlin scaffold", scaffoldIdentity())
    }
}
