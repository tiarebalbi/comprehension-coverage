# First real run — express (robustness check), 2026-09-13

Repo: expressjs/express @ main (6,169 commits). Runtime: 1.7s. Config:
prototype/express-config.json. As-of pinned: 2026-09-13T00:00:00Z.
Raw output: fixtures/express-run.json.

## Result: 6 of 8 modules DARK; tests COVERED; request AT_RISK.

## Acceptance criteria assessment (SPEC §7)

A1 NON-OBVIOUS — PASS. The ownership/coverage mismatch is textbook: `router`'s
top evidence holder is Douglas Wilson at score 0.0023 — git blame calls him the
owner, the map says that evidence fully decayed (he stepped back; router also
moved to the separate router package in v5, so in-repo evidence froze). One of
the most-downloaded packages on earth maps as mostly dark — not because nobody
maintains it, but because maintenance activity no longer produces the evidence
classes v0.1 can see.

A2 EXPLAINABLE — PASS. `application` DARK reconstructs cleanly from the events:
the historical mass (TJ-era, Wilson-era) is beyond 20+ wall half-lives; recent
contributors are drive-by (small, saturation-limited commits); v5 stability
means almost no churn through which anyone could re-earn standing.

A3 ONE FAILURE — PASS, and it is the interesting kind. Current maintainers
(Ulises Gascón, Chris de Almeida) demonstrably hold theory of express — they
run releases and review every consequential PR — yet score near zero on core
modules and appear only via `tests`. Two distinct causes, both model-level:
  1. Missing evidence class: REVIEWED evidence is invisible to plain git history.
     The people who understand express today mostly *review*; v0.1 only sees
     *authorship*. Exactly the predicted failure: an evidence detector is blind
     to comprehension held in evidence classes it does not ingest.
  2. Wall-clock floor too aggressive for frozen modules: H_wall=180d erases a
     2023 deep contribution by 2026 even when the module barely changed since.
     The churn model would have preserved it; the floor killed it. Candidate
     calibration: lengthen H_wall substantially (or scale it by module
     quiescence) so churn remains the primary clock, per C3's intent.

A4 REFERENCE AGREEMENT — pending Kotlin implementation (golden fixture ready:
fixtures/golden-synthetic.json).

## Calibration candidates for CALIBRATION.md (not yet applied)
- H_wall: 180d -> consider 540d, or quiescence-scaled floor. Driven by A3.2.
- OSS-vs-team caveat: in a team repo, `departed`/team-roster config prevents
  drive-by contributors from muddying counts; on OSS the θ_person threshold did
  that job adequately here.

## Editorial read for essay #06
The robustness check already delivers the one-line class-of-pattern claim: an
unrelated, famously-maintained repository maps mostly dark under evidence the
instrument can currently see, with the gap concentrated exactly where review
work replaced authorship work. The narrative specimen (author's own repo) is
still needed for the personal-surprise arc.
