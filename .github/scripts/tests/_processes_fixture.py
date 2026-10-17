"""Synthetic managed-process validator inputs; never used as real evidence."""

import json
import xml.etree.ElementTree as ET
import zipfile
from pathlib import Path


def write_processes_fixture(root, target):
    family = root / f"processes-integration-{target}-revision"
    family.mkdir(parents=True, exist_ok=True)
    library = {"macos-arm64": "libkwebshell_processes.dylib",
               "windows-x64": "kwebshell_processes.dll",
               "linux-x64": "libkwebshell_processes.so"}[target]
    archive = family / f"kweb-service-processes-1.0.0-{target}.zip"
    with zipfile.ZipFile(archive, "w") as output:
        output.writestr(f"native/{target}/{library}", b"validator test input, not a native library")
        output.writestr("include/kweb_processes.h", "validator test header")

    (family / "processes-integration.json").write_text(json.dumps({
        "schemaVersion": 1,
        "target": target,
        "provider": "processes.hosted",
        "declaredIsolation": "PROCESS_TREE,RESOURCE_LIMITS",
        "streamScenario": "verified",
        "quotaScenario": "verified",
        "timeoutScenario": "verified",
        "killScenario": "verified",
        "stdinScenario": "verified",
        "jvmWorkerScenario": "verified",
        "refusalsVerified": 3,
        "orderedChunks": 43,
        "burstBytes": 2097152,
        "peakMemoryBytes": 1048576,
        "liveProcessesAfterClose": 0,
        "status": "PASS",
        "failures": [],
    }) + "\n")
    (family / "native-processes-package.json").write_text(json.dumps({
        "schemaVersion": 1, "target": target, "archive": archive.name, "nativeLibrary": library,
        "exactContents": True,
    }) + "\n")
    for name, cases in (
        ("processes-native-tests.xml", {"kweb_processes_tests"}),
        ("TEST-io.github.kingsword09.kwebshell.service.processes.KWebProcessesContractTest.xml",
         {"contract", "descriptor"}),
    ):
        suite = ET.Element("testsuite")
        for case in sorted(cases):
            ET.SubElement(suite, "testcase", name=case)
        ET.ElementTree(suite).write(family / name)
    return family
