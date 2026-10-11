"""Synthetic system-preferences validator inputs; never used as real evidence."""

import json
import xml.etree.ElementTree as ET
import zipfile
from pathlib import Path


def write_preferences_fixture(root, target):
    preferences = root / f"preferences-integration-{target}-revision"
    preferences.mkdir(parents=True, exist_ok=True)
    library = {"macos-arm64": "libkwebshell_system_preferences.dylib",
               "windows-x64": "kwebshell_system_preferences.dll",
               "linux-x64": "libkwebshell_system_preferences.so"}[target]
    archive = preferences / f"kweb-service-system-preferences-1.0.0-{target}.zip"
    with zipfile.ZipFile(archive, "w") as output:
        output.writestr(f"native/{target}/{library}", b"validator test input, not a native library")
        output.writestr("include/kweb_system_preferences.h", "validator test header")

    (preferences / "preferences-integration.json").write_text(json.dumps({
        "schemaVersion": 1,
        "target": target,
        "library": library,
        "outcome": "passed",
        "provider": "preferences.hosted",
        "host": "available",
        "appearanceRequest": "DARK:applied",
        "appearanceSequence": "2",
        "restoredSequence": "3",
    }) + "\n")
    (preferences / "native-preferences-package.json").write_text(json.dumps({
        "schemaVersion": 1, "target": target, "archive": archive.name, "nativeLibrary": library,
        "exactContents": True,
    }) + "\n")
    for name, cases in (
        ("preferences-native-tests.xml", {"kweb_preferences_tests"}),
        ("TEST-io.github.kingsword09.kwebshell.service.preferences.KWebSystemPreferencesContractTest.xml",
         {"contract", "descriptor"}),
        ("TEST-io.github.kingsword09.kwebshell.service.preferences.KWebSystemPreferencesBridgeTest.xml",
         {"renderer-read", "renderer-subscribe", "renderer-appearance"}),
    ):
        suite = ET.Element("testsuite")
        for case in sorted(cases):
            ET.SubElement(suite, "testcase", name=case)
        ET.ElementTree(suite).write(preferences / name)
    theme = root / f"engine-integration-{target}-evidence"
    theme.mkdir(parents=True, exist_ok=True)
    (theme / "theme-evidence.json").write_text(json.dumps({
        "schemaVersion": 1,
        "target": target,
        "cefRuntime": "stock-cef-151",
        "startupObservations": "dark=false,light=true,contrast=true,reduced-motion=true,forced-colors=true",
        "platformColorScheme": "LIGHT",
        "contentColorSchemeFollowsPlatform": True,
        "restartRequiredPreferences": {"HIGH_CONTRAST": True, "REDUCED_MOTION": True},
    }) + "\n")
    return preferences
