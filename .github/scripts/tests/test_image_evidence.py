import json
from pathlib import Path
import tempfile
import unittest
import xml.etree.ElementTree as ET

from _image_fixture import write_image_fixture
from validate_image_evidence import PROVIDERS, validate_target


class ImageEvidenceTest(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory(prefix="image-evidence-test-")
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name)
        self.files = {target: write_image_fixture(self.root, target) for target in PROVIDERS}

    def test_all_target_inputs_are_validated_without_modification(self):
        before = {p.relative_to(self.root): p.read_bytes() for p in self.root.rglob("*") if p.is_file()}
        for target in PROVIDERS:
            validate_target(self.root, target)
        self.assertEqual(before, {p.relative_to(self.root): p.read_bytes() for p in self.root.rglob("*") if p.is_file()})

    def test_provider_identity_live_handles_and_false_claims_are_rejected(self):
        for target, (provider, _, _, _) in self.files.items():
            valid = json.loads(provider.read_text())
            for field, value in (("providerId", "other"), ("target", "other"), ("liveCountAfterClose", 1),
                                 ("liveCountAfterClose", False), ("deterministicPng", "true"), ("resourceDigestChecked", False)):
                with self.subTest(target=target, field=field, value=value):
                    provider.write_text(json.dumps({**valid, field: value}))
                    with self.assertRaises(ValueError):
                        validate_target(self.root, target)
            provider.write_text(json.dumps(valid))

    def test_missing_or_changed_migration_service_and_policy_are_rejected(self):
        target = "linux-x64"
        report = self.files[target][2]
        valid = json.loads(report.read_text())
        for changed in ({**valid, "serviceContractVersions": {}}, {**valid, "policies": {}}, {**valid, "migrationStatus": "BLOCKED"}):
            report.write_text(json.dumps(changed))
            with self.assertRaises(ValueError):
                validate_target(self.root, target)

    def test_missing_cef_negative_scenario_is_rejected(self):
        target = "macos-arm64"
        report = self.files[target][1]
        value = json.loads(report.read_text())
        del value["observed"]["malformedBase64"]
        report.write_text(json.dumps(value))
        with self.assertRaises(ValueError):
            validate_target(self.root, target)

    def test_unexecuted_skipped_and_failed_tests_are_rejected(self):
        target = "windows-x64"
        path = self.root / f"image-integration-{target}-revision/image-native-tests.xml"
        for tag in ("missing", "skipped", "failure", "error"):
            suite = ET.Element("testsuite")
            if tag != "missing":
                ET.SubElement(ET.SubElement(suite, "testcase", name="kweb_image_tests"), tag)
            ET.ElementTree(suite).write(path)
            with self.assertRaises(ValueError):
                validate_target(self.root, target)

    def test_missing_duplicate_or_modified_artifacts_are_rejected(self):
        target = "linux-x64"
        provider, _, _, archive = self.files[target]
        duplicate = provider.parent / "duplicate" / provider.name
        duplicate.parent.mkdir()
        duplicate.write_bytes(provider.read_bytes())
        with self.assertRaises(ValueError):
            validate_target(self.root, target)
        duplicate.unlink()
        archive.write_bytes(archive.read_bytes() + b"modified")
        with self.assertRaises(ValueError):
            validate_target(self.root, target)
        archive.unlink()
        with self.assertRaises(ValueError):
            validate_target(self.root, target)


if __name__ == "__main__":
    unittest.main()
