"""Exercise the real shell orchestrator with an isolated recorder test double.

The double checks catalog status and chains invocation records; it does not
generate RFC support evidence. Real recording and governance run next in CI.
"""

import json
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import unittest

from _msix_fixture import REVISION, create_fixture, write_report
from _image_fixture import write_image_fixture

REPOSITORY = Path(__file__).resolve().parents[3]
SCRIPT = REPOSITORY / ".github/scripts/aggregate-rfc-evidence.sh"
TARGETS = ("macos-arm64", "windows-x64", "linux-x64")
IMPLEMENTED = {
    "0001", "0002", "0003", "0004", "0006", "0007", "0008", "0009",
    "0010", "0011", "0012", "0013", "0014", "0015", "0027", "0030",
}
ARTIFACTS = {
    "rfc-governance": ["TEST-io.github.kingsword09.kwebshell.rfc.KWebRfcGovernanceCheckerTest.xml"],
    "provider-lifecycle": ["provider-lifecycle-report.json", "window-controls-report.json"],
    "native-dialogs": ["consent-status.json"],
    "application-package": ["application-package-report.json"],
    "application-lifecycle": ["application-lifecycle-report.json"],
    "clipboard": ["clipboard-evidence.json"],
    "image-integration": ["native-image-evidence.json"],
    "engine-integration": [
        "stream-conformance.json", "application-shutdown.json", "page-lifecycle-evidence.json",
        "renderer-lifecycle-evidence.json", "profile-data-evidence.json", "network-policy-evidence.json",
        "security-challenge-evidence.json", "mtls-probe-evidence.json", "downloads-evidence.json",
        "files-evidence.json",
    ],
    "electron-migration": [
        "compatibility.json", "migration-clipboard-evidence.json", "migration-shell-evidence.json",
    ],
}


class AggregationTest(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory(prefix="kweb-evidence-test-")
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name)
        script = self.root / ".github/scripts/aggregate-rfc-evidence.sh"
        script.parent.mkdir(parents=True)
        shutil.copyfile(SCRIPT, script)
        shutil.copyfile(SCRIPT.with_name("validate_image_evidence.py"), script.with_name("validate_image_evidence.py"))
        shutil.copyfile(SCRIPT.with_name("retain_windows_msix_evidence.py"), script.with_name("retain_windows_msix_evidence.py"))
        catalog = self.root / "docs/rfcs"
        catalog.mkdir(parents=True)
        for source in (REPOSITORY / "docs/rfcs").glob("[0-9][0-9][0-9][0-9]-*.md"):
            shutil.copyfile(source, catalog / source.name)
        self.catalog_before = {p.name: p.read_bytes() for p in catalog.glob("*.md")}
        self.manifest = catalog / "evidence/manifest.json"
        self.manifest.parent.mkdir()
        self.manifest.write_text('{"invocations": []}\n')
        self.manifest_before = self.manifest.read_bytes()
        for target in TARGETS:
            for family, names in ARTIFACTS.items():
                directory = self.root / f"build/rfc-evidence/downloaded/{family}-{target}-revision"
                directory.mkdir(parents=True)
                for name in names:
                    (directory / name).write_text("{}\n")
            self.report(target).write_text(json.dumps(self.valid_report(target)) + "\n")
            write_image_fixture(self.root / "build/rfc-evidence/downloaded", target)
        self.msix, self.msix_report_path, self.msix_report, _ = create_fixture(
            self.root / "build/rfc-evidence/downloaded/application-package-windows-x64-revision"
        )
        self.msix_before = self.msix.read_bytes()
        # Replace only the Gradle process boundary, never the production script.
        recorder = self.root / "recorder.py"
        recorder.write_text('''import json
from pathlib import Path
import sys
arguments = next(a.split("=", 1)[1].split() for a in sys.argv if a.startswith("-PrfcEvidenceArguments="))
assert arguments[0] == "record"
options = dict(zip(arguments[3::2], arguments[4::2]))
catalog = Path(options["--catalog"])
assert catalog == Path("docs/rfcs"), "Recorder must use the actual repository catalog"
rfc = options["--rfc"]
document, = catalog.glob(rfc + "-*.md")
assert "- Status: Implemented" in document.read_text().splitlines(), "Cannot record an unimplemented RFC"
manifest = json.loads(Path(arguments[1]).read_text())
artifacts = [arguments[i + 1] for i, value in enumerate(arguments) if value == "--artifact"]
manifest["invocations"].append({"rfc": rfc, "target": options["--target"], "artifacts": artifacts, "compatibility": options.get("--from-compatibility-report"), "service": options.get("--service")})
Path(arguments[2]).write_text(json.dumps(manifest))
''')
        wrapper = self.root / "gradlew"
        wrapper.write_text('#!/usr/bin/env bash\nexec "$KWEB_TEST_PYTHON" recorder.py "$@"\n')
        wrapper.chmod(0o755)

    def report(self, target):
        return self.root / (
            f"build/rfc-evidence/downloaded/electron-migration-{target}-revision/"
            "migration-notifications-evidence.json"
        )

    @staticmethod
    def valid_report(target):
        linux = target == "linux-x64"
        return {
            "actionVerificationMode": "os-ui" if linux else "contract",
            "actionActivationObserved": linux,
            "actionActivationCount": 1 if linux else 0,
        }

    def run_script(self):
        import os
        return subprocess.run(
            ["bash", ".github/scripts/aggregate-rfc-evidence.sh"], cwd=self.root,
            env={**os.environ, "KWEB_TEST_PYTHON": sys.executable, "GITHUB_SHA": REVISION},
            text=True, capture_output=True, timeout=60,
        )

    def assert_preflight_failure(self, target):
        result = self.run_script()
        self.assertNotEqual(0, result.returncode)
        self.assertIn(target, result.stderr)
        self.assertEqual(self.manifest_before, self.manifest.read_bytes())
        self.assertEqual([], list(self.root.glob("build/rfc-evidence/chain-*.json")))

    def test_records_only_implemented_rfcs_and_preserves_catalog_and_raw_reports(self):
        reports = {target: self.report(target).read_bytes() for target in TARGETS}
        result = self.run_script()
        self.assertEqual(0, result.returncode, result.stderr)
        calls = json.loads(self.manifest.read_text())["invocations"]
        self.assertEqual(48, len(calls))
        self.assertEqual(
            {(rfc, target) for rfc in IMPLEMENTED for target in TARGETS},
            {(call["rfc"], call["target"]) for call in calls},
        )
        self.assertEqual(self.catalog_before, {
            p.name: p.read_bytes() for p in (self.root / "docs/rfcs").glob("*.md")
        })
        self.assertEqual(reports, {target: self.report(target).read_bytes() for target in TARGETS})
        windows_package, = [call for call in calls if call["rfc"] == "0030" and call["target"] == "windows-x64"]
        self.assertIn("windows-msix-proof=build/rfc-evidence/windows-msix-proof.zip", windows_package["artifacts"])
        self.assertTrue((self.root / "build/rfc-evidence/windows-msix-proof.zip").is_file())
        self.assertEqual(self.msix_before, self.msix.read_bytes())

    def test_image_uses_real_migration_compatibility_and_retains_all_proofs(self):
        result = self.run_script()
        self.assertEqual(0, result.returncode, result.stderr)
        calls = [call for call in json.loads(self.manifest.read_text())["invocations"] if call["rfc"] == "0027"]
        self.assertEqual(3, len(calls))
        for call in calls:
            self.assertEqual("native-image", call["service"])
            self.assertIn("electron-migration-" + call["target"], call["compatibility"])
            self.assertEqual({"native-image", "compatibility", "migration-image", "image-package", "image-runtime",
                              "image-native-tests", "image-codec-tests", "image-contract-tests"},
                             {value.split("=", 1)[0] for value in call["artifacts"]})

    def test_missing_image_compatibility_blocks_before_any_recording(self):
        for target in TARGETS:
            with self.subTest(target=target):
                report = self.report(target).with_name("compatibility.json")
                original = report.read_bytes()
                report.write_text("{}")
                self.assert_preflight_failure(target)
                report.write_bytes(original)

    def test_missing_windows_msix_blocks_aggregation(self):
        self.msix.unlink()
        result = self.run_script()
        self.assertNotEqual(0, result.returncode)
        self.assertIn("Windows MSIX evidence", result.stderr)
        self.assertEqual(self.manifest_before, self.manifest.read_bytes())

    def test_failed_windows_native_report_blocks_before_recording(self):
        write_report(self.msix_report_path, {**self.msix_report, "normalShutdown": "FAIL"})
        self.assert_preflight_failure("Windows MSIX")

    def test_invalid_windows_activation_blocks_before_recording(self):
        for field, value in (("nativeActivation", "NOT_RUN"), ("activationHresult", "0x80070005"),
                             ("activationProcessId", 0), ("activationProcessId", True)):
            with self.subTest(field=field, value=value):
                write_report(self.msix_report_path, {**self.msix_report, field: value})
                self.assert_preflight_failure("Windows MSIX")

    def test_each_missing_notification_report_blocks_before_recording(self):
        for target in TARGETS:
            with self.subTest(target=target):
                report = self.report(target)
                original = report.read_bytes()
                report.unlink()
                self.assert_preflight_failure(target)
                report.write_bytes(original)

    def test_invalid_action_reports_block_before_recording(self):
        for target in TARGETS:
            valid = self.valid_report(target)
            invalid = ["", "{", "null", "[]", "{}"]
            for field, values in {
                "actionVerificationMode": [None, "unknown", "contract" if target == "linux-x64" else "os-ui"],
                "actionActivationObserved": [None, str(valid["actionActivationObserved"]).lower(), not valid["actionActivationObserved"]],
                "actionActivationCount": [None, str(valid["actionActivationCount"]), -1, 2, 0 if target == "linux-x64" else 1],
            }.items():
                invalid += [json.dumps({**valid, field: value}) for value in values]
                invalid.append(json.dumps({k: v for k, v in valid.items() if k != field}))
            for contents in invalid:
                with self.subTest(target=target, contents=contents):
                    self.report(target).write_text(contents)
                    self.assert_preflight_failure(target)
            self.report(target).write_text(json.dumps(valid))

    def test_missing_implemented_artifact_is_still_fatal(self):
        path = next(self.root.glob("build/rfc-evidence/downloaded/rfc-governance-macos-arm64-*/*.xml"))
        path.unlink()
        result = self.run_script()
        self.assertNotEqual(0, result.returncode)
        self.assertIn("Missing governance-contract-tests evidence file for macos-arm64", result.stderr)
        self.assertEqual(self.manifest_before, self.manifest.read_bytes())

    def test_recorder_failure_is_not_hidden_by_catalog_normalization(self):
        path = self.root / "docs/rfcs/0001-program-governance.md"
        path.write_text(path.read_text().replace("- Status: Implemented", "- Status: Accepted"))
        result = self.run_script()
        self.assertNotEqual(0, result.returncode)
        self.assertIn("Cannot record an unimplemented RFC", result.stderr)
        self.assertEqual(self.manifest_before, self.manifest.read_bytes())


if __name__ == "__main__":
    unittest.main()
