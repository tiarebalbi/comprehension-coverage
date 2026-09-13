package com.tiarebalbi.comprehensioncoverage.git

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FnmatchTest {

    @Test
    fun `star crosses path separators, unlike gitignore-style globs`() {
        assertTrue(fnmatch("lib/router/index.js", "lib/router*"))
        assertTrue(fnmatch("lib/router.js", "lib/router*"))
        assertFalse(fnmatch("lib/other/index.js", "lib/router*"))
    }

    @Test
    fun `matching is case-sensitive`() {
        assertFalse(fnmatch("SRC/CORE/foo.py", "src/core/*"))
    }

    @Test
    fun `question mark matches exactly one character`() {
        assertTrue(fnmatch("ab", "a?"))
        assertFalse(fnmatch("abc", "a?"))
    }

    @Test
    fun `bracket expression matches a character range`() {
        assertTrue(fnmatch("a1.py", "a[0-9].py"))
        assertFalse(fnmatch("ab.py", "a[0-9].py"))
    }

    @Test
    fun `negated bracket expression excludes the range`() {
        assertTrue(fnmatch("ab.py", "a[!0-9].py"))
        assertFalse(fnmatch("a1.py", "a[!0-9].py"))
    }
}
