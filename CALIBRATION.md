# Calibration log

Tracks proposed changes to the PROVISIONAL parameters in `SPEC.md` §4, and to
the v0.1 evidence-class scope in §2. Per `SPEC.md`: "A change must be recorded
in `CALIBRATION.md` with the observation that forced it." Every change here
that touches a tunable must land as one commit editing the prototype, the
spec's tunables table, this file, and the regenerated fixtures together (see
`CLAUDE.md`).

Status values: `OPEN` (observed, not yet decided), `ACCEPTED` (spec + code
changed), `REJECTED` (considered, not adopted — reasoning kept for the
record).

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

**Status:** OPEN. Resolution is issue #1 (see `PLAN.md`) — decide via
controlled experiment (re-run the express clone under 2–3 candidate values,
or the quiescence-scaled floor, compare resulting maps against RUN-NOTES'
`A2`/`A3` narratives) before any Kotlin work starts.

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

**Status:** OPEN. Resolution is issue #2 (see `PLAN.md`) — document the
decision and the `REVIEWED` interface contract (event shape, expected
source, how its weight would compose with existing evidence once
implemented) without implementing ingestion.

---

## Note: `ATTESTED` is declared but unimplemented

Not one of RUN-NOTES' two candidates, but adjacent and worth tracking here:
`SPEC.md` §2 lists `ATTESTED` as an evidence type v0.1 extracts (via
`.comprehension/attestations.yaml`), and `DEFAULTS["weights"]["ATTESTED"]`
exists in the prototype, but `collect()` never emits `ATTESTED` events — no
code reads `.comprehension/attestations.yaml`. This is a spec/prototype
conflict, not a calibration candidate: `ATTESTED` is supposed to exist in
v0.1, unlike `REVIEWED`. Flagged for `PLAN.md` sequencing rather than logged
as OPEN/ACCEPTED here, since it isn't a parameter change — it's a missing
implementation of already-decided scope.
