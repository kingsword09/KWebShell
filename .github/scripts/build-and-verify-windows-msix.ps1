param(
    [Parameter(Mandatory = $true)][string] $MetadataArchive,
    [Parameter(Mandatory = $true)][string] $MetadataVerification,
    [Parameter(Mandatory = $true)][string] $ApplicationImage,
    [Parameter(Mandatory = $true)][string] $OutputPackage,
    [Parameter(Mandatory = $true)][string] $ReportPath,
    [Parameter(Mandatory = $false)][string] $SourceRevision = ""
)

$ErrorActionPreference = "Stop"
. (Join-Path $PSScriptRoot "windows-msix-signing.ps1")

function Invoke-NativeTool {
    param([string] $Executable, [string[]] $Arguments)
    & $Executable @Arguments
    if ($LASTEXITCODE -ne 0) {
        throw "$([IO.Path]::GetFileName($Executable)) failed with exit code $LASTEXITCODE."
    }
}

function Find-WindowsSdkTool {
    param([string] $Name)
    $onPath = Get-Command $Name -ErrorAction SilentlyContinue
    if ($null -ne $onPath) { return $onPath.Source }

    $sdkRoot = Join-Path ${env:ProgramFiles(x86)} "Windows Kits\10\bin"
    $candidate = Get-ChildItem -Path $sdkRoot -Filter $Name -File -Recurse -ErrorAction SilentlyContinue |
        Where-Object { $_.Directory.Name -eq "x64" } |
        Sort-Object { [version]$_.Directory.Parent.Name } -Descending |
        Select-Object -First 1
    if ($null -eq $candidate) { throw "Windows SDK tool '$Name' was not found on PATH or under '$sdkRoot'." }
    return $candidate.FullName
}

function Get-PackageProcesses {
    param([string] $PackageRoot)
    $prefix = [IO.Path]::GetFullPath($PackageRoot).TrimEnd([IO.Path]::DirectorySeparatorChar) + [IO.Path]::DirectorySeparatorChar
    foreach ($entry in (Get-CimInstance -ClassName Win32_Process -Property ProcessId, ExecutablePath)) {
        if (-not $entry.ExecutablePath -or -not ([IO.Path]::GetFullPath($entry.ExecutablePath)).StartsWith($prefix, [StringComparison]::OrdinalIgnoreCase)) { continue }
        $candidate = Get-Process -Id $entry.ProcessId -ErrorAction SilentlyContinue
        if ($null -eq $candidate) { continue }
        try {
            $candidate.EnableRaisingEvents = $true
            if ($candidate.HasExited) { continue }
            $actualPath = $candidate.Path
            if (-not $actualPath) { throw "Unable to inspect a live package process: $($candidate.Id)" }
            if (([IO.Path]::GetFullPath($actualPath)).StartsWith($prefix, [StringComparison]::OrdinalIgnoreCase)) {
                $candidate
            }
        }
        catch {
            if (-not $candidate.HasExited) { throw }
        }
    }
}

function Get-Sha256 {
    param([string] $Path)
    return (Get-FileHash -LiteralPath $Path -Algorithm SHA256).Hash.ToLowerInvariant()
}

function Get-TreeSha256 {
    param([string] $Root)
    $rootPath = [IO.Path]::GetFullPath($Root).TrimEnd([IO.Path]::DirectorySeparatorChar) + [IO.Path]::DirectorySeparatorChar
    $lines = [Collections.Generic.List[string]]::new()
    Get-ChildItem -LiteralPath $Root -File -Recurse | Sort-Object { $_.FullName.Substring($rootPath.Length).Replace('\', '/') } | ForEach-Object {
        $relative = $_.FullName.Substring($rootPath.Length).Replace('\', '/')
        $lines.Add("$relative`t$($_.Length)`t$(Get-Sha256 $_.FullName)")
    }
    $inputBytes = [Text.Encoding]::UTF8.GetBytes(($lines -join "`n") + "`n")
    $algorithm = [Security.Cryptography.SHA256]::Create()
    try {
        $hash = $algorithm.ComputeHash($inputBytes)
        return (($hash | ForEach-Object { $_.ToString('x2') }) -join '')
    }
    finally { $algorithm.Dispose() }
}

function Get-AppxVersion {
    param([string] $Version)
    $parts = [regex]::Matches($Version, '\d+') | Select-Object -First 3 | ForEach-Object { [int]$_.Value }
    while ($parts.Count -lt 3) { $parts += 0 }
    return "$($parts[0]).$($parts[1]).$($parts[2]).0"
}

$metadata = [IO.Path]::GetFullPath($MetadataArchive)
$metadataProofPath = [IO.Path]::GetFullPath($MetadataVerification)
$image = [IO.Path]::GetFullPath($ApplicationImage)
$packagePath = [IO.Path]::GetFullPath($OutputPackage)
$report = [IO.Path]::GetFullPath($ReportPath)
$temporaryRoot = Join-Path $env:TEMP ("kwebshell-msix-" + [guid]::NewGuid().ToString('N'))
$recordsRoot = Join-Path $temporaryRoot "records"
$stageRoot = Join-Path $temporaryRoot "stage"
$unpackedRoot = Join-Path $temporaryRoot "unpacked"
$pfxPath = Join-Path $temporaryRoot "test-signing.pfx"
$cerPath = Join-Path $temporaryRoot "test-signing.cer"
$tamperedPackage = Join-Path $temporaryRoot "tampered.msix"
$publisherCertificate = $null
$installedPackage = $null
$installRoot = $null
$expectedPackageName = $null
$installationAttempted = $false
$applicationDataOwned = $false
$mainProcess = $null
$cefProcess = $null
$developerInstallPolicy = $null
$developerInstallKeyExisted = $false
$developerInstallPolicyTouched = $false
$cleanupErrors = [Collections.Generic.List[string]]::new()
$applicationDataRoot = if ($env:LOCALAPPDATA) { Join-Path ([IO.Path]::GetFullPath($env:LOCALAPPDATA)) "KWebShell" } else { $null }

$evidence = [ordered]@{
    schemaVersion = 1
    target = "windows-x64"
    format = "WINDOWS_MSIX"
    signingMode = "TEST"
    sourceRevision = $SourceRevision
    applicationId = $null
    productVersion = $null
    manifestSha256 = $null
    packageSha256 = $null
    status = "FAIL"
    applicationImageTreeSha256 = $null
    stagedPayloadTreeSha256 = $null
    runtimeReleaseSha256 = $null
    temurinLicenseSha256 = $null
    temurinAssemblyExceptionSha256 = $null
    packageSignatureVerification = "NOT_RUN"
    packageSignatureEntryCount = 0
    assetSha256 = [ordered]@{}
    metadataArchiveSha256 = $null
    msixSha256 = $null
    packageIdentityName = $null
    packagePublisher = $null
    packageVersion = $null
    packageDependencies = @()
    platformSignatureStatus = "NOT_RUN"
    platformRegistrationDigest = $null
    packageFamilyName = $null
    packageFullName = $null
    applicationUserModelId = $null
    signingCertificateSubject = $null
    resolvedPaths = [ordered]@{
        installRoot = $null
        launcher = $null
        launcherJar = $null
        javaRuntime = $null
        temurinLicense = $null
        temurinAssemblyException = $null
        cefSubprocess = $null
        nativeEngine = $null
        lifecycleLibrary = $null
    }
    windowsSdk = [ordered]@{ makeAppx = $null; signTool = $null }
    sdkPackageValidation = "NOT_RUN"
    signatureVerification = "NOT_RUN"
    tamperedPackageRejected = "NOT_RUN"
    installed = "NOT_RUN"
    developerInstallPolicyRestored = "NOT_RUN"
    launcherProcessId = $null
    launcherExitCode = $null
    remainingPackageProcessCount = $null
    launcherWindowObserved = $false
    cefSubprocessObserved = $false
    normalShutdown = "NOT_RUN"
    uninstalled = "NOT_RUN"
    applicationDataCleanup = "NOT_RUN"
    failure = $null
}

try {
    if (-not $env:OS -or $env:OS -ne "Windows_NT") { throw "MSIX packaging requires a Windows host." }
    if ($env:GITHUB_ACTIONS -ne "true" -or $env:RUNNER_ENVIRONMENT -ne "github-hosted") {
        throw "This install/uninstall test may run only on an ephemeral GitHub-hosted runner."
    }
    $hostArchitecture = [Runtime.InteropServices.RuntimeInformation]::OSArchitecture.ToString()
    if ($hostArchitecture -ne "X64") { throw "MSIX packaging requires a Windows x64 runner; observed $hostArchitecture." }
    if ($null -eq $applicationDataRoot -or (Test-Path -LiteralPath $applicationDataRoot)) {
        throw "The hosted runner must have a clean KWebShell user-data directory before the package test."
    }
    $applicationDataOwned = $true
    foreach ($required in @($metadata, $image)) {
        if (-not (Test-Path -LiteralPath $required -PathType Container) -and -not (Test-Path -LiteralPath $required -PathType Leaf)) {
            throw "Required input does not exist: $required"
        }
    }

    $makeAppx = Find-WindowsSdkTool "makeappx.exe"
    $signTool = Find-WindowsSdkTool "signtool.exe"
    $evidence.windowsSdk.makeAppx = $makeAppx
    $evidence.windowsSdk.signTool = $signTool
    $evidence.metadataArchiveSha256 = Get-Sha256 $metadata
    $metadataProof = Get-Content -LiteralPath $metadataProofPath -Raw -Encoding UTF8 | ConvertFrom-Json
    if ($metadataProof.status -ne "PASS" -or $metadataProof.packageSignatureVerification -ne "PASS" -or
        $metadataProof.metadataArchiveSha256 -ne $evidence.metadataArchiveSha256) {
        throw "The metadata archive does not match the independent JVM verification proof."
    }

    New-Item -ItemType Directory -Path $temporaryRoot, $recordsRoot, $stageRoot -Force | Out-Null
    Expand-Archive -LiteralPath $metadata -DestinationPath $recordsRoot
    Copy-Item -Path (Join-Path $image "*") -Destination $stageRoot -Recurse -Force
    $evidence.applicationImageTreeSha256 = Get-TreeSha256 $stageRoot
    Copy-Item -Path (Join-Path $recordsRoot "*") -Destination $stageRoot -Recurse -Force
    $recordPaths = @(
        "application/manifest.json", "application/package.json", "application/packaged-state.json",
        "application/registration.json", "application/capabilities.json", "application/sbom.json",
        "signatures/platform.json", "signatures/package.json", "signatures/package.ed25519",
        "runtime/release.pack.zip"
    )
    foreach ($relative in $recordPaths) {
        if (-not (Test-Path -LiteralPath (Join-Path $stageRoot $relative.Replace('/', '\')) -PathType Leaf)) {
            throw "The metadata archive lacks signed application package record '$relative'."
        }
    }
    $applicationManifest = Get-Content -LiteralPath (Join-Path $stageRoot "application/manifest.json") -Raw -Encoding UTF8 | ConvertFrom-Json
    $packageRecord = Get-Content -LiteralPath (Join-Path $stageRoot "application/package.json") -Raw -Encoding UTF8 | ConvertFrom-Json

    $requiredFiles = @(
        "AppxManifest.xml",
        "KWebShell.exe",
        "app/KWebShell.cfg",
        "app/kweb-application-launcher-$($applicationManifest.productVersion).jar",
        "runtime/bin/java.exe",
        "runtime/release",
        "runtime/release.pack.zip",
        "cef/KWebShellCef.exe",
        "cef/libcef.dll",
        "native/kwebshell_engine.dll",
        "native/kwebshell_application_lifecycle.dll",
        "runtime/legal/java.base/LICENSE",
        "runtime/legal/java.base/ASSEMBLY_EXCEPTION",
        "licenses/CEF-LICENSE.txt",
        "licenses/CEF-CREDITS.html",
        "Assets/Square44x44Logo.png",
        "Assets/Square150x150Logo.png",
        "Assets/Square310x310Logo.png",
        "Assets/Wide310x150Logo.png",
        "Assets/StoreLogo.png",
        "application/manifest.json",
        "application/package.json"
    )
    foreach ($relative in $requiredFiles) {
        $candidate = Join-Path $stageRoot $relative.Replace('/', '\')
        if (-not (Test-Path -LiteralPath $candidate -PathType Leaf)) { throw "MSIX staging input is missing: $relative" }
    }
    $runtime = Get-Content -LiteralPath (Join-Path $stageRoot "runtime/release") -Raw
    if ($runtime -notmatch 'JAVA_VERSION="25\.0\.4\.1"' -or $runtime -notmatch 'OS_ARCH="x86_64"') {
        throw "The app-image does not contain the pinned Temurin 25.0.4 Windows x64 runtime."
    }
    $temurinLicensePath = Join-Path $stageRoot "runtime/legal/java.base/LICENSE"
    $temurinAssemblyExceptionPath = Join-Path $stageRoot "runtime/legal/java.base/ASSEMBLY_EXCEPTION"
    $evidence.temurinLicenseSha256 = Get-Sha256 $temurinLicensePath
    $evidence.temurinAssemblyExceptionSha256 = Get-Sha256 $temurinAssemblyExceptionPath

    $appx = [xml](Get-Content -LiteralPath (Join-Path $stageRoot "AppxManifest.xml") -Raw -Encoding UTF8)
    $expectedPackageName = $applicationManifest.targets.'windows-x64'.packageIdentityName
    if (Get-AppxPackage -Name $expectedPackageName | Where-Object { $_.Name -eq $expectedPackageName }) {
        throw "The hosted runner already has the declared package installed; refusing to replace it."
    }
    $expectedPublisher = "CN=$($applicationManifest.publisher)"
    $expectedAppId = $applicationManifest.mainExecutable
    $expectedVersion = Get-AppxVersion $applicationManifest.productVersion
    $platformSignature = Get-Content -LiteralPath (Join-Path $stageRoot "signatures/platform.json") -Raw -Encoding UTF8 | ConvertFrom-Json
    $registrationDigest = Get-Sha256 (Join-Path $stageRoot "application/registration.json")
    if ($platformSignature.schemaVersion -ne 1 -or $platformSignature.target -ne "windows-x64" -or
        $platformSignature.format -ne "windows-msix" -or $platformSignature.identity -ne $expectedPackageName -or
        $platformSignature.signer -ne $expectedPublisher -or $platformSignature.registrationDigest -ne $registrationDigest -or
        $platformSignature.signatureStatus -ne "VERIFIED" -or $platformSignature.mode -ne "TEST") {
        throw "The signed platform record does not match the Windows x64 identity, test signer, or registration metadata."
    }
    $signatureStatement = Get-Content -LiteralPath (Join-Path $stageRoot "signatures/package.json") -Raw -Encoding UTF8 | ConvertFrom-Json
    $signedFiles = [Collections.Generic.List[string]]::new()
    foreach ($recordEntry in $signatureStatement.entrySha256.PSObject.Properties) {
        $entryPath = Join-Path $stageRoot $recordEntry.Name.Replace('/', '\')
        if (-not (Test-Path -LiteralPath $entryPath -PathType Leaf)) { throw "Signed package entry is missing: $($recordEntry.Name)" }
        if ((Get-Sha256 $entryPath) -ne $recordEntry.Value) { throw "Signed package entry digest mismatch: $($recordEntry.Name)" }
        $signedFiles.Add($recordEntry.Name)
    }
    if ($signedFiles.Count -lt 1 -or $metadataProof.signedEntryCount -ne $signedFiles.Count) {
        throw "The metadata archive signed entry set differs from the JVM verification proof."
    }
    $evidence.packageSignatureVerification = "PASS"
    $evidence.packageSignatureEntryCount = $signedFiles.Count
    if ($appx.Package.Identity.Name -ne $expectedPackageName -or $appx.Package.Identity.Publisher -ne $expectedPublisher -or $appx.Package.Identity.ProcessorArchitecture -ne "x64") {
        throw "AppxManifest identity or publisher does not match the application manifest."
    }
    if ($appx.Package.Identity.Version -ne $expectedVersion) { throw "AppxManifest version does not match the product version." }
    if ($appx.Package.Applications.Application.Id -ne $expectedAppId -or $appx.Package.Applications.Application.Executable -ne "KWebShell.exe") {
        throw "The MSIX application identity must activate the JVM/Compose KWebShell.exe entry point."
    }
    if (-not $appx.OuterXml.Contains('runFullTrust')) { throw "The desktop MSIX must declare the runFullTrust capability." }
    if ($appx.OuterXml.Contains("WindowsAppRuntime") -or $appx.OuterXml.Contains("Microsoft.WindowsAppSDK")) {
        throw "The RFC 0030 app-image must not declare the out-of-scope Windows App SDK dependency."
    }
    $packageDependencies = @($appx.Package.Dependencies.PackageDependency | ForEach-Object { $_.Name } | Where-Object { $_ })
    if ($packageDependencies | Where-Object { $_ -match 'WindowsAppRuntime|WindowsAppSDK' }) {
        throw "The MSIX declares an out-of-scope Windows App SDK package dependency."
    }
    $evidence.packageIdentityName = $appx.Package.Identity.Name
    $evidence.packagePublisher = $appx.Package.Identity.Publisher
    $evidence.packageVersion = $appx.Package.Identity.Version
    $evidence.packageDependencies = $packageDependencies
    $evidence.applicationId = $applicationManifest.applicationId
    $evidence.productVersion = $applicationManifest.productVersion
    $evidence.manifestSha256 = $packageRecord.manifestSha256
    $evidence.platformSignatureStatus = $platformSignature.signatureStatus
    $evidence.platformRegistrationDigest = $registrationDigest
    $evidence.runtimeReleaseSha256 = $packageRecord.runtimeReleaseSha256
    foreach ($asset in @("Square44x44Logo.png", "Square150x150Logo.png", "Square310x310Logo.png", "Wide310x150Logo.png", "StoreLogo.png")) {
        $assetPath = Join-Path $stageRoot "Assets/$asset"
        $evidence.assetSha256[$asset] = Get-Sha256 $assetPath
    }
    $evidence.stagedPayloadTreeSha256 = Get-TreeSha256 $stageRoot

    if (Test-Path -LiteralPath $packagePath -PathType Leaf) { Remove-Item -LiteralPath $packagePath -Force }
    Invoke-NativeTool $makeAppx @("pack", "/d", $stageRoot, "/p", $packagePath, "/o")
    Invoke-NativeTool $makeAppx @("unpack", "/p", $packagePath, "/d", $unpackedRoot, "/o")
    Add-Type -AssemblyName System.IO.Compression
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $sdkArchive = [IO.Compression.ZipFile]::OpenRead($packagePath)
    try {
        foreach ($generated in @("[Content_Types].xml", "AppxBlockMap.xml", "AppxManifest.xml")) {
            if ($null -eq $sdkArchive.GetEntry($generated)) {
                throw "MakeAppx did not generate or retain required MSIX content '$generated'."
            }
        }
    }
    finally { $sdkArchive.Dispose() }
    $evidence.sdkPackageValidation = "PASS"

    $publisherCertificate = New-KWebMsixSigningCertificate -Publisher $expectedPublisher
    $evidence.signingCertificateSubject = $publisherCertificate.Subject
    if ($publisherCertificate.Subject -ne $expectedPublisher) { throw "Test signing certificate subject differs from package Publisher." }
    $password = ConvertTo-SecureString ([guid]::NewGuid().ToString('N')) -AsPlainText -Force
    Export-PfxCertificate -Cert $publisherCertificate -FilePath $pfxPath -Password $password | Out-Null
    Export-Certificate -Cert $publisherCertificate -FilePath $cerPath | Out-Null
    Import-Certificate -FilePath $cerPath -CertStoreLocation "Cert:\CurrentUser\Root" | Out-Null
    Import-Certificate -FilePath $cerPath -CertStoreLocation "Cert:\LocalMachine\TrustedPeople" | Out-Null
    $plainPassword = [Net.NetworkCredential]::new('', $password).Password
    Invoke-NativeTool $signTool @("sign", "/fd", "SHA256", "/f", $pfxPath, "/p", $plainPassword, "/v", $packagePath)
    Invoke-NativeTool $signTool @("verify", "/pa", "/v", $packagePath)
    $evidence.signatureVerification = "PASS"
    $evidence.msixSha256 = Get-Sha256 $packagePath
    $evidence.packageSha256 = $evidence.msixSha256

    Copy-Item -LiteralPath $packagePath -Destination $tamperedPackage
    $tamperedArchive = [IO.Compression.ZipFile]::Open(
        $tamperedPackage,
        [IO.Compression.ZipArchiveMode]::Update
    )
    try {
        $manifestEntry = $tamperedArchive.GetEntry("AppxManifest.xml")
        if ($null -eq $manifestEntry) { throw "The signed MSIX has no root AppxManifest.xml." }
        $reader = [IO.StreamReader]::new($manifestEntry.Open(), [Text.Encoding]::UTF8, $true)
        try { $tamperedManifest = $reader.ReadToEnd() } finally { $reader.Dispose() }
        if (-not $tamperedManifest.Contains($expectedPublisher)) { throw "Unable to locate the signed publisher for the tamper test." }
        $tamperedArchive.GetEntry("AppxManifest.xml").Delete()
        $modifiedEntry = $tamperedArchive.CreateEntry("AppxManifest.xml", [IO.Compression.CompressionLevel]::Optimal)
        $writer = [IO.StreamWriter]::new($modifiedEntry.Open(), [Text.UTF8Encoding]::new($false))
        try { $writer.Write($tamperedManifest.Replace($expectedPublisher, "CN=Altered KWebShell Publisher")) }
        finally { $writer.Dispose() }
    }
    finally { $tamperedArchive.Dispose() }
    $savedErrorPreference = $ErrorActionPreference
    try {
        $ErrorActionPreference = "Continue"
        & $signTool verify /pa $tamperedPackage *> $null
        $tamperExitCode = $LASTEXITCODE
    }
    finally { $ErrorActionPreference = $savedErrorPreference }
    if ($tamperExitCode -eq 0) { throw "SignTool accepted a modified MSIX." }
    $evidence.tamperedPackageRejected = "PASS"

    $unlockKey = "HKLM:\SOFTWARE\Microsoft\Windows\CurrentVersion\AppModelUnlock"
    $developerInstallKeyExisted = Test-Path -LiteralPath $unlockKey
    $existingUnlock = Get-ItemProperty -Path $unlockKey -Name AllowAllTrustedApps -ErrorAction SilentlyContinue
    if ($null -eq $existingUnlock) { $developerInstallPolicy = $null }
    else { $developerInstallPolicy = [int]$existingUnlock.AllowAllTrustedApps }
    $developerInstallPolicyTouched = $true
    if (-not $developerInstallKeyExisted) { New-Item -Path $unlockKey -Force | Out-Null }
    New-ItemProperty -Path $unlockKey -Name AllowAllTrustedApps -PropertyType DWord -Value 1 -Force | Out-Null
    $installationAttempted = $true
    Add-AppxPackage -Path $packagePath
    $installedPackage = Get-AppxPackage -Name $expectedPackageName | Where-Object { $_.Name -eq $expectedPackageName } | Select-Object -First 1
    if ($null -eq $installedPackage) { throw "The signed MSIX did not register under its declared package identity." }
    if ($installedPackage.Publisher -ne $expectedPublisher -or $installedPackage.Version.ToString() -ne $expectedVersion) {
        throw "The installed package publisher or version differs from AppxManifest.xml."
    }
    $evidence.packageFamilyName = $installedPackage.PackageFamilyName
    $evidence.packageFullName = $installedPackage.PackageFullName
    $installRoot = $installedPackage.InstallLocation
    $installedPaths = [ordered]@{
        installRoot = $installRoot
        launcher = Join-Path $installRoot "KWebShell.exe"
        launcherJar = Join-Path $installRoot "app/kweb-application-launcher-$($applicationManifest.productVersion).jar"
        javaRuntime = Join-Path $installRoot "runtime/bin/java.exe"
        temurinLicense = Join-Path $installRoot "runtime/legal/java.base/LICENSE"
        temurinAssemblyException = Join-Path $installRoot "runtime/legal/java.base/ASSEMBLY_EXCEPTION"
        cefSubprocess = Join-Path $installRoot "cef/KWebShellCef.exe"
        nativeEngine = Join-Path $installRoot "native/kwebshell_engine.dll"
        lifecycleLibrary = Join-Path $installRoot "native/kwebshell_application_lifecycle.dll"
    }
    foreach ($path in $installedPaths.GetEnumerator()) {
        if ($path.Key -ne "installRoot" -and -not (Test-Path -LiteralPath $path.Value -PathType Leaf)) {
            throw "Installed MSIX payload is missing $($path.Key): $($path.Value)"
        }
        $evidence.resolvedPaths[$path.Key] = $path.Value
    }
    if ((Get-Sha256 $installedPaths.temurinLicense) -ne $evidence.temurinLicenseSha256 -or
        (Get-Sha256 $installedPaths.temurinAssemblyException) -ne $evidence.temurinAssemblyExceptionSha256) {
        throw "The installed MSIX changed or omitted the bundled Temurin license material."
    }
    $expectedAumid = "$($installedPackage.PackageFamilyName)!$expectedAppId"
    $registeredStartApp = Get-StartApps | Where-Object { $_.AppID -eq $expectedAumid } | Select-Object -First 1
    if ($null -eq $registeredStartApp) { throw "Windows Start registration does not expose the expected AppUserModelId '$expectedAumid'." }
    $aumid = $registeredStartApp.AppID
    $evidence.applicationUserModelId = $aumid
    $evidence.installed = "PASS"

    Start-Process -FilePath "$env:WINDIR\explorer.exe" -ArgumentList "shell:AppsFolder\$aumid"
    $deadline = [DateTime]::UtcNow.AddSeconds(60)
    while ([DateTime]::UtcNow -lt $deadline) {
        $packageProcesses = @(Get-PackageProcesses $installRoot)
        $mainProcess = $packageProcesses |
            Where-Object { $_.MainWindowHandle -ne 0 -and $_.MainWindowTitle -eq $applicationManifest.displayName } | Select-Object -First 1
        $cefProcess = $packageProcesses |
            Where-Object { $_.ProcessName -eq "KWebShellCef" } | Select-Object -First 1
        if ($null -ne $mainProcess -and $mainProcess.MainWindowHandle -ne 0 -and $null -ne $cefProcess) { break }
        Start-Sleep -Milliseconds 500
    }
    if ($null -eq $mainProcess) { throw "The installed MSIX did not start the KWebShell JVM launcher." }
    if ($mainProcess.MainWindowHandle -eq 0) { throw "The installed launcher did not create a visible Compose window." }
    if ($null -eq $cefProcess) { throw "The launcher did not start the KWebShellCef browser subprocess." }
    $evidence.launcherProcessId = $mainProcess.Id
    $evidence.launcherWindowObserved = $true
    $evidence.cefSubprocessObserved = $true

    if (-not $mainProcess.CloseMainWindow()) { throw "The Compose window refused a normal close request." }
    if (-not $mainProcess.WaitForExit(60000)) { throw "The launcher did not shut down after a normal window close." }
    $evidence.launcherExitCode = $mainProcess.ExitCode
    if ($mainProcess.ExitCode -ne 0) {
        throw "The installed Compose process exited with $($mainProcess.ExitCode) after normal close."
    }
    $deadline = [DateTime]::UtcNow.AddSeconds(30)
    $remaining = @(Get-PackageProcesses $installRoot)
    while ([DateTime]::UtcNow -lt $deadline -and $remaining.Count -gt 0) {
        Start-Sleep -Milliseconds 250
        $remaining = @(Get-PackageProcesses $installRoot)
    }
    $evidence.remainingPackageProcessCount = $remaining.Count
    if ($remaining.Count -gt 0) { throw "Package processes remained after normal launcher shutdown." }
    $evidence.normalShutdown = "PASS"
    $mainProcess = $null
    $cefProcess = $null

    Remove-AppxPackage -Package $installedPackage.PackageFullName
    $installedPackage = $null
    if (Get-AppxPackage -Name $expectedPackageName -ErrorAction SilentlyContinue | Where-Object { $_.Name -eq $expectedPackageName }) {
        throw "The MSIX remained registered after uninstall."
    }
    $evidence.uninstalled = "PASS"
    $evidence.status = "PASS"
}
catch {
    $evidence.failure = $_.Exception.Message
    throw
}
finally {
    $cleanupProcesses = @($mainProcess, $cefProcess)
    if ($null -ne $installRoot) {
        try { $cleanupProcesses += @(Get-PackageProcesses $installRoot) }
        catch { $cleanupErrors.Add("Unable to inspect remaining package processes: $($_.Exception.Message)") }
    }
    foreach ($process in ($cleanupProcesses | Where-Object { $null -ne $_ } | Sort-Object -Property Id -Unique)) {
        try {
            if (-not $process.HasExited) {
                $process.Kill()
                if (-not $process.WaitForExit(10000)) { throw "The package process did not stop." }
            }
        }
        catch { if (-not $process.HasExited) { $cleanupErrors.Add("Unable to stop process $($process.Id): $($_.Exception.Message)") } }
    }
    $remainingPackage = $null
    if ($null -ne $installedPackage) { $remainingPackage = $installedPackage }
    elseif ($installationAttempted -and $null -ne $expectedPackageName) {
        $remainingPackage = Get-AppxPackage -Name $expectedPackageName -ErrorAction SilentlyContinue |
            Where-Object { $_.Name -eq $expectedPackageName } | Select-Object -First 1
    }
    if ($null -ne $remainingPackage) {
        try {
            Remove-AppxPackage -Package $remainingPackage.PackageFullName -ErrorAction Stop
            if (Get-AppxPackage -Name $remainingPackage.Name -ErrorAction SilentlyContinue |
                Where-Object { $_.PackageFullName -eq $remainingPackage.PackageFullName }) {
                throw "The installed MSIX is still registered after cleanup."
            }
        }
        catch { $cleanupErrors.Add("Unable to remove installed package: $($_.Exception.Message)") }
    }
    if ($null -ne $publisherCertificate) {
        foreach ($store in @("CurrentUser\My", "LocalMachine\TrustedPeople", "CurrentUser\Root")) {
            $certificatePath = "Cert:\$store\$($publisherCertificate.Thumbprint)"
            if (Test-Path -LiteralPath $certificatePath) {
                try {
                    if ($store -eq "CurrentUser\My") { Remove-Item -LiteralPath $certificatePath -DeleteKey -Force -ErrorAction Stop }
                    else { Remove-Item -LiteralPath $certificatePath -Force -ErrorAction Stop }
                }
                catch { $cleanupErrors.Add("Unable to remove test certificate from ${store}: $($_.Exception.Message)") }
            }
            if (Test-Path -LiteralPath $certificatePath) { $cleanupErrors.Add("Test certificate remains in $store.") }
        }
        $publisherCertificate.Dispose()
    }
    if ($applicationDataOwned) {
        try {
            if (Test-Path -LiteralPath $applicationDataRoot) {
                $dataItem = Get-Item -LiteralPath $applicationDataRoot -Force
                if (($dataItem.Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0) {
                    throw "The KWebShell user-data path became a reparse point; refusing recursive cleanup."
                }
                Remove-Item -LiteralPath $applicationDataRoot -Recurse -Force -ErrorAction Stop
            }
            if (Test-Path -LiteralPath $applicationDataRoot) { throw "KWebShell user data remains after cleanup." }
            $evidence.applicationDataCleanup = "PASS"
        }
        catch { $cleanupErrors.Add("Unable to remove test-owned KWebShell user data: $($_.Exception.Message)") }
    }
    if ($developerInstallPolicyTouched) {
        $unlockKey = "HKLM:\SOFTWARE\Microsoft\Windows\CurrentVersion\AppModelUnlock"
        try {
            if (-not $developerInstallKeyExisted) {
                Remove-Item -LiteralPath $unlockKey -Force -ErrorAction Stop
            }
            elseif ($null -eq $developerInstallPolicy) {
                Remove-ItemProperty -Path $unlockKey -Name AllowAllTrustedApps -ErrorAction Stop
            }
            else {
                Set-ItemProperty -Path $unlockKey -Name AllowAllTrustedApps -Value $developerInstallPolicy -ErrorAction Stop
            }
            $currentUnlock = Get-ItemProperty -Path $unlockKey -Name AllowAllTrustedApps -ErrorAction SilentlyContinue
            if (-not $developerInstallKeyExisted -and (Test-Path -LiteralPath $unlockKey)) {
                throw "The test-created sideload policy key was not removed."
            }
            if ($developerInstallKeyExisted -and $null -eq $developerInstallPolicy -and $null -ne $currentUnlock) {
                throw "The sideload policy value was not removed."
            }
            if ($null -ne $developerInstallPolicy -and [int]$currentUnlock.AllowAllTrustedApps -ne $developerInstallPolicy) {
                throw "The sideload policy value was not restored."
            }
        }
        catch { $cleanupErrors.Add("Unable to restore the AppModelUnlock policy: $($_.Exception.Message)") }
    }
    $evidence.developerInstallPolicyRestored = if ($developerInstallPolicyTouched -and $cleanupErrors.Count -eq 0) { "PASS" } elseif ($developerInstallPolicyTouched) { "FAIL" } else { "NOT_CHANGED" }
    if ($cleanupErrors.Count -gt 0) {
        $evidence.status = "FAIL"
        $cleanupSummary = $cleanupErrors -join "; "
        if ($null -eq $evidence.failure) { $evidence.failure = "Cleanup failed: $cleanupSummary" }
        else { $evidence.failure = "$($evidence.failure) Cleanup failed: $cleanupSummary" }
    }
    if (Test-Path -LiteralPath $report -PathType Leaf) { Remove-Item -LiteralPath $report -Force }
    $json = ConvertTo-Json -InputObject $evidence -Depth 8
    [IO.File]::WriteAllText($report, $json + "`n", [Text.UTF8Encoding]::new($false))
    if (Test-Path -LiteralPath $temporaryRoot -PathType Container) {
        try { Remove-Item -LiteralPath $temporaryRoot -Recurse -Force -ErrorAction Stop }
        catch {
            $evidence.status = "FAIL"
            $evidence.failure = "$($evidence.failure) Temporary data cleanup failed: $($_.Exception.Message)"
            [IO.File]::WriteAllText($report, (ConvertTo-Json -InputObject $evidence -Depth 8) + "`n", [Text.UTF8Encoding]::new($false))
            throw
        }
    }
    if ($cleanupErrors.Count -gt 0) { throw "MSIX verification cleanup failed: $($cleanupErrors -join '; ')" }
}
