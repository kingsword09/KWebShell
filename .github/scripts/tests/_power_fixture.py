"""Synthetic power validator inputs; never used as real evidence."""

import json
import xml.etree.ElementTree as ET
import zipfile
from pathlib import Path


def write_power_fixture(root, target):
    power = root / f"power-integration-{target}-revision"
    power.mkdir(parents=True, exist_ok=True)
    library = {"macos-arm64": "libkwebshell_power.dylib", "windows-x64": "kwebshell_power.dll",
               "linux-x64": "libkwebshell_power.so"}[target]
    archive = power / f"kweb-service-power-1.0.0-{target}.zip"
    with zipfile.ZipFile(archive, "w") as output:
        output.writestr(f"native/{target}/{library}", b"validator test input, not a native library")
        output.writestr("include/kweb_power.h", "validator test header")

    (power / "power-integration.json").write_text(json.dumps({
        "schemaVersion": 1,
        "target": target,
        "library": library,
        "outcome": "passed",
        "provider": "power.hosted",
        "host": "available",
        "peakLeases": 2,
        "expiredLeases": 1,
        "liveLeasesAfterClose": 0,
    }) + "\n")
    (power / "native-power-package.json").write_text(json.dumps({
        "schemaVersion": 1, "target": target, "archive": archive.name, "nativeLibrary": library,
        "exactContents": True,
    }) + "\n")
    for name, cases in (
        ("power-native-tests.xml", {"kweb_power_tests"}),
        ("TEST-io.github.kingsword09.kwebshell.service.power.KWebPowerContractTest.xml",
         {"contract", "descriptor"}),
        ("TEST-io.github.kingsword09.kwebshell.service.power.KWebPowerBridgeTest.xml",
         {"renderer-read", "renderer-acquire"}),
    ):
        suite = ET.Element("testsuite")
        for case in sorted(cases):
            ET.SubElement(suite, "testcase", name=case)
        ET.ElementTree(suite).write(power / name)
    return power
