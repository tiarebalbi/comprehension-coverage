#!/usr/bin/env python3
"""Tests for the reference prototype: unit tests for the scoring math and
detection, plus a golden test against a deterministic synthetic repository.

Run: python3 test_comprehension.py
"""
import contextlib
import io
import json
import os
import shutil
import subprocess
import tempfile
from datetime import datetime, timezone

import comprehension as cc

DAY = 86400
T0 = 1700000000  # fixed epoch base for determinism


def _iso(ts: int) -> str:
    return datetime.fromtimestamp(ts, tz=timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")


def cfg_with(**over):
    cfg = json.loads(json.dumps(cc.DEFAULTS))
    cfg["modules"] = {"core": ["src/core/*"], "web": ["src/web/*"]}
    cfg.update(over)
    return cfg


# ---------------------------------------------------------------- unit tests

def test_decay_wall_clock_half_life():
    # quiescence_stretch=0 isolates the base h_wall_days mechanic (churn_ratio=0
    # -> h_wall_eff == h_wall_days) from the quiescence stretch tested below.
    cfg = cfg_with(quiescence_stretch=0.0)
    e = cc.Evidence("alice", "core", "AUTHORED", T0, cfg["sat_lines"])  # value 1.0
    scores = cc.score_all(cfg, [e], {"core": 1000.0}, T0 + 180 * DAY)
    assert abs(scores[("alice", "core")] - 0.5) < 1e-9, scores


def test_quiescence_stretches_frozen_evidence():
    # CALIBRATION.md candidate 1: a frozen module (churn_ratio=0) stretches the
    # wall half-life to h_wall*(1+stretch) -- default stretch=2.0 means 3x.
    cfg = cfg_with()
    e = cc.Evidence("alice", "core", "AUTHORED", T0, cfg["sat_lines"])
    scores = cc.score_all(cfg, [e], {"core": 1000.0}, T0 + 540 * DAY)
    assert abs(scores[("alice", "core")] - 0.5) < 1e-9, scores


def test_quiescence_does_not_stretch_fully_churned_evidence():
    # At churn_ratio == churn_cap (module fully rewritten by others since the
    # evidence), the quiescence stretch relaxes to 0: h_wall_eff == h_wall_days,
    # identical to the unstretched baseline -- churned-away evidence must not
    # get a second, redundant reprieve from the wall-clock floor (SPEC C3).
    cfg = cfg_with()
    e = cc.Evidence("alice", "core", "AUTHORED", T0, cfg["sat_lines"])
    e.churn_after = cfg["churn_cap"] * 1000.0  # churn_ratio == churn_cap
    stretched = cc.score_all(cfg, [e], {"core": 1000.0}, T0 + 180 * DAY)
    baseline_cfg = cfg_with(quiescence_stretch=0.0)
    baseline = cc.score_all(baseline_cfg, [e], {"core": 1000.0}, T0 + 180 * DAY)
    assert abs(stretched[("alice", "core")] - baseline[("alice", "core")]) < 1e-9


def test_decay_churn_half_life():
    cfg = cfg_with()
    e = cc.Evidence("alice", "core", "AUTHORED", T0, cfg["sat_lines"])
    e.churn_after = 1000.0  # equals module size -> churn_ratio 1.0 -> halve
    scores = cc.score_all(cfg, [e], {"core": 1000.0}, T0)
    assert abs(scores[("alice", "core")] - 0.5) < 1e-9, scores


def test_agent_weight_discount():
    cfg = cfg_with()
    a = cc.Evidence("alice", "core", "AUTHORED", T0, cfg["sat_lines"])
    b = cc.Evidence("bob", "core", "AGENT_MEDIATED", T0, cfg["sat_lines"])
    scores = cc.score_all(cfg, [a, b], {"core": 1000.0}, T0)
    assert abs(scores[("bob", "core")] / scores[("alice", "core")] - 0.3) < 1e-9


def test_saturation_caps_at_one():
    cfg = cfg_with()
    e = cc.Evidence("alice", "core", "AUTHORED", T0, 10 * cfg["sat_lines"])
    scores = cc.score_all(cfg, [e], {"core": 1000.0}, T0)
    assert scores[("alice", "core")] == 1.0


def test_trailer_detection():
    cfg = cfg_with()
    c = cc.Commit("x", "Tiare", "me@tiarebalbi.com", T0,
                  ["Claude Fable 5 <noreply@anthropic.com>"])
    assert cc.is_agent_mediated(cfg, c)
    c2 = cc.Commit("y", "Tiare", "me@tiarebalbi.com", T0, [])
    assert not cc.is_agent_mediated(cfg, c2)
    c3 = cc.Commit("z", "dependabot[bot]", "x@users.noreply.github.com", T0, [])
    assert cc.is_agent_mediated(cfg, c3)


def test_status_thresholds():
    cfg = cfg_with()
    scores = {("alice", "core"): 0.9, ("bob", "core"): 0.6, ("carol", "web"): 0.4}
    modules = cc.build_map(cfg, scores, {"core": 1.0, "web": 1.0})
    assert modules["core"]["status"] == "COVERED"
    assert modules["web"]["status"] == "DARK"


def test_departed_excluded_from_counts():
    cfg = cfg_with(departed=["alice"])
    scores = {("alice", "core"): 0.9}
    modules = cc.build_map(cfg, scores, {"core": 1.0})
    assert modules["core"]["status"] == "DARK"  # evidence exists, holder is gone


def test_as_of_requires_explicit_utc_offset():
    # C6 / issue #3: a tz-naive --as-of must be rejected, not silently
    # resolved against the invoking machine's local timezone.
    try:
        cc.parse_as_of("2026-09-13T00:00:00")  # no 'Z', no offset
        assert False, "expected ValueError for tz-naive --as-of"
    except ValueError:
        pass
    # tz-aware forms still work and agree on the same instant.
    assert cc.parse_as_of("2026-09-13T00:00:00Z") == \
        cc.parse_as_of("2026-09-13T00:00:00+00:00") == 1789257600


# ---------------------------------------------------------------- git determinism (issue #3)

def test_read_commits_scoped_to_ref():
    # SPEC §2.1: history comes from a single named ref, not every ref a
    # clone happens to have fetched (the --all this replaces would pull in
    # side-branch commits regardless of which branch is being analyzed).
    tmp = tempfile.mkdtemp()
    try:
        os.makedirs(f"{tmp}/src", exist_ok=True)
        git(tmp, "init", "-q", "-b", "main")
        with open(f"{tmp}/src/a.txt", "w") as f:
            f.write("a\n")
        commit(tmp, "Alice", "alice@example.com", T0, "on main")
        git(tmp, "checkout", "-q", "-b", "side")
        with open(f"{tmp}/src/b.txt", "w") as f:
            f.write("b\n")
        commit(tmp, "Bob", "bob@example.com", T0 + DAY, "on side")
        git(tmp, "checkout", "-q", "main")

        on_main = cc.read_commits(tmp, "main")
        assert [c.author_name for c in on_main] == ["Alice"]
        on_side = cc.read_commits(tmp, "side")
        assert [c.author_name for c in on_side] == ["Alice", "Bob"]
    finally:
        shutil.rmtree(tmp)


def test_read_commits_ties_broken_by_sha():
    # SPEC §2.1: two commits sharing a timestamp get a fixed, code-level
    # total order (timestamp, sha) rather than relying on git's own
    # same-timestamp ordering, which isn't guaranteed stable across
    # versions. Build two independent (parentless-in-effect) commits with
    # the identical author timestamp and confirm the returned order matches
    # ascending sha regardless of creation order.
    tmp = tempfile.mkdtemp()
    try:
        os.makedirs(f"{tmp}/src", exist_ok=True)
        git(tmp, "init", "-q", "-b", "main")
        with open(f"{tmp}/src/a.txt", "w") as f:
            f.write("a\n")
        commit(tmp, "Alice", "alice@example.com", T0, "first")
        with open(f"{tmp}/src/a.txt", "a") as f:
            f.write("a2\n")
        commit(tmp, "Alice", "alice@example.com", T0, "second, same timestamp")

        commits = cc.read_commits(tmp, "main")
        assert [c.timestamp for c in commits] == [T0, T0]
        shas = [c.sha for c in commits]
        assert shas == sorted(shas), "same-timestamp commits must sort by sha"
    finally:
        shutil.rmtree(tmp)


def test_attested_weight_applied():
    # weight(ATTESTED)=0.6, magnitude fixed at sat_lines (fully saturated):
    # same timestamp as as_of -> no decay -> score == weight exactly.
    cfg = cfg_with()
    e = cc.Evidence("dana", "core", "ATTESTED", T0, cfg["sat_lines"])
    scores = cc.score_all(cfg, [e], {"core": 1000.0}, T0)
    assert abs(scores[("dana", "core")] - cfg["weights"]["ATTESTED"]) < 1e-9


# ---------------------------------------------------------------- attestations (issue #13)

def write_attestations(repo, records):
    """Writes records in dict-insertion order, quoting `timestamp` and
    `incident_ref` (matching the schema and the break-glass stub) and
    leaving other keys bare -- so a record can carry `type`/`incident_ref`
    (SPEC §2 reserved-class fields) beyond the three required keys."""
    quoted = {"timestamp", "incident_ref"}
    os.makedirs(f"{repo}/.comprehension", exist_ok=True)
    lines = []
    for r in records:
        for i, (k, v) in enumerate(r.items()):
            prefix = "- " if i == 0 else "  "
            lines.append(f'{prefix}{k}: "{v}"' if k in quoted else f"{prefix}{k}: {v}")
    with open(f"{repo}/.comprehension/attestations.yaml", "w") as f:
        f.write("\n".join(lines) + "\n")


def test_read_attestations_missing_file_returns_empty():
    tmp = tempfile.mkdtemp()
    try:
        cfg = cfg_with()
        assert cc.read_attestations(tmp, cfg) == []
    finally:
        shutil.rmtree(tmp)


def test_read_attestations_returns_raw_email():
    # read_attestations no longer resolves identity itself -- that needs
    # the commit stream (SPEC §2.1 total order), which only collect() has.
    # It returns the raw (lowercased) email; collect() does the resolution.
    tmp = tempfile.mkdtemp()
    try:
        cfg = cfg_with()
        write_attestations(tmp, [
            {"email": "Dana@Example.com", "module": "core",
             "timestamp": "2026-09-08T00:00:00Z"},
        ])
        attestations = cc.read_attestations(tmp, cfg)
        assert attestations == [("dana@example.com", "core", 1788825600)]
    finally:
        shutil.rmtree(tmp)


def test_read_attestations_rejects_tz_naive_timestamp():
    tmp = tempfile.mkdtemp()
    try:
        cfg = cfg_with()
        write_attestations(tmp, [
            {"email": "dana@example.com", "module": "core",
             "timestamp": "2026-09-08T00:00:00"},  # no offset
        ])
        try:
            cc.read_attestations(tmp, cfg)
            assert False, "expected ValueError for tz-naive attestation timestamp"
        except ValueError:
            pass
    finally:
        shutil.rmtree(tmp)


def test_read_attestations_skips_unknown_module():
    tmp = tempfile.mkdtemp()
    try:
        cfg = cfg_with()
        write_attestations(tmp, [
            {"email": "dana@example.com", "module": "nonexistent",
             "timestamp": "2026-09-08T00:00:00Z"},
        ])
        stderr = io.StringIO()
        with contextlib.redirect_stderr(stderr):
            result = cc.read_attestations(tmp, cfg)
        assert result == []
        warning = stderr.getvalue()
        assert "attestations.yaml" in warning and "nonexistent" in warning, warning
    finally:
        shutil.rmtree(tmp)


def test_collect_folds_attestations_without_adding_churn():
    # An attestation must not itself count as churn for other evidence's
    # churn_after -- it's a self-report, not a code change.
    cfg = cfg_with()
    c = cc.Commit("x", "Alice", "alice@example.com", T0, [], [(400, 0, "src/core/e.txt")])
    attestations = [("dana@example.com", "core", T0 + 10 * DAY)]
    events, sizes = cc.collect(cfg, [c], T0 + 20 * DAY, attestations)
    alice_ev = next(e for e in events if e.person == "Alice")
    assert alice_ev.churn_after == 0.0  # Dana's attestation contributed no lines
    dana_ev = next(e for e in events if e.etype == "ATTESTED")
    # no identity mapping and no git history for dana@example.com -> raw email
    assert dana_ev.person == "dana@example.com" and dana_ev.module == "core"
    assert dana_ev.magnitude == cfg["sat_lines"]


def test_attestation_identity_resolves_via_commit_stream():
    # An attestation email with no `identity` config entry must resolve to
    # the same person as their git commits, not fork into a separate
    # "email-as-name" identity (SPEC §2.1: most recent author name used
    # with that email at or before the attestation's timestamp).
    cfg = cfg_with()  # no identity map
    c = cc.Commit("x", "Carol", "carol@example.com", T0, [], [(400, 0, "src/core/e.txt")])
    attestations = [("carol@example.com", "core", T0 + 10 * DAY)]
    events, _ = cc.collect(cfg, [c], T0 + 20 * DAY, attestations)
    people = {e.person for e in events}
    assert people == {"Carol"}, people  # not {"Carol", "carol@example.com"}


def test_attestation_identity_falls_back_to_raw_email_when_no_history():
    cfg = cfg_with()
    attestations = [("ghost@example.com", "core", T0)]
    events, _ = cc.collect(cfg, [], T0, attestations)
    assert events[0].person == "ghost@example.com"


# ---------------------------------------------------------------- golden test

def git(repo, *args, env=None):
    subprocess.run(["git", "-C", repo, *args], check=True, capture_output=True,
                   env=env)


def commit(repo, name, email, ts, message):
    env = dict(os.environ,
               GIT_AUTHOR_NAME=name, GIT_AUTHOR_EMAIL=email,
               GIT_COMMITTER_NAME=name, GIT_COMMITTER_EMAIL=email,
               GIT_AUTHOR_DATE=f"{ts} +0000", GIT_COMMITTER_DATE=f"{ts} +0000")
    git(repo, "add", "-A")
    git(repo, "commit", "-q", "--allow-empty", "-m", message, env=env)


def build_synthetic_repo(root):
    """Deterministic history: alice hand-builds core early; an agent-mediated
    rewrite churns core later; bob hand-works web recently; dana attests to
    having re-walked web shortly before as-of, with no git evidence at all
    (issue #13: exercises ATTESTED, the third v0.1 evidence class)."""
    os.makedirs(f"{root}/src/core", exist_ok=True)
    os.makedirs(f"{root}/src/web", exist_ok=True)
    git(root, "init", "-q", "-b", "main")
    with open(f"{root}/src/core/engine.txt", "w") as f:
        f.write("line\n" * 400)
    commit(root, "Alice", "alice@example.com", T0, "core: initial engine")
    with open(f"{root}/src/web/app.txt", "w") as f:
        f.write("line\n" * 200)
    commit(root, "Bob", "bob@example.com", T0 + 30 * DAY, "web: initial app")
    # agent-mediated rewrite of core, 300 days after alice, via tiare's account
    with open(f"{root}/src/core/engine.txt", "w") as f:
        f.write("new\n" * 500)
    commit(root, "Tiare", "me@tiarebalbi.com", T0 + 300 * DAY,
           "core: agent rewrite\n\nCo-Authored-By: Claude <noreply@anthropic.com>")
    # bob keeps working web recently by hand
    with open(f"{root}/src/web/app.txt", "a") as f:
        f.write("more\n" * 150)
    commit(root, "Bob", "bob@example.com", T0 + 320 * DAY, "web: feature by hand")
    # read_attestations reads straight off disk (not via git history), so no
    # commit is needed for this file to be picked up.
    write_attestations(root, [
        {"email": "dana@example.com", "module": "web",
         "timestamp": _iso(T0 + 325 * DAY)},
    ])


def test_golden_fixture():
    tmp = tempfile.mkdtemp()
    try:
        build_synthetic_repo(tmp)
        # identity map resolves dana's attestation (email-only) to a display
        # name, exactly as it would for any real config (SPEC §2 identity merge).
        cfg = cfg_with(identity={"dana@example.com": "Dana"})
        commits = cc.read_commits(tmp)
        attestations = cc.read_attestations(tmp, cfg)
        as_of = T0 + 330 * DAY
        events, sizes = cc.collect(cfg, commits, as_of, attestations)
        scores = cc.score_all(cfg, events, sizes, as_of)
        modules = cc.build_map(cfg, scores, sizes)

        # web: bob authored recently by hand, but is he above theta after decay?
        # core: alice's evidence is old AND churned by the agent rewrite;
        #        tiare's evidence is recent but agent-discounted.
        # dana (web) holds ATTESTED-only evidence -- no git history at all.
        assert modules["core"]["status"] == "DARK", modules["core"]
        assert modules["web"]["status"] in ("AT_RISK", "COVERED"), modules["web"]
        assert ("Dana", "web") in scores, "attestation-only evidence should still produce a score"

        # write the golden fixture the Kotlin implementation must reproduce
        public = {m: {k: v for k, v in d.items() if k != "people"}
                  for m, d in modules.items()}
        fixture = {"as_of": as_of, "modules": public,
                   "scores": {f"{p}|{m}": round(s, 6)
                              for (p, m), s in sorted(scores.items())}}
        out = os.path.join(os.path.dirname(__file__), "..", "fixtures",
                           "golden-synthetic.json")
        with open(out, "w") as f:
            json.dump(fixture, f, indent=2, sort_keys=True)
        print("golden fixture written:", os.path.abspath(out))
    finally:
        shutil.rmtree(tmp)


# ---------------------------------------------------------------- gate / break-glass (SPEC §5.2, issue #9)

def gate_cfg(**over):
    cfg = cfg_with(critical=["core", "web"])
    cfg.update(over)
    return cfg


def run_cli(repo, config_path, *extra_args):
    """Invokes the real CLI entry point via subprocess -- for the argument-
    validation paths that live in main() itself (before run_gate is ever
    called), which unit tests of run_gate() can't reach."""
    script = os.path.join(os.path.dirname(__file__), "comprehension.py")
    result = subprocess.run(
        ["python3", script, "--repo", repo, "--config", config_path, *extra_args],
        capture_output=True, text=True,
    )
    return result.returncode, result.stdout, result.stderr


def test_cli_break_glass_without_gate_exits_1():
    tmp = tempfile.mkdtemp()
    try:
        build_synthetic_repo(tmp)
        cfg_path = os.path.join(tmp, "cfg.json")
        with open(cfg_path, "w") as f:
            json.dump({"modules": {"core": ["src/core/*"], "web": ["src/web/*"]}}, f)
        rc, _, err = run_cli(tmp, cfg_path, "--break-glass", "INC-1")
        assert rc == 1, (rc, err)
        assert "--gate" in err
    finally:
        shutil.rmtree(tmp)


def test_cli_break_glass_blank_ref_exits_1_not_0():
    # The bug this guards: a blank --break-glass must never be silently
    # accepted (which would downgrade a red gate to green with no
    # incident ref on record).
    tmp = tempfile.mkdtemp()
    try:
        build_synthetic_repo(tmp)
        cfg_path = os.path.join(tmp, "cfg.json")
        with open(cfg_path, "w") as f:
            json.dump({"modules": {"core": ["src/core/*"], "web": ["src/web/*"]},
                       "critical": ["core"]}, f)
        rc, _, err = run_cli(tmp, cfg_path, "--gate", "--break-glass", "")
        assert rc == 1, (rc, err)
        assert "non-empty incident ref" in err
    finally:
        shutil.rmtree(tmp)


def test_cli_break_glass_person_blank_exits_1():
    tmp = tempfile.mkdtemp()
    try:
        build_synthetic_repo(tmp)
        cfg_path = os.path.join(tmp, "cfg.json")
        with open(cfg_path, "w") as f:
            json.dump({"modules": {"core": ["src/core/*"], "web": ["src/web/*"]},
                       "critical": ["core"]}, f)
        rc, _, err = run_cli(tmp, cfg_path, "--gate", "--break-glass", "INC-1",
                              "--break-glass-person", "  ")
        assert rc == 1, (rc, err)
        assert "non-empty email" in err
    finally:
        shutil.rmtree(tmp)


def test_run_gate_passes_when_nothing_critical_fails():
    cfg = gate_cfg()
    modules = {"core": {"status": "COVERED", "comprehenders": 2, "people": []},
               "web": {"status": "COVERED", "comprehenders": 2, "people": []}}
    result = cc.run_gate(cfg, "/nonexistent", modules, [], T0, None, None)
    assert result == (0, "")


def test_run_gate_exits_2_on_dark_critical():
    cfg = gate_cfg()
    modules = {"core": {"status": "DARK", "comprehenders": 0, "people": [("Alice", 0.12)]},
               "web": {"status": "COVERED", "comprehenders": 2, "people": []}}
    events = [cc.Evidence("Alice", "core", "AUTHORED", T0, 400.0)]
    exit_code, message = cc.run_gate(cfg, "/nonexistent", modules, events, T0 + 200 * DAY, None, None)
    assert exit_code == 2
    assert "GATE: DARK critical module(s): core" in message
    assert "Alice holds the strongest remaining evidence; last AUTHORED evidence 200d before as-of." in message
    assert "0.12" not in message  # C5: no per-person scalar in gate output


def test_run_gate_at_risk_does_not_exit_by_default():
    cfg = gate_cfg()  # gate.exit1_on_at_risk defaults to False
    modules = {"core": {"status": "AT_RISK", "comprehenders": 1, "people": [("Bob", 0.7)]},
               "web": {"status": "COVERED", "comprehenders": 2, "people": []}}
    result = cc.run_gate(cfg, "/nonexistent", modules, [], T0, None, None)
    assert result == (0, "")


def test_run_gate_exits_1_on_at_risk_when_enabled():
    cfg = gate_cfg(gate={"exit1_on_at_risk": True})
    modules = {"core": {"status": "AT_RISK", "comprehenders": 1, "people": [("Bob", 0.7)]},
               "web": {"status": "COVERED", "comprehenders": 2, "people": []}}
    events = [cc.Evidence("Bob", "core", "AUTHORED", T0, 400.0)]
    exit_code, message = cc.run_gate(cfg, "/nonexistent", modules, events, T0 + 50 * DAY, None, None)
    assert exit_code == 1
    assert message == (
        "GATE: AT_RISK critical module(s): core — comprehension exists but is below "
        "the bus-factor threshold (theta_covered=2).\n"
        "  core: Bob holds the strongest remaining evidence; last AUTHORED evidence 50d before as-of."
    ), message


def test_run_gate_no_evidence_recorded_message():
    cfg = gate_cfg()
    modules = {"core": {"status": "DARK", "comprehenders": 0, "people": []},
               "web": {"status": "COVERED", "comprehenders": 2, "people": []}}
    _, message = cc.run_gate(cfg, "/nonexistent", modules, [], T0, None, None)
    assert "core: no evidence recorded for this module." in message


def test_run_gate_remediation_breaks_same_timestamp_tie_by_kind():
    # Alice has an AUTHORED event and an ATTESTED event at the exact same
    # timestamp -- "last" must resolve deterministically via the (timestamp,
    # kind) rank collect() itself uses (commits before attestations), not
    # whichever happens to sit later in the events list.
    cfg = gate_cfg()
    modules = {"core": {"status": "DARK", "comprehenders": 0, "people": [("Alice", 0.1)]},
               "web": {"status": "COVERED", "comprehenders": 2, "people": []}}
    events = [
        cc.Evidence("Alice", "core", "ATTESTED", T0, cfg["sat_lines"]),
        cc.Evidence("Alice", "core", "AUTHORED", T0, 400.0),
    ]
    _, message = cc.run_gate(cfg, "/nonexistent", modules, events, T0 + 10 * DAY, None, None)
    assert "last ATTESTED evidence" in message


def test_run_gate_break_glass_requires_person():
    cfg = gate_cfg()
    modules = {"core": {"status": "DARK", "comprehenders": 0, "people": []},
               "web": {"status": "COVERED", "comprehenders": 2, "people": []}}
    try:
        cc.run_gate(cfg, "/nonexistent", modules, [], T0, "INC-1", None)
        assert False, "expected ValueError: break-glass with no resolvable person"
    except ValueError as e:
        assert "break-glass-person" in str(e)


def test_run_gate_break_glass_blank_person_falls_through_to_config_then_errors():
    # A blank string (CLI or config) must resolve the same as "not
    # provided" -- never silently accepted as a real person.
    tmp = tempfile.mkdtemp()
    try:
        cfg = gate_cfg()
        modules = {"core": {"status": "DARK", "comprehenders": 0, "people": []},
                   "web": {"status": "COVERED", "comprehenders": 2, "people": []}}
        try:
            cc.run_gate(cfg, tmp, modules, [], T0, "INC-1", "   ")
            assert False, "expected ValueError: blank --break-glass-person is not a person"
        except ValueError as e:
            assert "break-glass-person" in str(e)

        cfg2 = gate_cfg(break_glass_person="oncall@example.com")
        exit_code, message = cc.run_gate(cfg2, tmp, modules, [], T0, "INC-1", "   ")
        assert exit_code == 0 and "oncall@example.com" in message  # blank CLI value falls through to config
    finally:
        shutil.rmtree(tmp)


def test_format_iso_matches_the_schema_example():
    # 2026-09-08T00:00:00Z, the exact instant used throughout attestations fixtures.
    assert cc.format_iso(1788825600) == "2026-09-08T00:00:00Z"


def test_run_gate_break_glass_downgrades_exit_and_writes_stub():
    tmp = tempfile.mkdtemp()
    try:
        cfg = gate_cfg()
        modules = {"core": {"status": "DARK", "comprehenders": 0, "people": [("Alice", 0.1)]},
                   "web": {"status": "COVERED", "comprehenders": 2, "people": []}}
        events = [cc.Evidence("Alice", "core", "AUTHORED", T0, 400.0)]
        as_of = T0 + 200 * DAY
        exit_code, message = cc.run_gate(cfg, tmp, modules, events, as_of,
                                          "INC-42", "oncall@example.com")
        assert exit_code == 0
        assert "BREAK-GLASS: 'INC-42'" in message and "oncall@example.com" in message

        with open(f"{tmp}/.comprehension/attestations.yaml") as f:
            content = f.read()
        expected = (
            "- email: oncall@example.com\n"
            "  module: core\n"
            f'  timestamp: "{cc.format_iso(as_of)}"\n'
            "  type: INCIDENT_DIAGNOSED\n"
            '  incident_ref: "INC-42"\n'
        )
        assert content == expected, content
    finally:
        shutil.rmtree(tmp)


def test_run_gate_break_glass_appends_without_losing_a_missing_trailing_newline():
    tmp = tempfile.mkdtemp()
    try:
        os.makedirs(f"{tmp}/.comprehension")
        with open(f"{tmp}/.comprehension/attestations.yaml", "w") as f:
            f.write('- email: dana@example.com\n  module: web\n  timestamp: "2026-09-08T00:00:00Z"')  # no trailing \n
        cfg = gate_cfg()
        modules = {"core": {"status": "DARK", "comprehenders": 0, "people": []},
                   "web": {"status": "COVERED", "comprehenders": 2, "people": []}}
        as_of = T0
        cc.run_gate(cfg, tmp, modules, [], as_of, "INC-7", "oncall@example.com")
        with open(f"{tmp}/.comprehension/attestations.yaml") as f:
            content = f.read()
        expected = (
            '- email: dana@example.com\n  module: web\n  timestamp: "2026-09-08T00:00:00Z"\n'
            "- email: oncall@example.com\n"
            "  module: core\n"
            f'  timestamp: "{cc.format_iso(as_of)}"\n'
            "  type: INCIDENT_DIAGNOSED\n"
            '  incident_ref: "INC-7"\n'
        )
        assert content == expected, content
    finally:
        shutil.rmtree(tmp)


def test_read_attestations_skips_reserved_type_with_warning():
    tmp = tempfile.mkdtemp()
    try:
        cfg = cfg_with()
        write_attestations(tmp, [
            {"email": "oncall@example.com", "module": "core",
             "timestamp": "2026-09-08T00:00:00Z",
             "type": "INCIDENT_DIAGNOSED", "incident_ref": "INC-7"},
        ])
        stderr = io.StringIO()
        with contextlib.redirect_stderr(stderr):
            result = cc.read_attestations(tmp, cfg)
        assert result == []
        warning = stderr.getvalue()
        assert "INCIDENT_DIAGNOSED" in warning and "reserved" in warning, warning
    finally:
        shutil.rmtree(tmp)


def test_break_glass_stub_does_not_manufacture_comprehension():
    # Constraint: a break-glass stub records that the override happened but
    # must not, by itself, feed the score (SPEC §2: declared interface, not
    # ingested). Run the full pipeline before and after break-glass writes
    # the stub and assert the scores are byte-identical.
    tmp = tempfile.mkdtemp()
    try:
        build_synthetic_repo(tmp)
        cfg = cfg_with(critical=["core"])
        commits = cc.read_commits(tmp)
        as_of = T0 + 330 * DAY

        def run():
            attestations = cc.read_attestations(tmp, cfg)
            events, sizes = cc.collect(cfg, commits, as_of, attestations)
            return cc.score_all(cfg, events, sizes, as_of), events, sizes

        scores_before, events_before, sizes_before = run()
        modules = cc.build_map(cfg, scores_before, sizes_before)
        assert modules["core"]["status"] == "DARK", modules["core"]  # same as the golden fixture

        exit_code, _ = cc.run_gate(cfg, tmp, modules, events_before, as_of,
                                    "INC-99", "oncall@example.com")
        assert exit_code == 0

        scores_after, _, _ = run()
        assert scores_after == scores_before
    finally:
        shutil.rmtree(tmp)


if __name__ == "__main__":
    fns = [v for k, v in sorted(globals().items()) if k.startswith("test_")]
    for fn in fns:
        fn()
        print(f"ok  {fn.__name__}")
    print(f"all {len(fns)} tests passed")
