#!/usr/bin/env python3
"""Tests for the reference prototype: unit tests for the scoring math and
detection, plus a golden test against a deterministic synthetic repository.

Run: python3 test_comprehension.py
"""
import json
import os
import shutil
import subprocess
import tempfile

import comprehension as cc

DAY = 86400
T0 = 1700000000  # fixed epoch base for determinism


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
    rewrite churns core later; bob hand-works web recently."""
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


def test_golden_fixture():
    tmp = tempfile.mkdtemp()
    try:
        build_synthetic_repo(tmp)
        cfg = cfg_with()
        commits = cc.read_commits(tmp)
        as_of = T0 + 330 * DAY
        events, sizes = cc.collect(cfg, commits, as_of)
        scores = cc.score_all(cfg, events, sizes, as_of)
        modules = cc.build_map(cfg, scores, sizes)

        # web: bob authored recently by hand, but is he above theta after decay?
        # core: alice's evidence is old AND churned by the agent rewrite;
        #        tiare's evidence is recent but agent-discounted.
        assert modules["core"]["status"] == "DARK", modules["core"]
        assert modules["web"]["status"] in ("AT_RISK", "COVERED"), modules["web"]

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


if __name__ == "__main__":
    fns = [v for k, v in sorted(globals().items()) if k.startswith("test_")]
    for fn in fns:
        fn()
        print(f"ok  {fn.__name__}")
    print(f"all {len(fns)} tests passed")
