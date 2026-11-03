#!/usr/bin/env python3
"""Freeze a separate source checkout and bind one wrapper-built runtime to its hashes.

The user's working repository is only read. No Maven command runs during ``snapshot``.
``build`` requires a clean source checkout and publishes no manifest on any failure.
All paths, temporary files, Git state, Maven caches and logs remain in the workspace.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import os
import platform
import re
import shutil
import subprocess
import uuid
import xml.etree.ElementTree as ET
import zipfile
from datetime import datetime, timezone
from pathlib import Path
from typing import Callable

SCHEMA_VERSION = "ismt-build-v1"
DEPENDENCY_PLUGIN = "org.apache.maven.plugins:maven-dependency-plugin:3.7.0:build-classpath"
TRANSIENT_PARTS = {".git", "target", "__pycache__", ".idea", ".vscode", ".build-cache", "node_modules"}
SOURCE_TREES = ("matsim/src", "matsim/test/input", "examples/src", "scripts",
                "experiments/ismt_q720", "docs", ".mvn")
ROOT_FILES = ("pom.xml", "mvnw", "mvnw.cmd", ".gitignore", ".gitattributes",
              "COPYING", "LICENSE", "WARRANTY", "NOTICE", "README.md", "README.txt")


def digest(path: Path) -> str:
    hasher = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            hasher.update(chunk)
    return hasher.hexdigest()


def canonical(value: object, *, sort_keys: bool = False) -> bytes:
    return json.dumps(value, sort_keys=sort_keys, ensure_ascii=False,
                      separators=(",", ":"), allow_nan=False).encode("utf-8")


def source_sha256(files: dict[str, str]) -> str:
    return hashlib.sha256(canonical(files, sort_keys=True)).hexdigest()


def dependency_sha256(classpath: list[dict[str, str]]) -> str:
    # FileRef field order is part of the Java/Python shared contract.
    ordered = [{"path": entry["path"], "sha256": entry["sha256"]} for entry in classpath]
    return hashlib.sha256(canonical(ordered)).hexdigest()


def confined(workspace: Path, value: Path | str) -> Path:
    """Resolve only workspace-local paths and reject symlink/path-traversal ambiguity."""
    workspace = workspace.resolve(strict=True)
    value = Path(value)
    if ".." in value.parts:
        raise ValueError(f"Parent traversal is not allowed: {value}")
    candidate = value if value.is_absolute() else workspace / value
    try:
        relative = candidate.relative_to(workspace)
    except ValueError as error:
        raise ValueError(f"Path escapes the workspace: {candidate}") from error
    current = workspace
    for part in relative.parts:
        current /= part
        if current.is_symlink():
            raise ValueError(f"Symlink is not allowed in an artifact/source path: {current}")
    result = candidate.resolve()
    if not result.is_relative_to(workspace):
        raise ValueError(f"Path escapes the workspace: {candidate}")
    return result


def relative(workspace: Path, path: Path) -> str:
    return confined(workspace, path).relative_to(workspace.resolve()).as_posix()


def atomic_json(path: Path, value: object) -> None:
    """Publish without overwriting, including when another process wins the filename race."""
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = path.with_name(f".{path.name}.{uuid.uuid4().hex}.tmp")
    try:
        with temporary.open("xb") as stream:
            stream.write(json.dumps(value, ensure_ascii=False, indent=2, allow_nan=False).encode("utf-8") + b"\n")
            stream.flush()
            os.fsync(stream.fileno())
        os.link(temporary, path)  # Atomic no-clobber publication on the same filesystem.
    finally:
        temporary.unlink(missing_ok=True)


def local_environment(workspace: Path, state: Path) -> dict[str, str]:
    state = confined(workspace, state)
    state.mkdir(parents=True, exist_ok=True)
    env = {key: value for key, value in os.environ.items()
           if not key.startswith(("GIT_", "MVNW_")) and key not in {
               "JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS", "_JAVA_OPTIONS", "MAVEN_OPTS",
               "MAVEN_ARGS", "MAVEN_CONFIG", "MAVEN_PROJECTBASEDIR", "M2_HOME", "MAVEN_HOME",
               "MAVEN_EXT_CLASS_PATH", "MAVEN_DEBUG_OPTS", "BASH_ENV", "ENV", "JAVACMD", "JAVACCMD",
               "SSLKEYLOGFILE", "QLOGDIR"}}
    cache = confined(workspace, ".build-cache")
    downloader = confined(workspace, state / "downloader")
    curl_config = confined(workspace, downloader / "xdg-config")
    for directory in (cache / "tmp", cache / "maven-user-home", cache / "maven-repository",
                      cache / "xdg-cache", cache / "python-bytecode", state / "empty-git-template",
                      downloader, curl_config):
        directory = confined(workspace, directory)
        directory.mkdir(parents=True, exist_ok=True)
    # The cold wrapper prefers wget, then curl. Keep user configuration/HSTS state
    # out of the real HOME, and do not allow a reused operation to acquire custom
    # logging, cookies, netrc credentials or additional configuration directives.
    downloader_configs = {downloader / "wgetrc": b"hsts = off\nnetrc = off\n",
                          downloader / ".curlrc": b"", curl_config / "curlrc": b""}
    for path, expected in downloader_configs.items():
        path = confined(workspace, path)
        try:
            with path.open("xb") as stream:
                stream.write(expected)
        except FileExistsError:
            pass
        if not path.is_file() or path.read_bytes() != expected:
            raise ValueError(f"Isolated downloader configuration must retain exact content: {path}")
    git_config = confined(workspace, state / "empty.gitconfig")
    if not git_config.exists():
        git_config.touch(exist_ok=False)
    if git_config.stat().st_size:
        raise ValueError("Isolated Git configuration must remain empty")
    # JAVA_TOOL_OPTIONS supports quoted property values, including workspace paths with spaces.
    jvm_args = " ".join(f'-D{key}="{value}"' for key, value in (
        ("java.io.tmpdir", cache / "tmp"), ("user.home", cache / "maven-user-home")))
    if '"' in str(workspace) or "\n" in str(workspace):
        raise ValueError("Workspace path cannot contain quotes or newlines")
    env.update(TMPDIR=str(cache / "tmp"), TMP=str(cache / "tmp"), TEMP=str(cache / "tmp"),
               MAVEN_USER_HOME=str(cache / "maven-user-home"), JAVA_TOOL_OPTIONS=jvm_args,
               GIT_CONFIG_GLOBAL=str(git_config), GIT_CONFIG_NOSYSTEM="1",
               GIT_TERMINAL_PROMPT="0", GIT_OPTIONAL_LOCKS="0", PYTHONDONTWRITEBYTECODE="1",
               PYTHONPYCACHEPREFIX=str(cache / "python-bytecode"),
               WGETRC=str(downloader / "wgetrc"), CURL_HOME=str(downloader), XDG_CONFIG_HOME=str(curl_config),
               MAVEN_SKIP_RC="1", XDG_CACHE_HOME=str(cache / "xdg-cache"))
    return env


def git(source: Path, env: dict[str, str], *arguments: str) -> str:
    return subprocess.check_output(["git", "-C", str(source), *arguments], env=env,
                                   text=True, encoding="utf-8", stderr=subprocess.STDOUT).strip()


def tracked_files(source: Path, env: dict[str, str]) -> list[str]:
    data = subprocess.check_output(["git", "-C", str(source), "ls-files", "-z"], env=env)
    return sorted(name.decode("utf-8") for name in data.split(b"\0") if name)


def tree_files(workspace: Path, directory: Path) -> set[Path]:
    if not directory.exists():
        return set()
    directory = confined(workspace, directory)
    found: set[Path] = set()
    for current, directories, filenames in os.walk(directory, followlinks=False):
        directories[:] = sorted(name for name in directories if name not in TRANSIENT_PARTS)
        for name in directories:
            confined(workspace, Path(current) / name)
        for name in filenames:
            if name in {".DS_Store"} or name.endswith((".pyc", ".pyo")):
                continue
            path = confined(workspace, Path(current) / name)
            if not path.is_file():
                raise ValueError(f"Source is not a regular file: {path}")
            found.add(path)
    return found


def reactor_poms(workspace: Path, source: Path) -> set[Path]:
    pending = [source / "pom.xml"]
    found: set[Path] = set()
    while pending:
        pom = confined(workspace, pending.pop())
        if not pom.is_relative_to(source):
            raise ValueError(f"Reactor module escapes source checkout: {pom}")
        if pom in found:
            continue
        if not pom.is_file():
            raise ValueError(f"Reactor POM is missing: {pom}")
        found.add(pom)
        root = ET.parse(pom).getroot()
        for module in root.findall("./{*}modules/{*}module") + root.findall("./{*}profiles/{*}profile/{*}modules/{*}module"):
            name = (module.text or "").strip()
            if not name or "${" in name or Path(name).is_absolute():
                raise ValueError(f"Unresolved/invalid reactor module in {pom}: {name}")
            # Reactor layouts may legitimately use ../ to another in-repository module.
            module_directory = pom.parent
            for part in Path(name).parts:
                module_directory = module_directory.parent if part == ".." else module_directory / part
                module_directory = confined(workspace, module_directory)
            pending.append(module_directory / "pom.xml")
    return found


def select_source_files(workspace: Path, source: Path, env: dict[str, str]) -> list[Path]:
    source = confined(workspace, source)
    files = reactor_poms(workspace, source)
    files.update(source / name for name in ROOT_FILES if (source / name).is_file())
    for name in SOURCE_TREES:
        files.update(tree_files(workspace, source / name))
    # Native core tests depend on official examples. Avoid the many GB of untracked research scenarios.
    for name in tracked_files(source, env):
        if name.startswith("examples/scenarios/"):
            files.add(confined(workspace, source / name))
    for required in (source / "mvnw", source / ".mvn/wrapper/maven-wrapper.properties",
                     source / "matsim/pom.xml"):
        if required not in files:
            raise ValueError(f"Required source/build input is missing: {required}")
    return sorted(confined(workspace, path) for path in files)


def file_hashes(source: Path, files: list[Path]) -> dict[str, str]:
    if any(not path.is_file() for path in files):
        raise ValueError("Every source entry must be an existing regular file")
    return {path.relative_to(source).as_posix(): digest(path) for path in sorted(files)}


def create_snapshot(workspace: Path, source: Path, destination: Path) -> dict:
    workspace = workspace.resolve(strict=True)
    source, destination = confined(workspace, source), confined(workspace, destination)
    if source == workspace or destination == workspace or destination.is_relative_to(source) or source.is_relative_to(destination):
        raise ValueError("Snapshot and source must be distinct, non-overlapping workspace subdirectories")
    receipt = destination.with_name(destination.name + ".snapshot.json")
    if destination.exists() or receipt.exists():
        raise FileExistsError(f"Snapshot or receipt already exists: {destination}")
    state = confined(workspace, Path(".build-cache/snapshot-operations") / uuid.uuid4().hex)
    env = local_environment(workspace, state)
    if Path(git(source, env, "rev-parse", "--show-toplevel")).resolve() != source:
        raise ValueError("Source must be the Git checkout root")
    origin_commit = git(source, env, "rev-parse", "HEAD")
    before = file_hashes(source, select_source_files(workspace, source, env))
    destination.mkdir(parents=True, exist_ok=False)
    for name, expected in before.items():
        original, copied = confined(workspace, source / name), confined(workspace, destination / name)
        copied.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(original, copied, follow_symlinks=False)
        if digest(original) != expected or digest(copied) != expected:
            raise ValueError(f"Source changed during snapshot copy: {name}")
    after = file_hashes(source, select_source_files(workspace, source, env))
    if after != before or file_hashes(destination, [destination / name for name in before]) != before:
        raise ValueError("Source file set/content changed during snapshot; incomplete destination retained for inspection")
    template = state / "empty-git-template"
    git(destination, env, "init", "--initial-branch=ismt-source-snapshot", f"--template={template}")
    git(destination, env, "-c", "core.autocrlf=false", "add", "--force", "--all")
    source_hash = source_sha256(before)
    message = ("ISMT isolated source snapshot\n\n"
               f"Origin checkout commit: {origin_commit}\nSource SHA-256: {source_hash}\n"
               "Snapshot identity only; not a human research author and not an upstream MATSim commit.")
    git(destination, env, "-c", "user.name=ISMT Source Snapshot", "-c", "user.email=ismt-snapshot@invalid",
        "-c", "commit.gpgSign=false", "-c", f"core.hooksPath={template}", "commit", "-m", message)
    if set(tracked_files(destination, env)) != set(before) or git(destination, env, "status", "--porcelain", "--untracked-files=all"):
        raise ValueError("Snapshot Git state is not a clean, exact source-file inventory")
    result = {"schemaVersion": "ismt-source-snapshot-v1", "sourceRoot": relative(workspace, destination),
              "sourceCommit": git(destination, env, "rev-parse", "HEAD"), "sourceSha256": source_hash,
              "sourceFiles": before, "sourceClean": True, "originRoot": relative(workspace, source),
              "originCommit": origin_commit, "createdAt": datetime.now(timezone.utc).isoformat(),
              "scope": "Core production/resources/tests and native input fixtures; tracked official examples; all reactor POMs; wrapper; scripts; ISMT profiles; docs; licenses. No legacy run outputs."}
    atomic_json(receipt, result)
    return result


def checked_source(workspace: Path, source: Path, env: dict[str, str]) -> tuple[str, dict[str, str]]:
    if Path(git(source, env, "rev-parse", "--show-toplevel")).resolve() != source:
        raise ValueError("Build source must be the Git checkout root")
    if git(source, env, "status", "--porcelain", "--untracked-files=all"):
        raise ValueError("Formal build requires a clean isolated source checkout")
    names = tracked_files(source, env)
    if not names or any(set(Path(name).parts) & TRANSIENT_PARTS for name in names):
        raise ValueError("Tracked source inventory is empty or contains build/cache/Git artifacts")
    files = file_hashes(source, [confined(workspace, source / name) for name in names])
    return git(source, env, "rev-parse", "HEAD"), files


def run_logged(command: list[str], cwd: Path, env: dict[str, str], log: Path) -> str:
    with log.open("x", encoding="utf-8") as stream:
        process = subprocess.run(command, cwd=cwd, env=env, stdout=stream, stderr=subprocess.STDOUT, check=False)
    output = log.read_text(encoding="utf-8", errors="replace")
    if process.returncode:
        raise RuntimeError(f"Command failed with exit {process.returncode}; see {log}")
    return output


def maven_identity(source: Path) -> tuple[str, str, str]:
    root = ET.parse(source / "pom.xml").getroot()
    version = root.findtext("./{*}version")
    module = ET.parse(source / "matsim/pom.xml").getroot()
    artifact = module.findtext("./{*}artifactId")
    module_version = module.findtext("./{*}version") or module.findtext("./{*}parent/{*}version")
    final_name = module.findtext("./{*}build/{*}finalName") or f"{artifact}-{module_version}"
    if not version or version != module_version or not artifact or "${" in final_name or Path(final_name).name != final_name:
        raise ValueError("Unsupported/unresolved MATSim artifact coordinates")
    properties = {}
    for line in (source / ".mvn/wrapper/maven-wrapper.properties").read_text().splitlines():
        if "=" in line and not line.lstrip().startswith("#"):
            key, value = line.split("=", 1)
            properties[key.strip()] = value.strip()
    if not properties.get("wrapperVersion") or not re.fullmatch(r"[0-9a-fA-F]{64}", properties.get("distributionSha256Sum", "")):
        raise ValueError("Wrapper version and pinned distribution SHA-256 are required")
    return version, final_name, properties["wrapperVersion"]


def copy_classpath(workspace: Path, application: Path, dependency_file: Path) -> list[dict[str, str]]:
    application = confined(workspace, application)
    if not zipfile.is_zipfile(application):
        raise ValueError("Application artifact is not a readable JAR")
    runtime = confined(workspace, application.parent / "ismt-runtime-libs")
    runtime.mkdir(exist_ok=False)
    entries = [{"path": relative(workspace, application), "sha256": digest(application)}]
    seen: set[Path] = {application}
    text = dependency_file.read_text(encoding="utf-8").strip()
    for name in text.split(os.pathsep) if text else []:
        dependency = confined(workspace, Path(name))
        if not dependency.is_file() or dependency.suffix.lower() != ".jar" or not zipfile.is_zipfile(dependency):
            raise ValueError(f"Runtime dependency must be a local JAR: {dependency}")
        if dependency in seen:
            raise ValueError(f"Duplicate runtime classpath entry: {dependency}")
        seen.add(dependency)
        expected = digest(dependency)
        copied = runtime / f"{expected}-{dependency.name}"
        if copied.exists():
            raise ValueError(f"Duplicate runtime dependency content/name: {dependency}")
        shutil.copy2(dependency, copied)
        if digest(dependency) != expected or digest(copied) != expected:
            raise ValueError(f"Dependency changed while freezing classpath: {dependency}")
        entries.append({"path": relative(workspace, copied), "sha256": expected})
    return entries


def build(workspace: Path, source: Path, manifest: Path, *, offline: bool = True,
          executor: Callable = run_logged) -> dict:
    workspace = workspace.resolve(strict=True)
    source, manifest = confined(workspace, source), confined(workspace, manifest)
    if source == workspace or manifest.is_relative_to(source):
        raise ValueError("Keep the manifest outside the source checkout, but inside the workspace")
    if manifest.exists():
        raise FileExistsError(f"Build manifest already exists: {manifest}")
    state = confined(workspace, Path(".build-cache/build-operations") / uuid.uuid4().hex)
    env = local_environment(workspace, state)
    commit, files = checked_source(workspace, source, env)
    version, final_name, wrapper_version = maven_identity(source)
    application = confined(workspace, source / "matsim/target" / f"{final_name}.jar")
    if application.exists():
        raise FileExistsError(f"Application artifact already exists; do not rebuild/overwrite a frozen runtime: {application}")
    # A deterministic exclusive lock prevents two producers from building the same checkout.
    lock_dir = confined(workspace, ".build-cache/build-locks")
    lock_dir.mkdir(parents=True, exist_ok=True)
    lock = lock_dir / (hashlib.sha256(str(source).encode()).hexdigest() + ".lock")
    with lock.open("x", encoding="utf-8") as stream:
        json.dump({"pid": os.getpid(), "sourceRoot": relative(workspace, source), "state": relative(workspace, state)}, stream)
    try:
        settings = state / "empty-settings.xml"
        settings.write_text('<settings xmlns="http://maven.apache.org/SETTINGS/1.2.0"/>\n', encoding="utf-8")
        wrapper = ["sh", str(source / "mvnw"), "--batch-mode", "--no-transfer-progress", "-Dstyle.color=never",
                   "--settings", str(settings), "--global-settings", str(settings),
                   f"-Dmaven.repo.local={confined(workspace, '.build-cache/maven-repository')}"]
        if offline:
            wrapper.append("--offline")
        java = str(Path(env["JAVA_HOME"]) / "bin/java") if env.get("JAVA_HOME") else shutil.which("java")
        if not java:
            raise ValueError("Java is not available")
        java_output = executor([java, "-XshowSettings:properties", "-version"], source, env, state / "java-version.log")
        java_match = re.search(r"^\s*java\.runtime\.version\s*=\s*(\S+)\s*$", java_output, re.MULTILINE)
        maven_output = executor(wrapper + ["--version"], source, env, state / "maven-version.log")
        maven_match = re.search(r"Apache Maven\s+(\S+)", re.sub(r"\x1b\[[0-9;]*m", "", maven_output))
        if not java_match or not maven_match:
            raise ValueError("Cannot identify exact JVM/Maven versions; inspect version logs")
        classpath_file = state / "runtime-classpath.txt"
        command = wrapper + ["-pl", "matsim", "-DskipTests", "clean", "package", DEPENDENCY_PLUGIN,
                             "-DincludeScope=runtime", f"-Dmdep.outputFile={classpath_file}"]
        executor(command, source, env, state / "maven-build.log")
        classpath = copy_classpath(workspace, application, classpath_file)
        after_commit, after_files = checked_source(workspace, source, env)
        if after_commit != commit or after_files != files:
            raise ValueError("Source/commit changed during the build; no formal manifest will be published")
        for entry in classpath:
            if digest(confined(workspace, entry["path"])) != entry["sha256"]:
                raise ValueError("Runtime artifact changed before manifest publication")
        result = {"schemaVersion": SCHEMA_VERSION, "sourceRoot": relative(workspace, source),
                  "sourceCommit": commit, "sourceSha256": source_sha256(files), "sourceFiles": files,
                  "applicationJar": classpath[0], "runtimeClasspath": classpath,
                  "dependencySha256": dependency_sha256(classpath), "matsimVersion": version,
                  "javaRuntimeVersion": java_match.group(1), "mavenVersion": maven_match.group(1),
                  "wrapperVersion": wrapper_version, "os": platform.platform(), "buildCommand": command,
                  "createdAt": datetime.now(timezone.utc).isoformat(), "sourceClean": True}
        atomic_json(manifest, result)
        return result
    finally:
        lock.unlink(missing_ok=True)


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="operation", required=True)
    for name in ("snapshot", "build"):
        sub = commands.add_parser(name)
        sub.add_argument("--workspace", type=Path, required=True)
        sub.add_argument("--source", type=Path, required=True, help="Absolute or workspace-relative checkout root")
        if name == "snapshot":
            sub.add_argument("--destination", type=Path, required=True)
        else:
            sub.add_argument("--manifest", type=Path, required=True)
            sub.add_argument("--allow-downloads", action="store_true", help="Allow pinned wrapper/Maven dependency downloads; default Maven mode is offline")
    args = parser.parse_args()
    result = (create_snapshot(args.workspace, args.source, args.destination) if args.operation == "snapshot"
              else build(args.workspace, args.source, args.manifest, offline=not args.allow_downloads))
    print(json.dumps({key: result[key] for key in ("schemaVersion", "sourceRoot", "sourceCommit", "sourceSha256")}, indent=2))


if __name__ == "__main__":
    main()
