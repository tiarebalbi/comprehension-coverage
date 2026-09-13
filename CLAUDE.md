# Project conventions — comprehension-coverage

Read `SPEC.md` before touching scoring, evidence, or output semantics.
Constitutional sections (§1, §2's event model, C1–C7) are fixed; the
tunables table (§4) is provisional pending calibration.

## The spec-wins rule

Where code and `SPEC.md` disagree, the spec wins. Do not silently resolve a
conflict in either direction — flag it (in the PR description, in
`CALIBRATION.md` if it's a tunable, or as a new issue if it's a scope gap)
and let a human decide before changing behavior to match one or the other.

## The golden-fixture rule (SPEC §7 A4)

`fixtures/golden-synthetic.json` is a committed artifact, not a build
output. The Kotlin implementation must reproduce it byte-for-byte. Rules:

- Never hand-edit files under `fixtures/`.
- Only `python3 prototype/test_comprehension.py` regenerates
  `golden-synthetic.json`. If your change causes it to differ, that's a
  signal to look hard before committing the new fixture — CI fails the
  build on any undeclared drift (`.github/workflows/ci.yml`).
- A4 parity work must account for JVM/Python float-formatting and
  string-sort differences, not just the scoring math. Don't assume "the
  math matches" implies "the JSON is byte-identical" — verify the actual
  serialized bytes.

## Trunk-based flow

- One issue = one short-lived branch = one PR. Squash merge into `main`.
- `main` is always green (CI passing) — no long-lived phase branches.
- Keep PRs reviewable in one sitting: ~200–400 lines of non-generated diff.
  Split anything bigger into sequenced issues (generated scaffolding, e.g. a
  Gradle wrapper, doesn't count against this budget — say so in the PR).

## Phase tags

- `v0.1-spec` — tagged on the commit that establishes spec + prototype +
  fixtures + repo scaffolding (this is the starting point).
- `v0.1-calibration` — tagged once the calibration decisions in
  `CALIBRATION.md` land, the spec tunables table is updated to match, and
  fixtures are regenerated.
- `v0.1-kotlin` — tagged once the Kotlin implementation passes A4
  (byte-identical output to the prototype on the golden fixtures).

## Calibration changes

A change to any PROVISIONAL parameter in `SPEC.md` §4 must be a single
commit that touches, together:

1. `prototype/comprehension.py` (the new default/behavior),
2. `SPEC.md` §4 (the tunables table),
3. `CALIBRATION.md` (observation → change → status, citing the driving run),
4. Regenerated `fixtures/golden-synthetic.json`.

Never split these across commits — a partial calibration commit leaves the
spec, code, and fixtures inconsistent with each other, which is exactly the
kind of silent drift A4 exists to catch.

## Commit style

Imperative subject line. Body explains *why* (the observation or decision
driving the change), not what (the diff already shows that). Reference the
issue number the commit closes or advances.

## Evidence classes: naming

`SPEC.md` §2 reserves the name `REVIEWED` for the forge-API evidence class
(declared interface, not implemented in v0.1). Use that spelling — not
`REVIEW` — anywhere it's referenced, so the name is stable when it lands.

## JSON: read with a library, write by hand

Config *input* (issue #7) may use `kotlinx-serialization-json` — its
`JsonObject` preserves key order, which `modules`' first-match-wins
resolution (`moduleOf`) depends on. Do not use `org.json`: its `JSONObject`
is backed by a plain `HashMap` and would silently break that ordering
guarantee. The *output* writer (issue #8) stays hand-rolled regardless: it
must byte-match Python's `json.dump(indent=2, sort_keys=True)`, and no
off-the-shelf pretty-printer reproduces its separator and float-repr
conventions (see the golden-fixture rule above). Do not treat the reader
dependency as license to serialize output through the library.
