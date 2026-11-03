#!/usr/bin/env python3
"""Build the current source and run exactly iteration 0 of the synthetic mini example."""
from __future__ import annotations

import argparse
import hashlib
import json
import os
from pathlib import Path
import subprocess
import sys
from datetime import datetime, timezone

HERE = Path(__file__).resolve().parent
REPO = HERE.parents[1]


def digest(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, required=True,
                        help="A new output directory; existing paths are refused.")
    parser.add_argument("--maven", default="mvn", help="Maven executable (default: mvn).")
    parser.add_argument("--maven-repo", type=Path, help="Optional Maven dependency cache.")
    parser.add_argument("--offline", action="store_true", help="Use only already cached Maven dependencies.")
    args = parser.parse_args()
    output = args.output.resolve()
    if output.exists():
        parser.error(f"Output already exists: {output}")
    output.parent.mkdir(parents=True, exist_ok=True)
    # Keep local classpath and logs next to the new run, outside versioned example inputs.
    auxiliary = output.parent / (output.name + "-build")
    auxiliary.mkdir(exist_ok=False)
    classpath_file = auxiliary / "runtime-classpath.txt"
    command = [args.maven, "--batch-mode", "--no-transfer-progress"]
    if args.offline:
        command.append("--offline")
    if args.maven_repo:
        command.append("-Dmaven.repo.local=" + str(args.maven_repo.resolve()))
    command += ["-pl", "matsim", "-am", "-DskipTests", "compile",
                "org.apache.maven.plugins:maven-dependency-plugin:3.7.0:build-classpath",
                "-DincludeScope=runtime", "-Dmdep.outputFile=" + str(classpath_file)]
    source_paths = sorted((REPO / "matsim/src/main/java").rglob("*.java"))
    before = {p.relative_to(REPO).as_posix(): digest(p) for p in source_paths}
    started = datetime.now(timezone.utc).isoformat()
    with (auxiliary / "build.log").open("w") as log:
        subprocess.run(command, cwd=REPO, stdout=log, stderr=subprocess.STDOUT, check=True)
    runtime_classpath = str(REPO / "matsim/target/classes") + os.pathsep + classpath_file.read_text().strip()
    run_command = ["java", "-Xmx1g", "-cp", runtime_classpath,
                   "org.matsim.run.examples.RunCoTransportMini",
                   str(HERE / "input/config.xml"), str(output)]
    with (auxiliary / "run.log").open("w") as log:
        subprocess.run(run_command, cwd=REPO, stdout=log, stderr=subprocess.STDOUT, check=True)
    subprocess.run([sys.executable, str(HERE / "verify.py"), str(output)], check=True)
    after = {p.relative_to(REPO).as_posix(): digest(p) for p in source_paths}
    if before != after:
        raise RuntimeError("Source changed during the example run; receipt would be ambiguous.")
    selected = {name: value for name, value in before.items()
                if "/contrib/cotransport/" in name or name.endswith("/RunCoTransportMini.java")
                or "/core/mobsim/qsim/pt/" in name}
    receipt = {"schema": "cotransport-mini-run-v1", "startedAtUtc": started,
               "finishedAtUtc": datetime.now(timezone.utc).isoformat(),
               "iterations": [0], "simulationInvocations": 1, "dispatchPredictorInvocations": 0,
               "sourceUnchangedDuringRun": True, "currentSourceCount": len(before),
               "selectedSourceSha256": selected,
               "inputSha256": {p.name: digest(p) for p in sorted((HERE / "input").iterdir())},
               "buildCommand": command, "javaMainClass": run_command[-3],
               "javaVersion": subprocess.run(["java", "-version"], capture_output=True, text=True).stderr.strip(),
               "runtime": "Current repository matsim/target/classes, rebuilt by Maven before execution",
               "status": "PASS"}
    (output / "mini_run_receipt.json").write_text(json.dumps(receipt, ensure_ascii=False, indent=2) + "\n")
    print(f"Mini example completed and verified: {output}")


if __name__ == "__main__":
    main()
