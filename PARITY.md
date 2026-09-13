# A4 parity run — Kotlin vs. prototype

SPEC §7 A4: "Kotlin output is byte-identical to prototype JSON on the golden
fixtures before any real-repo number is published." PLAN.md #10 scope. This
is that run.

## Result: PASS

Both fixtures below are byte-identical between `prototype/comprehension.py`
and the Kotlin CLI on every output mode: console map, `--json`, console
`--show-individuals`, and console `--gate` (including exit code). No Kotlin
bug was found or fixed in this run — every comparison was already
byte-identical as of `1ef6c1f` (PR #26, gate mode + break-glass).

Diffing was scripted, not eyeballed: `scripts/parity_check.py`, committed
and wired into CI (`kotlin` job) for the synthetic-repo path so this check
runs on every push, not just this one-off local run.

## Runner of record (C6 determinism contract)

- **JVM:** GraalVM 21.0.10+8.1 (Oracle GraalVM, JVMCI 23.1-b84), aarch64,
  via `java version "21.0.10" 2026-01-20 LTS`.
- **Platform:** macOS (Darwin 25.6.0, arm64, Apple Silicon).
- **Python:** 3.14.7 (`/opt/homebrew/bin/python3`).
- **Git:** 2.50.1 (Apple Git-155).

This matters beyond bookkeeping: `ScoringTest.kt`'s
`` `half pow x stays within one ULP of Python at a half-integer exponent` ``
documents that `Math.pow`'s result for `0.5.pow(2.5)` differs by 1 ULP
between this exact JVM (GraalVM 21.0.10, aarch64) and this project's CI JVM
(Temurin 21, Linux x64) for identical Kotlin bytecode — a vendor/platform
divergence, not a Kotlin-vs-Python one. Neither express nor the synthetic
fixture happened to land a score on that razor's edge in this run (both
passed byte-identical here), but that is a property of this run's specific
inputs, not a guarantee. The JVM named above is the one this PARITY.md's
PASS verdict is conditioned on; CI's Temurin run is a second, independent
data point (also passing, on the synthetic fixture) but per the ScoringTest
finding is not guaranteed to remain bit-identical to this one on arbitrary
future data. No fix is proposed here — pinning the JVM distribution,
rounding before comparison, or a shared correctly-rounded `pow` are the
named options in `ScoringTest.kt`, left for a future issue if this class of
divergence is ever actually observed in a real score rather than a
targeted unit test.

## Fixture (a): golden-synthetic, rebuilt from the prototype's builder

Rebuilt via `prototype/test_comprehension.py`'s `build_synthetic_repo()` —
not the committed `fixtures/golden-synthetic.json` (that fixture captures
internal per-person `scores`, which the CLI's public JSON deliberately
omits per C5; it is not the artifact this gate compares). `T0 = 1700000000`,
`as_of = T0 + 330*86400` = `2024-10-09T22:13:20Z`, config
`{"modules": {"core": ["src/core/*"], "web": ["src/web/*"]}, "identity":
{"dana@example.com": "Dana"}}`, plus a `critical: ["core", "web"]` variant
for the `--gate` check.

Command:

```
python3 scripts/parity_check.py --synthetic
```

which builds the repo fresh via the prototype's own `build_synthetic_repo`
(no hand-authored git history) and internally runs the equivalent of:

```
python3 prototype/comprehension.py --repo <repo> --config <config.json> \
    --as-of 2024-10-09T22:13:20Z --json <out.json>
python3 prototype/comprehension.py --repo <repo> --config <config.json> \
    --as-of 2024-10-09T22:13:20Z --show-individuals
python3 prototype/comprehension.py --repo <repo> --config <gate-config.json> \
    --as-of 2024-10-09T22:13:20Z --gate

java -cp <kotlin-runtime-classpath> com.tiarebalbi.comprehensioncoverage.MainKt \
    --repo <repo> --config <config.json> --as-of 2024-10-09T22:13:20Z --json <out.json>
java -cp <kotlin-runtime-classpath> com.tiarebalbi.comprehensioncoverage.MainKt \
    --repo <repo> --config <config.json> --as-of 2024-10-09T22:13:20Z --show-individuals
java -cp <kotlin-runtime-classpath> com.tiarebalbi.comprehensioncoverage.MainKt \
    --repo <repo> --config <gate-config.json> --as-of 2024-10-09T22:13:20Z --gate
```

Result: `[map console] IDENTICAL (exit=0)`, `[map json] IDENTICAL`,
`[show-individuals console] IDENTICAL (exit=0)`,
`[gate console + exit code] IDENTICAL (exit=2)`.

## Fixture (b): expressjs/express, pinned clone

- **Repo:** `https://github.com/expressjs/express.git`
- **Pinned SHA:** `3ce6d0eb86e9d93529ff3191c6bb5db8ce6e72c8`
  (`ci: add npm staged publication with dist-tag support (#7464)`)
- **History depth:** 6,169 commits reachable from that SHA.
- **Config:** committed `prototype/express-config.json` (unmodified).
- **`--as-of`:** `2026-09-13T00:00:00Z`.

Commands used to produce the pinned clone:

```
git clone https://github.com/expressjs/express.git express-clone
git -C express-clone checkout 3ce6d0eb86e9d93529ff3191c6bb5db8ce6e72c8
```

Parity command:

```
python3 scripts/parity_check.py \
    --repo <path-to-express-clone> \
    --config prototype/express-config.json \
    --as-of 2026-09-13T00:00:00Z
```

which internally runs the same six invocations as above (prototype ×3,
Kotlin ×3) against the pinned clone instead of the synthetic repo. This
express-scale run is not wired into CI (network clone of full history is
slow and non-hermetic for a CI job); it is the documented, repeatable,
manually-invoked form of the same script CI runs automatically on the
synthetic fixture.

Result: `[map console] IDENTICAL (exit=0)`, `[map json] IDENTICAL`,
`[show-individuals console] IDENTICAL (exit=0)`,
`[gate console + exit code] IDENTICAL (exit=2)`.

Map: 7 of 8 modules DARK (`application`, `middleware`, `request`,
`response`, `router`, `utils`, `view`); `tests` is the sole COVERED module.
`--gate` (`critical: [application, response, router]`) exits 2 on all
three, with byte-identical remediation text from both implementations:

```
GATE: DARK critical module(s): application, response, router — an agent change here cannot merge without re-establishing comprehension.
  application: Phillip Barta holds the strongest remaining evidence; last AUTHORED evidence 612d before as-of.
  response: Sebastian Beltran holds the strongest remaining evidence; last AUTHORED evidence 236d before as-of.
  router: Douglas Christopher Wilson holds the strongest remaining evidence; last AUTHORED evidence 1297d before as-of.
```

Note: this is a different SHA and `as_of` than `RUN-NOTES.md`'s earlier
robustness-check run (`@main`, 6,169 commits at the time, no pinned SHA
recorded). `fixtures/express-run.json` is not a reference for this run and
divergence from it is expected, not drift.

## Repeatable check

`scripts/parity_check.py` — takes `--synthetic` (self-contained, CI-safe)
or `--repo`/`--config`/`--as-of`/`--gate-config` (generic, for a real
clone). Diffs exit codes and stdout byte-for-byte across map console,
`--json`, `--show-individuals`, and `--gate`; prints `PARITY: PASS`/`FAIL`
and exits non-zero on any mismatch. Wired into `.github/workflows/ci.yml`'s
`kotlin` job as `python3 scripts/parity_check.py --synthetic`.
