#!/usr/bin/env python3

import argparse
import ctypes
import hashlib
import json
import os
from pathlib import Path
import platform
import re
import shlex
import shutil
import subprocess
import sys
import zipfile


REPOSITORY_ROOT = Path(__file__).resolve().parents[2]
MANIFEST_PATH = REPOSITORY_ROOT / "runtime" / "cef" / "extension-adapter-patch.json"
CEF_REPOSITORY = "https://github.com/chromiumembedded/cef.git"
DEPOT_TOOLS_REPOSITORY = "https://chromium.googlesource.com/chromium/tools/depot_tools.git"
SUPPORTED_TARGETS = ("macos-arm64", "windows-x64", "linux-x64")
TARGET_CONFIGURATION = {
    "macos-arm64": {
        "cpu": "arm64",
        "cef_platform": "macosarm64",
        "arch_flag": "--arm64-build",
        "build_targets": ("cefclient",),
        "library": Path("Release/Chromium Embedded Framework.framework/Chromium Embedded Framework"),
    },
    "windows-x64": {
        "cpu": "x64",
        "cef_platform": "windows64",
        "arch_flag": "--x64-build",
        "build_targets": ("cefclient", "bootstrap", "bootstrapc"),
        "library": Path("Release/libcef.dll"),
    },
    "linux-x64": {
        "cpu": "x64",
        "cef_platform": "linux64",
        "arch_flag": "--x64-build",
        "build_targets": ("cefclient", "chrome_sandbox"),
        "library": Path("Release/libcef.so"),
    },
}


class BuildFailure(RuntimeError):
    pass


def parse_arguments():
    parser = argparse.ArgumentParser(description="Build a version-pinned KWebShell CEF runtime.")
    parser.add_argument("--work-dir", type=Path, required=True)
    parser.add_argument("--output-dir", type=Path, required=True)
    parser.add_argument("--target", choices=SUPPORTED_TARGETS, required=True)
    parser.add_argument("--jobs", type=int, default=max(1, (os.cpu_count() or 2) // 2))
    parser.add_argument("--install-linux-dependencies", action="store_true")
    return parser.parse_args()


def detect_host_target(system=None, machine=None):
    system = (system or platform.system()).lower()
    machine = (machine or platform.machine()).lower()
    operating_system = {"darwin": "macos", "windows": "windows", "linux": "linux"}.get(system)
    architecture = {
        "arm64": "arm64",
        "aarch64": "arm64",
        "x86_64": "x64",
        "amd64": "x64",
    }.get(machine)
    if operating_system is None or architecture is None:
        raise BuildFailure(f"Unsupported CEF build host: {system}/{machine}.")
    return f"{operating_system}-{architecture}"


def validate_build_options(target, jobs, install_linux_dependencies):
    if jobs <= 0:
        raise BuildFailure("--jobs must be positive.")
    if install_linux_dependencies and target != "linux-x64":
        raise BuildFailure("--install-linux-dependencies is valid only for linux-x64.")


def load_manifest(path=MANIFEST_PATH):
    with path.open("r", encoding="utf-8") as stream:
        manifest = json.load(stream)
    required = {
        "schemaVersion",
        "cefVersion",
        "cefCommit",
        "chromiumVersion",
        "chromiumCommit",
        "depotToolsCommit",
        "adapterAbiVersion",
        "adapterAbiFingerprint",
        "sisoVersion",
        "gnDefines",
        "exports",
        "patches",
        "customRuntimeArtifacts",
    }
    if set(manifest) != required:
        raise BuildFailure("The source patch manifest fields differ from the build contract.")
    if manifest["schemaVersion"] != 1 or len(manifest["patches"]) != 1:
        raise BuildFailure("The source patch manifest schema is unsupported.")
    return manifest


def ensure_empty_external_directory(path, name):
    absolute = path.expanduser().resolve()
    if absolute == REPOSITORY_ROOT or REPOSITORY_ROOT in absolute.parents:
        raise BuildFailure(f"{name} must be outside the repository: {absolute}")
    if " " in str(absolute):
        raise BuildFailure(f"{name} cannot contain spaces because CEF automation rejects them: {absolute}")
    if absolute.exists() and any(absolute.iterdir()):
        raise BuildFailure(f"{name} must be absent or empty: {absolute}")
    absolute.mkdir(parents=True, exist_ok=True)
    return absolute


def command_for_host(command, host_os_name=None):
    host_os_name = host_os_name or os.name
    if host_os_name == "nt" and Path(command[0]).suffix.lower() in (".bat", ".cmd"):
        return ["cmd.exe", "/d", "/s", "/c", subprocess.list2cmdline([str(value) for value in command])]
    return [str(value) for value in command]


def run(command, cwd=None, environment=None, capture=False):
    resolved = command_for_host(command)
    print(f"+ {shlex.join(resolved)}", flush=True)
    result = subprocess.run(
        resolved,
        cwd=cwd,
        env=environment,
        check=False,
        text=True,
        stdout=subprocess.PIPE if capture else None,
        stderr=subprocess.STDOUT if capture else None,
    )
    if result.returncode != 0:
        output = f"\n{result.stdout}" if capture and result.stdout else ""
        raise BuildFailure(f"Command exited with {result.returncode}: {shlex.join(resolved)}{output}")
    return result.stdout.strip() if capture else ""


def verify_revision(repository, expected):
    actual = run(["git", "-C", repository, "rev-parse", "HEAD"], capture=True)
    if actual != expected:
        raise BuildFailure(f"Revision mismatch for {repository}: expected {expected}, found {actual}.")


def write_pinned_gclient(chromium_root, manifest):
    chromium_root.mkdir(parents=True, exist_ok=True)
    chromium_url = f"https://chromium.googlesource.com/chromium/src.git@{manifest['chromiumVersion']}"
    specification = (
        "solutions = [{'managed': False, 'name': 'src', "
        f"'url': '{chromium_url}', "
        "'custom_vars': {'checkout_pgo_profiles': False, 'source_tarball': False, "
        f"'siso_version': '{manifest['sisoVersion']}'"
        "}, 'custom_deps': {}, 'deps_file': 'DEPS', 'safesync_url': ''}]\n"
    )
    (chromium_root / ".gclient").write_text(specification, encoding="utf-8")


def gradle_wrapper():
    return REPOSITORY_ROOT / ("gradlew.bat" if os.name == "nt" else "gradlew")


def bootstrap_sources(work_dir, target, manifest):
    cef_mirror = work_dir / "cef"
    run(["git", "clone", "--filter=blob:none", "--no-checkout", CEF_REPOSITORY, cef_mirror])
    run(["git", "-C", cef_mirror, "checkout", "--detach", manifest["cefCommit"]])
    verify_revision(cef_mirror, manifest["cefCommit"])

    chromium_root = work_dir / "chromium"
    write_pinned_gclient(chromium_root, manifest)
    depot_tools = work_dir / "depot_tools"
    run(["git", "clone", "--filter=blob:none", "--no-checkout", DEPOT_TOOLS_REPOSITORY, depot_tools])
    run(["git", "-C", depot_tools, "checkout", "--detach", manifest["depotToolsCommit"]])
    verify_revision(depot_tools, manifest["depotToolsCommit"])
    automate = cef_mirror / "tools" / "automate" / "automate-git.py"
    configuration = TARGET_CONFIGURATION[target]
    bootstrap_environment = os.environ.copy()
    bootstrap_environment["DEPOT_TOOLS_UPDATE"] = "0"
    run(
        [
            sys.executable,
            automate,
            f"--download-dir={work_dir}",
            f"--depot-tools-dir={work_dir / 'depot_tools'}",
            f"--checkout={manifest['cefCommit']}",
            f"--chromium-checkout=refs/tags/{manifest['chromiumVersion']}",
            "--no-chromium-history",
            "--no-build",
            "--no-distrib",
            "--no-debug-build",
            "--no-depot-tools-update",
            configuration["arch_flag"],
        ],
        cwd=cef_mirror,
        environment=bootstrap_environment,
    )

    chromium_source = chromium_root / "src"
    cef_source = chromium_source / "cef"
    verify_revision(cef_source, manifest["cefCommit"])
    verify_revision(chromium_source, manifest["chromiumCommit"])
    verify_revision(depot_tools, manifest["depotToolsCommit"])
    run(
        [
            gradle_wrapper(),
            "--no-daemon",
            ":kweb-runtime-pack:verifyCefSourcePatchTree",
            f"-PcefSourceRoot={cef_source}",
        ],
        cwd=REPOSITORY_ROOT,
    )
    patch = REPOSITORY_ROOT / "runtime" / "cef" / manifest["patches"][0]["file"]
    run(["git", "-C", cef_source, "apply", "--index", "--whitespace=error", patch])
    changed = run(["git", "-C", cef_source, "diff", "--cached", "--name-only"], capture=True).splitlines()
    expected = sorted(
        image["path"]
        for group in ("modifiedPreimages", "createdPostimages")
        for image in manifest["patches"][0][group]
    )
    if sorted(changed) != expected:
        raise BuildFailure(f"Applied patch changed unexpected files: {changed}")
    run(["git", "-C", cef_source, "diff", "--cached", "--check"])
    return chromium_source, cef_source, depot_tools


def build_environment(depot_tools, target, manifest):
    environment = os.environ.copy()
    environment.pop("GOROOT", None)
    environment.pop("GOBIN", None)
    environment["DEPOT_TOOLS_UPDATE"] = "0"
    environment["PATH"] = str(depot_tools) + os.pathsep + environment.get("PATH", "")
    defines = list(manifest["gnDefines"])
    if target == "linux-x64":
        defines.append("use_sysroot=true")
        environment["CEF_INSTALL_SYSROOT"] = "x64"
    environment["GN_DEFINES"] = " ".join(defines)
    environment["GN_OUT_CONFIGS"] = f"Release_GN_{TARGET_CONFIGURATION[target]['cpu']}"
    environment["CEF_ARCHIVE_FORMAT"] = "zip"
    if target == "windows-x64":
        environment["DEPOT_TOOLS_WIN_TOOLCHAIN"] = "0"
    return environment


def build_distribution(
    work_dir,
    output_dir,
    target,
    jobs,
    install_linux_dependencies,
    manifest,
    chromium_source,
    cef_source,
    depot_tools,
):
    if target == "linux-x64" and install_linux_dependencies:
        run(
            [
                "sudo",
                chromium_source / "build" / "install-build-deps.sh",
                "--no-prompt",
                "--no-arm",
                "--no-chromeos-fonts",
            ]
        )

    environment = build_environment(depot_tools, target, manifest)
    run([sys.executable, cef_source / "tools" / "gclient_hook.py"], cwd=cef_source, environment=environment)
    configuration = TARGET_CONFIGURATION[target]
    out_name = f"Release_GN_{configuration['cpu']}"
    autoninja = depot_tools / ("autoninja.bat" if os.name == "nt" else "autoninja")
    run(
        [autoninja, "-C", chromium_source / "out" / out_name, "-j", str(jobs), *configuration["build_targets"]],
        cwd=chromium_source,
        environment=environment,
    )

    distribution_output = work_dir / "distribution"
    distribution_output.mkdir()
    run(
        [
            sys.executable,
            cef_source / "tools" / "make_distrib.py",
            f"--output-dir={distribution_output}",
            "--ninja-build",
            "--minimal",
            configuration["arch_flag"],
            "--no-symbols",
            "--no-docs",
        ],
        cwd=cef_source,
        environment=environment,
    )

    distribution_name = f"cef_binary_{manifest['cefVersion']}_{configuration['cef_platform']}_minimal"
    distribution_root = distribution_output / distribution_name
    library = distribution_root / configuration["library"]
    verify_distribution(chromium_source, distribution_root, library, target, manifest)
    upstream_archive = distribution_output / f"{distribution_name}.zip"
    if not upstream_archive.is_file():
        raise BuildFailure(f"CEF did not produce the expected ZIP archive: {upstream_archive}")
    final_name = (
        f"kwebshell-cef_{manifest['cefVersion']}_{target}_abi{manifest['adapterAbiVersion']}.zip"
    )
    final_archive = output_dir / final_name
    shutil.copy2(upstream_archive, final_archive)
    metadata = {
        "schemaVersion": 1,
        "target": target,
        "fileName": final_name,
        "size": final_archive.stat().st_size,
        "sha256": sha256_file(final_archive),
        "librarySha256": sha256_file(library),
        "adapterAbiFingerprint": manifest["adapterAbiFingerprint"],
        "cefCommit": manifest["cefCommit"],
        "chromiumCommit": manifest["chromiumCommit"],
        "depotToolsCommit": manifest["depotToolsCommit"],
        "sisoVersion": manifest["sisoVersion"],
        "sourcePatchSha256": manifest["patches"][0]["sha256"],
    }
    metadata_path = output_dir / f"{final_name}.metadata.json"
    metadata_path.write_text(json.dumps(metadata, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(metadata, indent=2), flush=True)


def verify_distribution(chromium_source, distribution_root, library, target, manifest):
    if not library.is_file():
        raise BuildFailure(f"The minimal distribution has no libcef binary: {library}")
    header = distribution_root / "include" / "internal" / "cef_kweb_extension_abi.h"
    expected_header_digest = next(
        image["sha256"]
        for image in manifest["patches"][0]["createdPostimages"]
        if image["path"] == "include/internal/cef_kweb_extension_abi.h"
    )
    if not header.is_file() or sha256_file(header) != expected_header_digest:
        raise BuildFailure("The minimal distribution contains the wrong adapter ABI header.")

    llvm_bin = chromium_source / "third_party" / "llvm-build" / "Release+Asserts" / "bin"
    if target == "windows-x64":
        symbols = run([llvm_bin / "llvm-readobj.exe", "--coff-exports", library], capture=True)
    else:
        llvm_nm = llvm_bin / "llvm-nm"
        flags = ["--defined-only", "--extern-only"]
        if target == "linux-x64":
            flags.append("--dynamic")
        symbols = run([llvm_nm, *flags, library], capture=True)
    exported = parse_adapter_exports(symbols, target)
    expected_exports = set(manifest["exports"])
    if exported != expected_exports:
        raise BuildFailure(f"Custom libcef exports {sorted(exported)}, expected {sorted(expected_exports)}.")

    dll_scope = None
    if os.name == "nt":
        dll_scope = os.add_dll_directory(str(library.parent))
    try:
        cef = ctypes.CDLL(str(library))
        fingerprint = cef.cef_kweb_extension_abi_fingerprint
        fingerprint.restype = ctypes.c_char_p
        actual = fingerprint()
        actual_fingerprint = actual.decode("ascii") if actual else ""
    finally:
        if dll_scope is not None:
            dll_scope.close()
    if actual_fingerprint != manifest["adapterAbiFingerprint"]:
        raise BuildFailure(
            f"Custom libcef fingerprint is {actual_fingerprint}, expected {manifest['adapterAbiFingerprint']}."
        )


def parse_adapter_exports(output, target):
    if target == "windows-x64":
        candidates = re.findall(r"^\s*Name:\s*(\S+)\s*$", output, flags=re.MULTILINE)
    else:
        candidates = [line.split()[-1] for line in output.splitlines() if line.split()]
    exported = set()
    for symbol in candidates:
        if target == "macos-arm64" and symbol.startswith("_cef_kweb_"):
            symbol = symbol[1:]
        if symbol.startswith("cef_kweb_"):
            exported.add(symbol)
    return exported


def sha256_file(path):
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def main():
    arguments = parse_arguments()
    requested = arguments.target
    validate_build_options(requested, arguments.jobs, arguments.install_linux_dependencies)
    detected = detect_host_target()
    if requested != detected:
        raise BuildFailure(f"Target {requested} must be built on an exact {requested} host, found {detected}.")
    work_dir = ensure_empty_external_directory(arguments.work_dir, "--work-dir")
    output_dir = ensure_empty_external_directory(arguments.output_dir, "--output-dir")
    manifest = load_manifest()
    run(
        [gradle_wrapper(), "--no-daemon", ":kweb-runtime-pack:verifyCefSourcePatchManifest"],
        cwd=REPOSITORY_ROOT,
    )
    chromium_source, cef_source, depot_tools = bootstrap_sources(work_dir, requested, manifest)
    build_distribution(
        work_dir=work_dir,
        output_dir=output_dir,
        target=requested,
        jobs=arguments.jobs,
        install_linux_dependencies=arguments.install_linux_dependencies,
        manifest=manifest,
        chromium_source=chromium_source,
        cef_source=cef_source,
        depot_tools=depot_tools,
    )


if __name__ == "__main__":
    try:
        main()
    except (BuildFailure, OSError, json.JSONDecodeError, zipfile.BadZipFile) as error:
        print(f"custom-cef-build-failed: {error}", file=sys.stderr)
        sys.exit(2)
