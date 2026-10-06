import hashlib
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
import zipfile

from _msix_fixture import REVISION, create_fixture, write_report


SCRIPT = Path(__file__).resolve().parents[1] / "retain_windows_msix_evidence.py"
spec = importlib.util.spec_from_file_location("msix_evidence", SCRIPT)
collector = importlib.util.module_from_spec(spec)
spec.loader.exec_module(collector)


class WindowsMsixEvidenceTest(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory(prefix="kweb-msix-proof-test-")
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name)
        self.artifacts = self.root / "application-package-windows-x64-revision"
        self.package, self.report_path, self.report, self.contents = create_fixture(self.artifacts)
        self.output = self.root / "proof.zip"
        self.smoke = self.root / "windows-launcher-smoke-windows-x64-revision/smoke-summary.txt"
        self.failed_start = self.smoke.with_name("failed-start-summary.txt")

    def retain(self, revision=REVISION):
        return collector.retain(self.package, self.report_path, self.output, revision, self.smoke, self.failed_start)

    def assert_rejected(self, message):
        with self.assertRaisesRegex(ValueError, message):
            self.retain()
        self.assertFalse(self.output.exists())

    def test_retains_exact_signed_entries_and_raw_report_deterministically(self):
        package_before = self.package.read_bytes()
        report_before = self.report_path.read_bytes()
        index = self.retain()
        first = self.output.read_bytes()
        self.retain()
        self.assertEqual(first, self.output.read_bytes())
        self.assertEqual(package_before, self.package.read_bytes())
        self.assertEqual(report_before, self.report_path.read_bytes())
        self.assertEqual(len(package_before), index["msixSizeBytes"])
        self.assertEqual(hashlib.sha256(package_before).hexdigest(), index["msixSha256"])
        with zipfile.ZipFile(self.output) as proof:
            self.assertEqual(set("msix/" + name for name in self.contents) | {
                "index.json", "verification/application-package-report.json",
                "verification/windows-launcher-smoke.txt", "verification/windows-launcher-failed-start.txt",
            }, set(proof.namelist()))
            for name, data in self.contents.items():
                self.assertEqual(data, proof.read("msix/" + name))
            self.assertEqual(report_before, proof.read("verification/application-package-report.json"))
            self.assertEqual(self.smoke.read_bytes(), proof.read("verification/windows-launcher-smoke.txt"))
            self.assertEqual(self.failed_start.read_bytes(), proof.read("verification/windows-launcher-failed-start.txt"))
            self.assertEqual(index, json.loads(proof.read("index.json")))

    def test_every_native_gate_must_have_passed(self):
        original = dict(self.report)
        for field in ("status", "packageSignatureVerification", "sdkPackageValidation", "signatureVerification",
                      "tamperedPackageRejected", "installed", "nativeActivation", "normalShutdown", "uninstalled",
                      "applicationDataCleanup", "developerInstallPolicyRestored", "launcherWindowObserved",
                      "cefSubprocessObserved", "packageSignatureEntryCount", "launcherExitCode",
                      "remainingPackageProcessCount", "schemaVersion", "target", "failure"):
            with self.subTest(field=field):
                write_report(self.report_path, {**original, field: "FAIL"})
                self.assert_rejected("Windows MSIX")
        write_report(self.report_path, original)

    def test_native_activation_requires_exact_success_and_positive_dword_pid(self):
        for field, values in (
            ("nativeActivation", (None, "NOT_RUN", "FAIL", True)),
            ("activationHresult", (None, "0x80070005", "0", 0, True)),
            ("activationProcessId", (None, 0, -1, True, "100", 1.0, 0x100000000)),
        ):
            for value in values:
                with self.subTest(field=field, value=value):
                    write_report(self.report_path, {**self.report, field: value})
                    self.assert_rejected("Windows MSIX")
            missing = dict(self.report)
            del missing[field]
            write_report(self.report_path, missing)
            self.assert_rejected("Windows MSIX")
        write_report(self.report_path, self.report)

    def test_mutated_package_wrong_revision_and_wrong_resource_hashes_fail(self):
        with self.assertRaisesRegex(ValueError, "sourceRevision"):
            self.retain("b" * 40)
        for field in ("manifestSha256", "assetSha256", "temurinLicenseSha256", "temurinAssemblyExceptionSha256"):
            with self.subTest(field=field):
                write_report(self.report_path, {**self.report, field: "0" * 64})
                self.assert_rejected("differ")
        write_report(self.report_path, self.report)
        self.package.write_bytes(self.package.read_bytes() + b"altered")
        self.assert_rejected("package digest")

    def test_missing_signature_or_license_is_not_a_complete_proof(self):
        for name in ("AppxSignature.p7x", "AppxBlockMap.xml", "runtime/legal/java.base/ASSEMBLY_EXCEPTION"):
            with self.subTest(entry=name):
                self.package, self.report_path, self.report, self.contents = create_fixture(self.artifacts, omitted=(name,))
                self.assert_rejected("missing required proof entry")

    def test_case_ambiguous_and_traversal_entries_are_rejected(self):
        for name in ("APPXMANIFEST.XML", "../escape"):
            with self.subTest(entry=name):
                self.package, self.report_path, self.report, self.contents = create_fixture(self.artifacts)
                with zipfile.ZipFile(self.package, "a") as package:
                    package.writestr(name, b"invalid entry")
                digest = hashlib.sha256(self.package.read_bytes()).hexdigest()
                write_report(self.report_path, {**self.report, "msixSha256": digest, "packageSha256": digest})
                self.assert_rejected("entry")

    def test_oversized_proof_does_not_replace_an_existing_output(self):
        self.output.write_bytes(b"existing-proof")
        with patch.object(collector, "MAX_PROOF_BYTES", 1):
            with self.assertRaisesRegex(ValueError, "size limit"):
                self.retain()
        self.assertEqual(b"existing-proof", self.output.read_bytes())
        self.assertEqual([], list(self.root.glob(".windows-msix-proof-*")))

    def test_collection_requires_one_package_and_one_report(self):
        self.assertEqual(tuple(p.resolve() for p in (self.package, self.report_path, self.smoke, self.failed_start)), collector.locate_inputs(self.root))
        duplicate = self.artifacts / "duplicate.msix"
        duplicate.write_bytes(self.package.read_bytes())
        with self.assertRaisesRegex(ValueError, "exactly one"):
            collector.locate_inputs(self.root)
        duplicate.unlink()
        self.report_path.unlink()
        with self.assertRaisesRegex(ValueError, "exactly one"):
            collector.locate_inputs(self.root)

    def test_both_launcher_shutdown_summaries_are_required(self):
        for path, before, after in (
            (self.smoke, "launcherExit=0", "launcherExit=1"),
            (self.smoke, "windowAlive=false", "windowAlive=true"),
            (self.smoke, "windowExit=0", "windowExit=1"),
            (self.smoke, "packageProcesses=", "packageProcesses=remaining-process"),
            (self.failed_start, "exitCode=1", "exitCode=0"),
            (self.failed_start, "profileFailureObserved=true", "profileFailureObserved=false"),
            (self.failed_start, "processesDrained=true", "processesDrained=false"),
        ):
            with self.subTest(summary=path.name, field=before):
                original = path.read_text()
                path.write_text(original.replace(before, after))
                self.assert_rejected("Windows launcher")
                path.write_text(original)


if __name__ == "__main__":
    unittest.main()
