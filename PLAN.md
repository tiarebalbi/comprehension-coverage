# Plan — v0.1

Ordered, GitHub-issue-ready work items. Each is sized to be reviewable in one
sitting (~200–400 lines of non-generated diff; generated scaffolding, e.g.
the Gradle wrapper, is called out and excluded from that budget where it
applies). Every item names its blocking issue(s). Labels: `phase:calibration`,
`phase:kotlin`, `phase:field-test`. Milestone: `v0.1`.

No Kotlin work starts before issues #1–#3 land, since they change the
contract Kotlin has to implement (calibrated parameters, the `REVIEWED`
disposition, and a fully-deterministic reference behavior).

---

## #1 — Resolve calibration candidate 1: `H_wall` [phase:calibration]

**Scope:** Decide, via experiment on the prototype, whether/how to change
`H_wall` (currently 180 days, flagged too aggressive for frozen modules per
`CALIBRATION.md` candidate 1). Does not touch candidate 2 or any Kotlin code.
Blocked by: none.

**Constraints:**
- The original express run (`RUN-NOTES.md`) is not exactly replayable: it
  cites "express @ main (6,169 commits)" with no pinned commit SHA. This
  issue must first re-run against a **pinned clone** (record the exact SHA,
  clone depth, and `--as-of 2026-09-13T00:00:00Z`) so the experiment has a
  fixed baseline others can reproduce.
- Test at minimum: current default (180d), the candidate from RUN-NOTES
  (540d), and one more spaced value, or a quiescence-scaled floor (`H_wall`
  as a function of a module's own churn rate rather than a constant).
  **Note:** a quiescence-scaled floor is a new formula in SPEC §3, not a
  tunables-table edit — if that's the direction, scope this issue to decide
  *whether* to go that route, and treat the formula design as a follow-up
  if it needs more than a single sitting.
- Compare resulting maps against the A2/A3 narratives in `RUN-NOTES.md`:
  does the change preserve `application`'s explainability story while fixing
  the frozen-module false decay?
- This is a calibration commit (see `CLAUDE.md`): it must, in one commit,
  edit `prototype/comprehension.py`, `SPEC.md` §4 (and §3 if a new formula),
  `CALIBRATION.md` (candidate 1 → status ACCEPTED or REJECTED with
  reasoning), and regenerate `fixtures/golden-synthetic.json`.

**Done criteria:** `CALIBRATION.md` candidate 1 has status ACCEPTED or
REJECTED, citing the pinned-SHA experiment. If ACCEPTED, `SPEC.md` §4 (and
§3, if applicable) reflects the new value/formula, the prototype
implements it, and the golden fixture is regenerated and committed. Tag
`v0.1-calibration` on the resulting commit.

**Test requirements:** `python3 prototype/test_comprehension.py` passes
with the new default. Existing unit tests
(`test_decay_wall_clock_half_life` etc.) must be updated if they hardcode
180d. No new fixture format — same shape, new numbers.

**Estimated review size:** ~100–150 lines (prototype + spec + calibration
log), plus the regenerated fixture diff (small, mechanical).

---

## #2 — Document calibration candidate 2 disposition: `REVIEWED` [phase:calibration]

**Scope:** Document the decision that v0.1 ships `REVIEWED` as a declared
interface only (SPEC §2 already reserves the name) — not an implementation.
Docs only; no scoring code changes. Blocked by: none.

**Constraints:** Spelling is `REVIEWED` (SPEC §2), not `REVIEW` — RUN-NOTES
and casual references use the latter; fix references as you touch them.
Distinguish this from the adjacent `ATTESTED` gap (see backlog): `ATTESTED`
is supposed to be *in* v0.1 and isn't implemented (a bug); `REVIEWED` is
*not supposed to be* in v0.1 (a decision).

**Done criteria:** `CALIBRATION.md` candidate 2 status set to ACCEPTED
(disposition: declared-interface-only for v0.1). A short interface-contract
note exists (in `SPEC.md` §2 or a linked doc) describing: expected event
shape (`person, module, REVIEWED, timestamp, magnitude`), expected source
(forge API — PR review submissions), and how its weight would compose with
existing evidence once implemented (so a future implementer doesn't have to
re-derive intent).

**Test requirements:** None (docs-only change); confirm
`prototype/test_comprehension.py` still passes untouched.

**Estimated review size:** ~40–80 lines, docs only.

---

## #3 — Close the two C6 determinism gaps before Kotlin [phase:calibration]

**Scope:** Fix two determinism holes in the prototype/spec that any Kotlin
port would otherwise silently diverge on. Blocked by: #1 (so fixtures
regenerate once, not twice).

**Constraints:**
1. `comprehension.py:236` parses `--as-of` via
   `datetime.fromisoformat(a.as_of.replace("Z","+00:00"))` — a caller
   passing a timezone-naive string (no `Z`, no offset) gets local-time
   resolution, violating C6 ("no wall-clock reads... the 'as of' instant is
   an explicit input"). Fix: reject tz-naive `--as-of` input with a clear
   error, rather than silently localizing it.
2. The exact `git log` invocation
   (`--all --numstat --no-renames --date-order --reverse`) is not pinned
   anywhere in SPEC §2, but affects results: `--all` means the answer
   depends on which refs happen to be fetched locally, and `--date-order`
   tie-breaking isn't guaranteed stable across git versions. Pin the exact
   flag set (or an equivalent explicit contract, e.g. "first-parent history
   of the ref named by config, oldest-first, stable tie-break by SHA") into
   SPEC §2 as part of the evidence-extraction contract.

**Done criteria:** `comprehension.py --as-of <naive string>` exits with a
clear error instead of silently using local time. SPEC §2 states the exact
git invocation / ref selection contract. Golden fixture regenerated (should
be a no-op numerically, since the synthetic repo's `--as-of` is already
tz-aware and single-branch — confirms the fix doesn't perturb existing
behavior).

**Test requirements:** New unit test asserting tz-naive `--as-of` raises/
exits rather than succeeding. Existing golden test unaffected.

**Estimated review size:** ~60–100 lines.

---

## #4 — Kotlin: Gradle scaffold + CI job [phase:kotlin]

**Scope:** Walking-skeleton scaffold only — no scoring logic. Gradle project
(JVM 21), wrapper committed, CI job that builds and runs an empty test
suite. Blocked by: #1, #2, #3.

**Constraints:** Wrapper (`gradlew`, `gradle/wrapper/*`) is generated;
exclude it from the review-size budget explicitly in the PR description.
Match the CI style of `.github/workflows/ci.yml` (separate job, doesn't
replace the Python CI job — both must stay green).

**Done criteria:** `./gradlew build` succeeds in CI on a trivial passing
test. Project structure ready to receive the scoring engine.

**Test requirements:** One placeholder test proving the toolchain wires up.

**Estimated review size:** ~50–100 lines of hand-written config
(build.gradle.kts, settings.gradle.kts, CI job); wrapper excluded.

---

## #5 — Kotlin: core scoring engine + golden tests [phase:kotlin]

**Scope:** Port `score_all`, `collect`'s decay/value math, and `build_map`
from the prototype. Does not include git ingestion (events are
constructed directly in tests, mirroring
`test_comprehension.py`'s unit tests) or config loading. Blocked by: #4.

**Constraints — known A4 landmines, must be replicated exactly:**
- Module assignment is **first-match-wins** in config declaration order
  (`module_of`'s loop over `cfg["modules"]`) — use an order-preserving map
  (e.g. `LinkedHashMap`), not a hash map, for the Kotlin config
  representation.
- `fnmatch`-style glob matching lets `*` cross `/` (this is how
  `lib/router*` in `express-config.json` matches
  `lib/router/index.js`) — Kotlin's `PathMatcher` glob does not do this by
  default. Implement matching with the same semantics, not `java.nio`
  globbing, unless verified equivalent.
- Matching is case-sensitive (Python's `fnmatch` case-folds only via
  `normcase`, which is identity on POSIX — the platform this pipeline
  targets).
- Module size clamp is `max(1.0, net_lines)`, including when net lines
  goes negative (net deletions) — this can push `churn_ratio` to
  `CHURN_CAP`. Replicate the clamp exactly, not just "size in lines."
- Rounding: Python's `round()` is round-half-to-even on the underlying
  binary double, not `HALF_UP`. Match this in Kotlin (`Math.rint`-style),
  not naive `BigDecimal.HALF_UP` rounding.

**Done criteria:** Given the same evidence-event inputs as
`test_comprehension.py`'s unit tests (decay half-lives, agent discount,
saturation, status thresholds, departed exclusion), Kotlin produces
identical scores to within the fixture's declared precision.

**Test requirements:** Port each unit test in `test_comprehension.py`
(`test_decay_wall_clock_half_life`, `test_decay_churn_half_life`,
`test_agent_weight_discount`, `test_saturation_caps_at_one`,
`test_status_thresholds`, `test_departed_excluded_from_counts`) as a
Kotlin test with the same fixed inputs/expected outputs. Full golden-JSON
parity is issue #10, not this one — this issue proves the math, not the
serialization.

**Estimated review size:** ~250–350 lines (engine + tests).

---

## #6 — Kotlin: GitSource ingestion [phase:kotlin]

**Scope:** Shell out to `git log` per the flag set pinned in #3, parse
commits/trailers/numstat, classify `AUTHORED` vs `AGENT_MEDIATED` per SPEC
§2's marker rules. Mirrors `read_commits`, `is_agent_mediated`,
`canonical`. Does not include `ATTESTED` (`.comprehension/attestations.yaml`
parsing is out of v0.1 scope per the backlog note below — this issue only
covers git-history-derived evidence). Blocked by: #3, #5.

**Constraints:** Use the exact flag set/contract pinned in #3. Trailer
parsing must match `%(trailers:key=Co-Authored-By,valueonly)` semantics
(case, multiple trailers, missing trailers) — verify against a small fixture
repo rather than assuming `git4idea`/JGit trailer parsing matches Git's own.

**Done criteria:** Given the synthetic repo built by
`test_comprehension.py`'s `build_synthetic_repo`, Kotlin's parsed commit
list and derived evidence events match the prototype's, field for field.

**Test requirements:** Build the same synthetic repo (or an equivalent
fixture repo checked into the Kotlin test resources) and assert on parsed
`Commit`/evidence-event structures, not just final scores — isolates
ingestion bugs from scoring bugs.

**Estimated review size:** ~200–300 lines.

---

## #7 — Kotlin: config loading + validation [phase:kotlin]

**Scope:** Load and validate the JSON config (defaults merge, `modules`
required and order-preserving, `identity`/`departed`/`critical` maps).
Mirrors `load_config`. Blocked by: #4.

**Constraints:** Defaults-merge behavior must match `load_config`'s
shallow-dict-merge-then-override semantics (nested dicts like `weights`
merge key-by-key; everything else overrides wholesale). Config module
order must be preserved end-to-end into the structure #5 consumes
(first-match-wins depends on it).

**Done criteria:** Given `prototype/express-config.json`, Kotlin produces
an equivalent in-memory config to the Python prototype's merged `cfg` dict.
Missing `modules` fails loudly (mirrors `sys.exit` in `load_config`).

**Test requirements:** Unit tests for default merge, override merge,
missing-`modules` error case.

**Estimated review size:** ~120–180 lines.

---

## #8 — Kotlin: map/JSON reporting [phase:kotlin]

**Scope:** Console render (mirrors `render`/`BAR`) and JSON output (mirrors
the `--json` writer). This is the highest-risk item for A4 — see
`PLAN-REVIEW.md`. Blocked by: #5, #6, #7.

**Constraints — the actual A4 risk, verified against the current
prototype output:**
- Float serialization must drop trailing zeros the way Python's `json`
  module does (`round(0.26727002, 6)` serializes as `0.26727`, not
  `0.267270`) — Java/Kotlin's default `%.6f`-style formatting will not do
  this by default; a custom formatter or minimal-digits serialization is
  required.
- Python's `json` module switches to scientific notation below `1e-4`;
  Java's `Double.toString` switches below `1e-3`. Scores in this band are
  not hypothetical — RUN-NOTES cites `router`'s top score at `0.0023`.
  Confirm the actual threshold behavior needed and implement it explicitly
  rather than trusting either language's default `toString`.
- `as_of` serializes as a JSON integer (`1728512000`), never `1728512000.0`.
- Keys are sorted (`sort_keys=True`) over the **concatenated** string key
  (e.g. `"Tiare|core"`), not a nested/tuple sort — this matters once person
  names or module names contain characters that sort differently
  concatenated vs. as a tuple.
- No trailing newline in the written JSON file (`json.dump` doesn't add
  one) — match byte-for-byte, not just semantically-equal JSON.

**Done criteria:** JSON output for the golden-synthetic scenario is
byte-identical to `fixtures/golden-synthetic.json`. (Full A4 sign-off,
including the express-scale float-formatting edge cases this fixture
doesn't exercise, is issue #10.)

**Test requirements:** Byte-comparison test against
`fixtures/golden-synthetic.json` using the same synthetic-repo fixture as
#6. Additional unit tests for the float-formatting edge cases (trailing
zero, sub-`1e-4` magnitude) using values the golden fixture does *not*
happen to cover.

**Estimated review size:** ~150–250 lines.

---

## #9 — Kotlin: gate mode with break-glass [phase:kotlin]

**Scope:** Implement SPEC §5.2 in full: exit 2 on `DARK` critical module
(matches the prototype), exit 1 on `AT_RISK` critical module
(configurable — **no prototype reference for this**), and
`--break-glass "<ref>"` converting a red exit to a warning while writing an
`INCIDENT_DIAGNOSED`-class attestation stub. Blocked by: #7, #8.

**Constraints:** The prototype only implements exit-2-on-DARK
(`comprehension.py`'s `main`, the `--gate` block) — it has no AT_RISK exit
code and no `--break-glass` at all. **There is no golden reference for this
issue's new behavior.** Acceptance criteria must be derived directly from
SPEC §5.2's prose, not from prototype parity. Consider whether the
prototype should also gain this behavior for consistency — if so, that's a
separate small prototype PR (does not need to block this one, since it
isn't a scoring/evidence change and doesn't affect A4).

**Done criteria:** `--gate` exits 2 on any critical `DARK` module, exits 1
on any critical `AT_RISK` module (with the AT_RISK-triggers-exit-1 behavior
itself configurable, per SPEC), prints remediation text (who last held
evidence, how it decayed) for failing modules. `--break-glass "<ref>"`
converts what would be a non-zero exit into exit 0 with a warning, and
writes a stub attestation record of type `INCIDENT_DIAGNOSED` referencing
the incident ref for the invoking person.

**Test requirements:** Spec-derived acceptance tests (not golden-fixture
parity): construct module states that should trigger each exit code and
each break-glass path, assert on exit code + stub attestation content.

**Estimated review size:** ~150–250 lines.

---

## #10 — A4 parity run: Kotlin vs. prototype [phase:kotlin]

**Scope:** Run both implementations against the express clone (same pinned
SHA as issue #1) and diff the JSON output byte-for-byte. This is the actual
SPEC §7 A4 gate — no real-repo number may be published before this passes.
Blocked by: #5, #6, #7, #8, #9.

**Constraints:** See `PLAN-REVIEW.md` for why the golden-synthetic fixture
alone is insufficient evidence of parity (it never exercises sub-`1e-4`
scores or overlapping-glob precedence, both of which the express config
does). Do not consider A4 satisfied on golden-fixture parity alone — this
issue's express-scale diff is the real test.

**Done criteria:** Byte-identical JSON from both implementations on (a) the
golden-synthetic fixture and (b) the pinned express clone. Any divergence
found is fixed here, not deferred. Tag `v0.1-kotlin` once this passes.

**Test requirements:** Scripted diff (not manual eyeballing) comparing both
outputs; committed as a repeatable check (e.g. a script under `scripts/` or
a CI job), not a one-off local run.

**Estimated review size:** ~50–100 lines (comparison tooling) plus a
run report, not a large diff.

---

## #11 — README update + `v0.1-kotlin` tag + JitPack release [phase:kotlin]

**Scope:** Update README's status section to reflect the Kotlin
implementation, document how to run it (parallel to the existing prototype
instructions), and cut a JitPack-consumable release. Blocked by: #10.

**Constraints:** Keep the prototype instructions in README — the prototype
remains the reference semantics per SPEC, not a deprecated artifact.

**Done criteria:** README documents both prototype and Kotlin usage.
`v0.1-kotlin` tag exists (from #10) and resolves via JitPack.

**Test requirements:** Manual verification that the JitPack coordinate
resolves in a scratch consumer project.

**Estimated review size:** ~40–80 lines (docs), plus release config.

---

## #12 — Narrative-specimen run (author's own repo) [phase:field-test]

**Scope:** Run the (by-then-validated) instrument against the author's own
repository once A4 passes. Feeds blog essay #06. Blocked by: #10.

**Constraints:** Results are **not committed to this repo** without the
author's explicit review — this is personal/potentially sensitive data
about the author's own history, distinct from the express robustness
check. Follow the same pinned-SHA + explicit `--as-of` discipline as #1.

**Done criteria:** A run report (private until reviewed) assessing SPEC §7
A1–A3 against the author's repo, analogous to `RUN-NOTES.md` for express.

**Test requirements:** None (a run, not a code change) — though it should
surface as issues/fixes to file against the instrument if it finds bugs
rather than calibration questions.

**Estimated review size:** N/A (report, not code).

---

## Backlog — unblocked, non-critical-path for v0.1

These are real gaps but do not block the sequence above. File as separate
issues when picked up; not sequenced here because nothing else depends on
them and they don't gate A4.

- **`ATTESTED` evidence class unimplemented.** SPEC §2 lists it as v0.1
  scope (`.comprehension/attestations.yaml`), and
  `DEFAULTS["weights"]["ATTESTED"]` exists, but `collect()` never emits
  `ATTESTED` events — no code reads the attestations file. Unlike
  `REVIEWED` (issue #2, correctly out of scope), this is scope the spec
  already committed to that the prototype hasn't delivered. Needs its own
  calibration-adjacent issue: implement attestation-file parsing in the
  prototype (with its own unit test and a fixture addition), before or
  after the Kotlin track — doesn't block it either way since Kotlin ports
  whatever the prototype does at the time it's ported.
- **SPEC §5.1 map fields not in prototype output.** Spec requires
  per-module "strongest evidence age" and "agent-mediated share of recent
  churn" in the map output; the prototype's JSON currently emits only
  `status` and `comprehenders`. Extending this is a prototype change (new
  fixture) that can happen independently of the Kotlin track — Kotlin
  simply ports whatever shape the prototype has when #6/#8 are picked up.
  If this lands before #8, sequence it as a small prototype-only issue
  first so #8 targets the final shape once, not twice.
