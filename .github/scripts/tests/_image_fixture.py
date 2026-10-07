"""Synthetic validator inputs; never used as native conformance evidence."""

import hashlib
import json
from pathlib import Path
import sys
import xml.etree.ElementTree as ET
import zipfile

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from validate_image_evidence import CODEC_TESTS, CONTRACT_TESTS, PROVIDERS


def write_image_fixture(root, target):
    image = root / f"image-integration-{target}-revision"
    migration = root / f"electron-migration-{target}-revision"
    image.mkdir(parents=True, exist_ok=True)
    migration.mkdir(parents=True, exist_ok=True)
    library = {"macos-arm64": "libkwebshell_image.dylib", "windows-x64": "kwebshell_image.dll",
               "linux-x64": "libkwebshell_image.so"}[target]
    library_bytes = b"validator test input, not a native library"
    archive = image / f"kweb-service-image-1.0.0-{target}.zip"
    with zipfile.ZipFile(archive, "w") as output:
        output.writestr(f"native/{target}/{library}", library_bytes)
        output.writestr("include/kweb_image.h", "validator test header")

    def write(directory, name, value):
        path = directory / name
        path.write_text(json.dumps(value) + "\n")
        return path

    provider = write(image, "native-image-evidence.json", {
        "schemaVersion": 1, "target": target, "providerId": PROVIDERS[target], "nativeLibrary": library,
        "nativeLibrarySha256": hashlib.sha256(library_bytes).hexdigest(),
        "liveCountAfterClose": 0, "deterministicPng": True, "resourceDigestChecked": True,
        "decode": {format_name: {"width": 2, "height": 2, "alphaMode": alpha, "colorSpace": "SRGB", "pngSha256": "a" * 64}
                   for format_name, alpha in (("png", "STRAIGHT"), ("jpeg", "OPAQUE"))},
        "rejected": {"digestMismatch": "image.resource-digest-mismatch", "ownerClosed": "image.owner-closed",
                     **{name: "image.format-unsupported" for name in ("image/webp", "image/gif", "image/svg+xml", "image/x-icon")}},
    })
    write(image, "native-image-package.json", {
        "schemaVersion": 1, "target": target, "archive": archive.name, "nativeLibrary": library,
        "sha256": hashlib.sha256(archive.read_bytes()).hexdigest(), "exactContents": True,
    })
    cef = write(migration, "migration-image-evidence.json", {
        "schemaVersion": 1, "target": target, "childFrameTransportAbsent": True,
        "crossOriginBridgeAbsent": True, "unconfiguredBridgeAbsent": True, "ownerClosed": True,
        "observed": {
            "roundTrip": json.dumps({"width": 2, "height": 1, "alphaMode": "STRAIGHT", "colorSpace": "SRGB", "roundTrip": True, "format": "image/png"}),
            "malformedBase64": "image.payload-invalid", "oversizedInput": "image.payload-too-large", "oversizedOutput": "image.payload-too-large",
            "svgRejected": "image.format-unsupported", "urlRejected": "image.payload-invalid",
            "pathRejected": "image.resource-id-invalid", "cancelled": "bridge.call.cancelled",
            "ownerClosed": "image.owner-closed", "permissionDenied": "service.permission-denied",
        },
    })
    compatibility = write(migration, "compatibility.json", {
        "target": target, "migrationStatus": "READY", "serviceContractVersions": {"native-image": "1.0.0"},
        "policies": {f"channel:nativeImage.{method}": {"rendererGrant": f"native.native-image.{operation}", "requiresUserGesture": False, "requiresOsConsent": False}
                     for method, operation in (("decode", "decode"), ("encodePng", "encode-png"))},
    })
    for name, cases in (
        ("image-native-tests.xml", {"kweb_image_tests"}),
        ("TEST-io.github.kingsword09.kwebshell.service.image.JvmKWebImageCodecTest.xml", CODEC_TESTS),
        ("TEST-io.github.kingsword09.kwebshell.service.image.KWebImageContractTest.xml", CONTRACT_TESTS),
    ):
        suite = ET.Element("testsuite")
        for case in sorted(cases):
            ET.SubElement(suite, "testcase", name=case)
        ET.ElementTree(suite).write(image / name)
    return provider, cef, compatibility, archive
