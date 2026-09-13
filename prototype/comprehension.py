#!/usr/bin/env python3
"""Comprehension coverage — reference prototype (spec v0.1).

Reference semantics for the Kotlin implementation. Deterministic: the "as of"
instant is an explicit input, never the wall clock.

Usage:
  python3 comprehension.py --repo PATH --config comprehension.json \
      [--as-of 2026-09-13T00:00:00Z] [--json OUT.json] [--gate] \
      [--show-individuals]
"""
from __future__ import annotations

import argparse
import fnmatch
import json
import math
import subprocess
import sys
from collections import defaultdict
from dataclasses import dataclass, field
from datetime import datetime, timezone

# ---------------------------------------------------------------- config

DEFAULTS = {
    "h_churn": 1.0,          # churn_ratio at which evidence halves
    "h_wall_days": 180.0,    # wall-clock half-life (floor decay) at full churn
    "quiescence_stretch": 2.0,  # stretches h_wall toward h_wall*(1+this) as the
                                 # module's churn since the evidence -> 0 (CALIBRATION.md
                                 # candidate 1); 0 recovers the flat, unstretched floor

    "churn_cap": 4.0,
    "sat_lines": 400.0,
    "theta_person": 0.5,
    "theta_covered": 2,
    "weights": {"AUTHORED": 1.0, "AGENT_MEDIATED": 0.3, "ATTESTED": 0.6},
    "agent_trailer_emails": [
        "noreply@anthropic.com", "copilot@github.com", "cursoragent@cursor.com",
    ],
    "bot_author_patterns": ["[bot]"],   # literal substrings, not globs
    "identity": {},           # email -> canonical name
    "departed": [],           # canonical names excluded from current counts
    "modules": {},            # module name -> list of path globs
    "critical": [],           # module names gated in --gate mode
}


def load_config(path: str) -> dict:
    with open(path) as f:
        user = json.load(f)
    cfg = json.loads(json.dumps(DEFAULTS))
    for k, v in user.items():
        if isinstance(v, dict) and isinstance(cfg.get(k), dict):
            cfg[k].update(v)
        else:
            cfg[k] = v
    if not cfg["modules"]:
        sys.exit("config error: 'modules' must map module names to path globs")
    return cfg

# ---------------------------------------------------------------- git

@dataclass
class Commit:
    sha: str
    author_name: str
    author_email: str
    timestamp: int
    trailers: list[str]
    files: list[tuple[int, int, str]] = field(default_factory=list)  # add, del, path


def read_commits(repo: str) -> list[Commit]:
    """Full first-parent-inclusive history, oldest first, with numstat."""
    fmt = "@@C@@%n%H%n%an%n%ae%n%at%n%(trailers:key=Co-Authored-By,valueonly)%n@@F@@"
    out = subprocess.run(
        ["git", "-C", repo, "log", "--all", "--numstat", "--no-renames",
         "--date-order", "--reverse", f"--pretty=format:{fmt}"],
        capture_output=True, text=True, check=True, errors="replace",
    ).stdout
    commits: list[Commit] = []
    cur: Commit | None = None
    lines = iter(out.splitlines())
    for line in lines:
        if line == "@@C@@":
            sha = next(lines); name = next(lines); email = next(lines)
            ts = int(next(lines))
            trailers = []
            for tl in lines:
                if tl == "@@F@@":
                    break
                if tl.strip():
                    trailers.append(tl.strip())
            cur = Commit(sha, name, email.lower(), ts, trailers)
            commits.append(cur)
        elif line.strip() and cur is not None:
            parts = line.split("\t")
            if len(parts) == 3:
                add = 0 if parts[0] == "-" else int(parts[0])
                dele = 0 if parts[1] == "-" else int(parts[1])
                cur.files.append((add, dele, parts[2]))
    return commits

# ---------------------------------------------------------------- model

def canonical(cfg: dict, name: str, email: str) -> str:
    return cfg["identity"].get(email.lower(), name)


def is_agent_mediated(cfg: dict, c: Commit) -> bool:
    for t in c.trailers:
        email = t[t.find("<") + 1:t.find(">")].lower() if "<" in t else t.lower()
        if email in [e.lower() for e in cfg["agent_trailer_emails"]]:
            return True
    hay = f"{c.author_name} {c.author_email}".lower()
    return any(p.lower() in hay for p in cfg["bot_author_patterns"])


def module_of(cfg: dict, path: str) -> str | None:
    for mod, globs in cfg["modules"].items():
        for g in globs:
            if fnmatch.fnmatch(path, g):
                return mod
    return None


@dataclass
class Evidence:
    person: str
    module: str
    etype: str
    timestamp: int
    magnitude: float
    total_at: float = 0.0     # module total churn when event was recorded
    own_at: float = 0.0       # this person's churn in module at event time
    churn_after: float = 0.0  # filled in at the end: churn by others after event


def collect(cfg: dict, commits: list[Commit], as_of: int):
    """Build evidence events and per-module churn/size, deterministic order.

    churn_after(e) = (final_total[m] - total_at(e)) - (final_own[m,p] - own_at(e))
    i.e. lines changed in the module by anyone other than the event's person,
    between the event and the as-of instant. O(events), not O(events^2).
    """
    events: list[Evidence] = []
    module_size: dict[str, float] = defaultdict(float)
    total: dict[str, float] = defaultdict(float)            # module -> churn
    own: dict[tuple[str, str], float] = defaultdict(float)  # (module, person)
    for c in commits:
        if c.timestamp > as_of:
            continue
        person = canonical(cfg, c.author_name, c.author_email)
        etype = "AGENT_MEDIATED" if is_agent_mediated(cfg, c) else "AUTHORED"
        per_mod: dict[str, float] = defaultdict(float)
        for add, dele, path in c.files:
            mod = module_of(cfg, path)
            if mod is None:
                continue
            per_mod[mod] += add + dele
            module_size[mod] += add - dele
        for mod, lines in sorted(per_mod.items()):
            if lines <= 0:
                continue
            events.append(Evidence(person, mod, etype, c.timestamp, lines,
                                   total_at=total[mod], own_at=own[(mod, person)]))
            total[mod] += lines
            own[(mod, person)] += lines
    for e in events:
        e.churn_after = (total[e.module] - e.total_at) \
            - (own[(e.module, e.person)] - e.own_at) - 0.0
        # exclude the event's own lines from "after": they were added to totals
        # after total_at was snapshotted, but they belong to the same person,
        # so the own-delta subtraction already removes them.
    sizes = {m: max(1.0, s) for m, s in module_size.items()}
    return events, sizes


def score_all(cfg: dict, events: list[Evidence], sizes: dict[str, float], as_of: int):
    scores: dict[tuple[str, str], float] = defaultdict(float)
    for e in events:
        churn_ratio = min(cfg["churn_cap"], e.churn_after / sizes.get(e.module, 1.0))
        days = max(0.0, (as_of - e.timestamp) / 86400.0)
        # quiescence-scaled floor (SPEC §3, CALIBRATION.md candidate 1): the
        # wall-clock half-life stretches toward h_wall*(1+stretch) as churn_ratio
        # -> 0 (module frozen) and relaxes to the base h_wall as churn_ratio -> churn_cap.
        h_wall_eff = cfg["h_wall_days"] * (1 + cfg["quiescence_stretch"] * (1 - churn_ratio / cfg["churn_cap"]))
        eff_age = churn_ratio / cfg["h_churn"] + days / h_wall_eff
        decay = 0.5 ** eff_age
        value = cfg["weights"][e.etype] * min(1.0, math.sqrt(e.magnitude / cfg["sat_lines"]))
        scores[(e.person, e.module)] += value * decay
    return {k: min(1.0, v) for k, v in scores.items()}


def build_map(cfg: dict, scores: dict, sizes: dict) -> dict:
    modules = {}
    for mod in sorted(cfg["modules"]):
        people = sorted(
            [(p, round(s, 4)) for (p, m), s in scores.items() if m == mod],
            key=lambda x: (-x[1], x[0]),
        )
        comprehenders = [p for p, s in people
                         if s >= cfg["theta_person"] and p not in cfg["departed"]]
        n = len(comprehenders)
        status = ("DARK" if n == 0 else
                  "AT_RISK" if n < cfg["theta_covered"] else "COVERED")
        modules[mod] = {
            "status": status,
            "comprehenders": n,
            "people": people,   # stripped unless --show-individuals
        }
    return modules

# ---------------------------------------------------------------- output

BAR = {"COVERED": "█", "AT_RISK": "▓", "DARK": "░"}


def render(modules: dict, show_individuals: bool) -> str:
    out = []
    width = max(len(m) for m in modules) + 2
    for mod, d in modules.items():
        bar = BAR[d["status"]] * 10
        line = f"  {mod:<{width}}{bar}  {d['comprehenders']} comprehender(s)  {d['status']}"
        out.append(line)
        if show_individuals:
            for p, s in d["people"][:5]:
                out.append(f"  {'':<{width}}  {p}: {s}")
    return "\n".join(out)


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--repo", required=True)
    ap.add_argument("--config", required=True)
    ap.add_argument("--as-of", default=None)
    ap.add_argument("--json", default=None)
    ap.add_argument("--gate", action="store_true")
    ap.add_argument("--show-individuals", action="store_true")
    a = ap.parse_args()

    cfg = load_config(a.config)
    as_of = int(datetime.fromisoformat(a.as_of.replace("Z", "+00:00")).timestamp()) \
        if a.as_of else int(
            subprocess.run(["git", "-C", a.repo, "log", "-1", "--format=%at"],
                           capture_output=True, text=True, check=True).stdout.strip())

    commits = read_commits(a.repo)
    events, sizes = collect(cfg, commits, as_of)
    scores = score_all(cfg, events, sizes, as_of)
    modules = build_map(cfg, scores, sizes)

    public = {m: {k: v for k, v in d.items() if k != "people"}
              for m, d in modules.items()}
    print(render(modules, a.show_individuals))
    if a.json:
        with open(a.json, "w") as f:
            json.dump({"as_of": as_of, "modules": public}, f, indent=2, sort_keys=True)

    if a.gate:
        dark = [m for m in cfg["critical"] if modules.get(m, {}).get("status") == "DARK"]
        if dark:
            print(f"\nGATE: DARK critical module(s): {', '.join(dark)} — "
                  f"an agent change here cannot merge without re-establishing comprehension.")
            return 2
    return 0


if __name__ == "__main__":
    sys.exit(main())
