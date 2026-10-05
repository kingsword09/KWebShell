"""Collector test data only; these bytes make no native signing/install claim."""

import hashlib
import json
from pathlib import Path
import zipfile


REVISION = "a" * 40
ASSETS = ("Square44x44Logo.png", "Square150x150Logo.png", "Square310x310Logo.png", "Wide310x150Logo.png", "StoreLogo.png")
ENTRIES = (
    "[Content_Types].xml", "AppxManifest.xml", "AppxBlockMap.xml", "AppxSignature.p7x",
    "application/manifest.json", "application/package.json", "application/packaged-state.json",
    "application/registration.json", "application/capabilities.json", "application/sbom.json",
    "signatures/platform.json", "signatures/package.json", "signatures/package.ed25519",
    "runtime/legal/java.base/LICENSE", "runtime/legal/java.base/ASSEMBLY_EXCEPTION",
    *("Assets/" + name for name in ASSETS),
)


def write_report(path, report):
    path.write_bytes((json.dumps(report, indent=2).replace("\n", "\r\n") + "\r\n").encode())


def create_fixture(directory, omitted=()):
    directory = Path(directory)
    directory.mkdir(parents=True, exist_ok=True)
    package = directory / "KWebShell-test.msix"
    contents = {name: ("collector-fixture:" + name).encode() for name in ENTRIES if name not in omitted}
    with zipfile.ZipFile(package, "w") as archive:
        for name, data in contents.items():
            archive.writestr(name, data)
    digest = hashlib.sha256(package.read_bytes()).hexdigest()
    report = {
        "schemaVersion": 1, "target": "windows-x64", "format": "WINDOWS_MSIX",
        "signingMode": "TEST", "status": "PASS", "sourceRevision": REVISION,
        "platformSignatureStatus": "VERIFIED", "packageSignatureVerification": "PASS",
        "sdkPackageValidation": "PASS", "signatureVerification": "PASS",
        "tamperedPackageRejected": "PASS", "installed": "PASS", "normalShutdown": "PASS",
        "uninstalled": "PASS", "applicationDataCleanup": "PASS", "developerInstallPolicyRestored": "PASS",
        "launcherWindowObserved": True, "cefSubprocessObserved": True, "packageSignatureEntryCount": 20,
        "msixSha256": digest, "packageSha256": digest, "failure": None,
        "manifestSha256": hashlib.sha256(contents.get("application/manifest.json", b"")).hexdigest(),
        "assetSha256": {name: hashlib.sha256(contents.get("Assets/" + name, b"")).hexdigest() for name in ASSETS},
        "temurinLicenseSha256": hashlib.sha256(contents.get("runtime/legal/java.base/LICENSE", b"")).hexdigest(),
        "temurinAssemblyExceptionSha256": hashlib.sha256(contents.get("runtime/legal/java.base/ASSEMBLY_EXCEPTION", b"")).hexdigest(),
    }
    report_path = directory / "application-package-report.json"
    write_report(report_path, report)
    return package, report_path, report, contents
