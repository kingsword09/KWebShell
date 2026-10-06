from pathlib import Path
import unittest


ROOT = Path(__file__).resolve().parents[3]
POWERSHELL_SCRIPT = ROOT / ".github/scripts/build-and-verify-windows-msix.ps1"
SIGNING_SCRIPT = ROOT / ".github/scripts/windows-msix-signing.ps1"
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
            "import-trusted-people-certificate",
            "signtool-sign",
            "install-tampered-msix",
            "install-msix",
            "activate-installed-aumid",
            "observe-installed-window-cef",
            "normal-shutdown",
            "uninstall-msix",
        ):
            with self.subTest(phase=phase):
                self.assertIn(phase, source)
        self.assertIn('Set-MsixPhase "$Phase.start"', source)
        self.assertIn('Set-MsixPhase "$Phase.complete"', source)
        self.assertIn('Add-AppxPackage -Path $tamperedPackage', source)

    def test_appx_deployment_trust_uses_machine_store_and_cleans_same_thumbprint(self):
        source = POWERSHELL_SCRIPT.read_text()
        signing = SIGNING_SCRIPT.read_text()
        self.assertIn('Add-KWebMsixTrustedCertificate -CertificatePath $cerPath', source)
        self.assertIn('StoreLocation]::LocalMachine', signing)
        self.assertIn('"TrustedPeople",', signing)
        self.assertNotIn('StoreLocation]::CurrentUser', signing)
        self.assertNotIn('"Root",', signing)
        self.assertNotIn('certutil.exe', source + signing)
        self.assertIn('@("CurrentUser\\My", "LocalMachine\\TrustedPeople")', source)
        self.assertIn('$certificatePath = "Cert:\\$store\\$($publisherCertificate.Thumbprint)"', source)
        self.assertIn('Remove-Item -LiteralPath $certificatePath -DeleteKey', source)

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

    def test_native_activation_has_no_explorer_or_executable_fallback(self):
        source = POWERSHELL_SCRIPT.read_text()
        helper = (POWERSHELL_SCRIPT.parent / "windows-msix-activation.ps1").read_text()
        self.assertIn("Invoke-KWebMsixActivation -ApplicationUserModelId $aumid", source)
        self.assertIn("$activation.signedHresult -ne 0 -or $activation.processId -eq 0", source)
        self.assertNotIn("shell:AppsFolder", source)
        self.assertNotIn("Start-Process", source)
        self.assertIn("[PreserveSig] int ActivateApplication", helper)
        self.assertIn("CoCreateInstance(ref classId, IntPtr.Zero, 4", helper)
        self.assertIn("manager.ActivateApplication(appId, null, 2", helper)
        self.assertIn("Marshal.ReleaseComObject(manager)", helper)

    def test_diagnostics_precede_cleanup_and_cannot_replace_primary_failure(self):
        source = POWERSHELL_SCRIPT.read_text()
        finalizer = source[source.index("finally {\n    if ($null -ne $activationStartedAt)"):]
        self.assertLess(finalizer.index("Save-KWebMsixActivationDiagnostics"), finalizer.index("$process.Kill()"))
        self.assertLess(finalizer.index("Save-KWebMsixActivationDiagnostics"), finalizer.index("Remove-AppxPackage"))
        self.assertLess(finalizer.index("Save-KWebMsixActivationDiagnostics"), finalizer.index("Remove-Item -LiteralPath $applicationDataRoot"))
        self.assertIn("catch { $evidence.activationDiagnosticsFailure = $_.Exception.Message }", finalizer)
        self.assertIn("$evidence.launcherWindowObserved = $true", source[source.index("while ([DateTime]::UtcNow -lt $deadline)"):])


if __name__ == "__main__":
    unittest.main()
