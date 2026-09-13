package com.tiarebalbi.comprehensioncoverage.git

import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

data class FileChange(val added: Int, val deleted: Int, val path: String)

/**
 * A parsed commit (SPEC §2.1). Mirrors the prototype's `Commit` dataclass;
 * `files` is mutated in place while parsing `git log --numstat` output,
 * same as the prototype's `list.append`.
 */
data class Commit(
    val sha: String,
    val authorName: String,
    val authorEmail: String,
    val timestamp: Long,
    val trailers: List<String>,
    val files: MutableList<FileChange> = mutableListOf(),
)

/**
 * Decodes git's raw stdout bytes as UTF-8, replacing malformed sequences
 * with U+FFFD instead of throwing -- mirrors the prototype's
 * `subprocess.run(..., errors="replace")`. Git repositories are not
 * required to contain valid UTF-8 (author names, messages, and paths can
 * carry arbitrary bytes), so ingestion must not crash on them. `internal`
 * so it can be unit-tested directly against a crafted invalid byte
 * sequence, rather than trying to force one through a real git commit.
 */
internal fun decodeReplacing(bytes: ByteArray): String {
    val decoder = StandardCharsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPLACE)
        .onUnmappableCharacter(CodingErrorAction.REPLACE)
    return decoder.decode(ByteBuffer.wrap(bytes)).toString()
}

private const val COMMIT_MARKER = "@@C@@"
private const val FILES_MARKER = "@@F@@"
private const val PRETTY_FORMAT =
    "$COMMIT_MARKER%n%H%n%an%n%ae%n%at%n%(trailers:key=Co-Authored-By,valueonly)%n$FILES_MARKER"

/**
 * Reads the full history reachable from `ref` (SPEC §2.1 -- never `--all`:
 * determinism must not depend on which other refs a clone happens to have
 * fetched), with numstat, mirroring the prototype's `read_commits`.
 * Returned oldest-first with ties on timestamp broken by sha -- a total
 * order fixed here in code, not delegated to git's own same-timestamp
 * ordering (SPEC §2.1).
 */
fun readCommits(repo: String, ref: String = "HEAD"): List<Commit> {
    val process = ProcessBuilder(
        "git", "-C", repo, "log", ref, "--numstat", "--no-renames",
        "--pretty=format:$PRETTY_FORMAT",
    ).redirectError(ProcessBuilder.Redirect.INHERIT).start()
    val stdoutBytes = process.inputStream.readBytes()
    val exit = process.waitFor()
    check(exit == 0) { "git log $ref exited $exit" }
    val out = decodeReplacing(stdoutBytes)

    val lines = out.split("\n")
    val commits = mutableListOf<Commit>()
    var cur: Commit? = null
    var i = 0
    while (i < lines.size) {
        val line = lines[i]
        if (line == COMMIT_MARKER) {
            val sha = lines[i + 1]
            val name = lines[i + 2]
            val email = lines[i + 3].lowercase()
            val timestamp = lines[i + 4].toLong()
            var j = i + 5
            val trailers = mutableListOf<String>()
            while (j < lines.size && lines[j] != FILES_MARKER) {
                if (lines[j].isNotBlank()) trailers.add(lines[j].trim())
                j++
            }
            cur = Commit(sha, name, email, timestamp, trailers)
            commits.add(cur)
            i = j + 1 // skip past the @@F@@ marker itself
            continue
        } else if (line.isNotBlank() && cur != null) {
            val parts = line.split("\t")
            if (parts.size == 3) {
                val added = if (parts[0] == "-") 0 else parts[0].toInt()
                val deleted = if (parts[1] == "-") 0 else parts[1].toInt()
                cur.files.add(FileChange(added, deleted, parts[2]))
            }
        }
        i++
    }
    return commits.sortedWith(compareBy({ it.timestamp }, { it.sha }))
}

/** The timestamp of `ref`'s most recent commit -- mirrors `main()`'s fallback when `--as-of` is omitted. */
fun lastCommitTimestamp(repo: String, ref: String): Long {
    val process = ProcessBuilder("git", "-C", repo, "log", ref, "-1", "--format=%at")
        .redirectError(ProcessBuilder.Redirect.INHERIT).start()
    val stdoutBytes = process.inputStream.readBytes()
    val exit = process.waitFor()
    check(exit == 0) { "git log $ref -1 exited $exit" }
    return decodeReplacing(stdoutBytes).trim().toLong()
}
