package com.tiarebalbi.comprehensioncoverage.git

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

private const val T0 = 1700000000L
private const val DAY = 86400L

class GitSourceTest {

    @Test
    fun `history is scoped to the given ref, not every fetched ref`() {
        // SPEC §2.1: a prior implementation used `git log --all`, making the
        // result depend on which branches happen to be present locally.
        val repo = tempGitRepo()
        try {
            File(repo, "src").mkdirs()
            File(repo, "src/a.txt").writeText("a\n")
            commit(repo, "Alice", "alice@example.com", T0, "on main")
            git(repo, "checkout", "-q", "-b", "side")
            File(repo, "src/b.txt").writeText("b\n")
            commit(repo, "Bob", "bob@example.com", T0 + DAY, "on side")
            git(repo, "checkout", "-q", "main")

            val onMain = IngestConfig(ref = "main")
            val onSide = IngestConfig(ref = "side")
            assertEquals(listOf("Alice"), readCommits(repo.path, onMain.ref).map { it.authorName })
            assertEquals(listOf("Alice", "Bob"), readCommits(repo.path, onSide.ref).map { it.authorName })
        } finally {
            repo.deleteRecursively()
        }
    }

    @Test
    fun `same-timestamp commits sort by sha ascending`() {
        // SPEC §2.1: a total order fixed in code, not delegated to git's own
        // same-timestamp ordering, which isn't guaranteed stable across
        // versions.
        val repo = tempGitRepo()
        try {
            File(repo, "src").mkdirs()
            File(repo, "src/a.txt").writeText("a\n")
            commit(repo, "Alice", "alice@example.com", T0, "first")
            File(repo, "src/a.txt").appendText("a2\n")
            commit(repo, "Alice", "alice@example.com", T0, "second, same timestamp")

            val commits = readCommits(repo.path, "main")
            assertEquals(listOf(T0, T0), commits.map { it.timestamp })
            val shas = commits.map { it.sha }
            assertEquals(shas.sorted(), shas, "same-timestamp commits must sort by sha")
        } finally {
            repo.deleteRecursively()
        }
    }

    @Test
    fun `trailer parsing handles multiple trailers, case, and a commit with no trailers`() {
        val repo = tempGitRepo()
        try {
            File(repo, "src").mkdirs()
            File(repo, "src/a.txt").writeText("a\n")
            commit(repo, "Alice", "alice@example.com", T0, "no trailer at all")
            File(repo, "src/a.txt").appendText("a2\n")
            commit(
                repo, "Tiare", "me@tiarebalbi.com", T0 + DAY,
                "agent rewrite\n\nCo-Authored-By: Claude <NoReply@Anthropic.COM>\n" +
                    "Co-Authored-By: Someone Else <someone@example.com>",
            )

            val commits = readCommits(repo.path, "main")
            assertEquals(emptyList(), commits[0].trailers)
            assertEquals(
                listOf("Claude <NoReply@Anthropic.COM>", "Someone Else <someone@example.com>"),
                commits[1].trailers,
            )
        } finally {
            repo.deleteRecursively()
        }
    }

    @Test
    fun `binary files report as zero lines changed, matching numstat's dash`() {
        val repo = tempGitRepo()
        try {
            File(repo, "src").mkdirs()
            File(repo, "src/blob.bin").writeBytes(byteArrayOf(0, 1, 2, 0, 3))
            commit(repo, "Alice", "alice@example.com", T0, "add binary")

            val files = readCommits(repo.path, "main").single().files
            assertEquals(FileChange(0, 0, "src/blob.bin"), files.single())
        } finally {
            repo.deleteRecursively()
        }
    }

    @Test
    fun `malformed UTF-8 bytes decode via replacement, not an exception`() {
        // Mirrors the prototype's `errors="replace"`. Exercised directly
        // against the decoder rather than through a real git commit --
        // forcing invalid UTF-8 through ProcessBuilder's environment (which
        // round-trips via the JVM's platform charset) isn't reliably
        // reproducible across platforms.
        val bytes = byteArrayOf('A'.code.toByte(), 0xFF.toByte(), 'B'.code.toByte())
        assertEquals("A�B", decodeReplacing(bytes))
    }
}
