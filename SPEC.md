# Comprehension Coverage — Specification v0.1

Status: draft for first-run calibration. This spec separates **constitutional
decisions** (fixed; changing one changes the concept) from **tunable parameters**
(provisional; the first real runs exist to challenge them). The Python prototype
in `prototype/` is the reference semantics: the Kotlin implementation must
reproduce its outputs exactly on the golden fixtures.

Concept reference: the blog essay "Introducing Comprehension Coverage"
(tiarebalbi.com). Diagnosis reference: Wheeler, *The Substrate Collapse*
(arXiv:2606.20882).

---

## 1. Constitutional decisions (fixed)

C1. **Evidence, not authorship.** A person's standing in a module is earned only
    by recorded acts that plausibly build or demonstrate theory. Raw authorship
    is one evidence class among several — not the definition.

C2. **Staleness, not memory.** The instrument does not claim to know what anyone
    remembers. It computes how stale the available evidence has become. It is an
    *evidence detector with an explicit model of staleness*, never a
    comprehension detector.

C3. **Churn-indexed decay with a wall-clock floor.** Evidence decays primarily as
    the module changes under it, and secondarily with elapsed time even if the
    module is frozen.

C4. **The module is the unit.** Scores attach to (person, module) but the
    product output is per-module status. Module boundaries come from explicit
    configuration (globs), aligned where possible with architectural slices.

C5. **Module aggregates are public; individual scores are private.** Default
    output never names individuals. Per-person data appears only behind an
    explicit flag intended for the person themselves or team-local use.

C6. **Deterministic.** Same repository state + same config = same map, bit for
    bit. No model calls, no sampling, no wall-clock reads (the "as of" instant
    is an explicit input).

C7. **Agent-mediated authorship is weaker evidence than hand authorship** —
    never zero by fiat (briefing and reviewing an agent can build theory), never
    equal (Anthropic's comprehension-gap RCT and Krüger's passive-tracking
    result justify the discount). The magnitude of the discount is tunable; its
    existence is constitutional.

---

## 2. Evidence model

An **evidence event** is `(person, module, type, timestamp, magnitude)`.

v0.1 extracts from plain git history only:

| Type | Detection | Notes |
|---|---|---|
| `AUTHORED` | commit author, no agent markers | magnitude = lines touched in module |
| `AGENT_MEDIATED` | commit carries an AI co-author trailer or bot author | same magnitude, discounted weight |
| `ATTESTED` | entry in `.comprehension/attestations.yaml` | explicit "I re-walked this module" record |

Declared interfaces, no v0.1 implementation: `REVIEWED` (forge API),
`INCIDENT_DIAGNOSED` (incident tooling), `ADR_AUTHORED`. The schema reserves
their names so fixtures stay stable when they land.

**`REVIEWED` interface contract (declared, not implemented in v0.1;
CALIBRATION.md candidate 2, decided 2026-09-13):** the express run
(RUN-NOTES.md) surfaced the predicted gap directly — current maintainers
who demonstrably hold theory of the codebase (they run releases and review
every consequential PR) score near zero, because v0.1 only ingests git
history and review leaves no trace there. `REVIEWED` is reserved, not
built, for v0.1: this note fixes the contract a future implementation
lands against, so it doesn't have to be re-derived from scratch.

- **Event shape:** `(person, module, REVIEWED, timestamp, magnitude)` — the
  same tuple every evidence type uses (§2, top).
- **Expected source:** a forge API (GitHub/GitLab/etc. PR review
  submissions), not git history — a `REVIEWED` event is emitted per
  qualifying review action (e.g. an "approve" or a substantive
  change-requested review), attributed to the reviewer, not the author.
- **Magnitude:** left open pending real review data, but the two
  precedents already in v0.1 bound the design space: `AUTHORED`/
  `AGENT_MEDIATED` scale magnitude by lines touched (a continuous
  quantity the forge API can supply per review, e.g. lines in the diff
  reviewed); `ATTESTED` instead fixes magnitude at `SAT_LINES` (a discrete
  self-report). A review is closer to the `ATTESTED` shape in spirit — a
  qualitative act ("I reviewed this"), not inherently size-scaled — but
  unlike a self-attestation it does have a real diff size available from
  the forge API, so scaling by reviewed-lines is defensible too. Whoever
  implements this should decide against real review data, not by
  assumption.
- **Weight composition:** not assigned a `weights.REVIEWED` default in §4
  — adding one now would be committing to a number with no data behind it.
  Qualitatively, review evidence sits somewhere between `AGENT_MEDIATED`
  (0.3 — weaker than hand authorship by construction, C7) and `AUTHORED`
  (1.0 — the anchor): a careful review builds real theory of a module but
  is generally lighter-touch than authoring it, and review depth varies
  enormously in ways a forge API can't directly measure (a rubber-stamp
  approval and a line-by-line review both register as one `REVIEWED`
  event). Its weight is PROVISIONAL from the moment it's implemented,
  exactly like every other weight in §4, and should go through the same
  calibration process (`CALIBRATION.md`) rather than shipping a guessed
  default.

**Agent markers** (configurable lists):
- Trailer emails: `noreply@anthropic.com`, `copilot@github.com`,
  `cursoragent@cursor.com` (extend in config).
- Author patterns: `*[bot]`, configured bot emails.
Absence of markers is treated as hand authorship; the spec acknowledges this
under-detects agent code in repos with poor hygiene (see §6 limits).

**Identity merge:** config maps emails → canonical person. Departed people are
listed in config and excluded from *current* comprehender counts while retained
in history.

---

## 3. Scoring (reference semantics)

For evidence event `e` of person `p` in module `m`, evaluated "as of" instant T:

```
H_wall_eff(e, T)    = H_wall * (1 + Q * (1 - churn_ratio(e, T) / CHURN_CAP))
effective_age(e, T) = churn_ratio(e, T) / H_churn  +  days(e, T) / H_wall_eff(e, T)
decay(e, T)         = 0.5 ^ effective_age(e, T)
value(e)            = weight(type) * saturate(magnitude)
score(p, m, T)      = min(1.0, Σ_e value(e) * decay(e, T))
```

- `churn_ratio(e, T)` = lines changed in `m` by *anyone other than p* between
  `e.timestamp` and `T`, divided by module size at T (capped at `CHURN_CAP`).
- `saturate(x)` = `min(1.0, sqrt(x / SAT_LINES))` — the tenth hundred-line
  commit teaches less than the first.
- `H_wall_eff(e, T)` is the **quiescence-scaled wall-clock floor**
  (CALIBRATION.md candidate 1, decided 2026-09-13): the wall-clock half-life
  stretches toward `H_wall * (1 + Q)` as `churn_ratio(e, T) → 0` (the module
  has barely moved since `e` — a frozen module should not force-decay its
  evidence on elapsed time alone) and relaxes to the plain `H_wall` as
  `churn_ratio(e, T) → CHURN_CAP` (the module has been fully rewritten by
  others since `e` — that evidence should decay exactly as fast as the
  un-stretched floor would decay it; the stretch must not give already
  churned-away evidence a second reprieve). `Q = 0` recovers the original
  flat floor exactly. This directly implements C3's ordering ("decays
  primarily as the module changes... secondarily with elapsed time"): the
  wall-clock term now only dominates when the churn term has nothing to say.
- A person is a **current comprehender** of `m` iff `score(p,m,T) ≥ θ_person`.

**Module status:**

| Status | Condition |
|---|---|
| `COVERED` | comprehenders ≥ `θ_covered` |
| `AT_RISK` | comprehenders = 1..θ_covered−1 |
| `DARK` | comprehenders = 0 |

## 4. Tunable parameters — PROVISIONAL pending first-run calibration, except where marked CALIBRATED

| Param | Default | Rationale sketch | Status |
|---|---|---|---|
| `H_churn` | 1.0 (one full rewrite halves evidence) | plausibility only | PROVISIONAL |
| `H_wall` | 180 days | Krüger's 30–45d is per-file recall; module-level theory assumed slower | CALIBRATED (express run, 2026-09-13 — see CALIBRATION.md candidate 1; value unchanged, now modulated by `Q` below) |
| `Q` (quiescence stretch) | 2.0 | express run: flat H_wall=540 fixed frozen-module false decay but also gave a 2.16× decay reprieve to evidence already superseded by others' churn (synthetic check, CALIBRATION.md); `Q=2` gives frozen modules the same fix (H_wall_eff→540) while fully-churned evidence keeps the exact un-stretched decay rate | CALIBRATED (express run, 2026-09-13 — see CALIBRATION.md candidate 1) |
| `CHURN_CAP` | 4.0 | beyond 4 rewrites, treat as fully decayed path | PROVISIONAL |
| `weight(AUTHORED)` | 1.0 | anchor | fixed as anchor |
| `weight(AGENT_MEDIATED)` | 0.3 | RCT gap direction, magnitude unknown | PROVISIONAL |
| `weight(ATTESTED)` | 0.6 | self-report, gameable | PROVISIONAL |
| `SAT_LINES` | 400 | ~one deep sitting (review-budget literature) | PROVISIONAL |
| `θ_person` | 0.5 | half-life symmetry | PROVISIONAL |
| `θ_covered` | 2 | bus-factor convention | PROVISIONAL |

Calibration rule: the first runs may move any PROVISIONAL value. A change must
be recorded in `CALIBRATION.md` with the observation that forced it. The Kotlin
implementation ships whatever this table says at tag time.

## 5. Outputs

1. **Map** (default): per module — status, comprehender count, strongest
   evidence age, agent-mediated share of recent churn. Console + JSON.
2. **Gate** (`--gate`): exit 2 if any module listed in `critical:` is `DARK`;
   exit 1 if any critical module `AT_RISK` (configurable); print the failing
   modules with remediation text (who last held evidence, how it decayed).
   Break-glass: `--break-glass "<incident-ref>"` converts a red exit into a
   warning AND writes an `INCIDENT_DIAGNOSED`-class attestation stub for the
   invoking person — the escape hatch that feeds the map.
3. **Individual view** (`--show-individuals`): per-person scores. Never the
   default; C5.

## 6. Known limits (state them, do not fix them in v0.1)

- Evidence ≠ comprehension (C2). A false positive/negative rate is expected and
  §7 requires hunting for one.
- Agent detection by markers under-counts in repos with poor trailer hygiene;
  where all history is marker-free the instrument degrades toward a
  churn-decayed authorship map — still novel (decay + evidence framing), but
  closest to prior art there.
- Squash merges erase co-author granularity; noted per-repo in run reports.
- Renames tracked via `git log --follow`-equivalent at module level only.

## 7. First-run acceptance criteria (editorial gate for essay #06)

The first real runs justify the final essay only if:

A1. **Non-obvious result.** At least one module where conventional ownership
    (top historical author still active) and coverage status disagree — not
    merely "old code scores low."
A2. **Explainable result.** For at least one DARK module, the evidence timeline
    reconstructs *why* it went dark, in plain language, from the tool's own
    output.
A3. **One failure (required, not hoped).** At least one module whose status the
    repo owner can refute from ground truth — documented as the instrument's
    false positive, with the evidence class that was missing. If no natural one
    appears, the runs continue (more repos, more history) until one does.
A4. **Reference agreement.** Kotlin output is byte-identical to prototype JSON
    on the golden fixtures before any real-repo number is published.

Run roles: the author's repository is the **narrative specimen**; at least one
unrelated OSS repository is the **robustness check** (published, if at all, as a
one-line class-of-pattern confirmation).
