# Comprehension Coverage

Codebases outlive the people who understood them. Authorship graphs and
`git blame` tell you who *touched* a module, not who could still explain it —
and as agent-mediated commits become a larger share of history, that gap
widens: a file can be rewritten end to end without a single human building a
mental model of it. Comprehension coverage is a per-module, evidence-based,
churn-decaying map of which humans still plausibly understand a codebase. It
does not claim to read minds; it scores the evidence of comprehension it can
observe (authorship, agent-mediated commits, attestations) and decays that
evidence as the module changes under it and as time passes. The concept is
introduced in ["Introducing Comprehension Coverage"](https://tiarebalbi.com)
on tiarebalbi.com; the diagnosis it responds to is Wheeler's *The Substrate
Collapse* ([arXiv:2606.20882](https://arxiv.org/abs/2606.20882)) — the
observation that AI-accelerated change can outpace the humans responsible for
a system's ability to explain itself.

**Status:** spec v0.1 (see [`SPEC.md`](SPEC.md)) with a Python reference
prototype (`prototype/comprehension.py`) that implements the scoring
semantics exactly. A Kotlin implementation is planned; until it lands and
passes reference-agreement (SPEC §7 A4), the prototype is the only
executable form of the instrument. Tunable parameters in the spec are
provisional pending calibration — see [`CALIBRATION.md`](CALIBRATION.md).

## Running the prototype

Requires Python 3.10+ and `git` on `PATH`. No third-party dependencies.

```sh
python3 prototype/comprehension.py \
  --repo /path/to/target/repo \
  --config prototype/express-config.json \
  --as-of 2026-09-13T00:00:00Z \
  --json out.json
```

- `--repo` — path to the git repository to analyze.
- `--config` — JSON config mapping module names to path globs (see
  `prototype/express-config.json` for an example), plus any overrides to the
  tunable parameters in `SPEC.md` §4.
- `--as-of` — the instant to score as of, ISO-8601 with an explicit UTC
  offset (e.g. `Z`). Required for reproducible results; omitting it falls
  back to the timestamp of the repo's last commit.
- `--gate` — exit non-zero if any `critical:` module in the config is `DARK`
  (or `AT_RISK`, depending on config). Intended for CI gating of
  agent-authored changes to modules nobody currently comprehends.
- `--show-individuals` — print per-person scores. Off by default (see C5
  below); intended for the person themselves or team-local use.

Run the test suite (unit tests + golden fixture regeneration) with:

```sh
python3 prototype/test_comprehension.py
```

This must pass, and `fixtures/golden-synthetic.json` must not change, before
any change to the prototype is merged (see `CLAUDE.md`).

## What this does not measure

(SPEC §6, and constitutional decision C2.)

- **This is not a comprehension detector.** It is an evidence detector with
  an explicit model of staleness. It cannot know what anyone actually
  remembers or understands — only how recent and substantial the recorded
  evidence of engagement with a module is, and how much that evidence has
  decayed. A person can score high and have forgotten everything; a person
  can score low and still hold full working knowledge the tool has no
  evidence class for.
- **Evidence ≠ comprehension.** False positives and false negatives are
  expected, not a bug to be silently patched away — SPEC §7 requires each
  first run to hunt for at least one of each.
- **Agent detection is marker-based and under-counts.** It relies on
  co-author trailers and known bot patterns; a repo with poor trailer
  hygiene will misclassify agent-mediated work as hand authorship. Where all
  history is marker-free, the instrument degrades toward a churn-decayed
  authorship map — still novel, but closer to prior art.
- **v0.1 only sees git history.** Review, incident diagnosis, and ADR
  authorship are real evidence of comprehension but are not implemented in
  v0.1 — they are reserved schema interfaces only (SPEC §2). A codebase
  whose comprehension is sustained mainly through review, not authorship,
  will map darker than it plausibly is.
- **Squash merges erase co-author granularity**, and renames are tracked at
  module level only.
- **No individual is named by default.** Module aggregates are public;
  per-person scores are private and appear only behind `--show-individuals`
  (C5). This tool is not designed or intended for individual performance
  evaluation.

## License

Apache-2.0. See [`LICENSE`](LICENSE).
