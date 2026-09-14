# Plan — v0.2: shippable surfaces (executable CLI + Gradle plugin)

Ordered, GitHub-issue-ready work items, same conventions as `PLAN.md` (v0.1):
each is sized to be reviewable in one sitting (~200–400 lines of non-generated
diff; mechanical file moves and generated scaffolding are called out and
excluded from that budget). Every item names its blocking work.

Items use **stable local ids** (A1, B2, …). All 19 are filed against milestone
[`v0.2`](../../milestone/2), and every issue title is prefixed with its local
id, so the `Blocked by: A2`-style cross-references below resolve by title:

| Item | Issue | Item | Issue | Item | Issue |
|------|-------|------|-------|------|-------|
| A1 | #32 | C1 | #40 | D3 | #46 |
| A2 | #33 | C2 | #41 | D4 | #47 |
| A3 | #34 | C3 | #42 | E1 | #48 |
| B1 | #35 | C4 | #43 | E2 | #49 |
| B2 | #36 | D1 | #44 | E3 | #50 |
| B3 | #37 | D2 | #45 | | |
| B4 | #38 | | | | |
| B5 | #39 | | | | |

Labels: `phase:kotlin` (A), `phase:packaging` (B, D), `phase:gradle-plugin`
(C), `phase:library` (E).

Goal: the instrument currently runs only as `java -cp <hand-resolved
classpath> …MainKt` (which is why `scripts/parity_check.py` resolves the
classpath through a throwaway Gradle init script). v0.2 turns it into two
consumable surfaces — a packaged executable and a Gradle plugin — without
changing a single byte of its output, and publishes the executable as
downloadable macOS and Linux binaries on GitHub Releases.

## The invariant this whole plan is organized around

A4 (SPEC §7) says the implementation must be byte-identical to the Python
prototype. v0.2 adds **two new surfaces**, and each is a new opportunity for
output to drift: a packaged launcher brings its own JVM args, charset and
locale; a Gradle plugin brings its own config model and process handling.

So the structural rule for every item below: **the prototype stays the
reference, `scripts/parity_check.py` stays the judge, and every new surface
must pass it before it ships.** That is what A2 exists to make possible, and
it's why B and C can run in parallel.

## Flow

```
A1 → A2 → ( B1 → B2 → B3 → B4 → B5 ‖ C1 → C2 → C3 → C4 ) → D1 ‖ D2 ‖ D3 ‖ D4
                                 │      └── C3 blocked by #31 ──┘         ▲
                                 └──── D4 uploads only what B5 verified ──┘

A1 → ( E1 ‖ E2 ‖ E3 )    ← nothing publishes at D1 until these land
```

## Reframing: a library, not essay support

The project is shifting from "instrument that produces blog essay #06" to
"library other people depend on." Three consequences that change this plan,
not just its tone:

1. **Issue #29 flips from preserve-to-fix.** It is currently marked
   **DO-NOT-FIX** because a bot clearing `theta_person` is SPEC §7 A3's
   *required failure* — the evidence the essay needed. For a library, a
   comprehender count that credits `copilot-swe-agent[bot]` as a module's sole
   comprehender is a correctness defect shipped to strangers. That hold was
   predicated on the essay, and the essay is no longer the point.
   **Recommendation: #29 becomes a release blocker on D1.** The field-test
   observations file already preserves the finding as a record, so fixing the
   behavior no longer destroys the evidence. Owner's call, but it should be an
   explicit one rather than an inherited default.
2. **SPEC §7's framing needs a human decision.** A1–A3 are labelled "editorial
   gate for essay #06." A4 survives untouched as an engineering contract, but
   the other three now describe a purpose the project is leaving behind. Per
   the spec-wins rule this plan flags it and stops: someone decides whether §7
   is rewritten as library acceptance criteria, retained as historical record,
   or split in two.
3. **The prototype's role narrows.** It stops being "the only implementation
   anyone should run" and becomes the internal oracle the library is verified
   against. That is a README framing change, not a semantics change — the
   spec-wins rule, the golden-fixture rule, and A4 all still point at it.

Phase E exists because of this shift.

---

## Phase A — Restructure (blocks everything)

### A1 — Split into `:core` and `:cli` modules (no behavior change)

**Scope:** Convert the single-module build into `:core` (`config`, `scoring`,
`report`, `git`, `gate`, `Timestamps.kt`) and `:cli` (`Cli.kt`, `Main.kt`).
~1,327 lines of main source **moved**, essentially none rewritten. Update
`settings.gradle.kts`, split `build.gradle.kts`, and update
`scripts/parity_check.py`, which currently hardcodes the root project's
`runtimeClasspath` plus `build/classes/kotlin/main` and will break on the
move. Blocked by: nothing.

**Constraints:** Keep package names (`com.tiarebalbi.comprehensioncoverage.*`)
stable — no import churn, and no coordinate break for anyone already
consuming the tag. `kotlinx-serialization-json` stays a `:core` dependency for
config *reading* only; the output writer stays hand-rolled (CLAUDE.md). Do not
touch `fixtures/` (golden-fixture rule). Byte-identical output is the
acceptance test, not a nice-to-have: this is a refactor whose entire success
condition is "nothing changed."

**Done criteria:** `./gradlew build` green; `scripts/parity_check.py
--synthetic` PASS; the pinned-express parity run PASS; `git diff --exit-code --
fixtures/` clean.

**Test requirements:** Existing suites move with their modules; no new tests.
The parity script is the regression guard.

**Estimated review size:** Large but mechanical — the PR body must state the
moves-vs-rewrites split so reviewers can read the diff as a move.

### A2 — Make the parity harness surface-agnostic (`--runner`)

**Scope:** Refactor `scripts/parity_check.py` so "how the Kotlin side is
invoked" becomes a pluggable runner: `classpath` (today's behavior), plus
`dist` (B3) and `gradle-plugin` (C4) later. The prototype side and the diff
logic stay as they are. Blocked by: A1.

**Constraints:** Both existing invocation forms (`--synthetic`, and
`--repo`/`--config`/`--as-of`) and the CI call must keep working unchanged.
Adding a runner must not require touching the diff logic. **This is the item
that makes B and C genuinely parallel instead of nominally parallel** — without
it, both tracks would each grow their own ad-hoc parity check.

**Done criteria:** `--runner classpath` reproduces today's PASS on both
fixtures; a new runner is a self-contained addition.

**Test requirements:** The harness's own PASS/FAIL behavior verified by
pointing it at a deliberately mismatched runner once.

**Estimated review size:** ~100–150 lines.

### A3 — Version catalog + shared build conventions (optional)

**Scope:** `gradle/libs.versions.toml` and a convention plugin for the shared
Kotlin/JVM setup, so three modules don't repeat toolchain and test config.
Blocked by: A1.

**Constraints:** Pure build hygiene; zero behavior change. Fold into A1 if
reviewers would rather see one build change than two.

**Estimated review size:** ~60–100 lines (generated/config, not logic).

---

## Phase B — CLI as an executable

### B1 — `application` plugin + distribution

**Scope:** Apply `application` to `:cli`, set `mainClass`, produce
`installDist`/`distZip`/`distTar` with real start scripts. Blocked by: A2.

**Constraints:** The 0/1/2 exit-code contract is SPEC §5.2 semantics — a
launcher that swallows exit 2 silently disables the gate, so exit-code
propagation through the generated script is an explicit test, not an
assumption. Pin JVM args for charset here (see B2).

**Done criteria:** `./gradlew :cli:installDist` yields a runnable script; a
gate run through the script exits 2.

**Test requirements:** Script-level test asserting each exit code; output
parity comes in B3.

**Estimated review size:** ~80–150 lines.

### B2 — Pin charset/locale, and prove it under hostile environments

**Scope:** Pin `-Dfile.encoding=UTF-8` and `-Dstdout.encoding=UTF-8` (and
stderr) in `applicationDefaultJvmArgs`; change `report/Json.kt`'s
`"\\u%04x".format(...)` to an explicitly `Locale.ROOT`-formatted call; add a
test matrix that runs the golden fixture under hostile environments (`LANG=C`,
`-Duser.language=tr -Duser.country=TR`, and a locale with non-ASCII digits) and
asserts byte-identical output. Blocked by: B1.

**Constraints:** This is **latent in the current code, not introduced by
packaging.** `System.out` has no charset pinned anywhere in the codebase, and
the console map emits non-ASCII (`█`/`▓`/`░` and the `—` in the GATE line), so
a packaged run under `LANG=C` or cp1252 can produce different bytes and break
A4 on someone else's machine. Every parity run to date has been on a UTF-8
host, which is why this hasn't surfaced.

Two things are **verified safe and must stay that way** — the hostile-locale
test is their guard, since both are easy to regress without noticing:

- Kotlin's `lowercase()` is locale-independent (unlike Java's
  `toLowerCase()`). The 8 identity/agent-detection call sites in
  `git/Ingestion.kt`, `git/GitSource.kt` and `git/Attestations.kt` are
  therefore correct today; switching any of them to `toLowerCase()` or
  `lowercase(Locale.getDefault())` would change agent detection under a
  Turkish locale.
- `File.readText`/`writeText` default to UTF-8 in Kotlin's stdlib, so config
  reading and the break-glass stub write are already charset-stable.

**Done criteria:** Parity PASS under every environment in the matrix; no
default-locale formatting left in the output path.

**Test requirements:** The hostile-environment matrix above, run against the
golden fixture; at least one case asserted on raw bytes, not decoded strings.

**Estimated review size:** ~120–200 lines (mostly test matrix).

### B3 — Parity for the packaged artifact (`--runner dist`)

**Scope:** Add the `dist` runner to A2's harness and wire it into CI after
`installDist`, so the thing that actually ships is the thing that's verified.
Blocked by: A2, B1.

**Done criteria:** The packaged CLI is byte-identical to the prototype on both
the synthetic fixture and the pinned express clone.

**Estimated review size:** ~60–100 lines.

### B4 — DECISION (narrowed): per-platform executables for macOS and Linux

**Scope:** Produce genuine per-platform executables for macOS and Linux, for
upload to GitHub Releases (D4). Blocked by: B3.

**The decision is narrowed, not open.** "An executable for mac and linux" rules
out the platform-independent options — a `distZip` or fat jar is one artifact
that requires a JVM already installed, not a per-platform executable. What's
left:

- (a) **GraalVM `native-image`** — true standalone single-file binaries, fast
  startup, no JVM required on the user's machine.
- (b) **`jlink`/`jpackage`** — per-platform images bundling a trimmed JRE;
  larger, but keeps the HotSpot math that PARITY.md's current verdict was
  actually measured against.

**Constraints:** `native-image` ships its own math implementation, so each
binary is a **new A4 surface that must be re-verified** — issue #30's
`Math.pow` ≤1 ULP vendor-divergence finding is precisely this axis, and B5 is
where the verification happens. In exchange it *pins* both the math and the
runtime into the artifact, which is the strongest available answer to #30: a
shipped binary can no longer drift with whatever JVM the user happens to have.
`jlink` keeps HotSpot semantics but still pins one specific JDK build per
artifact. Either way, C6's determinism contract gains a concrete runner of
record *per platform* instead of a caveat — which is the substance of #30.

**Done criteria:** `./gradlew` produces a runnable single-file executable for
the host platform; it passes B5's parity check; exit codes 0/1/2 survive.

**Test requirements:** B5's matrix, plus a startup smoke test on the built
binary asserting `--version` and a gate exit-2 path.

**Estimated review size:** ~150–250 lines (build config + smoke tests).

### B5 — Cross-platform parity matrix

**Scope:** Extend `ci.yml`'s parity step into an OS matrix (ubuntu + macos)
running A2's `dist` runner on each, so platform drift is caught on pull
requests rather than discovered at release time. Update `PARITY.md`: with
per-platform binaries, "runner of record" stops being a single JVM and becomes
a matrix. Blocked by: B3, B4.

**Constraints:** Each published binary is its own A4 surface — that is the
direct consequence of B4's per-platform packaging, and it is what turns the
ULP and charset concerns from theoretical into tested. B2's hostile-locale
cases belong inside this matrix rather than as a separate one-off run.

**Done criteria:** Parity PASS on every matrix cell; a deliberately broken
single cell fails CI.

**Test requirements:** The matrix is the test. Verify it can fail (point one
cell at a mismatched runner once).

**Estimated review size:** ~80–150 lines (workflow + `PARITY.md`).

---

## Phase C — Gradle plugin

### C1 — `:gradle-plugin` module + extension DSL

**Scope:** New module with `java-gradle-plugin`, a plugin id (proposal:
`com.tiarebalbi.comprehension-coverage`), and an extension exposing repo,
config, as-of, critical modules and the module map, depending on `:core`.
Blocked by: A2.

**Constraints:** **Module declaration order is load-bearing.** `moduleOf` is
first-match-wins over the declared module map (which is exactly why CLAUDE.md
mandates an order-preserving JSON reader), so the DSL must use an ordered type
— an ordered list of module specs. Gradle's container and map property types
either reorder entries or don't guarantee insertion order, and either would
silently change scoring rather than fail loudly. The plugin should *also*
accept the existing JSON config file, so plugin and CLI share one config
source of truth and a second config dialect can't drift from SPEC.

**Done criteria:** `plugins { id("…") }` applies in a fixture build;
`validatePlugins` clean.

**Estimated review size:** ~200–300 lines.

### C2 — Task implementation, configuration cache, up-to-date semantics

**Scope:** A report task and a gate task; git invoked from the task action,
never at configuration time. Blocked by: C1.

**Constraints:** Gradle's configuration cache forbids external process
execution at configuration time, so the `git log` invocation must go through
`ValueSource`/`ExecOperations`. More importantly, **the up-to-date check is a
correctness hazard, not an efficiency one**: `--as-of` defaults to the last
commit timestamp, so a stale `UP-TO-DATE` on a gate task means a gate that
silently *passes* on a repo whose comprehension has since decayed — a failure
of the instrument's entire purpose. Default the gate task to
`outputs.upToDateWhen { false }` and let review argue it down, rather than
trying to enumerate inputs correctly on the first pass. Gate exit codes map to
build outcomes: 2 (DARK critical) fails the build; 1 (AT_RISK, configurable)
fails only when enabled.

**Done criteria:** Gate task fails the build under exit-2 conditions; builds
clean with `--configuration-cache`; re-runs when HEAD moves.

**Estimated review size:** ~250–350 lines.

### C3 — Break-glass on the plugin surface (hard-blocked by #31)

**Scope:** Expose break-glass as an explicit task option that is never
default-on. Blocked by: C2, **and issue #31**.

**Constraints:** `gate/Gate.kt`'s `writeBreakGlassStub` does `File(repo,
".comprehension/attestations.yaml")` — an unmanaged write to an arbitrary path.
Inside a build that breaks Gradle's input/output model: it writes into the
project directory with no declared output. It must either go through Gradle's
file APIs with a declared `@OutputFile`, or be refused on the plugin surface
entirely and remain a CLI-only escape hatch. **That is a design decision, not
a port.**

**#31 is a hard blocker, not a nice-to-have:** break-glass takes an incident
ref, incident refs routinely contain `#` (`INCIDENT #42`), and the attestations
reader truncates at `#`. So the headline feature of this item writes a
corrupted record for its single most likely input.

C5 privacy applies to the DSL too: individual scores stay off by default.

**Estimated review size:** ~150–250 lines.

### C4 — TestKit functional tests + plugin parity (`--runner gradle-plugin`)

**Scope:** GradleTestKit fixture project asserting report contents, gate
build-failure mapping and configuration-cache reuse; plus the `gradle-plugin`
runner in A2's harness, extending A4 to this third surface. Blocked by: C2
(C3 if break-glass ships on this surface).

**Done criteria:** Plugin-produced JSON byte-identical to the prototype on both
fixtures.

**Estimated review size:** ~200–300 lines.

---

## Phase D — Publish and document

### D1 — DECISION: publishing targets

**Scope:** `:core`/`:cli` via JitPack (the path open issue #11 already names)
or Maven Central; the plugin via `com.gradle.plugin-publish` to the Gradle
Plugin Portal — effectively required for `plugins {}` DSL resolution without
asking consumers to add a custom repository. Blocked by: B3, C4, **E1–E3**, and
(recommended, see Reframing) **#29**.

**Constraints:** Reconcile with issue #11, which is now partly stale — the
`v0.1-kotlin` tag it calls for already exists. Decide whether #11 absorbs D1
or is closed in favour of it.

### D2 — Docs, and a FLAGGED SPEC decision

**Scope:** README usage for all three surfaces (prototype, executable,
plugin), keeping the prototype documented as reference semantics rather than a
deprecated artifact. Blocked by: B3, C4.

**Constraints:** A4 currently reads "Kotlin output is byte-identical to
prototype JSON on the golden fixtures." With three shipped surfaces, the
contract needs to name them. **That is a SPEC change, so per the spec-wins rule
this item writes up what the amendment would need to say and stops — a human
decides.** Do not amend SPEC as a side effect of shipping.

### D3 — Labels, tags, milestone

**Scope:** Labels `phase:packaging`, `phase:gradle-plugin` and `phase:library`,
plus milestone `v0.2` — **all three labels and the milestone already exist**,
created when this plan's issues were filed, so what remains here is only the
tags: `v0.2-cli` and `v0.2-gradle-plugin`, cut after their phases merge (never
on pre-squash commits, per CLAUDE.md).

### D4 — `release.yml`: build, verify, and publish the executables

**Scope:** A new workflow, separate from `ci.yml`, triggered on `v*` tag push
plus `workflow_dispatch` for dry runs. Build matrix produces the macOS and
Linux executables from B4; each runner executes B5's parity check against the
binary it just built; verified artifacts are handed between jobs via
`actions/upload-artifact`/`download-artifact`; the final job attaches them to
the GitHub Release along with a `SHA256SUMS` file. Blocked by: B4, B5.

**Constraints:**

- **Keep it out of `ci.yml`.** Uploading release assets needs `permissions:
  contents: write`, and that permission has no business being granted on every
  pull request. `ci.yml` stays the per-push gate; `release.yml` is
  tag-triggered.
- **Upload exactly the bytes that passed parity.** The publish job must
  download the artifacts the matrix built and verified — never rebuild them.
  Rebuilding after verification breaks the chain of custody that A4 exists to
  establish in the first place.
- **The release is parity-gated.** If any platform's binary fails A4, the whole
  release fails. A partially-verified release is worse than no release, because
  the numbers the tool prints *are* the product.
- **One runner per target.** `native-image` does not cross-compile, so each
  OS/arch needs its own runner: `macos-latest` (arm64), `macos-13` (x86_64),
  `ubuntu-latest` (linux x86_64). Linux arm64 is a separate call — dedicated
  arm runner, or ship without it initially.
- **Pin action versions**, consistent with the practice set by the "Bump CI
  action versions" commit — including the GraalVM setup action and whichever
  release action is used (or plain `gh release upload`).
- Asset naming `comprehension-coverage-<version>-<os>-<arch>`, shipped as
  `.tar.gz` so the executable bit and `LICENSE` survive the download. Attach
  the platform-independent JVM `distZip` too, as the fallback for platforms
  with no native binary.

**Two distribution facts to decide, not discover:**

- **macOS:** an unsigned binary downloaded through a browser is quarantined and
  refuses to run until the user clears `com.apple.quarantine` (or
  right-click-opens it). Notarizing requires an Apple Developer account.
  Decide: accept it and document the workaround, or notarize.
- **Linux:** a `native-image` binary links against the build runner's glibc, so
  it won't run on older distros or on musl (Alpine). Decide: document a minimum
  glibc, or build fully static against musl.

**Done criteria:** Pushing a `v0.2.x` tag produces a GitHub Release carrying
verified macOS and Linux executables plus `SHA256SUMS`; a parity failure on any
platform blocks the release.

**Test requirements:** Exercise via `workflow_dispatch` against a pre-release
tag before relying on it. Smoke-test the *downloaded* asset (not the build
output): it runs, and exits 2 on a gate failure — that's what catches a lost
exec bit, a quarantined binary, or a mislinked libc.

**Estimated review size:** ~150–250 lines of workflow YAML — generated-ish
config, called out in the PR body.

---

## Phase E — Library-grade API and compatibility (gates D1)

These exist because the project is becoming a library people depend on rather
than essay support. Each one is cheap now and expensive later: every item here
becomes a **breaking change** once something is published.

### E1 — Decide and lock the public API surface

**Scope:** Turn on Kotlin's `explicitApi()`, mark everything not intended for
consumers `internal`, and add `binary-compatibility-validator`
(`apiDump`/`apiCheck`) so the public surface is a reviewed, committed artifact
instead of an accident. Blocked by: A1.

**Constraints:** Every top-level declaration in what becomes `:core` is public
today by Kotlin default — `scoreAll`, `collect`, `buildModuleMap`,
`readCommits`, `remediationText`, `writeBreakGlassStub`, `runGate`, and all the
data classes. As internal details behind a CLI that cost nothing; as a
published library it is a compatibility commitment the instant D1 runs.
**This must land before the first publish**, because narrowing a published API
afterwards is a breaking change. It also retroactively changes A1's character:
the module split should decide *what is public*, not merely move files — so if
A1 has already merged, E1 is the item that draws the line before anything ships.

The committed `.api` dump is a fixture in the same spirit as `fixtures/`: CI
fails on undeclared drift, and a diff in it is a deliberate API decision.

**Done criteria:** `apiCheck` runs in CI; `api/*.api` committed; nothing is
public that wasn't chosen on purpose.

**Test requirements:** `apiCheck` failing on an unapproved public addition,
demonstrated once.

**Estimated review size:** ~100–200 lines plus the generated dump.

### E2 — JVM target floor and dependency exposure

**Scope:** Decide the minimum consumer JVM and set the compile target
accordingly; decide how `kotlinx-serialization-json` is exposed to consumers.
Blocked by: A1.

**Constraints:** `build.gradle.kts` currently sets `jvmToolchain(21)`, which
targets bytecode 21 and therefore **excludes every consumer still on JVM 17** —
a common floor for libraries. Building on 21 while targeting a lower release is
the usual resolution, and it's a decision to make once, publicly, rather than
discover from a consumer's bug report.

Separately, `kotlinx-serialization-json` is declared `implementation`, so it
stays off the consumer's compile classpath but remains on their *runtime*
classpath, where it can collide with their own version. Options: leave it and
document the coordinate, relocate/shade it, or retire it by hand-rolling an
order-preserving config reader (CLAUDE.md's key-order requirement is the only
reason the dependency exists). Note this cuts against CLAUDE.md's current
"read with a library" guidance, so changing it is a documented-convention
change, not a free refactor.

**Done criteria:** The library resolves and runs on the chosen JVM floor,
proven by a consumer smoke-test project, not by inspection.

**Estimated review size:** ~80–150 lines.

### E3 — Versioning, changelog, coordinates

**Scope:** A semver policy, `CHANGELOG.md`, and the final Maven coordinates
plus plugin id. Blocked by: nothing.

**Constraints:** `v0.1-spec`/`v0.1-calibration`/`v0.1-kotlin` have been
*project phase* tags, not release versions — a library needs published versions
that mean something to a dependency resolver, and a documented policy for what
a major bump implies about scoring changes (a recalibration that moves scores
is arguably breaking, even with an unchanged API — worth stating explicitly,
since this project's whole output is numbers). Coordinates are effectively
permanent once anyone depends on them. A4 joins the release checklist: no
version publishes without a parity PASS on every shipped surface.

**Estimated review size:** ~60–120 lines (docs and config).

---

## Interactions with currently-open issues

- **#14 (SPEC §5.1 map fields missing from output) — do not interleave.** It
  changes the JSON shape and regenerates fixtures. Landing it mid-refactor would
  make the parity guard meaningless exactly when it's needed most. Land it
  before A1 or after C4; **after is recommended**, so A–C run against a frozen
  output shape.
- **#29 (bot identities can qualify as comprehenders) — status changed by the
  reframing; see above.** Filed as DO-NOT-FIX because it was SPEC §7 A3's
  required failure for the essay. As a shipped library it reads as a plain
  correctness defect, and the recommendation is that it becomes a **release
  blocker on D1**. The issue body still carries the original DO-NOT-FIX
  instruction in bold, so it needs an explicit comment revising that stance —
  otherwise whoever picks it up follows the old reasoning.
- **#30 (pin the JVM distribution in C6's determinism contract) — answered or
  deliberately deferred at B4.** Shipping binaries is what turns it from a
  documented caveat into a user-facing one.
- **#31 (attestations reader quoting limits) — promoted to a hard blocker on
  C3**, per the reasoning in that item.
