#!/usr/bin/env python3
"""Prepare or verify a standalone, allowlisted local GitHub release checkout.

The default operation is a read-only preview. --write creates a new directory,
preserves the upstream commit ancestry, stages the curated changes, and never
commits, creates a GitHub repository, or pushes. Existing destinations are refused.
Only the Python standard library and git are required (Python >= 3.9).
"""
from __future__ import annotations

import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
from datetime import datetime, timezone

ROOT = Path(__file__).resolve().parents[1]
MANIFEST = "RELEASE_MANIFEST.json"
EXTRA_TREES = (
    "matsim/src/main",
    "matsim/src/test",
    ".mvn",
    "docs/module_guide_20261103",
    "examples/cotransport-mini",
)
EXTRA_FILES = (
    "mvnw", "mvnw.cmd", "README_COTRANSPORT_zh.md",
    "scripts/prepare_github_release.py",
    "scripts/ismt_build.py", "scripts/ismt_unified.py",
)
SKIP_PARTS = {
    ".git", "__pycache__", ".DS_Store", ".build-cache", ".runtime",
    ".pycache_check", "target", "runs",
}
SECRET_PATTERNS = {
    "private-key": re.compile(rb"-----BEGIN (?:RSA |OPENSSH |EC )?PRIVATE KEY-----"),
    "github-token": re.compile(rb"\bgh[pousr]_[A-Za-z0-9]{30,}\b"),
    "openai-project-key": re.compile(rb"\bsk-proj-[A-Za-z0-9_-]{30,}\b"),
    "aws-access-key": re.compile(rb"\bAKIA[A-Z0-9]{16}\b"),
}


def git(root: Path, *args: str) -> bytes:
    return subprocess.check_output(["git", "-C", str(root), *args])


def digest(path: Path) -> str:
    h = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            h.update(chunk)
    return h.hexdigest()


def selected_files() -> tuple[set[str], list[str]]:
    tracked = {os.fsdecode(p) for p in git(ROOT, "ls-tree", "-r", "--name-only", "-z", "HEAD").split(b"\0") if p}
    # Publication workflows belong to the original MATSim organisation. Keep
    # their history but remove them from this independent release working tree.
    selected = {p for p in tracked if not p.startswith(".github/workflows/")}
    for tree in EXTRA_TREES:
        base = ROOT / tree
        if not base.is_dir():
            raise ValueError(f"Required release directory missing: {tree}")
        for path in base.rglob("*"):
            relative = path.relative_to(ROOT)
            if not SKIP_PARTS.intersection(relative.parts) and path.is_file():
                selected.add(relative.as_posix())
    selected.update(EXTRA_FILES)
    missing = sorted(p for p in selected if not (ROOT / p).is_file())
    # Tracked deletions are preserved, including the pre-existing Makefile
    # deletion; new release prerequisites, however, must all exist.
    for relative in EXTRA_FILES:
        if relative in missing:
            raise ValueError(f"Required release file missing: {relative}")
    selected.difference_update(missing)
    for relative in selected:
        path = ROOT / relative
        if path.is_symlink() or ROOT not in path.resolve().parents:
            raise ValueError(f"Release paths must be ordinary repository files: {relative}")
    return selected, missing


def secret_findings(directory: Path, paths: set[str]) -> list[dict]:
    findings = []
    for relative in sorted(paths):
        path = directory / relative
        if path.stat().st_size > 4 * 1024 * 1024:
            continue
        data = path.read_bytes()
        if b"\0" in data:
            continue
        for label, pattern in SECRET_PATTERNS.items():
            for match in pattern.finditer(data):
                findings.append({"path": relative, "kind": label,
                                 "line": data[:match.start()].count(b"\n") + 1})
    return findings


def verify(destination: Path) -> dict:
    if destination.is_symlink() or (destination / ".git").is_symlink():
        raise ValueError("Release checkout and .git must not be symbolic links")
    top = Path(git(destination, "rev-parse", "--show-toplevel").decode().strip()).resolve()
    if top != destination:
        raise ValueError("Destination must be the exact Git checkout root")
    manifest = json.loads((destination / MANIFEST).read_text(encoding="utf-8"))
    expected = manifest["files"]
    symlinks = sorted(p.relative_to(destination).as_posix() for p in destination.rglob("*")
                      if ".git" not in p.relative_to(destination).parts and p.is_symlink())
    actual = {p.relative_to(destination).as_posix()
              for p in destination.rglob("*")
              if p.is_file() and ".git" not in p.relative_to(destination).parts
              and p.name != MANIFEST}
    missing = sorted(set(expected) - actual)
    extra = sorted(actual - set(expected))
    changed = sorted(p for p in set(expected) & actual
                     if digest(destination / p) != expected[p]["sha256"])
    findings = secret_findings(destination, actual)
    remotes = git(destination, "remote").decode().split()
    origin_absent = "origin" not in remotes
    upstream_push_disabled = git(destination, "remote", "get-url", "--push", "upstream").decode().strip() == "DISABLED"
    base_matches = git(destination, "rev-parse", "HEAD").decode().strip() == manifest["upstreamCommit"]
    branch_matches = git(destination, "branch", "--show-current").decode().strip() == "release/cotransport-20261103"
    indexed = {os.fsdecode(p) for p in git(destination, "ls-files", "-z").split(b"\0") if p}
    index_matches = indexed == actual | {MANIFEST}
    unstaged = [os.fsdecode(p) for p in git(destination, "diff", "--name-only", "-z").split(b"\0") if p]
    passed = (not (missing or extra or changed or findings or symlinks or unstaged)
              and origin_absent and upstream_push_disabled and base_matches and branch_matches and index_matches)
    return {"passed": passed, "files": len(expected), "bytes": sum(v["bytes"] for v in expected.values()),
            "missing": missing, "extra": extra, "changed": changed,
            "credentialPatternFindings": findings,
            "symlinks": symlinks, "unstagedChanges": unstaged,
            "originAbsent": origin_absent, "upstreamPushDisabled": upstream_push_disabled,
            "upstreamBaseMatches": base_matches,
            "releaseBranchMatches": branch_matches, "indexFileCoverageMatches": index_matches,
            "scope": "File integrity and common credential patterns only; not a build or scientific validation."}


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--destination", type=Path, required=True)
    operation = parser.add_mutually_exclusive_group()
    operation.add_argument("--write", action="store_true")
    operation.add_argument("--verify", action="store_true")
    args = parser.parse_args()
    destination = args.destination.expanduser().resolve()
    if args.verify:
        result = verify(destination)
        print(json.dumps(result, ensure_ascii=False, indent=2))
        if not result["passed"]:
            raise SystemExit(1)
        return
    if destination == ROOT or ROOT in destination.parents or destination in ROOT.parents:
        raise ValueError("Destination must be an independent sibling or external checkout")
    if destination.exists():
        raise FileExistsError(f"Refusing an existing destination: {destination}")
    selected, missing = selected_files()
    findings = secret_findings(ROOT, selected)
    if findings:
        raise ValueError("Review credential-pattern findings before release: " + json.dumps(findings))
    commit = git(ROOT, "rev-parse", "HEAD").decode().strip()
    upstream_url = "https://github.com/matsim-org/matsim-libs.git"
    summary = {"operation": "write" if args.write else "preview", "destination": str(destination),
               "upstreamCommit": commit, "selectedSourceFiles": len(selected),
               "selectedSourceBytes": sum((ROOT / p).stat().st_size for p in selected),
               "preservedTrackedDeletions": missing, "credentialPatternFindings": findings,
               "commit": False, "push": False}
    if not args.write:
        print(json.dumps(summary, ensure_ascii=False, indent=2))
        return
    destination.parent.mkdir(parents=True, exist_ok=True)
    subprocess.run(["git", "clone", "--no-hardlinks", "--no-checkout", "--origin", "upstream",
                    str(ROOT), str(destination)], check=True, stdout=subprocess.DEVNULL)
    subprocess.run(["git", "-C", str(destination), "remote", "set-url", "upstream", upstream_url], check=True)
    subprocess.run(["git", "-C", str(destination), "remote", "set-url", "--push", "upstream", "DISABLED"], check=True)
    subprocess.run(["git", "-C", str(destination), "checkout", "-b", "release/cotransport-20261103", commit],
                   check=True, stdout=subprocess.DEVNULL)
    original = {os.fsdecode(p) for p in git(destination, "ls-files", "-z").split(b"\0") if p}
    for relative in sorted(original - selected):
        (destination / relative).unlink()
    source_hashes = {}
    for relative in sorted(selected):
        source, target = ROOT / relative, destination / relative
        target.parent.mkdir(parents=True, exist_ok=True)
        source_hashes[relative] = digest(source)
        shutil.copy2(source, target)
        if digest(target) != source_hashes[relative]:
            raise RuntimeError(f"Source changed while copying: {relative}")
    # Use the extension guide as the published landing page. Keep the official
    # baseline README verbatim as a separately attributed upstream document.
    (destination / "README_UPSTREAM.md").write_bytes(git(ROOT, "show", f"{commit}:README.md"))
    (destination / "README.md").write_bytes((ROOT / "README_COTRANSPORT_zh.md").read_bytes())
    with (destination / ".gitignore").open("a", encoding="utf-8") as stream:
        stream.write("\n\n# Local research outputs and build caches\n.build-cache/\n.pycache_check/\n__pycache__/\n*.pyc\noutputs/\ntmp/\nexamples/cotransport-mini/runs/\n")
    paths = {p.relative_to(destination).as_posix()
             for p in destination.rglob("*")
             if p.is_file() and ".git" not in p.relative_to(destination).parts}
    manifest = {"schemaVersion": "cotransport-local-release-v1",
                "createdAt": datetime.now(timezone.utc).isoformat(),
                "upstreamCommit": commit, "upstreamRepository": upstream_url,
                "sourceScope": list(EXTRA_TREES) + list(EXTRA_FILES) + ["all existing upstream-tracked files except publication workflows"],
                "sourceFileSha256": source_hashes,
                "transformations": ["README.md is the extension guide; original upstream README retained in README_UPSTREAM.md",
                                    "upstream publication workflows removed from working tree; ancestry retained",
                                    "release-only cache/output ignores appended",
                                    "pre-existing tracked deletions preserved"],
                "preservedTrackedDeletions": missing,
                "files": {p: {"bytes": (destination / p).stat().st_size,
                              "sha256": digest(destination / p)} for p in sorted(paths)},
                "published": False, "committed": False}
    (destination / MANIFEST).write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    # Force is safe here: every copied path was selected explicitly. It also
    # retains upstream fixtures whose names match the inherited ignore rules.
    subprocess.run(["git", "-C", str(destination), "add", "-A", "-f", "--", "."], check=True)
    result = verify(destination)
    print(json.dumps({**summary, "verification": result}, ensure_ascii=False, indent=2))
    if not result["passed"]:
        raise SystemExit(1)


if __name__ == "__main__":
    main()
