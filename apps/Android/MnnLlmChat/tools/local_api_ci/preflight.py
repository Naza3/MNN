#!/usr/bin/env python3
"""Freeze the engine at job start; never silently substitute another engine."""
import argparse
import datetime
import hashlib
import json
from pathlib import Path
import re
import subprocess
import sys

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[4]
ENGINE_PATHS = ("CMakeLists.txt", "include", "source", "express", "transformers",
                "tools", "project/android", "3rd_party", "schema/default", "schema/current", "cmake", "codegen", "apps/frameworks/sherpa-mnn")


def run(*args):
    return subprocess.check_output(args, cwd=ROOT, text=True, timeout=120).strip()


def write_json(path, value):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(value, indent=2) + "\n")


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--report-dir", type=Path, required=True)
    args = parser.parse_args()
    lock = json.loads((HERE / "build-lock.json").read_text())
    sha = lock["engine_base_sha"]
    if not re.fullmatch(r"[0-9a-f]{40}", sha):
        raise RuntimeError("Invalid engine base SHA")
    if run("git", "status", "--porcelain", "--untracked-files=normal"):
        raise RuntimeError("Build input checkout is dirty; commit/review the inputs before recording exact provenance")
    report = {
        "checked_at_utc": datetime.datetime.now(datetime.timezone.utc).isoformat(),
        "app_commit_sha": run("git", "rev-parse", "HEAD"),
        "engine_base_sha": sha,
        "lock_sha256": hashlib.sha256((HERE / "build-lock.json").read_bytes()).hexdigest(),
        "toolchain_requested": lock["toolchain"],
        "freshness_status": "unverified",
    }
    report_path = args.report_dir / "source-provenance.json"
    write_json(report_path, report)
    # The public upstream is queried once, at build start. A later upstream
    # commit does not change the frozen source used by this job.
    latest = run("git", "ls-remote", "--exit-code", lock["upstream_url"], lock["upstream_ref"])
    latest_sha = latest.split()[0] if latest.split() else ""
    if not re.fullmatch(r"[0-9a-f]{40}", latest_sha):
        raise RuntimeError("Upstream did not return one verifiable commit SHA")
    report["upstream_sha_at_start"] = latest_sha
    report["freshness_status"] = "current" if latest_sha == sha else "stale"
    write_json(report_path, report)
    if latest_sha != sha:
        raise RuntimeError(f"Engine baseline {sha} is stale; upstream is {latest_sha}. "
                           "Synchronize and revalidate the feature branch, then update build-lock.json. "
                           "No floating download or older-engine fallback is permitted.")
    subprocess.run(["git", "merge-base", "--is-ancestor", sha, "HEAD"], cwd=ROOT, check=True)
    subprocess.run(["git", "diff", "--exit-code", "--quiet", sha, "HEAD", "--", *ENGINE_PATHS],
                   cwd=ROOT, check=True)
    # Compare tree identities without reading engine implementation files.
    report["engine_tree_objects"] = {p: run("git", "rev-parse", f"{sha}:{p}") for p in ENGINE_PATHS}
    report["engine_source_matches_locked_upstream"] = True
    write_json(report_path, report)
    print(f"Engine {sha}; App {report['app_commit_sha']}; latest upstream verified at job start")


if __name__ == "__main__":
    try:
        main()
    except (RuntimeError, OSError, subprocess.CalledProcessError, subprocess.TimeoutExpired) as error:
        print(f"PRECHECK FAILED: {error}", file=sys.stderr)
        sys.exit(1)
