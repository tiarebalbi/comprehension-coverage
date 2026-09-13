# Calibration log

Tracks proposed changes to the PROVISIONAL parameters in `SPEC.md` §4, and to
the v0.1 evidence-class scope in §2. Per `SPEC.md`: "A change must be recorded
in `CALIBRATION.md` with the observation that forced it." Every change here
that touches a tunable must land as one commit editing the prototype, the
spec's tunables table, this file, and the regenerated fixtures together (see
`CLAUDE.md`).

Status values: `OPEN` (observed, not yet decided), `DECIDED` (an experiment
compared candidate variants and one was adopted — spec + code changed,
rejected variants and why are kept for the record), `ACCEPTED` (a single
proposed change was adopted without a multi-variant experiment), `REJECTED`
(considered, not adopted — reasoning kept for the record).

---

## Candidate 1 — `H_wall` too aggressive for frozen modules

**Observation** (from `RUN-NOTES.md`, express run, 2026-09-13): with
`H_wall = 180` days, a module (`application`/`router`) that has barely
changed since a substantial 2023 contribution scores that evidence as fully
decayed by 2026, purely from wall-clock elapsed time — even though the churn
model (`H_churn`) would have preserved it, since almost nothing has actually
changed under it. The wall-clock floor is meant to catch evidence that is
old *and* the module is still moving without the person; here it fires on
modules that are old and static, which contradicts C3's intent ("decays
primarily as the module changes ... secondarily with elapsed time").

**Proposed change:** lengthen `H_wall` substantially (candidate: 540 days),
or replace the fixed floor with a quiescence-scaled floor (e.g. `H_wall`
grows with a module's own churn rate, so a module that isn't moving doesn't
force-decay its evidence on time alone).

**Status:** DECIDED — quiescence-scaled floor adopted (SPEC §3, §4).

### Experiment (2026-09-13)

**Baseline reproducibility note:** the original express run (RUN-NOTES.md,
2026-09-13) cited "express @ main (6,169 commits)" with no pinned SHA. For
this experiment, `expressjs/express` was freshly cloned and pinned at:

```
SHA:      3ce6d0eb86e9d93529ff3191c6bb5db8ce6e72c8
Cloned:   2026-09-13
--as-of:  2026-09-13T00:00:00Z
```

Note for future reproduction: `prototype/comprehension.py`'s `read_commits`
uses `git log --all`, which pulls in every ref a clone happens to fetch —
this pinned clone parsed **6,422** commits via `--all`, not the 6,169 a plain
`git log --oneline` on the default branch shows. This is the non-determinism
tracked separately as issue #3 (pin the exact git invocation before Kotlin
starts); it doesn't change this experiment's conclusion (all three variants
below were run against the identical commit set), but it means this
experiment's absolute numbers are not bit-for-bit reproducible against
RUN-NOTES' original run until #3 lands.

**Three variants**, same config (`prototype/express-config.json`), same
pinned clone and `--as-of`:

| Variant | `H_wall` | `Q` (quiescence_stretch) |
|---|---|---|
| (a) baseline | 180d | 0 (disabled) |
| (b) flat lengthen | 540d | 0 (disabled) |
| (c) quiescence-scaled | 180d | 2.0 |

**Module-level result:** identical `status` on every module across all three
variants (`application`/`middleware`/`response`/`router`/`utils`/`view` stay
`DARK`; `request` stays `AT_RISK`). Comprehender counts are identical too,
except `tests`, which moves from 3 comprehenders (baseline) to 8 in both (b)
and (c) — several historical `tests` authors were being wall-clock-erased
despite `tests` itself having had comparatively little churn since their
contributions, the same mechanism working as intended. `tests` stays
`COVERED` in all three variants, so this is a count change, not a status
change, and non-critical (`tests` isn't in `express-config.json`'s
`critical` list). This calibration issue is about whether *evidence scores*
correctly reflect decayed-but-real standing — it does not, and should not,
manufacture comprehenders where the underlying evidence classes v0.1 can see
(authorship) don't support it. That gap is candidate 2 (`REVIEWED`
evidence), not this one.

**Score-level result — the false-positive fix, and the overcorrection
check** (Douglas Christopher Wilson, `router` — the exact case RUN-NOTES A1
cites: git-blame owner, evidence reads as fully decayed):

| Variant | Wilson `router` score | Wilson `application` | Wilson `tests` |
|---|---|---|---|
| (a) baseline 180d | 0.0023 | 0.0001 | 0.0238 |
| (b) flat 540d | 0.1379 | 0.0325 | 1.0 |
| (c) quiescence Q=2 | 0.1196 | 0.0262 | 1.0 |

Both (b) and (c) fix the false decay (Wilson's `router` evidence goes from
"reads as erased" to "reads as decayed-but-real"). **Neither overcorrects**
by the stated criterion: `θ_person = 0.5` and Wilson's `router` score stays
well below it in both (0.1379, 0.1196) — `router` remains `DARK`, TJ/Wilson-era
mass does not get revived to comprehender status anywhere it shouldn't.
`tests` saturates to `1.0` in both (b) and (c) alike (Wilson's historical
volume there is large enough to hit the saturation cap regardless of which
wall-clock fix is used), so `tests` doesn't discriminate between the two
variants — it only confirms neither one is obviously broken.

**Why (c) over (b), given the real-repo numbers alone don't strongly
discriminate them:** express's modules are almost all currently quiescent
(RUN-NOTES A2's own diagnosis: "v5 stability means almost no churn"), so on
*this* dataset a flat 540d floor and a quiescence-scaled floor land close
together — both largely see low churn_ratio and both stretch close to their
respective ceilings. That similarity is exactly the blind spot: it means the
express run alone cannot show what a flat floor does on a module that is
old *and* still being actively rewritten by others. A synthetic check
isolates that case directly (single evidence event, 300 days elapsed,
`H_wall=180`, `Q=2`, module size 1000):

| Scenario | churn_ratio | baseline (180d) | flat 540d | quiescence Q=2 |
|---|---|---|---|---|
| frozen (churn_after=0) | 0.0 | 0.315 | 0.6804 | 0.6804 |
| fully churned-away (churn_after=4000) | 4.0 (=cap) | 0.0197 | 0.0425 | 0.0197 |
| partially churned (churn_after=2000) | 2.0 | 0.0787 | 0.1701 | 0.1403 |

On the frozen row, (b) and (c) fix the false decay identically — this is
the case candidate 1 exists to fix, and both variants fix it the same way.
On the fully-churned-away row, (b) gives that evidence a **2.16× decay
reprieve it should not get** — the module *has* moved on since, by
construction (`churn_ratio` is already at `CHURN_CAP`), and a flat,
unconditional floor lengthening slows its decay anyway, diluting the
churn-primacy C3 requires. (c) gives it **no reprieve at all**
(`0.0197 == 0.0197`, exact): at `churn_ratio = CHURN_CAP`, `Q`'s multiplier
term is exactly zero by construction, so `H_wall_eff` collapses back to the
plain `H_wall` — quiescence-scaled decay degrades to the un-stretched
baseline precisely where the module has genuinely moved on. The partial-churn
row shows this isn't a step function: (c) interpolates the fix
proportionally to how quiescent the module actually is, where (b) applies
the same 2.16× multiplier regardless of churn. This is the concrete,
falsifiable reason to prefer (c): it is at least as good as (b) everywhere
the express data can check, and strictly better on the case express's
current quiescence happens to hide.

**Decision:** adopt quiescence-scaled floor, `Q = 2.0`, `H_wall` unchanged
at 180 days (SPEC §3 formula, §4 table). `H_wall_eff = H_wall * (1 + Q * (1
- churn_ratio/CHURN_CAP))`. `Q = 0` recovers the original flat floor exactly
(verified: `test_quiescence_does_not_stretch_fully_churned_evidence`), so no
existing deployment loses the ability to run the un-stretched model.
`fixtures/golden-synthetic.json` regenerated under the new default (see
commit); statuses unchanged, `Bob|web` now saturates to `1.0`,
`Alice|core`/`Tiare|core` shift upward slightly (`core`'s single
agent-mediated rewrite event keeps `churn_ratio` well above 0 for Alice's
earlier evidence, so the stretch here is partial, not maximal — consistent
with the mechanism, not a special case).

**Flat 540d is not adopted as a fallback or alternative default.** It is
kept in this record as the comparison baseline that motivated preferring
the quiescence-scaled mechanism; there is no config value that reproduces
it exactly other than setting `Q` such that `H_wall*(1+Q) = 540` *and*
accepting the churned-away-evidence reprieve that comes with it, which is
the behavior this decision explicitly rejects.

---

## Candidate 2 — `REVIEWED` evidence class missing

**Observation** (from `RUN-NOTES.md`, express run, 2026-09-13): current
express maintainers who demonstrably hold theory of the codebase (they run
releases and review every consequential PR) score near zero on core modules,
because v0.1 only ingests git-history evidence (`AUTHORED`,
`AGENT_MEDIATED`, `ATTESTED`). Review is real evidence of comprehension but
produces no git-history signal the instrument currently sees. This is the
predicted failure named in SPEC §6: "an evidence detector is blind to
comprehension held in evidence classes it does not ingest."

**Proposed change:** none for v0.1. `SPEC.md` §2 already reserves
`REVIEWED` as a declared-interface evidence class (forge API-sourced,
unimplemented). The decision is to *keep* it out of v0.1 scope and document
the interface contract, not to build it now.

**Status:** ACCEPTED — declared-interface-only for v0.1, no ingestion
built. `SPEC.md` §2 now carries the full interface contract (event shape,
expected forge-API source, and the reasoning for leaving `weights.REVIEWED`
unassigned rather than guessing a default) so a future implementation has
something to land against instead of re-deriving intent. No code changes:
this candidate resolves as documentation only, unlike candidate 1.

---

## Note: C6 determinism gaps (git ref scope, tz-naive `--as-of`) — resolved, issue #3

Not one of RUN-NOTES' two candidates, but load-bearing for reproducing any
of them: `--as-of` was parsed via
`datetime.fromisoformat(...).timestamp()` on a possibly tz-naive datetime,
which resolves in the invoking machine's local timezone — a direct C6
violation ("no wall-clock reads... the 'as of' instant is an explicit
input"). Separately, `read_commits()` used `git log --all --date-order
--reverse`: `--all` makes the result depend on which refs a clone happens
to have fetched (not just the branch being analyzed), and `--date-order`'s
same-timestamp tie-break isn't guaranteed stable across git versions. The
candidate 1 experiment above hit this directly: its pinned express clone
parsed 6,422 commits via `--all`, not the 6,169 RUN-NOTES' original run
cited.

**Resolved** in issue #3: new `parse_as_of()` rejects tz-naive input
outright. `read_commits()` now takes an explicit `config["ref"]` (default
`HEAD`), and sorts fetched commits by `(timestamp, sha)` in code — the
total order `collect()` depends on is fixed where it's specified and
testable, not delegated to git. Documented as `SPEC.md` §2.1, the contract
the Kotlin ingestion track (#6) implements against. Re-verified against the
same pinned clone: scoped to `HEAD`, it now parses exactly 6,169 commits —
matching RUN-NOTES' original citation exactly, closing the reproducibility
gap noted above.

---

## Note: `ATTESTED` is declared but unimplemented — resolved, issue #13

Not one of RUN-NOTES' two candidates, but adjacent and was worth tracking
here: `SPEC.md` §2 listed `ATTESTED` as an evidence type v0.1 extracts (via
`.comprehension/attestations.yaml`), and `DEFAULTS["weights"]["ATTESTED"]`
existed in the prototype, but `collect()` never emitted `ATTESTED` events —
no code read `.comprehension/attestations.yaml`. This was a spec/prototype
conflict, not a calibration candidate: `ATTESTED` was supposed to exist in
v0.1, unlike `REVIEWED`.

**Resolved** in issue #13: `read_attestations()` now parses
`.comprehension/attestations.yaml` (a restricted YAML subset — no
third-party dependency, per README) and `collect()` folds the resulting
events into the same chronological pass as commits, so their churn-based
decay reflects real churn up to the attestation's timestamp without the
attestation itself counting as churn. `SPEC.md` §2 documents the schema and
the magnitude convention (`ATTESTED` fixes magnitude at `SAT_LINES` — a
discrete claim, not scaled by lines). Golden fixture gained a case: Dana
holds attestation-only evidence on `web` (no git history at all), moving
`web` from `AT_RISK` to `COVERED` — `weights.ATTESTED` (0.6) is now
exercised, not just an unused default.

**Review fixups:** identity resolution for an unmapped attestation email
now falls back to that email's most recent git author name (SPEC §2.1
total order) before falling back to the raw email, so a person's git and
attestation evidence can't fork into two identities — `read_attestations`
itself no longer resolves identity at all, since only `collect()` has the
commit stream needed to do it correctly. An attestation naming an
unconfigured module is still skipped (a subset-module config is a
legitimate run mode) but now prints a one-line warning naming the record,
rather than dropping it silently. `parse_as_of` (issue #3) is now the one
C6 timestamp parser in the codebase — attestation timestamps go through it
too (with a context-specific error label) instead of a separate, near-
identical `_parse_attestation_timestamp`.
