#!/usr/bin/env python3
"""A4 parity check (SPEC §7, PLAN.md #10).

Runs the Python prototype (`prototype/comprehension.py`) and the Kotlin CLI
against the same repo/config/as-of and byte-diffs every output mode: the
console map, `--show-individuals`, `--json`, and `--gate` (console output
and exit code). Per CLAUDE.md's parity rule, any difference found here is a
Kotlin bug by definition, never a reason to change the prototype.

Usage:
  scripts/parity_check.py --synthetic
      Self-contained: builds the same deterministic repo as
      prototype/test_comprehension.py's build_synthetic_repo(), with a
      critical:[core, web] gate config layered on top so the --gate path
      is exercised too. No network access; safe for CI.

  scripts/parity_check.py --repo PATH --config PATH --as-of TIMESTAMP
                           [--gate-config PATH]
      Generic form for a real clone (e.g. the pinned express clone used for
      issue #10's A4 run). --gate-config defaults to --config.
"""
import argparse
import json
import os
import subprocess
import sys
import tempfile

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
PROTOTYPE = os.path.join(ROOT, "prototype", "comprehension.py")
MAIN_CLASS = "com.tiarebalbi.comprehensioncoverage.MainKt"


def _kotlin_classpath() -> str:
    """Resolves the Kotlin runtime classpath by asking Gradle directly,
    via a throwaway init script -- avoids depending on the `application`
    plugin (not applied; see build.gradle.kts) just to get `./gradlew run`."""
    subprocess.run([os.path.join(ROOT, "gradlew"), "-q", "classes"], cwd=ROOT, check=True)
    init_script = tempfile.NamedTemporaryFile(
        mode="w", suffix=".init.gradle.kts", delete=False)
    init_script.write(
        "allprojects {\n"
        "    tasks.register(\"printRuntimeClasspathForParity\") {\n"
        "        doLast {\n"
        "            val cfg = configurations.findByName(\"runtimeClasspath\")\n"
        "            if (cfg != null) println(\"CP::\" + cfg.files.joinToString(\":\"))\n"
        "        }\n"
        "    }\n"
        "}\n"
    )
    init_script.close()
    try:
        out = subprocess.run(
            [os.path.join(ROOT, "gradlew"), "-q", "--init-script", init_script.name,
             "printRuntimeClasspathForParity"],
            cwd=ROOT, capture_output=True, text=True, check=True,
        ).stdout
    finally:
        os.unlink(init_script.name)
    for line in out.splitlines():
        if line.startswith("CP::"):
            deps = line[len("CP::"):]
            classes = os.path.join(ROOT, "build", "classes", "kotlin", "main")
            resources = os.path.join(ROOT, "build", "resources", "main")
            return os.pathsep.join([classes, resources, deps])
    raise RuntimeError("could not resolve Kotlin runtimeClasspath")


def run_prototype(repo, config, as_of, extra_args):
    result = subprocess.run(
        ["python3", PROTOTYPE, "--repo", repo, "--config", config,
         "--as-of", as_of, *extra_args],
        capture_output=True, text=True,
    )
    return result.returncode, result.stdout, result.stderr


def run_kotlin(classpath, repo, config, as_of, extra_args):
    result = subprocess.run(
        ["java", "-cp", classpath, MAIN_CLASS, "--repo", repo, "--config", config,
         "--as-of", as_of, *extra_args],
        capture_output=True, text=True,
    )
    return result.returncode, result.stdout, result.stderr


def diff_mode(label, py_result, kt_result, compare_stdout=True):
    py_code, py_out, py_err = py_result
    kt_code, kt_out, kt_err = kt_result
    ok = True
    if py_code != kt_code:
        print(f"[{label}] exit code differs: prototype={py_code} kotlin={kt_code}")
        ok = False
    if compare_stdout and py_out != kt_out:
        print(f"[{label}] stdout differs")
        print(f"  prototype: {py_out!r}")
        print(f"  kotlin:    {kt_out!r}")
        ok = False
    if ok:
        print(f"[{label}] IDENTICAL (exit={py_code})")
    return ok


def compare(repo, config, as_of, gate_config, classpath):
    all_ok = True

    with tempfile.NamedTemporaryFile(suffix=".json", delete=False) as py_json_f:
        py_json_path = py_json_f.name
    with tempfile.NamedTemporaryFile(suffix=".json", delete=False) as kt_json_f:
        kt_json_path = kt_json_f.name
    try:
        py_map = run_prototype(repo, config, as_of, ["--json", py_json_path])
        kt_map = run_kotlin(classpath, repo, config, as_of, ["--json", kt_json_path])
        all_ok &= diff_mode("map console", py_map, kt_map)

        with open(py_json_path) as f:
            py_json = f.read()
        with open(kt_json_path) as f:
            kt_json = f.read()
        if py_json != kt_json:
            print("[map json] differs byte-for-byte")
            all_ok = False
        else:
            print("[map json] IDENTICAL")
    finally:
        os.unlink(py_json_path)
        os.unlink(kt_json_path)

    py_ind = run_prototype(repo, config, as_of, ["--show-individuals"])
    kt_ind = run_kotlin(classpath, repo, config, as_of, ["--show-individuals"])
    all_ok &= diff_mode("show-individuals console", py_ind, kt_ind)

    py_gate = run_prototype(repo, gate_config, as_of, ["--gate"])
    kt_gate = run_kotlin(classpath, repo, gate_config, as_of, ["--gate"])
    all_ok &= diff_mode("gate console + exit code", py_gate, kt_gate)

    return all_ok


def build_synthetic():
    sys.path.insert(0, os.path.join(ROOT, "prototype"))
    import test_comprehension as tc  # noqa: E402  (path must be set first)

    tmp = tempfile.mkdtemp()
    tc.build_synthetic_repo(tmp)
    as_of = tc.T0 + 330 * tc.DAY
    as_of_iso = tc._iso(as_of)

    config = {
        "modules": {"core": ["src/core/*"], "web": ["src/web/*"]},
        "identity": {"dana@example.com": "Dana"},
    }
    gate_config = dict(config, critical=["core", "web"])

    config_path = os.path.join(tmp, "..", "synthetic-config.json")
    config_path = os.path.abspath(config_path)
    gate_config_path = os.path.join(tmp, "..", "synthetic-gate-config.json")
    gate_config_path = os.path.abspath(gate_config_path)
    with open(config_path, "w") as f:
        json.dump(config, f)
    with open(gate_config_path, "w") as f:
        json.dump(gate_config, f)

    return tmp, config_path, gate_config_path, as_of_iso


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__,
                                  formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--synthetic", action="store_true",
                     help="build and use the deterministic synthetic repo (no network)")
    ap.add_argument("--repo")
    ap.add_argument("--config")
    ap.add_argument("--as-of")
    ap.add_argument("--gate-config", help="defaults to --config")
    args = ap.parse_args()

    classpath = _kotlin_classpath()

    if args.synthetic:
        repo, config, gate_config, as_of = build_synthetic()
    else:
        if not (args.repo and args.config and args.as_of):
            ap.error("--repo, --config, and --as-of are required unless --synthetic is given")
        repo, config, as_of = args.repo, args.config, args.as_of
        gate_config = args.gate_config or args.config

    ok = compare(repo, config, as_of, gate_config, classpath)
    print()
    print("PARITY: PASS" if ok else "PARITY: FAIL")
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())
