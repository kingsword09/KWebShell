"""Retain exact MSIX proof bytes after the real Windows SDK job succeeds."""

import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import tempfile
import zipfile


ASSETS = ("Square44x44Logo.png", "Square150x150Logo.png", "Square310x310Logo.png", "Wide310x150Logo.png", "StoreLogo.png")
REQUIRED_ENTRIES = (
    "[Content_Types].xml", "AppxManifest.xml", "AppxBlockMap.xml", "AppxSignature.p7x",
    "application/manifest.json", "application/package.json", "application/packaged-state.json",
    "application/registration.json", "application/capabilities.json", "application/sbom.json",
    "signatures/platform.json", "signatures/package.json", "signatures/package.ed25519",
    "runtime/legal/java.base/LICENSE", "runtime/legal/java.base/ASSEMBLY_EXCEPTION",
    *("Assets/" + name for name in ASSETS),
)
PASS_FIELDS = (
    "packageSignatureVerification", "sdkPackageValidation", "signatureVerification",
    "tamperedPackageRejected", "installed", "normalShutdown", "uninstalled",
    "applicationDataCleanup", "developerInstallPolicyRestored",
)
MAX_PROOF_BYTES = 96 * 1024 * 1024


def digest_bytes(data):
    return hashlib.sha256(data).hexdigest()


def digest_file(path):
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def locate_inputs(downloaded):
    root = downloaded.resolve()

    def find(pattern):
        matches = [p for p in root.rglob(pattern) if any(
            part.startswith("application-package-windows-x64-") for part in p.relative_to(root).parts
        )]
        if len(matches) != 1:
            raise ValueError(f"Expected exactly one Windows MSIX {pattern} artifact; found {len(matches)}")
        path = matches[0]
        if path.is_symlink() or not path.is_file() or not path.resolve().is_relative_to(root):
            raise ValueError("Windows MSIX evidence must be a regular file inside the downloaded artifacts")
        return path

    return find("*.msix"), find("application-package-report.json")


def retain(package, report_path, output, source_revision):
    if not re.fullmatch(r"[0-9a-f]{40}", source_revision):
        raise ValueError("Windows MSIX evidence requires the actual hosted source revision")
    if report_path.stat().st_size > 1024 * 1024:
        raise ValueError("Windows MSIX report exceeds its size limit")
    report_bytes = report_path.read_bytes()
    report = json.loads(report_bytes)
    expected = {
        "schemaVersion": 1, "target": "windows-x64", "format": "WINDOWS_MSIX",
        "signingMode": "TEST", "status": "PASS", "sourceRevision": source_revision,
        "platformSignatureStatus": "VERIFIED",
    }
    if not isinstance(report, dict):
        raise ValueError("Windows MSIX report must be an object")
    for field, value in expected.items():
        if type(report.get(field)) is not type(value) or report[field] != value:
            raise ValueError(f"Windows MSIX report has an invalid {field}")
    for field in PASS_FIELDS:
        if report.get(field) != "PASS":
            raise ValueError(f"Windows MSIX verification did not pass: {field}")
    for field in ("launcherWindowObserved", "cefSubprocessObserved"):
        if report.get(field) is not True:
            raise ValueError(f"Windows MSIX verification did not observe {field}")
    if type(report.get("packageSignatureEntryCount")) is not int or report["packageSignatureEntryCount"] < 1:
        raise ValueError("Windows MSIX report lacks verified signed entries")
    if report.get("failure") is not None:
        raise ValueError("Windows MSIX report contains a failure")
    package_digest = digest_file(package)
    if report.get("msixSha256") != package_digest or report.get("packageSha256") != package_digest:
        raise ValueError("Windows MSIX bytes do not match the verified package digest")

    retained = {}
    total = len(report_bytes)
    with zipfile.ZipFile(package) as archive:
        entries = {}
        names = set()
        for entry in archive.infolist():
            name = entry.filename.replace("\\", "/")
            if name.startswith("/") or any(part in ("", ".", "..") for part in name.rstrip("/").split("/")):
                raise ValueError("Windows MSIX contains an unsafe entry path")
            if name.casefold() in names:
                raise ValueError("Windows MSIX contains ambiguous entry names")
            names.add(name.casefold())
            if not entry.is_dir():
                entries[name] = entry
        for name in REQUIRED_ENTRIES:
            entry = entries.get(name)
            if entry is None or entry.file_size < 1:
                raise ValueError(f"Windows MSIX is missing required proof entry {name}")
            total += entry.file_size
            if total > MAX_PROOF_BYTES:
                raise ValueError("Windows MSIX proof exceeds its repository size limit")
            retained[name] = archive.read(entry)

    hashes = {name: digest_bytes(data) for name, data in sorted(retained.items())}
    if report.get("manifestSha256") != hashes["application/manifest.json"]:
        raise ValueError("Windows MSIX application manifest differs from its verified report")
    assets = {name: hashes["Assets/" + name] for name in ASSETS}
    if report.get("assetSha256") != assets:
        raise ValueError("Windows MSIX asset bytes differ from their verified report")
    if report.get("temurinLicenseSha256") != hashes["runtime/legal/java.base/LICENSE"] or report.get(
        "temurinAssemblyExceptionSha256"
    ) != hashes["runtime/legal/java.base/ASSEMBLY_EXCEPTION"]:
        raise ValueError("Windows MSIX Temurin legal bytes differ from their verified report")

    index = {
        "schemaVersion": 1, "target": "windows-x64", "sourceRevision": source_revision,
        "msixFileName": package.name, "msixSha256": package_digest,
        "msixSizeBytes": package.stat().st_size, "entrySha256": hashes,
        "reportSha256": digest_bytes(report_bytes),
    }
    proof_entries = {"msix/" + name: data for name, data in retained.items()}
    proof_entries["verification/application-package-report.json"] = report_bytes
    proof_entries["index.json"] = (json.dumps(index, sort_keys=True, indent=2) + "\n").encode()
    output.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.NamedTemporaryFile(dir=output.parent, prefix=".windows-msix-proof-", delete=False) as temporary:
        temporary_path = Path(temporary.name)
    try:
        with zipfile.ZipFile(temporary_path, "w") as archive:
            for name, data in sorted(proof_entries.items()):
                info = zipfile.ZipInfo(name, (2000, 1, 1, 0, 0, 0))
                info.create_system = 3
                info.external_attr = 0o100644 << 16
                info.compress_type = zipfile.ZIP_DEFLATED
                archive.writestr(info, data)
        if temporary_path.stat().st_size > MAX_PROOF_BYTES:
            raise ValueError("Windows MSIX proof archive exceeds its repository size limit")
        os.replace(temporary_path, output)
    finally:
        temporary_path.unlink(missing_ok=True)
    return index


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--downloaded", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--source-revision", required=True)
    arguments = parser.parse_args()
    try:
        package, report = locate_inputs(arguments.downloaded)
        index = retain(package, report, arguments.output, arguments.source_revision)
    except (OSError, ValueError, zipfile.BadZipFile) as error:
        parser.exit(1, f"Windows MSIX evidence: {error}\n")
    print(f"Retained exact MSIX proof for {index['msixSha256']} ({index['msixSizeBytes']} bytes)")


if __name__ == "__main__":
    main()
