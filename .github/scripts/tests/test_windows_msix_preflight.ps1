$ErrorActionPreference = "Stop"
$scriptPath = Join-Path $PSScriptRoot "../build-and-verify-windows-msix.ps1"
. (Join-Path $PSScriptRoot "../windows-msix-signing.ps1")
$testRoot = Join-Path ([IO.Path]::GetTempPath()) ("kweb-msix-preflight-" + [guid]::NewGuid().ToString('N'))
$savedEnvironment = @{}
foreach ($name in @("OS", "GITHUB_ACTIONS", "RUNNER_ENVIRONMENT", "LOCALAPPDATA", "TEMP")) {
    $savedEnvironment[$name] = [Environment]::GetEnvironmentVariable($name, "Process")
}
$windowsHost = [Runtime.InteropServices.RuntimeInformation]::IsOSPlatform([Runtime.InteropServices.OSPlatform]::Windows)
$cases = @(
    @{ name = "non-windows"; os = "Darwin"; actions = "true"; runner = "github-hosted"; message = "requires a Windows host" },
    @{ name = "outside-actions"; os = "Windows_NT"; actions = "false"; runner = "github-hosted"; message = "ephemeral GitHub-hosted runner" },
    @{ name = "self-hosted"; os = "Windows_NT"; actions = "true"; runner = "self-hosted"; message = "ephemeral GitHub-hosted runner" }
)
if ($windowsHost) {
    $cases += @{ name = "existing-data"; os = "Windows_NT"; actions = "true"; runner = "github-hosted"; message = "clean KWebShell user-data directory" }
}
try {
    New-Item -ItemType Directory -Path $testRoot | Out-Null
    foreach ($case in $cases) {
        $caseRoot = Join-Path $testRoot $case.name
        $localData = Join-Path $caseRoot "local-data"
        $existingData = Join-Path $localData "KWebShell"
        New-Item -ItemType Directory -Path $existingData -Force | Out-Null
        $sentinel = Join-Path $existingData "pre-existing-profile.txt"
        [IO.File]::WriteAllText($sentinel, "preserve-existing-data")
        $env:OS = $case.os
        $env:GITHUB_ACTIONS = $case.actions
        $env:RUNNER_ENVIRONMENT = $case.runner
        $env:LOCALAPPDATA = $localData
        $env:TEMP = $caseRoot
        $reportPath = Join-Path $caseRoot "report.json"
        $failure = $null
        try {
            & $scriptPath `
                -MetadataArchive (Join-Path $caseRoot "absent-metadata.zip") `
                -MetadataVerification (Join-Path $caseRoot "absent-proof.json") `
                -ApplicationImage (Join-Path $caseRoot "absent-app-image") `
                -OutputPackage (Join-Path $caseRoot "output.msix") `
                -ReportPath $reportPath
        }
        catch { $failure = $_.Exception.Message }
        if ($null -eq $failure -or -not $failure.Contains($case.message)) {
            throw "$($case.name): wrong preflight result: $failure"
        }
        if (-not (Test-Path -LiteralPath $sentinel -PathType Leaf) -or
            [IO.File]::ReadAllText($sentinel) -ne "preserve-existing-data") {
            throw "$($case.name): preflight cleanup changed pre-existing application data."
        }
        $report = Get-Content -LiteralPath $reportPath -Raw | ConvertFrom-Json
        if ($report.status -ne "FAIL" -or $report.installed -ne "NOT_RUN" -or
            $report.uninstalled -ne "NOT_RUN" -or $report.applicationDataCleanup -ne "NOT_RUN" -or
            $report.developerInstallPolicyRestored -ne "NOT_CHANGED") {
            throw "$($case.name): preflight failure claimed operations that must not run."
        }
        if (Test-Path -LiteralPath (Join-Path $caseRoot "output.msix")) {
            throw "$($case.name): rejected preflight published a package."
        }
        Write-Output "PASS: $($case.name) preserves pre-existing state."
    }
    Write-Output "Passed $($cases.Count) real-script MSIX preflight rejection cases."
    if ($windowsHost) {
        $certificate = $null
        try {
            $publisher = "CN=KWebShell RFC 0030 signing fixture"
            $certificate = New-KWebMsixSigningCertificate -Publisher $publisher
            if ($certificate.Subject -ne $publisher -or -not $certificate.HasPrivateKey) {
                throw "The Windows signing fixture must own a private key and match its publisher."
            }
            $eku = $certificate.Extensions | Where-Object { $_.Oid.Value -eq "2.5.29.37" }
            $usages = @($eku.EnhancedKeyUsages | ForEach-Object { $_.Value })
            $constraints = $certificate.Extensions | Where-Object { $_.Oid.Value -eq "2.5.29.19" }
            if ($usages -notcontains "1.3.6.1.5.5.7.3.3" -or $null -eq $constraints -or $constraints.CertificateAuthority) {
                throw "The Windows signing fixture must be a non-CA code-signing certificate."
            }
        }
        finally {
            if ($null -ne $certificate) {
                $certificatePath = "Cert:\CurrentUser\My\$($certificate.Thumbprint)"
                Remove-Item -LiteralPath $certificatePath -DeleteKey -Force
                $certificate.Dispose()
                if (Test-Path -LiteralPath $certificatePath) { throw "The signing fixture certificate remains after cleanup." }
            }
        }
        Write-Output "PASS: real publisher-bound code-signing certificate creation and cleanup."
    }
}
finally {
    foreach ($entry in $savedEnvironment.GetEnumerator()) {
        [Environment]::SetEnvironmentVariable($entry.Key, $entry.Value, "Process")
    }
    if (Test-Path -LiteralPath $testRoot) { Remove-Item -LiteralPath $testRoot -Recurse -Force }
}
