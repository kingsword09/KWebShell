"""Exercise the real shell orchestrator with an isolated recorder test double.

The double checks catalog status and records the batch invocation; it does not
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
from _menus_fixture import write_menus_fixture
from _preferences_fixture import write_preferences_fixture
from _screen_fixture import write_screen_fixture
from _shortcuts_fixture import write_shortcuts_fixture
from _tray_fixture import write_tray_fixture

REPOSITORY = Path(__file__).resolve().parents[3]
SCRIPT = REPOSITORY / ".github/scripts/aggregate-rfc-evidence.sh"
TARGETS = ("macos-arm64", "windows-x64", "linux-x64")
IMPLEMENTED = {
    "0001", "0002", "0003", "0004", "0006", "0007", "0008", "0009",
    "0010", "0011", "0012", "0013", "0014", "0015", "0017", "0018", "0019", "0020", "0021", "0027", "0030",
}
ARTIFACTS = {
    "rfc-governance": ["TEST-io.github.kingsword09.kwebshell.rfc.KWebRfcGovernanceCheckerTest.xml",
                       "TEST-io.github.kingsword09.kwebshell.rfc.KWebRfcEvidenceBatchTest.xml"],
    "provider-lifecycle": ["provider-lifecycle-report.json", "window-controls-report.json"],
    "native-dialogs": ["consent-status.json"],
    "application-package": ["application-package-report.json"],
    "application-lifecycle": ["application-lifecycle-report.json"],
    "clipboard": ["clipboard-evidence.json"],
    "image-integration": ["native-image-evidence.json"],
    "menus-integration": ["menus-integration.json"],
    "tray-integration": ["tray-integration.json"],
    "engine-integration": [
        "stream-conformance.json", "application-shutdown.json", "page-lifecycle-evidence.json",
        "renderer-lifecycle-evidence.json", "profile-data-evidence.json", "network-policy-evidence.json",
        "security-challenge-evidence.json", "mtls-probe-evidence.json", "downloads-evidence.json",
        "files-evidence.json", "context-menu-evidence.json",
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
        shutil.copyfile(SCRIPT.with_name("ci_plan.py"), script.with_name("ci_plan.py"))
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
            write_menus_fixture(self.root / "build/rfc-evidence/downloaded", target)
            write_tray_fixture(self.root / "build/rfc-evidence/downloaded", target)
            write_preferences_fixture(self.root / "build/rfc-evidence/downloaded", target)
            write_screen_fixture(self.root / "build/rfc-evidence/downloaded", target)
            write_shortcuts_fixture(self.root / "build/rfc-evidence/downloaded", target)
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
assert arguments[0] == "recordBatch"
options = dict(zip(arguments[3::2], arguments[4::2]))
catalog = Path(options["--catalog"])
assert catalog == Path("docs/rfcs"), "Recorder must use the actual repository catalog"
manifest = json.loads(Path(arguments[1]).read_text())
batch = json.loads(Path(options["--batch"]).read_text())
assert batch["schemaVersion"] == 1
with Path("gradle-invocations.txt").open("a") as log:
    log.write("batch\\n")
for record in batch["records"]:
    rfc = record["rfcId"]
    document, = catalog.glob(rfc + "-*.md")
    assert "- Status: Implemented" in document.read_text().splitlines(), "Cannot record an unimplemented RFC"
    artifacts = [a["name"] + "=" + a["path"] for a in record["artifacts"]]
    for artifact in record["artifacts"]:
        assert Path(artifact["path"]).is_file(), "Missing batch artifact"
    manifest["invocations"].append({"rfc": rfc, "target": record["target"], "artifacts": artifacts, "compatibility": record.get("compatibilityReportPath"), "service": record.get("serviceId"), "matrixRows": record["matrixRowIds"]})
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

    def run_script(self, plan=None):
        import os
        return subprocess.run(
            ["bash", ".github/scripts/aggregate-rfc-evidence.sh"] + ([str(plan)] if plan else []), cwd=self.root,
            env={**os.environ, "KWEB_TEST_PYTHON": sys.executable, "GITHUB_SHA": REVISION},
            text=True, capture_output=True, timeout=60,
        )

    def partial_plan(self, rfcs, families):
        plan = self.root / "build/ci/plan.json"
        plan.parent.mkdir(parents=True, exist_ok=True)
        for target in TARGETS:
            audit = self.root / "build/rfc-evidence/downloaded/ci-audits" / ("ci-audit-" + target + "-fixture")
            audit.mkdir(parents=True, exist_ok=True)
            (audit / "audit.json").write_text('{"schemaVersion":1}')
        policy = self.root / "build/rfc-evidence/downloaded/ci-policy-tests-fixture"
        policy.mkdir(parents=True, exist_ok=True)
        (policy / "tests.txt").write_text("fixture policy tests\n")
        plan.write_text(json.dumps({"schemaVersion": 1, "revision": REVISION, "base": REVISION,
                                   "changedPaths": [], "full": False, "modules": [], "roots": [],
                                   "tasks": [], "refreshRfcs": rfcs, "families": families, "reasons": [],
                                   "features": {name: False for name in ("cef", "native", "node", "notifications", "shell", "screen", "msix", "benchmark", "liveHtml5")}}))
        return plan

    def test_partial_governance_batch_does_not_require_unselected_preflights(self):
        for target in TARGETS:
            self.report(target).unlink()
        self.msix.unlink()
        result = self.run_script(self.partial_plan(["0001"], ["rfc-governance"]))
        self.assertEqual(0, result.returncode, result.stderr)
        calls = json.loads(self.manifest.read_text())["invocations"]
        self.assertEqual({("0001", target) for target in TARGETS}, {(call["rfc"], call["target"]) for call in calls})
        self.assertEqual(["batch"], (self.root / "gradle-invocations.txt").read_text().splitlines())

    def test_no_refresh_plan_preserves_manifest_without_gradle(self):
        result = self.run_script(self.partial_plan([], []))
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual(self.manifest_before, self.manifest.read_bytes())
        self.assertFalse((self.root / "gradle-invocations.txt").exists())

    def test_partial_batch_requires_last_target_and_preserves_manifest_on_failure(self):
        missing = next(self.root.glob("build/rfc-evidence/downloaded/**/rfc-governance-linux-x64-*/TEST-io.github.kingsword09.kwebshell.rfc.KWebRfcGovernanceCheckerTest.xml"))
        missing.unlink()
        result = self.run_script(self.partial_plan(["0001"], ["rfc-governance"]))
        self.assertNotEqual(0, result.returncode)
        self.assertEqual(self.manifest_before, self.manifest.read_bytes())
        self.assertFalse((self.root / "gradle-invocations.txt").exists())

    def test_selected_migration_producer_still_requires_notification_preflight(self):
        self.report("linux-x64").unlink()
        result = self.run_script(self.partial_plan(["0018"], ["tray-integration", "electron-migration"]))
        self.assertNotEqual(0, result.returncode)
        self.assertIn("notifications evidence", result.stderr)
        self.assertEqual(self.manifest_before, self.manifest.read_bytes())

    def test_selected_governance_requires_actual_policy_tests_and_audit(self):
        plan = self.partial_plan(["0001"], ["rfc-governance"])
        (self.root / "build/rfc-evidence/downloaded/ci-policy-tests-fixture/tests.txt").unlink()
        result = self.run_script(plan)
        self.assertNotEqual(0, result.returncode)
        self.assertIn("CI policy tests", result.stderr)
        self.assertEqual(self.manifest_before, self.manifest.read_bytes())

    def test_unknown_rfc_and_invalid_plan_fail_before_recording(self):
        plan = self.partial_plan(["0099"], [])
        self.assertNotEqual(0, self.run_script(plan).returncode)
        plan.write_text('{"schemaVersion":1}')
        self.assertEqual(2, self.run_script(plan).returncode)
        self.assertEqual(self.manifest_before, self.manifest.read_bytes())

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
        self.assertEqual(3 * len(IMPLEMENTED), len(calls))
        self.assertEqual(["batch"], (self.root / "gradle-invocations.txt").read_text().splitlines())
        for call in [c for c in calls if c["rfc"] == "0001"]:
            self.assertEqual({"governance-contract-tests", "governance-batch-tests", "batch-plan"},
                             {a.split("=", 1)[0] for a in call["artifacts"]})
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

    def test_missing_last_target_artifact_never_starts_gradle_or_changes_manifest(self):
        path = next(self.root.glob("build/rfc-evidence/downloaded/provider-lifecycle-linux-x64-*/window-controls-report.json"))
        path.unlink()
        result = self.run_script()
        self.assertNotEqual(0, result.returncode)
        self.assertIn("Missing window-controls-report", result.stderr)
        self.assertFalse((self.root / "gradle-invocations.txt").exists())
        self.assertEqual(self.manifest_before, self.manifest.read_bytes())

    def test_paths_with_spaces_preserve_artifact_arguments(self):
        original = next(self.root.glob("build/rfc-evidence/downloaded/rfc-governance-macos-arm64-*"))
        original.rename(original.with_name(original.name + " with spaces"))
        result = self.run_script()
        self.assertEqual(0, result.returncode, result.stderr)
        calls = json.loads(self.manifest.read_text())["invocations"]
        call, = [c for c in calls if c["rfc"] == "0001" and c["target"] == "macos-arm64"]
        self.assertIn("with spaces/", call["artifacts"][0])

    def test_missing_implemented_artifact_is_still_fatal(self):
        path = next(self.root.glob("build/rfc-evidence/downloaded/rfc-governance-macos-arm64-*/TEST-io.github.kingsword09.kwebshell.rfc.KWebRfcGovernanceCheckerTest.xml"))
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
