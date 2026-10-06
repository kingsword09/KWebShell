$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot '../windows-msix-activation.ps1')
$testRoot = [IO.Path]::GetFullPath((Join-Path ([IO.Path]::GetTempPath()) ('kweb-msix-activation-' + [guid]::NewGuid().ToString('N'))))
$expectedRoot = $testRoot
$junction = Join-Path $testRoot 'linked-profile'

function Assert-ActivationFixture {
    param([bool] $Condition, [string] $Message)
    if (-not $Condition) { throw $Message }
}

function Assert-ActivationFixtureRejected {
    param([scriptblock] $Operation, [string] $Message)
    $rejected = $false
    try { & $Operation }
    catch {
        if ($_.Exception.Message -notlike "*$Message*") { throw }
        $rejected = $true
    }
    Assert-ActivationFixture $rejected "Expected rejection containing '$Message'."
}

try {
    New-Item -ItemType Directory -Path $testRoot | Out-Null
    $unknownAumid = 'KWebShell.Unregistered.' + [guid]::NewGuid().ToString('N') + '!Application'
    $activation = Invoke-KWebMsixActivation -ApplicationUserModelId $unknownAumid
    Assert-ActivationFixture ($activation.signedHresult -lt 0) 'Native unregistered-AUMID activation must fail.'
    Assert-ActivationFixture ($activation.hresult -match '^0x[89A-F][0-9A-F]{7}$') 'Native failure must retain its HRESULT.'
    Assert-ActivationFixture ($activation.processId -eq 0) 'Native failed activation must not report a started process.'
    Write-Output "PASS: real native unregistered-AUMID rejection ($($activation.hresult), PID 0)."

    $snapshots = [Collections.Generic.List[object]]::new()
    $current = [Diagnostics.Process]::GetCurrentProcess()
    for ($index = 0; $index -lt 75; $index++) {
        Add-KWebMsixProcessSnapshot -Snapshots $snapshots -Processes (@($current) * 18)
    }
    Assert-ActivationFixture ($snapshots.Count -eq 64) 'Snapshot count must be bounded.'
    Assert-ActivationFixture ($snapshots[0].processes.Count -eq 16 -and $snapshots[0].processCount -eq 18) 'Process count must be bounded without hiding truncation.'
    Assert-ActivationFixture ((Limit-KWebMsixDiagnosticText ('x' * 4000)).Length -eq 2048) 'Diagnostic text must be bounded.'

    $dataRoot = Join-Path $testRoot 'owned-data'
    $profiles = Join-Path $dataRoot 'profiles'
    New-Item -ItemType Directory -Path $profiles | Out-Null
    $log = Join-Path $profiles 'kweb-cef.log'
    [IO.File]::WriteAllText($log, ('a' * 100000) + 'retained-tail')
    $tail = Read-KWebMsixOwnedLog -Root $dataRoot -Path $log
    Assert-ActivationFixture ($tail.truncated -and $tail.tail.Length -eq 65536 -and $tail.tail.EndsWith('retained-tail')) 'Owned log must retain a bounded tail.'
    Assert-ActivationFixture ($tail.name -eq 'profiles\kweb-cef.log') 'Owned log must use an application-relative path.'
    Assert-ActivationFixtureRejected { Read-KWebMsixOwnedLog -Root $profiles -Path (Join-Path $testRoot 'outside.log') } 'outside the owned'
    New-Item -ItemType Junction -Path $junction -Target $profiles | Out-Null
    Assert-ActivationFixtureRejected { Read-KWebMsixOwnedLog -Root $testRoot -Path (Join-Path $junction 'kweb-cef.log') } 'reparse-point'
    [IO.Directory]::Delete($junction)

    $evidence = [ordered]@{
        sourceRevision = 'native-fixture-only'
        applicationUserModelId = $unknownAumid
        activationHresult = $activation.hresult
        activationProcessId = $activation.processId
        failure = 'original native activation failure'
    }
    $diagnosticsPath = Join-Path $testRoot 'native-diagnostics.json'
    Save-KWebMsixActivationDiagnostics -Path $diagnosticsPath -Evidence $evidence -StartedAt ([DateTime]::UtcNow.AddMinutes(-1)) `
        -Snapshots $snapshots -ApplicationDataRoot $dataRoot -ApplicationDataOwned $true -ActivationProcess $current
    $diagnostics = Get-Content -LiteralPath $diagnosticsPath -Raw -Encoding UTF8 | ConvertFrom-Json
    Assert-ActivationFixture ($diagnostics.failure -eq $evidence.failure) 'Diagnostics must retain the original failure.'
    Assert-ActivationFixture ($diagnostics.caller.integrityRid -gt 0 -and $diagnostics.caller.elevated -is [bool]) 'Native caller token must be observed.'
    Assert-ActivationFixture ($diagnostics.logs.Count -eq 1 -and $diagnostics.snapshots.Count -eq 64) 'Diagnostics must retain owned logs and bounded snapshots.'
    Assert-ActivationFixture ((Get-Item -LiteralPath $diagnosticsPath).Length -lt 2MB) 'Diagnostics file must be bounded.'
    Assert-ActivationFixtureRejected {
        Save-KWebMsixActivationDiagnostics -Path $diagnosticsPath -Evidence $evidence -StartedAt ([DateTime]::UtcNow) -Snapshots $snapshots
    } 'already exists'
    Assert-ActivationFixture ($evidence.failure -eq 'original native activation failure') 'Diagnostic write failure must not mutate primary evidence.'

    function Get-WinEvent {
        param($FilterHashtable, $MaxEvents, $ErrorAction)
        Assert-ActivationFixture ($MaxEvents -eq 64) 'Event query must use a numeric ceiling.'
        throw 'fixture event-channel unavailable'
    }
    $unownedPath = Join-Path $testRoot 'unowned-diagnostics.json'
    Save-KWebMsixActivationDiagnostics -Path $unownedPath -Evidence $evidence -StartedAt ([DateTime]::UtcNow) `
        -Snapshots ([Collections.Generic.List[object]]::new()) -ApplicationDataRoot $dataRoot -ApplicationDataOwned $false
    $unowned = Get-Content -LiteralPath $unownedPath -Raw -Encoding UTF8 | ConvertFrom-Json
    Assert-ActivationFixture ($unowned.logs.Count -eq 0) 'Non-owned application data must not be read.'
    Assert-ActivationFixture (@($unowned.errors | Where-Object { $_ -like '*fixture event-channel unavailable*' }).Count -eq 3) 'Unavailable event channels must remain explicit.'
    for ($index = 0; $index -lt 6; $index++) { [IO.File]::WriteAllText((Join-Path $profiles "hs_err_pid$index.log"), 'fixture') }
    $boundedPath = Join-Path $testRoot 'bounded-diagnostics.json'
    Save-KWebMsixActivationDiagnostics -Path $boundedPath -Evidence $evidence -StartedAt ([DateTime]::UtcNow) `
        -Snapshots $snapshots -ApplicationDataRoot $dataRoot -ApplicationDataOwned $true
    $bounded = Get-Content -LiteralPath $boundedPath -Raw -Encoding UTF8 | ConvertFrom-Json
    Assert-ActivationFixture ($bounded.logs.Count -eq 4) 'Log file count must be bounded.'
    Assert-ActivationFixture ($bounded.logs[0].name -eq 'profiles\kweb-cef.log') 'The CEF log must not be displaced by crash logs.'
    function Get-WinEvent {
        param($FilterHashtable, $MaxEvents, $ErrorAction)
        Assert-ActivationFixture ($MaxEvents -eq 64) 'Event query must use a numeric ceiling.'
        for ($index = 0; $index -lt $MaxEvents; $index++) {
            [pscustomobject]@{ TimeCreated = [DateTime]::Now; Id = 1; Level = 2; Message = 'x' * 4000 }
        }
    }
    $eventsPath = Join-Path $testRoot 'event-bound-diagnostics.json'
    Save-KWebMsixActivationDiagnostics -Path $eventsPath -Evidence $evidence -StartedAt ([DateTime]::UtcNow) `
        -Snapshots ([Collections.Generic.List[object]]::new())
    $events = Get-Content -LiteralPath $eventsPath -Raw -Encoding UTF8 | ConvertFrom-Json
    foreach ($channel in $events.events.PSObject.Properties) {
        Assert-ActivationFixture ($channel.Value.Count -eq 64 -and $channel.Value[0].message.Length -eq 2048) 'Event count and message text must be bounded.'
    }
    $oversized = [ordered]@{ sourceRevision = $evidence.sourceRevision; failure = 'x' * 2MB }
    $oversizedPath = Join-Path $testRoot 'oversized-diagnostics.json'
    Assert-ActivationFixtureRejected {
        Save-KWebMsixActivationDiagnostics -Path $oversizedPath -Evidence $oversized -StartedAt ([DateTime]::UtcNow) -Snapshots $snapshots
    } '2-MiB limit'
    Assert-ActivationFixture (-not (Test-Path -LiteralPath $oversizedPath)) 'An oversized sidecar must not be published.'
    Remove-Item Function:\Get-WinEvent
    Write-Output 'PASS: real token/session inspection, bounded snapshots/logs, reparse/ownership rejection, unavailable channels and primary-failure preservation.'
}
finally {
    if ([IO.Path]::GetFullPath($testRoot) -ne $expectedRoot -or [IO.Path]::GetFileName($testRoot) -notlike 'kweb-msix-activation-*') {
        throw 'Refusing activation-fixture cleanup outside its exact owned root.'
    }
    if (Test-Path -LiteralPath $junction) { [IO.Directory]::Delete($junction) }
    if (Test-Path -LiteralPath $testRoot) {
        $reparsePoints = @(Get-Item -LiteralPath $testRoot -Force; Get-ChildItem -LiteralPath $testRoot -Recurse -Force) |
            Where-Object { ($_.Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0 }
        if ($reparsePoints) { throw 'Refusing activation-fixture recursive cleanup through a reparse point.' }
        Remove-Item -LiteralPath $testRoot -Recurse -Force
    }
}
