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
import os
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

    "ref": "HEAD",  # git ref whose reachable history is analyzed (SPEC §2.1)
                    # -- not --all: determinism must not depend on which
                    # other refs a clone happens to have fetched.
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


def parse_as_of(s: str, label: str = "--as-of") -> int:
    """Parse an ISO-8601 instant. C6 requires an explicit instant, never one
    resolved against the invoking machine's timezone -- so a tz-naive
    string (no offset, no trailing 'Z') is rejected rather than silently
    interpreted in local time. This is the one C6 timestamp parser in the
    codebase -- every caller (the --as-of flag, attestation timestamps)
    goes through it; `label` only adjusts the error message for context."""
    dt = datetime.fromisoformat(s.replace("Z", "+00:00"))
    if dt.tzinfo is None:
        raise ValueError(
            f"{label} {s!r} has no UTC offset; C6 forbids resolving it "
            "against the local machine's timezone. Use an explicit offset, "
            "e.g. '2026-09-13T00:00:00Z'."
        )
    return int(dt.timestamp())


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


def read_commits(repo: str, ref: str = "HEAD") -> list[Commit]:
    """History reachable from `ref` only (SPEC §2.1 -- not --all: the result
    must not depend on which other refs a clone happens to have fetched),
    with numstat. Returned oldest-first, ties broken by SHA: a total order
    fixed in code, not delegated to git's own same-timestamp ordering,
    which §2.1 notes is not guaranteed stable across git versions."""
    fmt = "@@C@@%n%H%n%an%n%ae%n%at%n%(trailers:key=Co-Authored-By,valueonly)%n@@F@@"
    out = subprocess.run(
        ["git", "-C", repo, "log", ref, "--numstat", "--no-renames",
         f"--pretty=format:{fmt}"],
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
    commits.sort(key=lambda c: (c.timestamp, c.sha))
    return commits


def read_attestations(repo: str, cfg: dict) -> list[tuple[str, str, int]]:
    """Parse .comprehension/attestations.yaml (SPEC §2): explicit "I
    re-walked this module" records. Returns (email, module, timestamp)
    triples -- email lowercased, not yet resolved to a person. Identity
    resolution happens in collect(), which has the chronological commit
    stream needed to resolve an unmapped email deterministically (SPEC
    §2.1); read_attestations has no such context on its own.

    Hand-rolled reader for a restricted YAML subset -- the project ships no
    third-party dependencies (README), so this avoids a PyYAML dependency
    by reading exactly the shape the schema below produces: a flat list of
    `- key: value` mappings, no nesting, no multi-line scalars:

        - email: person@example.com
          module: core
          timestamp: "2026-09-08T00:00:00Z"

    Any real YAML document following this exact shape parses identically
    under a full YAML parser, so adopting one later is not a format break.
    """
    path = os.path.join(repo, ".comprehension", "attestations.yaml")
    if not os.path.isfile(path):
        return []

    records: list[dict[str, str]] = []
    current: dict[str, str] | None = None
    with open(path) as f:
        for raw in f:
            line = raw.split("#", 1)[0].rstrip("\n")
            if not line.strip():
                continue
            stripped = line.strip()
            if stripped.startswith("- "):
                if current is not None:
                    records.append(current)
                current = {}
                stripped = stripped[2:]
            if current is None:
                raise ValueError(f"attestations.yaml: expected a list, got: {line!r}")
            if ":" not in stripped:
                raise ValueError(f"attestations.yaml: expected 'key: value', got: {line!r}")
            key, _, value = stripped.partition(":")
            current[key.strip()] = value.strip().strip('"').strip("'")
    if current is not None:
        records.append(current)

    attestations: list[tuple[str, str, int]] = []
    for rec in records:
        for required in ("email", "module", "timestamp"):
            if required not in rec:
                raise ValueError(f"attestations.yaml: record missing {required!r}: {rec}")
        if rec["module"] not in cfg["modules"]:
            # A subset-module config is a legitimate run mode, so this is a
            # skip, not an error -- but silent drops are how real data goes
            # missing unnoticed, so name the record on the way out.
            print(f"comprehension: attestations.yaml: skipping record for "
                  f"unconfigured module {rec['module']!r}: {rec}", file=sys.stderr)
            continue
        ts = parse_as_of(rec["timestamp"], "attestations.yaml timestamp")
        attestations.append((rec["email"].strip().lower(), rec["module"], ts))
    return attestations

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


def collect(cfg: dict, commits: list[Commit], as_of: int,
            attestations: list[tuple[str, str, int]] | None = None):
    """Build evidence events and per-module churn/size, deterministic order.

    churn_after(e) = (final_total[m] - total_at(e)) - (final_own[m,p] - own_at(e))
    i.e. lines changed in the module by anyone other than the event's person,
    between the event and the as-of instant. O(events), not O(events^2).

    `attestations` (SPEC §2, (email, module, timestamp) triples from
    read_attestations, email lowercased and NOT yet resolved to a person)
    are folded into the same chronological pass so their total_at/own_at
    snapshots -- and therefore their churn-based decay -- reflect real
    churn up to their timestamp. An attestation itself contributes no
    lines: it doesn't mutate `total`/`own`, since it's a self-report, not a
    code change. Its magnitude is fixed at `cfg["sat_lines"]` (fully
    saturated: a re-walk is a discrete claim, not something scaled by
    lines touched).

    Identity resolution for an attestation's email (SPEC §2 ATTESTED note):
    (1) the `identity` config map, same as any git evidence; (2) if
    unmapped, the author name most recently used with that email by a
    commit at or before the attestation's timestamp, walking the same
    total order this function already establishes; (3) the raw email
    string, only if it never appears in the commit stream at all. This
    keeps one person from forking into two identities depending on whether
    their evidence came from git or from an attestation.
    """
    events: list[Evidence] = []
    module_size: dict[str, float] = defaultdict(float)
    total: dict[str, float] = defaultdict(float)            # module -> churn
    own: dict[tuple[str, str], float] = defaultdict(float)  # (module, person)
    last_name_for_email: dict[str, str] = {}

    # merge commits and attestations into one chronological action stream so
    # attestations see the correct as-of-that-instant churn snapshot AND the
    # correct as-of-that-instant identity; ties at the same timestamp process
    # commits first (stable, deterministic) so "at or before" includes same-
    # timestamp commits.
    actions: list[tuple[int, int, object]] = (
        [(c.timestamp, 0, c) for c in commits if c.timestamp <= as_of] +
        [(ts, 1, (email, mod)) for email, mod, ts in (attestations or []) if ts <= as_of]
    )
    actions.sort(key=lambda a: (a[0], a[1]))

    for ts, kind, payload in actions:
        if kind == 0:
            c = payload
            last_name_for_email[c.author_email] = c.author_name
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
                events.append(Evidence(person, mod, etype, ts, lines,
                                       total_at=total[mod], own_at=own[(mod, person)]))
                total[mod] += lines
                own[(mod, person)] += lines
        else:
            email, mod = payload
            fallback_name = last_name_for_email.get(email, email)
            person = canonical(cfg, fallback_name, email)
            events.append(Evidence(person, mod, "ATTESTED", ts, cfg["sat_lines"],
                                   total_at=total[mod], own_at=own[(mod, person)]))
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
    if a.as_of:
        try:
            as_of = parse_as_of(a.as_of)
        except ValueError as e:
            sys.exit(str(e))
    else:
        as_of = int(
            subprocess.run(["git", "-C", a.repo, "log", cfg["ref"], "-1", "--format=%at"],
                           capture_output=True, text=True, check=True).stdout.strip())

    commits = read_commits(a.repo, cfg["ref"])
    attestations = read_attestations(a.repo, cfg)
    events, sizes = collect(cfg, commits, as_of, attestations)
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
