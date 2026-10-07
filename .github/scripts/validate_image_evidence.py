"""Validate RFC 0027 provider, CEF, tests and package artifacts before recording."""

import argparse
import hashlib
import json
from pathlib import Path
import re
import xml.etree.ElementTree as ET
import zipfile


PROVIDERS = {
    "macos-arm64": "macos.CoreGraphics.CGImage",
    "windows-x64": "windows.Win32.HBITMAP",
    "linux-x64": "linux.GdkPixbuf",
}
CODEC_TESTS = {
    "pngDecodeNormalizesToDeterministicRgbaPng",
    "jpegDecodeAndUnsupportedFormatsFailExplicitly",
    "malformedAndTruncatedPayloadsFailBeforeNativeUse",
    "packageDigestIsCheckedBeforeDecode",
    "premultipliedAlphaCannotBeEncodedWithoutAnExplicitConversion",
    "declaredDimensionsMustMatchThePng",
    "opaqueMetadataCannotDescribeTransparentPng",
    "jpegExifOrientationIsParsedAndOnlyIdentityIsAccepted",
    "malformedExifOffsetsAndOrientationEntriesAreRejected",
    "pngCrcTruncationAnimationAndTrailingBytesAreRejected",
    "pixelAndDimensionLimitsAreCheckedBeforePixelAllocation",
    "embeddedLinearRgbIccIsConvertedToSrgbWithAlphaPreserved",
    "jpegEmbeddedIccIsConvertedToSrgb",
    "malformedAndOversizedIccProfilesFailWithinTheirBound",
    "canonicalPngPreservesPixelsAndMaximumWidthRows",
}
CONTRACT_TESTS = {
    "encodedBytesAreCopiedAndOnlyPublishedFormatsAreAccepted",
    "resourceIdentifiersAndDigestsArePathSafe",
    "variantsRequireMatchingLogicalDimensionsAndUniqueScales",
    "resourceStoreRejectsDigestMismatchBeforeUse",
    "descriptorPublishesApplicationOperationsAndTargets",
}


def require(condition, message):
    if not condition:
        raise ValueError(message)


def artifact(root, family, target, name):
    prefix = f"{family}-{target}-"
    matches = [p for p in root.rglob(name) if any(part.startswith(prefix) for part in p.parts)]
    require(len(matches) == 1, f"{target}: require exactly one {family}/{name}")
    return matches[0]


def document(path):
    require(path.stat().st_size <= 1024 * 1024, "Image report exceeds its size bound")
    value = json.loads(path.read_bytes())
    require(isinstance(value, dict), "Image evidence must be an object")
    return value


def digest(value):
    return isinstance(value, str) and re.fullmatch(r"[0-9a-f]{64}", value) is not None


def tests(path, expected):
    root = ET.parse(path).getroot()
    cases = list(root.iter("testcase"))
    names = {case.get("name", "").split("(")[0] for case in cases}
    require(expected <= names, f"Missing required image tests in {path.name}")
    require(all(case.find(tag) is None for case in cases for tag in ("failure", "error", "skipped")),
            f"Failed or skipped image test in {path.name}")


def validate_target(root, target):
    provider = document(artifact(root, "image-integration", target, "native-image-evidence.json"))
    require(provider.get("target") == target and provider.get("providerId") == PROVIDERS[target], "Image provider identity mismatch")
    require(type(provider.get("schemaVersion")) is int and provider["schemaVersion"] == 1, "Invalid image report schema")
    require(type(provider.get("liveCountAfterClose")) is int and provider["liveCountAfterClose"] == 0, "Image handles remain live")
    require(provider.get("deterministicPng") is True and provider.get("resourceDigestChecked") is True, "Missing image codec observations")
    require(digest(provider.get("nativeLibrarySha256")), "Missing native image library digest")
    for format_name, alpha in (("png", "STRAIGHT"), ("jpeg", "OPAQUE")):
        observed = provider.get("decode", {}).get(format_name, {})
        require(type(observed.get("width")) is int and observed["width"] == 2 and
                type(observed.get("height")) is int and observed["height"] == 2 and
                observed.get("alphaMode") == alpha and observed.get("colorSpace") == "SRGB" and
                digest(observed.get("pngSha256")), f"Invalid observed {format_name} decode")
    rejected = provider.get("rejected", {})
    require(rejected.get("digestMismatch") == "image.resource-digest-mismatch" and
            rejected.get("ownerClosed") == "image.owner-closed", "Missing image lifecycle/resource failures")
    require(all(rejected.get(name) == "image.format-unsupported" for name in
                ("image/webp", "image/gif", "image/svg+xml", "image/x-icon")), "Missing unsupported image format failures")

    migration = document(artifact(root, "electron-migration", target, "migration-image-evidence.json"))
    require(migration.get("target") == target, "Image CEF evidence target mismatch")
    require(all(migration.get(field) is True for field in
                ("childFrameTransportAbsent", "crossOriginBridgeAbsent", "unconfiguredBridgeAbsent", "ownerClosed")),
            "Missing image CEF authority/lifecycle observations")
    observed = migration.get("observed", {})
    for scenario, code in {
        "malformedBase64": "image.payload-invalid", "oversizedInput": "image.payload-too-large", "oversizedOutput": "image.payload-too-large",
        "svgRejected": "image.format-unsupported", "urlRejected": "image.payload-invalid",
        "intentRejected": "image.intent-invalid",
        "pathRejected": "image.resource-id-invalid", "cancelled": "bridge.call.cancelled",
        "ownerClosed": "image.owner-closed", "permissionDenied": "service.permission-denied",
    }.items():
        require(observed.get(scenario) == code, f"Missing image CEF scenario {scenario}")
    round_trip = json.loads(observed["roundTrip"])
    require(round_trip == {"width": 2, "height": 1, "alphaMode": "STRAIGHT", "colorSpace": "SRGB",
                           "roundTrip": True, "format": "image/png"}, "Invalid image CEF round trip")

    compatibility = document(artifact(root, "electron-migration", target, "compatibility.json"))
    require(compatibility.get("target") == target and compatibility.get("migrationStatus") == "READY" and
            compatibility.get("serviceContractVersions", {}).get("native-image") == "1.0.0", "Missing native-image migration compatibility")
    for method, operation in (("decode", "decode"), ("encodePng", "encode-png")):
        policy = compatibility.get("policies", {}).get(f"channel:nativeImage.{method}", {})
        require(policy.get("rendererGrant") == f"native.native-image.{operation}" and
                policy.get("requiresUserGesture") is False and policy.get("requiresOsConsent") is False,
                "Image migration policy does not match its service")

    package = document(artifact(root, "image-integration", target, "native-image-package.json"))
    archive_name = f"kweb-service-image-1.0.0-{target}.zip"
    require(package.get("target") == target and package.get("archive") == archive_name and
            package.get("exactContents") is True and digest(package.get("sha256")), "Invalid image package evidence")
    archive = artifact(root, "image-integration", target, archive_name)
    require(archive.stat().st_size <= 16 * 1024 * 1024 and
            hashlib.sha256(archive.read_bytes()).hexdigest() == package["sha256"], "Image package digest mismatch")
    library_name = {"macos-arm64": "libkwebshell_image.dylib", "windows-x64": "kwebshell_image.dll",
                    "linux-x64": "libkwebshell_image.so"}[target]
    require(package.get("nativeLibrary") == provider.get("nativeLibrary") == library_name, "Image library identity mismatch")
    library_entry = f"native/{target}/{library_name}"
    with zipfile.ZipFile(archive) as archive_file:
        entries = [entry for entry in archive_file.infolist() if not entry.is_dir()]
        require(len(entries) == 2 and {entry.filename for entry in entries} == {library_entry, "include/kweb_image.h"},
                "Image package contains unexpected files")
        require(all(entry.file_size <= 8 * 1024 * 1024 for entry in entries), "Image package entry exceeds its bound")
        require(hashlib.sha256(archive_file.read(library_entry)).hexdigest() == provider["nativeLibrarySha256"],
                "Packaged native image provider differs from tested library")

    tests(artifact(root, "image-integration", target, "image-native-tests.xml"), {"kweb_image_tests"})
    for class_name, expected in (("JvmKWebImageCodecTest", CODEC_TESTS), ("KWebImageContractTest", CONTRACT_TESTS)):
        tests(artifact(root, "image-integration", target, f"TEST-io.github.kingsword09.kwebshell.service.image.{class_name}.xml"), expected)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--downloaded", type=Path, required=True)
    arguments = parser.parse_args()
    for target in PROVIDERS:
        try:
            validate_target(arguments.downloaded, target)
        except (ValueError, KeyError, TypeError, OSError, ET.ParseError, zipfile.BadZipFile) as error:
            raise SystemExit(f"Invalid native image evidence for {target}: {error}") from error
    print("Validated real RFC 0027 provider, CEF, unit, native and package evidence for all targets.")


if __name__ == "__main__":
    main()
