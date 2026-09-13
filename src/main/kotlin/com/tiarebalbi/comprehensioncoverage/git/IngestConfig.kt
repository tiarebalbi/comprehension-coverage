package com.tiarebalbi.comprehensioncoverage.git

/**
 * Configuration consumed by git ingestion (SPEC §2, §2.1). Defaults match
 * the prototype's `DEFAULTS` dict. Config loading (JSON, defaults-merge,
 * validation) is out of scope for this engine -- see #7.
 *
 * `modules` iteration order is load-bearing: [moduleOf] returns the first
 * glob match, so a path matching two modules' globs resolves by `modules`'
 * insertion order (mirrors Python dict iteration order in the prototype's
 * `module_of`). `mapOf(...)` preserves insertion order today; a future
 * JSON-backed loader (#7) must keep that guarantee (e.g. a
 * `LinkedHashMap` built from the parsed key order), not silently switch to
 * an unordered map.
 */
data class IngestConfig(
    val ref: String = "HEAD",
    val agentTrailerEmails: List<String> = listOf(
        "noreply@anthropic.com", "copilot@github.com", "cursoragent@cursor.com",
    ),
    val botAuthorPatterns: List<String> = listOf("[bot]"),
    val identity: Map<String, String> = emptyMap(),
    val modules: Map<String, List<String>> = emptyMap(),
    val satLines: Double = 400.0,
)
