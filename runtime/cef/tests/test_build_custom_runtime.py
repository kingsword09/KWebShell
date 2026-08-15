import importlib.util
from pathlib import Path
import subprocess
import tempfile
import unittest


SCRIPT = Path(__file__).resolve().parents[1] / "build-custom-runtime.py"
SPEC = importlib.util.spec_from_file_location("kweb_cef_builder", SCRIPT)
BUILDER = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(BUILDER)


class CustomRuntimeBuildToolTest(unittest.TestCase):
    def test_detects_only_exact_supported_hosts(self):
        self.assertEqual("macos-arm64", BUILDER.detect_host_target("Darwin", "arm64"))
        self.assertEqual("windows-x64", BUILDER.detect_host_target("Windows", "AMD64"))
        self.assertEqual("linux-x64", BUILDER.detect_host_target("Linux", "x86_64"))
        with self.assertRaisesRegex(BUILDER.BuildFailure, "Unsupported CEF build host"):
            BUILDER.detect_host_target("Plan9", "mips")

    def test_loads_the_strict_pinned_manifest(self):
        manifest = BUILDER.load_manifest()
        self.assertEqual("be1e15d8892c064f0299ba18350236a9b272ce7f", manifest["cefCommit"])
        self.assertEqual("28a7a6c409e03c701d3474ef9e3b1f0be6249039", manifest["chromiumCommit"])
        self.assertEqual("94e89b10b92cc9d6e58fc8d1b6474b7d29e8a114", manifest["depotToolsCommit"])
        self.assertRegex(manifest["sisoVersion"], r"^git_revision:[0-9a-f]{40}$")
        self.assertEqual(["is_official_build=true", "symbol_level=0"], manifest["gnDefines"])

    def test_writes_a_pinned_gclient_solution(self):
        with tempfile.TemporaryDirectory(prefix="kweb-gclient-test-") as temporary:
            root = Path(temporary)
            manifest = BUILDER.load_manifest()
            BUILDER.write_pinned_gclient(root, manifest)
            contents = (root / ".gclient").read_text(encoding="utf-8")
            compile(contents, str(root / ".gclient"), "exec")
            self.assertIn(f"src.git@{manifest['chromiumVersion']}", contents)
            self.assertIn(manifest["sisoVersion"], contents)
            self.assertNotIn("'siso_version': 'latest'", contents)

    def test_wraps_windows_batch_commands_without_changing_native_executables(self):
        command = [Path("C:/depot_tools/autoninja.bat"), "-C", Path("C:/src/out/Release_GN_x64")]
        wrapped = BUILDER.command_for_host(command, host_os_name="nt")
        self.assertEqual(["cmd.exe", "/d", "/s", "/c"], wrapped[:4])
        self.assertEqual(subprocess.list2cmdline([str(value) for value in command]), wrapped[4])
        self.assertEqual([str(value) for value in command], BUILDER.command_for_host(command, "posix"))

    def test_parses_exact_adapter_exports_for_each_binary_format(self):
        expected = {
            "cef_kweb_extension_abi_fingerprint",
            "cef_kweb_extension_start",
        }
        macho = "0001 T _cef_kweb_extension_start\n0002 T _cef_kweb_extension_abi_fingerprint\n"
        elf = "0001 T cef_kweb_extension_start\n0002 T cef_kweb_extension_abi_fingerprint\n"
        pe = "Export {\n  Name: cef_kweb_extension_start\n}\nExport {\n  Name: cef_kweb_extension_abi_fingerprint\n}\n"
        self.assertEqual(expected, BUILDER.parse_adapter_exports(macho, "macos-arm64"))
        self.assertEqual(expected, BUILDER.parse_adapter_exports(elf, "linux-x64"))
        self.assertEqual(expected, BUILDER.parse_adapter_exports(pe, "windows-x64"))

    def test_build_plan_contains_every_platform_required_target(self):
        self.assertEqual(("cefclient",), BUILDER.TARGET_CONFIGURATION["macos-arm64"]["build_targets"])
        self.assertEqual(
            ("cefclient", "bootstrap", "bootstrapc"),
            BUILDER.TARGET_CONFIGURATION["windows-x64"]["build_targets"],
        )
        self.assertEqual(
            ("cefclient", "chrome_sandbox"),
            BUILDER.TARGET_CONFIGURATION["linux-x64"]["build_targets"],
        )

    def test_rejects_invalid_build_options_before_bootstrap(self):
        with self.assertRaisesRegex(BUILDER.BuildFailure, "--jobs must be positive"):
            BUILDER.validate_build_options("macos-arm64", 0, False)
        with self.assertRaisesRegex(BUILDER.BuildFailure, "valid only for linux-x64"):
            BUILDER.validate_build_options("windows-x64", 1, True)
        BUILDER.validate_build_options("linux-x64", 1, True)

    def test_rejects_repository_and_nonempty_work_directories(self):
        with self.assertRaisesRegex(BUILDER.BuildFailure, "outside the repository"):
            BUILDER.ensure_empty_external_directory(BUILDER.REPOSITORY_ROOT / "runtime", "work")
        with tempfile.TemporaryDirectory(prefix="kweb-builder-test-") as temporary:
            root = Path(temporary)
            (root / "occupied").write_text("state", encoding="utf-8")
            with self.assertRaisesRegex(BUILDER.BuildFailure, "absent or empty"):
                BUILDER.ensure_empty_external_directory(root, "work")


if __name__ == "__main__":
    unittest.main()
