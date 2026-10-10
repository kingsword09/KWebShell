"""Synthetic menus validator inputs; never used as native conformance evidence."""

import json
import xml.etree.ElementTree as ET
import zipfile
from pathlib import Path


def write_menus_fixture(root, target):
    menus = root / f"menus-integration-{target}-revision"
    engine = root / f"engine-integration-{target}-revision"
    menus.mkdir(parents=True, exist_ok=True)
    engine.mkdir(parents=True, exist_ok=True)
    library = {"macos-arm64": "libkwebshell_menus.dylib", "windows-x64": "kwebshell_menus.dll",
               "linux-x64": "libkwebshell_menus.so"}[target]
    archive = menus / f"kweb-service-menus-1.0.0-{target}.zip"
    with zipfile.ZipFile(archive, "w") as output:
        output.writestr(f"native/{target}/{library}", b"validator test input, not a native library")
        output.writestr("include/kweb_menus.h", "validator test header")

    (menus / "menus-integration.json").write_text(json.dumps({
        "schemaVersion": 1,
        "target": target,
        "library": library,
        "outcome": "passed",
        "applicationBar": "native",
        "pageMenuItems": 2,
        "staleVersion": "menus.version-stale",
        "undeclaredMenu": "menus.menu-not-declared",
    }) + "\n")
    (menus / "native-menus-package.json").write_text(json.dumps({
        "schemaVersion": 1, "target": target, "archive": archive.name, "nativeLibrary": library,
        "exactContents": True,
    }) + "\n")
    (engine / "context-menu-evidence.json").write_text(json.dumps({
        "schemaVersion": 1, "target": target, "requestCount": 2, "mainFrame": True,
        "continuedOutcome": "CONTINUED", "duplicateOutcome": "page.context-menu.already-resolved",
        "dismissedOutcome": "DISMISSED", "engineAbiVersion": 17, "menuBodyRetained": False,
    }) + "\n")
    for name, cases in (
        ("menus-native-tests.xml", {"kweb_menus_tests"}),
        ("TEST-io.github.kingsword09.kwebshell.service.menus.KWebMenusContractTest.xml", {"contract"}),
        ("TEST-io.github.kingsword09.kwebshell.service.menus.KWebMenusBridgeTest.xml", {"bridge"}),
    ):
        suite = ET.Element("testsuite")
        for case in sorted(cases):
            ET.SubElement(suite, "testcase", name=case)
        ET.ElementTree(suite).write(menus / name)
    if not (menus / "menus-integration.json").is_file():
        raise AssertionError(f"missing menus integration report for {target}")
    return menus
