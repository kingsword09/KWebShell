"""Synthetic tray validator inputs; never used as native conformance evidence."""

import json
import xml.etree.ElementTree as ET
import zipfile
from pathlib import Path


def write_tray_fixture(root, target):
    tray = root / f"tray-integration-{target}-revision"
    tray.mkdir(parents=True, exist_ok=True)
    library = {"macos-arm64": "libkwebshell_tray.dylib", "windows-x64": "kwebshell_tray.dll",
               "linux-x64": "libkwebshell_tray.so"}[target]
    archive = tray / f"kweb-service-tray-1.0.0-{target}.zip"
    with zipfile.ZipFile(archive, "w") as output:
        output.writestr(f"native/{target}/{library}", b"validator test input, not a native library")
        output.writestr("include/kweb_tray.h", "validator test header")

    (tray / "tray-integration.json").write_text(json.dumps({
        "schemaVersion": 1,
        "target": target,
        "library": library,
        "outcome": "passed",
        "provider": "tray.hosted",
        "host": "available",
        "duplicateItem": "tray.item-exists",
        "staleMenu": "tray.menu-version-stale",
        "roleMenuBoundary": "tray.menu-invalid",
        "closedItem": "tray.item-unknown",
    }) + "\n")
    (tray / "native-tray-package.json").write_text(json.dumps({
        "schemaVersion": 1, "target": target, "archive": archive.name, "nativeLibrary": library,
        "exactContents": True,
    }) + "\n")
    for name, cases in (
        ("tray-native-tests.xml", {"kweb_tray_tests"}),
        ("TEST-io.github.kingsword09.kwebshell.service.tray.KWebTraysContractTest.xml", {"contract"}),
    ):
        suite = ET.Element("testsuite")
        for case in sorted(cases):
            ET.SubElement(suite, "testcase", name=case)
        ET.ElementTree(suite).write(tray / name)
    return tray
