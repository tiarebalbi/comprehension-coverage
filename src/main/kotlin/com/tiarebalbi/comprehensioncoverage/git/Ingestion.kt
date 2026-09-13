package com.tiarebalbi.comprehensioncoverage.git

import com.tiarebalbi.comprehensioncoverage.scoring.Evidence
import com.tiarebalbi.comprehensioncoverage.scoring.EvidenceType

/** Identity merge (SPEC §2): config maps emails to a canonical person; an unmapped email keeps its git author name. */
fun canonical(config: IngestConfig, name: String, email: String): String =
    config.identity[email.lowercase()] ?: name

/**
 * Extracts the email from a `Co-Authored-By` trailer value the same way
 * the prototype does: `t[t.find("<")+1 : t.find(">")]`. Deliberately
 * replicates Python's slicing behavior when `<` is present but `>` is
 * not -- `find` returns -1 for the missing `>`, so the slice's end becomes
 * `t[-1]` (up to, not including, the trailer's *last* character) rather
 * than an error. The whole trailer text is used verbatim (lowercased)
 * when there's no `<` at all.
 */
private fun trailerEmail(trailer: String): String {
    val lt = trailer.indexOf('<')
    if (lt < 0) return trailer.lowercase()
    val start = lt + 1
    val gt = trailer.indexOf('>')
    val end = if (gt >= 0) gt else trailer.length - 1
    return if (start >= end) "" else trailer.substring(start, end).lowercase()
}

/**
 * AUTHORED vs AGENT_MEDIATED (SPEC §2 marker rules): a `Co-Authored-By`
 * trailer whose email matches the configured trailer-email list
 * (case-insensitively), OR an author name/email containing a configured
 * bot pattern as a literal lowercase substring -- not a glob, not a regex
 * (the prototype had a fnmatch bug here once; the contract is substring).
 */
fun isAgentMediated(config: IngestConfig, commit: Commit): Boolean {
    val trailerEmails = config.agentTrailerEmails.map { it.lowercase() }
    if (commit.trailers.any { trailerEmail(it) in trailerEmails }) return true
    val hay = "${commit.authorName} ${commit.authorEmail}".lowercase()
    return config.botAuthorPatterns.any { hay.contains(it.lowercase()) }
}

/** Matches `path` against every module's globs (fnmatch semantics), first match wins in `modules`' iteration order. */
fun moduleOf(config: IngestConfig, path: String): String? {
    for ((mod, globs) in config.modules) {
        if (globs.any { fnmatch(path, it) }) return mod
    }
    return null
}

/** (email, module, timestamp) -- email lowercased, not yet resolved to a person (see [collect]). */
data class Attestation(val email: String, val module: String, val timestamp: Long)

private class PendingEvidence(
    val person: String,
    val module: String,
    val type: EvidenceType,
    val timestamp: Long,
    val magnitude: Double,
    val totalAt: Double,
    val ownAt: Double,
)

private sealed class Action(val timestamp: Long, val order: Int)
private class CommitAction(val commit: Commit) : Action(commit.timestamp, 0)
private class AttestationAction(val attestation: Attestation) : Action(attestation.timestamp, 1)

/**
 * Builds evidence events and per-module sizes (SPEC §2, §2.1), mirroring
 * the prototype's `collect`. Commits and attestations are merged into one
 * chronological action stream -- ties at the same timestamp process
 * commits first -- so each event's total/own snapshot (and therefore its
 * churn-based decay) reflects real churn up to that instant, and so an
 * attestation's identity resolves against the commit stream as of its own
 * timestamp rather than the full history. `churnAfter` is filled in once,
 * after the stream is fully walked, from the final totals: lines changed
 * in the module by anyone other than the event's person, between the
 * event and `asOf`. An attestation itself contributes no lines -- it's a
 * self-report, not a code change -- and its magnitude is fixed at
 * `config.satLines` (fully saturated).
 *
 * Relies on [commits] already being sorted (timestamp, sha) ascending
 * (readCommits' contract) and on `sortWith` being a stable sort: ties
 * within the same action kind keep their input order, which for commits
 * is that pre-established total order.
 */
fun collect(
    config: IngestConfig,
    commits: List<Commit>,
    asOf: Long,
    attestations: List<Attestation> = emptyList(),
): Pair<List<Evidence>, Map<String, Double>> {
    val moduleSize = HashMap<String, Double>()
    val total = HashMap<String, Double>()
    val own = HashMap<Pair<String, String>, Double>()
    val lastNameForEmail = HashMap<String, String>()
    val pending = mutableListOf<PendingEvidence>()

    val actions = mutableListOf<Action>()
    for (c in commits) if (c.timestamp <= asOf) actions.add(CommitAction(c))
    for (a in attestations) if (a.timestamp <= asOf) actions.add(AttestationAction(a))
    actions.sortWith(compareBy({ it.timestamp }, { it.order }))

    for (action in actions) {
        when (action) {
            is CommitAction -> {
                val c = action.commit
                lastNameForEmail[c.authorEmail] = c.authorName
                val person = canonical(config, c.authorName, c.authorEmail)
                val type = if (isAgentMediated(config, c)) EvidenceType.AGENT_MEDIATED else EvidenceType.AUTHORED
                val perModule = LinkedHashMap<String, Double>()
                for (f in c.files) {
                    val mod = moduleOf(config, f.path) ?: continue
                    perModule[mod] = (perModule[mod] ?: 0.0) + f.added + f.deleted
                    moduleSize[mod] = moduleSize.getOrDefault(mod, 0.0) + (f.added - f.deleted)
                }
                // sorted(per_mod.items()) in the prototype; module names are
                // ASCII in practice, but note for A4: this is UTF-16
                // code-unit order, not Python's code-point order.
                for (mod in perModule.keys.sorted()) {
                    val lines = perModule.getValue(mod)
                    if (lines <= 0) continue
                    pending.add(
                        PendingEvidence(
                            person, mod, type, c.timestamp, lines,
                            totalAt = total.getOrDefault(mod, 0.0),
                            ownAt = own.getOrDefault(mod to person, 0.0),
                        )
                    )
                    total[mod] = total.getOrDefault(mod, 0.0) + lines
                    own[mod to person] = own.getOrDefault(mod to person, 0.0) + lines
                }
            }
            is AttestationAction -> {
                val a = action.attestation
                val fallbackName = lastNameForEmail[a.email] ?: a.email
                val person = canonical(config, fallbackName, a.email)
                pending.add(
                    PendingEvidence(
                        person, a.module, EvidenceType.ATTESTED, a.timestamp, config.satLines,
                        totalAt = total.getOrDefault(a.module, 0.0),
                        ownAt = own.getOrDefault(a.module to person, 0.0),
                    )
                )
            }
        }
    }

    val events = pending.map { e ->
        val churnAfter = (total.getOrDefault(e.module, 0.0) - e.totalAt) -
            (own.getOrDefault(e.module to e.person, 0.0) - e.ownAt)
        Evidence(e.person, e.module, e.type, e.timestamp, e.magnitude, churnAfter)
    }
    val sizes = moduleSize.mapValues { (_, s) -> maxOf(1.0, s) }
    return events to sizes
}
