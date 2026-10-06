from pathlib import Path
import unittest


ROOT = Path(__file__).resolve().parents[3]
POWERSHELL_SCRIPT = ROOT / ".github/scripts/build-and-verify-windows-msix.ps1"
INTEGRATION_MAIN = ROOT / "kweb-runtime-pack/src/test/kotlin/io/github/kingsword09/kwebshell/runtime/KWebApplicationPackageIntegrationMain.kt"


class WindowsMsixExecutionGuardsTest(unittest.TestCase):
    def test_powershell_reports_each_blocking_phase(self):
        source = POWERSHELL_SCRIPT.read_text()
        for phase in (
            "makeappx-pack",
            "makeappx-unpack",
            "verify-package-archive",
            "create-signing-certificate",
            "export-signing-pfx",
            "export-signing-certificate",
            "import-root-certificate",
            "import-trusted-people-certificate",
            "signtool-sign",
            "signtool-verify",
            "signtool-verify-tampered",
            "install-msix",
            "uninstall-msix",
        ):
            with self.subTest(phase=phase):
                self.assertIn(phase, source)
        self.assertIn('Set-MsixPhase "$Phase.start"', source)
        self.assertIn('Set-MsixPhase "$Phase.complete"', source)

    def test_powershell_failure_retains_last_phase(self):
        source = POWERSHELL_SCRIPT.read_text()
        self.assertIn('$currentPhase = "initialization"', source)
        self.assertIn("Phase '$currentPhase'", source)

    def test_kotlin_process_wait_is_bounded_and_cleans_descendants(self):
        source = INTEGRATION_MAIN.read_text()
        self.assertIn("WINDOWS_MSIX_PROCESS_TIMEOUT_MINUTES = 15L", source)
        self.assertIn("process.waitFor(WINDOWS_MSIX_PROCESS_TIMEOUT_MINUTES, TimeUnit.MINUTES)", source)
        self.assertIn("process.toHandle().descendants()", source)
        self.assertNotIn("check(process.waitFor() == 0)", source)


if __name__ == "__main__":
    unittest.main()
