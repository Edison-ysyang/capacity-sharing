#!/usr/bin/env python3
"""Explicit single-arm C/M/T/D front door; by default, print a read-only plan.

UNIFIED_CURRENT requires existing v1 C/D or v2 windowed M/T declarations; it
does not convert historical inputs or launch a four-arm matrix. Like
WINDOWED_MIXED, it is inspect/development-only pending separate formal
registration. PRESERVE_REGISTERED retains the historical entry semantics.
Even inspect/development invokes Java only with an explicit --execute.
UNIFIED_CURRENT development M/T additionally requires --paired-spec for the
opposite arm. Java verifies the declared single-switch comparison; comparing
declarations or input hashes is not itself verification of actual input bytes.
"""
import argparse
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import zipfile

sys.dont_write_bytecode = True
from ismt_build import confined, digest

MAIN = "org.matsim.run.ismt.UnifiedExperimentRunner"
PROFILES = ("PRESERVE_REGISTERED", "WINDOWED_MIXED", "UNIFIED_CURRENT")


def validate_operation(args):
    """Reject unsupported launch modes before touching runtime artifacts."""
    if args.compatibility is not None and (
            args.operation != "formal" or args.arm != "T" or args.profile != "PRESERVE_REGISTERED"):
        raise ValueError("Compatibility evidence is only accepted for preserved formal T")
    if args.operation == "formal":
        if args.profile in {"WINDOWED_MIXED", "UNIFIED_CURRENT"}:
            raise ValueError("Forward profiles are inspect/development-only; formal use requires "
                             "separate experiment registration, not promotion of development results")
        if args.arm == "T" and args.compatibility is None:
            raise ValueError("Preserved formal T needs exact-build compatibility evidence")
    paired_required = (args.profile == "UNIFIED_CURRENT" and args.operation == "development"
                       and args.arm in {"M", "T"})
    if paired_required and args.paired_spec is None:
        raise ValueError("UNIFIED_CURRENT development M/T requires --paired-spec for the opposite arm")
    if args.paired_spec is not None and not paired_required:
        raise ValueError("--paired-spec is only accepted for UNIFIED_CURRENT development M/T")


def validate_current_specification(spec, arm):
    """Check only explicit schema/arm identity; Java verifies the full contract."""
    def unique_object(pairs):
        result = {}
        for key, value in pairs:
            if key in result:
                raise ValueError(f"Duplicate specification field: {key}")
            result[key] = value
        return result

    def reject_constant(value):
        raise ValueError(f"Non-JSON specification constant: {value}")

    metadata = json.loads(spec.read_text(encoding="utf-8"),
                          object_pairs_hook=unique_object, parse_constant=reject_constant)
    if not isinstance(metadata, dict):
        raise ValueError("UNIFIED_CURRENT requires one explicit specification object")
    expected_schema = "ismt-experiment-v1" if arm in {"C", "D"} else "ismt-dynamic-experiment-v2"
    expected_arm = {"C": "CONTROL", "M": "MIXED", "T": "DYNAMIC_MIXED", "D": "DEDICATED"}[arm]
    if metadata.get("schemaVersion") != expected_schema:
        raise ValueError(f"UNIFIED_CURRENT {arm} requires explicit {expected_schema}; no implicit conversion")
    identity = metadata if arm in {"C", "D"} else metadata.get("design")
    if not isinstance(identity, dict) or identity.get("arm") != expected_arm:
        raise ValueError(f"UNIFIED_CURRENT {arm} requires specification arm {expected_arm}")


def runtime_environment():
    # Do not allow inherited JVM agents/options or a second classpath to bypass
    # the explicit, manifest-bound invocation printed by this entry.
    return {key: value for key, value in os.environ.items()
            if key not in {"JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS", "_JAVA_OPTIONS", "CLASSPATH"}}


def command(args):
    validate_operation(args)
    workspace = args.workspace.resolve(strict=True)
    manifest_path = confined(workspace, args.manifest)
    manifest = json.loads(manifest_path.read_text())
    if manifest.get("schemaVersion") != "ismt-build-v1":
        raise ValueError("Expected explicit ismt-build-v1 manifest")
    classpath = manifest.get("runtimeClasspath", [])
    if not classpath or classpath[0] != manifest.get("applicationJar"):
        raise ValueError("Require the recorded application-first classpath")
    jars = []
    for ref in classpath:
        path = confined(workspace, ref["path"])
        if path.suffix != ".jar" or digest(path) != ref["sha256"]:
            raise ValueError(f"Runtime file missing or changed: {path}")
        jars.append(str(path))
    with zipfile.ZipFile(jars[0]) as jar:
        if MAIN.replace(".", "/") + ".class" not in jar.namelist():
            raise ValueError("Selected historical JAR has no unified entry; use a newly built unified manifest")
    spec = confined(workspace, args.spec)
    if not spec.is_file():
        raise ValueError("Specification must already exist; no implicit conversion")
    if args.profile == "UNIFIED_CURRENT":
        validate_current_specification(spec, args.arm)
    paired_spec = None
    if args.paired_spec is not None:
        paired_spec = confined(workspace, args.paired_spec)
        if not paired_spec.is_file():
            raise ValueError("Paired specification must already exist; no implicit conversion")
        validate_current_specification(paired_spec, "T" if args.arm == "M" else "M")
    output = confined(workspace, args.output)
    if output == workspace:
        raise ValueError("Output cannot be the workspace root")
    java = str(Path(os.environ["JAVA_HOME"]) / "bin/java") if os.environ.get("JAVA_HOME") else shutil.which("java")
    if not java or not Path(java).is_file():
        raise ValueError("Set JAVA_HOME to the JDK used by the build")
    invocation = [java, f"-Xmx{args.heap_gb}g", "-cp", os.pathsep.join(jars), MAIN,
                  args.operation, args.arm, args.profile, str(workspace), str(output), str(manifest_path), str(spec)]
    if paired_spec is not None:
        invocation.append(str(paired_spec))
    if args.compatibility is not None:
        compatibility = confined(workspace, args.compatibility)
        invocation += [str(compatibility), digest(compatibility)]
    return invocation


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("operation", choices=["inspect", "development", "formal"])
    parser.add_argument("--workspace", type=Path, default=Path(__file__).resolve().parents[2])
    parser.add_argument("--arm", required=True, choices=list("CMTD"))
    parser.add_argument("--profile", required=True, choices=PROFILES)
    parser.add_argument("--manifest", type=Path, required=True)
    parser.add_argument("--spec", type=Path, required=True)
    parser.add_argument("--paired-spec", type=Path,
                        help="Required opposite-arm declaration for UNIFIED_CURRENT development M/T only")
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--compatibility", type=Path)
    parser.add_argument("--heap-gb", type=int, choices=range(1, 65), default=4)
    parser.add_argument("--execute", action="store_true", help="Actually invoke the selected Java operation; otherwise only print the plan")
    args = parser.parse_args(argv)
    invocation = command(args)
    print(json.dumps({"arm": args.arm, "profile": args.profile, "operation": args.operation,
                      "command": invocation, "simulationRequested": args.execute and args.operation != "inspect",
                      "note": "Runtime payload verified; Java still verifies source, typed inputs, "
                              "the execution contract and applicable gates. A printed plan is not "
                              "a formal registration or simulation validation."}, indent=2), flush=True)
    if args.execute:
        return subprocess.run(invocation, cwd=args.workspace.resolve(), env=runtime_environment()).returncode
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
