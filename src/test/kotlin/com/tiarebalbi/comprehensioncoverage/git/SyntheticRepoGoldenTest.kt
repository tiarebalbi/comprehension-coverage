package com.tiarebalbi.comprehensioncoverage.git

import com.tiarebalbi.comprehensioncoverage.scoring.Evidence
import com.tiarebalbi.comprehensioncoverage.scoring.EvidenceType
import java.io.File
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals

private const val T0 = 1700000000L
private const val DAY = 86400L

/**
 * SPEC.md's ordering contract (issue #6): the emitted event sequence for
 * the synthetic repo built by `prototype/test_comprehension.py`'s
 * `build_synthetic_repo` recipe must be element-for-element identical
 * across implementations. This test builds the same repo (same
 * timestamps, authors, trailers, attestation) via the same git-command
 * recipe and asserts the full parsed-commit and evidence-event lists.
 *
 * Expected values (commit count/fields, per-event magnitude/churnAfter,
 * module sizes) were transcribed from a live run of
 * `prototype/comprehension.py`'s `read_commits`/`collect` against this
 * exact repo recipe on 2026-09-13 -- not a committed fixture file (see PR
 * description for why). Commit SHAs are deliberately not asserted here:
 * they're git's content hash, not something this code computes, and
 * pinning them would test git's hashing (plus local commit-signing/config
 * quirks) rather than this port -- same-timestamp sha ordering already has
 * a dedicated test in GitSourceTest.
 */
class SyntheticRepoGoldenTest {

    private fun buildSyntheticRepo(root: File) {
        File(root, "src/core").mkdirs()
        File(root, "src/web").mkdirs()
        File(root, "src/core/engine.txt").writeText("line\n".repeat(400))
        commit(root, "Alice", "alice@example.com", T0, "core: initial engine")
        File(root, "src/web/app.txt").writeText("line\n".repeat(200))
        commit(root, "Bob", "bob@example.com", T0 + 30 * DAY, "web: initial app")
        // agent-mediated rewrite of core, 300 days after alice, via tiare's account
        File(root, "src/core/engine.txt").writeText("new\n".repeat(500))
        commit(
            root, "Tiare", "me@tiarebalbi.com", T0 + 300 * DAY,
            "core: agent rewrite\n\nCo-Authored-By: Claude <noreply@anthropic.com>",
        )
        // bob keeps working web recently by hand
        File(root, "src/web/app.txt").appendText("more\n".repeat(150))
        commit(root, "Bob", "bob@example.com", T0 + 320 * DAY, "web: feature by hand")
        // read_attestations reads straight off disk (not via git history), so
        // no commit is needed for this file to be picked up.
        File(root, ".comprehension").mkdirs()
        File(root, ".comprehension/attestations.yaml").writeText(
            "- email: dana@example.com\n" +
                "  module: web\n" +
                "  timestamp: \"${Instant.ofEpochSecond(T0 + 325 * DAY)}\"\n"
        )
    }

    @Test
    fun `parsed commits match the prototype field for field`() {
        val repo = tempGitRepo()
        try {
            buildSyntheticRepo(repo)
            val commits = readCommits(repo.path)

            assertEquals(4, commits.size)
            assertEquals(listOf("Alice", "Bob", "Tiare", "Bob"), commits.map { it.authorName })
            assertEquals(
                listOf("alice@example.com", "bob@example.com", "me@tiarebalbi.com", "bob@example.com"),
                commits.map { it.authorEmail },
            )
            assertEquals(
                listOf(T0, T0 + 30 * DAY, T0 + 300 * DAY, T0 + 320 * DAY),
                commits.map { it.timestamp },
            )
            assertEquals(
                listOf(emptyList(), emptyList(), listOf("Claude <noreply@anthropic.com>"), emptyList()),
                commits.map { it.trailers },
            )
            assertEquals(
                listOf(
                    listOf(FileChange(400, 0, "src/core/engine.txt")),
                    listOf(FileChange(200, 0, "src/web/app.txt")),
                    listOf(FileChange(500, 400, "src/core/engine.txt")),
                    listOf(FileChange(150, 0, "src/web/app.txt")),
                ),
                commits.map { it.files.toList() },
            )
        } finally {
            repo.deleteRecursively()
        }
    }

    @Test
    fun `derived evidence events match the prototype field for field`() {
        val repo = tempGitRepo()
        try {
            buildSyntheticRepo(repo)
            val config = IngestConfig(
                modules = mapOf("core" to listOf("src/core/*"), "web" to listOf("src/web/*")),
                identity = mapOf("dana@example.com" to "Dana"),
            )
            val commits = readCommits(repo.path)
            val attestations = readAttestations(repo.path, config)
            val asOf = T0 + 330 * DAY
            val (events, sizes) = collect(config, commits, asOf, attestations)

            assertEquals(mapOf("core" to 500.0, "web" to 350.0), sizes)
            assertEquals(
                listOf(
                    Evidence("Alice", "core", EvidenceType.AUTHORED, T0, 400.0, churnAfter = 900.0),
                    Evidence("Bob", "web", EvidenceType.AUTHORED, T0 + 30 * DAY, 200.0, churnAfter = 0.0),
                    Evidence("Tiare", "core", EvidenceType.AGENT_MEDIATED, T0 + 300 * DAY, 900.0, churnAfter = 0.0),
                    Evidence("Bob", "web", EvidenceType.AUTHORED, T0 + 320 * DAY, 150.0, churnAfter = 0.0),
                    Evidence("Dana", "web", EvidenceType.ATTESTED, T0 + 325 * DAY, 400.0, churnAfter = 0.0),
                ),
                events,
            )
        } finally {
            repo.deleteRecursively()
        }
    }
}
