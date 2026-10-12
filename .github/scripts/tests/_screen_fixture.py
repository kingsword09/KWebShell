"""Synthetic screen validator inputs; never used as real evidence."""

import json
import xml.etree.ElementTree as ET
import zipfile
from pathlib import Path


def write_screen_fixture(root, target):
    screen = root / f"screen-integration-{target}-revision"
    screen.mkdir(parents=True, exist_ok=True)
    library = {"macos-arm64": "libkwebshell_screen.dylib", "windows-x64": "kwebshell_screen.dll",
               "linux-x64": "libkwebshell_screen.so"}[target]
    archive = screen / f"kweb-service-screen-1.0.0-{target}.zip"
    with zipfile.ZipFile(archive, "w") as output:
        output.writestr(f"native/{target}/{library}", b"validator test input, not a native library")
        output.writestr("include/kweb_screen.h", "validator test header")

    (screen / "screen-integration.json").write_text(json.dumps({
        "schemaVersion": 1,
        "target": target,
        "library": library,
        "outcome": "passed",
        "provider": "screen.hosted",
        "host": "available",
        "displays": "1",
        "resolved": "display.1",
        "conversion": "960,540",
        "topologyStable": "true",
    }) + "\n")
    (screen / "native-screen-package.json").write_text(json.dumps({
        "schemaVersion": 1, "target": target, "archive": archive.name, "nativeLibrary": library,
        "exactContents": True,
    }) + "\n")
    (screen / "screen-wayland-transcript.txt").write_text("kweb screen native checks passed.\n")
    for name, cases in (
        ("screen-native-tests.xml", {"kweb_screen_tests"}),
        ("TEST-io.github.kingsword09.kwebshell.service.screen.KWebScreensContractTest.xml",
         {"contract", "descriptor"}),
        ("TEST-io.github.kingsword09.kwebshell.service.screen.KWebScreensBridgeTest.xml",
         {"renderer-read", "renderer-subscribe"}),
    ):
        suite = ET.Element("testsuite")
        for case in sorted(cases):
            ET.SubElement(suite, "testcase", name=case)
        ET.ElementTree(suite).write(screen / name)
    return screen
