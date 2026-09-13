# Plan review

## Three riskiest items

**#10 — A4 parity run.** This is where the plan is most likely to look done
before it is done. Float serialization is the concrete failure mode: Python
drops trailing zeros (`round(0.26727002, 6)` → `0.26727`) and switches to
scientific notation below `1e-4`, while Java's default `Double.toString`
switches below `1e-3`. A naive Kotlin JSON writer using `%.6f` or
`toString()` will produce a plausible-looking, byte-different file and pass
every test that doesn't specifically check for this. RUN-NOTES already puts
real scores in the danger band (`router` top score: `0.0023`), so this
isn't a theoretical edge case — it's squarely inside the first real dataset
this project cares about. Mitigation is in #8/#10's constraints, but the
risk is real enough to name here: budget explicit time for a custom
float-to-JSON serializer, not "get the math right and see what happens."

**#6 — GitSource ingestion / the pinned `git log` invocation.** The
prototype's `git log --all --numstat --no-renames --date-order --reverse`
is not fully deterministic as specified: `--all` means the result depends
on which refs are present locally (a fresh clone vs. one with extra
fetched branches gives different history), and `--date-order`'s tie-break
for same-timestamp commits isn't guaranteed stable across git versions.
This is a C6 ("deterministic... same repository state + same config = same
map, bit for bit") violation hiding in the reference implementation
itself. Issue #3 is supposed to close this before Kotlin starts, but the
fix requires picking an actual replacement contract (first-parent of a
named ref? all reachable commits from a named ref, explicitly excluding
other local refs?) — that's a design decision, not a one-line pin, and
should not be treated as a checkbox inside #3.

**#1 — H_wall experiment on an unreplayable baseline.** RUN-NOTES documents
"express @ main (6,169 commits)" with no commit SHA. `main` moves; by the
time #1 runs, "express @ main" is a different repository state than the
one RUN-NOTES describes, and the two won't be comparable. If #1 proceeds
without first pinning and recording a SHA, the calibration decision is
being made against a moving target and can't be audited later. This is
flagged as a constraint in #1, but it's worth stating plainly here: no
experiment should run until a SHA is chosen and written down, even before
touching `H_wall` values.

## What golden-fixture parity can miss

- **Float formatting near zero.** The golden-synthetic fixture's three
  scores (`0.080586`, `0.26727`, `0.811964`) all sit comfortably above the
  `1e-3`–`1e-4` band where Python and Java diverge on scientific-notation
  thresholds, and all happen to need the "drop trailing zero" behavior in a
  way that's easy to special-case without actually generalizing it. A
  Kotlin implementation could special-case its way to fixture parity here
  and still fail on express-scale data. **Verified:** confirmed directly —
  `json.dumps(round(0.26727002, 6))` → `0.26727` (no trailing zero) and
  `json.dumps(0.0009)` → `0.0009` while Java's `Double.toString(0.0009)` →
  `9.0E-4`. The fixture is real evidence of the first divergence and zero
  evidence of the second.
- **JSON key ordering.** Confirmed correct in the current prototype:
  `json.dump(..., sort_keys=True)` at both `comprehension.py:251` and
  `test_comprehension.py:149`. One subtlety worth flagging for the Kotlin
  port: the `scores` dict's keys are the *concatenated* string
  `"person|module"`, and `sort_keys=True` sorts on that concatenated
  string — not on `(person, module)` as a tuple. The two orderings agree
  whenever no person name contains `|` or sorts unusually relative to
  module names, which is true of the golden fixture's three keys
  (`Alice`, `Bob`, `Tiare` vs. `core`, `web` — no ambiguity). A Kotlin
  implementation that sorts by tuple instead of concatenated string could
  pass the golden fixture and still diverge on a real repo with names that
  interact with `|`-based sorting differently.
- **Timezone handling.** Two separate findings, not one:
  - Evidence timestamps themselves are genuinely UTC-safe: `%at` (used in
    both `git log` numstat parsing and the no-`--as-of` fallback) is Unix
    epoch seconds, timezone-free by construction.
  - The `--as-of` **CLI argument** is not safe as written. `comprehension.py:236`
    does `datetime.fromisoformat(a.as_of.replace("Z","+00:00")).timestamp()`.
    A caller who passes `--as-of 2026-09-13T00:00:00` (no `Z`, no offset)
    gets a timezone-naive `datetime`, and `.timestamp()` on a naive
    `datetime` resolves it in the *local* timezone of whatever machine runs
    the tool — a direct violation of C6 ("no wall-clock reads... the 'as
    of' instant is an explicit input") since the same command produces
    different scores on machines in different timezones. This is real, not
    hypothetical, and is scoped as issue #3 rather than left as a note:
    the fix (reject tz-naive input) is small but must ship before Kotlin
    has a stable, actually-deterministic contract to port.

## Spec ambiguity to resolve before issue #1

`SPEC.md` §4 lists `H_wall` as a single scalar (currently 180 days) to be
recalibrated. But `RUN-NOTES.md`'s own proposed fix includes "a
quiescence-scaled floor," which is not a parameter change — it's a new
term in the §3 formula (`H_wall` becomes a function of the module's own
churn rate rather than a constant). These are different sizes of change:
one is a tunables-table edit within the existing calibration-commit
process; the other is a change to the *scoring formula itself*, which sits
closer to constitutional territory (C3 states the *existence* of a
wall-clock floor is fixed; its *shape* is presumably tunable, but SPEC
doesn't say so explicitly). Before issue #1 starts, this should be
resolved: is a quiescence-scaled floor in scope for a "tunable parameter"
calibration issue, or does introducing a new formula term require treating
it as a scope decision on par with issues #1/#2 rather than a same-day
experiment? Recommend deciding this narrowly — e.g. "any change that adds
a new term to the §3 formula, not just a new value for an existing
constant, requires a written note in SPEC §3 alongside the §4 table
update" — before #1 is picked up, so the issue doesn't stall mid-experiment
on a process question.
