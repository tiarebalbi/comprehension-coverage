package com.tiarebalbi.comprehensioncoverage.config

import com.tiarebalbi.comprehensioncoverage.git.IngestConfig
import com.tiarebalbi.comprehensioncoverage.scoring.EvidenceType
import com.tiarebalbi.comprehensioncoverage.scoring.ScoringConfig
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.io.File

/**
 * Mirrors the prototype's `DEFAULTS` dict exactly (SPEC §4, evidence
 * markers, module defaults). Values are wrapped in [JsonPrimitive]
 * explicitly rather than via `put(key, 1.0)`/`add("x")` convenience
 * overloads: `JsonObjectBuilder.put`/`JsonArrayBuilder.add` are genuine
 * members taking a plain `JsonElement`, which shadow those overloads for
 * unqualified calls inside the builder lambda (a known kotlinx.serialization
 * gotcha) -- explicit `JsonPrimitive(...)` sidesteps the ambiguity entirely.
 */
private fun defaultConfigJson(): JsonObject = buildJsonObject {
    put("h_churn", JsonPrimitive(1.0))
    put("h_wall_days", JsonPrimitive(180.0))
    put("quiescence_stretch", JsonPrimitive(2.0))
    put("ref", JsonPrimitive("HEAD"))
    put("churn_cap", JsonPrimitive(4.0))
    put("sat_lines", JsonPrimitive(400.0))
    put("theta_person", JsonPrimitive(0.5))
    put("theta_covered", JsonPrimitive(2))
    putJsonObject("weights") {
        put("AUTHORED", JsonPrimitive(1.0))
        put("AGENT_MEDIATED", JsonPrimitive(0.3))
        put("ATTESTED", JsonPrimitive(0.6))
    }
    putJsonArray("agent_trailer_emails") {
        add(JsonPrimitive("noreply@anthropic.com"))
        add(JsonPrimitive("copilot@github.com"))
        add(JsonPrimitive("cursoragent@cursor.com"))
    }
    putJsonArray("bot_author_patterns") { add(JsonPrimitive("[bot]")) }
    putJsonObject("identity") {}
    putJsonArray("departed") {}
    putJsonObject("modules") {}
    putJsonArray("critical") {}
}

private fun shallowMergeObject(base: JsonObject, override: JsonObject): JsonObject {
    val merged = LinkedHashMap<String, JsonElement>(base)
    for ((k, v) in override) merged[k] = v
    return JsonObject(merged)
}

/**
 * Ports the prototype's `load_config` merge exactly: a nested object merges
 * key-by-key, one level deep (Python's `cfg[k].update(v)`) only when both
 * the default and the user's value for that key are objects; everything
 * else -- including a user value that's an object where the default
 * isn't -- overrides wholesale (`cfg[k] = v`). `JsonElement` is immutable,
 * so unlike Python's mutable dicts (which need `json.loads(json.dumps(DEFAULTS))`
 * to avoid corrupting the shared `DEFAULTS` constant across calls), no
 * separate "deep copy" step is needed here: every merge below builds a new
 * `JsonObject`, `defaults` itself is never touched.
 */
private fun mergeUserConfig(defaults: JsonObject, user: JsonObject): JsonObject {
    val merged = LinkedHashMap<String, JsonElement>(defaults)
    for ((k, v) in user) {
        val existing = merged[k]
        merged[k] = if (v is JsonObject && existing is JsonObject) shallowMergeObject(existing, v) else v
    }
    return JsonObject(merged)
}

private fun JsonObject.string(key: String): String = getValue(key).jsonPrimitive.content
private fun JsonObject.double(key: String): Double = getValue(key).jsonPrimitive.double
private fun JsonObject.int(key: String): Int = getValue(key).jsonPrimitive.int
private fun JsonObject.stringList(key: String): List<String> = getValue(key).jsonArray.map { it.jsonPrimitive.content }

private fun JsonObject.stringMap(key: String): Map<String, String> {
    val result = LinkedHashMap<String, String>()
    for ((k, v) in getValue(key).jsonObject) result[k] = v.jsonPrimitive.content
    return result
}

/** Order-preserving: [IngestConfig]'s KDoc documents why `modules`' key order is load-bearing (first-match-wins). */
private fun JsonObject.moduleMap(key: String): Map<String, List<String>> {
    val result = LinkedHashMap<String, List<String>>()
    for ((k, v) in getValue(key).jsonObject) result[k] = v.jsonArray.map { it.jsonPrimitive.content }
    return result
}

/** Unrecognized weight keys are silently ignored, matching the prototype's permissive untyped dict (never validated). */
private fun JsonObject.weightsMap(key: String): Map<EvidenceType, Double> {
    val result = LinkedHashMap<EvidenceType, Double>()
    for ((k, v) in getValue(key).jsonObject) {
        val type = EvidenceType.entries.find { it.name == k } ?: continue
        result[type] = v.jsonPrimitive.double
    }
    return result
}

/**
 * Both configs the rest of the engine consumes, built from one loaded and
 * merged JSON config -- a single entry point so a future CLI (#8) never
 * constructs [ScoringConfig] and [IngestConfig] separately and risks them
 * drifting out of sync (e.g. `satLines`, which both need).
 */
data class LoadedConfig(
    val scoring: ScoringConfig,
    val ingest: IngestConfig,
    /** Module names gated in `--gate` mode (SPEC §5) -- not consumed by this engine yet; carried for #9. */
    val critical: List<String>,
)

/**
 * Loads and validates a JSON config file, mirroring the prototype's
 * `load_config`. Missing `modules` (still empty after the merge) fails
 * loudly, matching `load_config`'s `sys.exit`.
 */
fun loadConfig(path: String): LoadedConfig {
    val userJson = Json.parseToJsonElement(File(path).readText()).jsonObject
    val cfg = mergeUserConfig(defaultConfigJson(), userJson)

    val modules = cfg.moduleMap("modules")
    require(modules.isNotEmpty()) { "config error: 'modules' must map module names to path globs" }

    val satLines = cfg.double("sat_lines")
    val scoring = ScoringConfig(
        hChurn = cfg.double("h_churn"),
        hWallDays = cfg.double("h_wall_days"),
        quiescenceStretch = cfg.double("quiescence_stretch"),
        churnCap = cfg.double("churn_cap"),
        satLines = satLines,
        thetaPerson = cfg.double("theta_person"),
        thetaCovered = cfg.int("theta_covered"),
        weights = cfg.weightsMap("weights"),
        departed = cfg.stringList("departed").toSet(),
    )
    val ingest = IngestConfig(
        ref = cfg.string("ref"),
        agentTrailerEmails = cfg.stringList("agent_trailer_emails"),
        botAuthorPatterns = cfg.stringList("bot_author_patterns"),
        identity = cfg.stringMap("identity"),
        modules = modules,
        satLines = satLines,
    )
    return LoadedConfig(scoring, ingest, cfg.stringList("critical"))
}
