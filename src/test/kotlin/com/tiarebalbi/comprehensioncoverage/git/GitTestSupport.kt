package com.tiarebalbi.comprehensioncoverage.git

import java.io.File
import java.nio.file.Files

/** Runs a git command against `repo`, mirroring the prototype test suite's `git()` helper. */
internal fun git(repo: File, vararg args: String, env: Map<String, String> = emptyMap()) {
    val pb = ProcessBuilder(listOf("git", "-C", repo.path) + args.toList())
    pb.environment().putAll(env)
    pb.redirectErrorStream(true)
    val process = pb.start()
    val output = process.inputStream.readBytes()
    val exit = process.waitFor()
    check(exit == 0) { "git ${args.joinToString(" ")} failed: ${String(output)}" }
}

/** Stages everything and commits with a fixed author/committer date, mirroring the prototype's `commit()` helper. */
internal fun commit(repo: File, name: String, email: String, ts: Long, message: String) {
    val env = mapOf(
        "GIT_AUTHOR_NAME" to name, "GIT_AUTHOR_EMAIL" to email,
        "GIT_COMMITTER_NAME" to name, "GIT_COMMITTER_EMAIL" to email,
        "GIT_AUTHOR_DATE" to "$ts +0000", "GIT_COMMITTER_DATE" to "$ts +0000",
    )
    git(repo, "add", "-A")
    git(repo, "commit", "-q", "--allow-empty", "-m", message, env = env)
}

internal fun tempGitRepo(): File {
    val dir = Files.createTempDirectory("comprehension-git-test").toFile()
    git(dir, "init", "-q", "-b", "main")
    return dir
}
