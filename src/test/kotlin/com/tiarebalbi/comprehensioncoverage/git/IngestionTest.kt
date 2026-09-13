package com.tiarebalbi.comprehensioncoverage.git

import com.tiarebalbi.comprehensioncoverage.scoring.EvidenceType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val T0 = 1700000000L
private const val DAY = 86400L

class IngestionTest {

    // ---------------------------------------------------------------- isAgentMediated

    @Test
    fun `trailer email inside angle brackets is matched case-insensitively`() {
        val config = IngestConfig()
        val c = Commit("x", "Tiare", "me@tiarebalbi.com", T0, listOf("Claude Fable 5 <NoReply@Anthropic.COM>"))
        assertTrue(isAgentMediated(config, c))
    }

    @Test
    fun `commit without agent markers is not agent-mediated`() {
        val config = IngestConfig()
        val c = Commit("y", "Tiare", "me@tiarebalbi.com", T0, emptyList())
        assertFalse(isAgentMediated(config, c))
    }

    @Test
    fun `bot author pattern is a literal substring, not a glob or regex`() {
        // The prototype had a fnmatch bug here once; the contract is
        // substring, so a pattern with a regex metacharacter must only
        // match that literal text, never act as a wildcard.
        val config = IngestConfig(botAuthorPatterns = listOf("a.b"))
        val literalMatch = Commit("1", "a.b", "x@example.com", T0, emptyList())
        val wouldMatchIfRegex = Commit("2", "axb", "x@example.com", T0, emptyList())
        assertTrue(isAgentMediated(config, literalMatch))
        assertFalse(isAgentMediated(config, wouldMatchIfRegex))

        val botCommit = Commit("z", "dependabot[bot]", "x@users.noreply.github.com", T0, emptyList())
        assertTrue(isAgentMediated(IngestConfig(), botCommit))
    }

    // ---------------------------------------------------------------- moduleOf

    @Test
    fun `fnmatch glob matches a nested path with star crossing a slash`() {
        val config = IngestConfig(modules = mapOf("lib" to listOf("lib/router*")))
        assertEquals("lib", moduleOf(config, "lib/router/index.js"))
    }

    @Test
    fun `unmatched path returns null`() {
        val config = IngestConfig(modules = mapOf("core" to listOf("src/core/*")))
        assertNull(moduleOf(config, "src/web/app.txt"))
    }

    // ---------------------------------------------------------------- canonical

    @Test
    fun `identity map resolves email to a canonical person, case-insensitively`() {
        val config = IngestConfig(identity = mapOf("dana@example.com" to "Dana"))
        assertEquals("Dana", canonical(config, "dana", "Dana@Example.com"))
    }

    @Test
    fun `unmapped email falls back to the given name`() {
        assertEquals("Alice", canonical(IngestConfig(), "Alice", "alice@example.com"))
    }

    // ---------------------------------------------------------------- collect

    @Test
    fun `an attestation contributes no churn to surrounding evidence`() {
        val config = IngestConfig(modules = mapOf("core" to listOf("src/core/*")))
        val c = Commit(
            "x", "Alice", "alice@example.com", T0, emptyList(),
            mutableListOf(FileChange(400, 0, "src/core/e.txt")),
        )
        val attestations = listOf(Attestation("dana@example.com", "core", T0 + 10 * DAY))
        val (events, _) = collect(config, listOf(c), T0 + 20 * DAY, attestations)

        val aliceEvent = events.single { it.person == "Alice" }
        assertEquals(0.0, aliceEvent.churnAfter) // Dana's attestation contributed no lines
        val danaEvent = events.single { it.type == EvidenceType.ATTESTED }
        // no identity mapping and no git history for dana@example.com -> raw email
        assertEquals("dana@example.com", danaEvent.person)
        assertEquals("core", danaEvent.module)
        assertEquals(config.satLines, danaEvent.magnitude)
    }

    @Test
    fun `attestation identity resolves via the commit stream, not a separate email identity`() {
        val config = IngestConfig(modules = mapOf("core" to listOf("src/core/*")))
        val c = Commit(
            "x", "Carol", "carol@example.com", T0, emptyList(),
            mutableListOf(FileChange(400, 0, "src/core/e.txt")),
        )
        val attestations = listOf(Attestation("carol@example.com", "core", T0 + 10 * DAY))
        val (events, _) = collect(config, listOf(c), T0 + 20 * DAY, attestations)
        assertEquals(setOf("Carol"), events.map { it.person }.toSet()) // not {"Carol", "carol@example.com"}
    }

    @Test
    fun `a same-timestamp commit and attestation resolve identity via the commit-first tie-break`() {
        // SPEC §2.1: ties at the same timestamp process commits first, so
        // "at or before" identity resolution includes a same-timestamp
        // commit -- this is the only test that would catch the action
        // stream's (timestamp, order) tie-break being dropped or inverted.
        val config = IngestConfig(modules = mapOf("core" to listOf("src/core/*")))
        val c = Commit(
            "x", "Carol", "carol@example.com", T0, emptyList(),
            mutableListOf(FileChange(400, 0, "src/core/e.txt")),
        )
        val attestations = listOf(Attestation("carol@example.com", "core", T0))
        val (events, _) = collect(config, listOf(c), T0, attestations)
        assertEquals(setOf("Carol"), events.map { it.person }.toSet())
    }

    @Test
    fun `attestation with no matching git history falls back to the raw email`() {
        val config = IngestConfig(modules = mapOf("core" to listOf("src/core/*")))
        val attestations = listOf(Attestation("ghost@example.com", "core", T0))
        val (events, _) = collect(config, emptyList(), T0, attestations)
        assertEquals("ghost@example.com", events.single().person)
    }

    @Test
    fun `a commit whose only matched change is binary produces no event but still updates module size`() {
        // numstat's "-" for binary files decodes to 0 add / 0 del (GitSource);
        // collect() only emits an event when lines (add+del) > 0.
        val config = IngestConfig(modules = mapOf("core" to listOf("src/core/*")))
        val c = Commit(
            "x", "Alice", "alice@example.com", T0, emptyList(),
            mutableListOf(FileChange(0, 0, "src/core/blob.bin")),
        )
        val (events, sizes) = collect(config, listOf(c), T0, emptyList())
        assertEquals(emptyList(), events)
        assertEquals(1.0, sizes.getValue("core")) // size floor: add - del == 0, floored at 1.0
    }

    @Test
    fun `module size is floored at 1_0 when net churn is negative`() {
        val config = IngestConfig(modules = mapOf("core" to listOf("src/core/*")))
        val c = Commit(
            "x", "Alice", "alice@example.com", T0, emptyList(),
            mutableListOf(FileChange(10, 500, "src/core/e.txt")), // add - del == -490
        )
        val (_, sizes) = collect(config, listOf(c), T0, emptyList())
        assertEquals(1.0, sizes.getValue("core"))
    }
}
