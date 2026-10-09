import hashlib
import json
import os
from pathlib import Path
import subprocess
import tempfile
import unittest


ROOT = Path(__file__).resolve().parents[3]
RETAINED_JSON = "docs/rfcs/evidence/artifacts/0030/revision/windows-x64/report.json"
SOURCE_JSON = "module/config.json"
RETAINED_CLI_XML = "docs/rfcs/0005-migrate-cli-evidence/p6/run/windows-x64/report.xml"
SOURCE_XML = "module/report.xml"


class EvidenceAttributesTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(prefix="kweb-evidence-attributes-")
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.git("init", "--quiet")
        (self.root / ".gitattributes").write_bytes((ROOT / ".gitattributes").read_bytes())
        for name in (RETAINED_JSON, SOURCE_JSON, RETAINED_CLI_XML, SOURCE_XML):
            path = self.root / name
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_bytes(b"")
        self.git("add", ".gitattributes", RETAINED_JSON, SOURCE_JSON, RETAINED_CLI_XML, SOURCE_XML)

    def git(self, *arguments, check=True):
        environment = os.environ.copy()
        environment["GIT_CONFIG_NOSYSTEM"] = "1"
        environment["GIT_CONFIG_GLOBAL"] = os.devnull
        return subprocess.run(
            [
                "git", "-C", str(self.root),
                "-c", "core.autocrlf=false",
                "-c", "core.whitespace=blank-at-eol,blank-at-eof,space-before-tab",
                *arguments,
            ],
            env=environment,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            check=check,
            timeout=30,
        )

    def test_crlf_is_only_tolerated_for_retained_json(self):
        payload = b'{"status":"PASS"}\r\n'
        (self.root / RETAINED_JSON).write_bytes(payload)
        (self.root / SOURCE_JSON).write_bytes(payload)
        self.assertEqual(0, self.git("diff", "--check", "--", RETAINED_JSON).returncode)
        ordinary = self.git("diff", "--check", "--", SOURCE_JSON, check=False)
        self.assertEqual(2, ordinary.returncode)
        self.assertIn(b"trailing whitespace", ordinary.stdout)

    def test_real_trailing_spaces_in_retained_json_still_fail(self):
        (self.root / RETAINED_JSON).write_bytes(b'{"status":"PASS"} \r\n')
        result = self.git("diff", "--check", "--", RETAINED_JSON, check=False)
        self.assertEqual(2, result.returncode)
        self.assertIn(b"trailing whitespace", result.stdout)

    def test_staging_does_not_normalize_retained_json_bytes(self):
        payload = b'{\r\n  "status":"PASS"\r\n}\r\n'
        (self.root / RETAINED_JSON).write_bytes(payload)
        self.git("add", RETAINED_JSON)
        indexed = self.git("show", ":" + RETAINED_JSON).stdout
        self.assertEqual(payload, indexed)
        self.assertEqual(hashlib.sha256(payload).hexdigest(), hashlib.sha256(indexed).hexdigest())
        self.assertEqual(0, self.git("diff", "--cached", "--check").returncode)

    def test_real_windows_report_keeps_recorded_native_digest(self):
        manifest = json.loads((ROOT / "docs/rfcs/evidence/manifest.json").read_text())
        record = next(
            entry for entry in manifest["records"]
            if entry["rfcId"] == "0030" and entry["target"] == "windows-x64"
        )
        artifact = next(entry for entry in record["artifacts"] if entry["name"] == "application-package")
        payload = (ROOT / artifact["path"]).read_bytes()
        self.assertEqual(artifact["sha256"], hashlib.sha256(payload).hexdigest())
        self.assertEqual("windows-x64", json.loads(payload)["target"])
        indexed = subprocess.run(
            ["git", "-C", str(ROOT), "show", ":" + artifact["path"]],
            stdout=subprocess.PIPE, stderr=subprocess.PIPE, check=True, timeout=30,
        ).stdout
        self.assertEqual(payload, indexed)

    def test_crlf_is_only_tolerated_for_retained_cli_xml(self):
        payload = b'<testsuite tests="1" failures="0"/>\r\n'
        (self.root / RETAINED_CLI_XML).write_bytes(payload)
        (self.root / SOURCE_XML).write_bytes(payload)
        self.assertEqual(0, self.git("diff", "--check", "--", RETAINED_CLI_XML).returncode)
        ordinary = self.git("diff", "--check", "--", SOURCE_XML, check=False)
        self.assertEqual(2, ordinary.returncode)
        self.assertIn(b"trailing whitespace", ordinary.stdout)

    def test_real_trailing_spaces_in_retained_cli_xml_still_fail(self):
        (self.root / RETAINED_CLI_XML).write_bytes(b'<testsuite tests="1"/> \r\n')
        result = self.git("diff", "--check", "--", RETAINED_CLI_XML, check=False)
        self.assertEqual(2, result.returncode)
        self.assertIn(b"trailing whitespace", result.stdout)

    def test_staging_does_not_normalize_real_windows_cli_xml_bytes(self):
        relative = Path("docs/rfcs/0005-migrate-cli-evidence/p6/37759212962/windows-x64")
        retained = ROOT / relative
        for report in sorted(retained.glob("*.xml")):
            with self.subTest(report=report.name):
                payload = report.read_bytes()
                self.assertIn(b"\r\n", payload)
                staged_path = relative / report.name
                destination = self.root / staged_path
                destination.parent.mkdir(parents=True, exist_ok=True)
                destination.write_bytes(payload)
                self.git("add", str(staged_path))
                self.assertEqual(payload, self.git("show", ":" + str(staged_path)).stdout)
        self.assertEqual(2, len(list(retained.glob("*.xml"))))
        self.assertEqual(0, self.git("diff", "--cached", "--check").returncode)


if __name__ == "__main__":
    unittest.main()
