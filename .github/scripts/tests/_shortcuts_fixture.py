"""Synthetic shortcut validator inputs; never used as real evidence."""

import json
import xml.etree.ElementTree as ET
import zipfile
from pathlib import Path


def write_shortcuts_fixture(root, target):
    shortcuts = root / f"shortcuts-integration-{target}-revision"
    shortcuts.mkdir(parents=True, exist_ok=True)
    library = {"macos-arm64": "libkwebshell_shortcuts.dylib", "windows-x64": "kwebshell_shortcuts.dll",
               "linux-x64": "libkwebshell_shortcuts.so"}[target]
    archive = shortcuts / f"kweb-service-shortcuts-1.0.0-{target}.zip"
    with zipfile.ZipFile(archive, "w") as output:
        output.writestr(f"native/{target}/{library}", b"validator test input, not a native library")
        output.writestr("include/kweb_shortcuts.h", "validator test header")

    (shortcuts / "shortcuts-integration.json").write_text(json.dumps({
        "schemaVersion": 1,
        "target": target,
        "library": library,
        "outcome": "passed",
        "provider": "shortcuts.hosted",
        "host": "available",
        "registrations": "1",
        "dispatched": "text.copy",
    }) + "\n")
    (shortcuts / "native-shortcuts-package.json").write_text(json.dumps({
        "schemaVersion": 1, "target": target, "archive": archive.name, "nativeLibrary": library,
        "exactContents": True,
    }) + "\n")
    for name, cases in (
        ("shortcuts-native-tests.xml", {"kweb_shortcuts_tests"}),
        ("TEST-io.github.kingsword09.kwebshell.service.shortcuts.KWebShortcutsContractTest.xml",
         {"contract", "descriptor"}),
        ("TEST-io.github.kingsword09.kwebshell.service.shortcuts.KWebShortcutsBridgeTest.xml",
         {"renderer-read", "renderer-dispatch"}),
    ):
        suite = ET.Element("testsuite")
        for case in sorted(cases):
            ET.SubElement(suite, "testcase", name=case)
        ET.ElementTree(suite).write(shortcuts / name)
    return shortcuts
