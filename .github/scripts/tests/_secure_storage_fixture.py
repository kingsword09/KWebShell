"""Synthetic secure-storage validator inputs; never used as real evidence."""

import json
import xml.etree.ElementTree as ET
import zipfile
from pathlib import Path


def write_secure_storage_fixture(root, target):
    family = root / f"secure-storage-integration-{target}-revision"
    family.mkdir(parents=True, exist_ok=True)
    library = {"macos-arm64": "libkwebshell_secure_storage.dylib",
               "windows-x64": "kwebshell_secure_storage.dll",
               "linux-x64": "libkwebshell_secure_storage.so"}[target]
    archive = family / f"kweb-service-secure-storage-1.0.0-{target}.zip"
    with zipfile.ZipFile(archive, "w") as output:
        output.writestr(f"native/{target}/{library}", b"validator test input, not a native library")
        output.writestr("include/kweb_secure_storage.h", "validator test header")

    (family / "secure-storage-integration.json").write_text(json.dumps({
        "schemaVersion": 1,
        "target": target,
        "library": library,
        "outcome": "passed",
        "provider": "secure-storage.hosted",
        "host": "available",
        "roundTrip": "verified",
        "rotation": "verified",
        "storedSizeBytes": 27,
        "liveHandlesAfterClose": 0,
    }) + "\n")
    (family / "native-secure-storage-package.json").write_text(json.dumps({
        "schemaVersion": 1, "target": target, "archive": archive.name, "nativeLibrary": library,
        "exactContents": True,
    }) + "\n")
    for name, cases in (
        ("secure-storage-native-tests.xml", {"kweb_secure_storage_tests"}),
        ("TEST-io.github.kingsword09.kwebshell.service.securestorage.KWebSecureStorageContractTest.xml",
         {"contract", "descriptor"}),
        ("TEST-io.github.kingsword09.kwebshell.service.securestorage.KWebSecureStorageMetadataAdapterTest.xml",
         {"renderer-metadata", "undeclared-denied"}),
    ):
        suite = ET.Element("testsuite")
        for case in sorted(cases):
            ET.SubElement(suite, "testcase", name=case)
        ET.ElementTree(suite).write(family / name)
    return family
