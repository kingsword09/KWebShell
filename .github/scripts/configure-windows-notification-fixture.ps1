param([switch]$Cleanup)
$ErrorActionPreference = 'Stop'

# Register only the hosted fixture's desktop AUMID. This is the warm WinRT
# notification test identity, not evidence of MSIX/cold activation support.
$applicationId = 'io.github.kwebshell.migration.fixture'
$key = "HKCU:\Software\Classes\AppUserModelId\$applicationId"
$owner = 'KWebShell.NotificationIntegrationFixture'

function Remove-FixtureIdentity {
    if (Test-Path $key) {
        $properties = Get-ItemProperty -LiteralPath $key
        if ($properties.KWebFixtureOwner -ne $owner) {
            throw 'Notification fixture: refusing to remove an identity owned by another application.'
        }
        Remove-Item -LiteralPath $key -Recurse -Force
    }
}

if ($Cleanup) {
    Remove-FixtureIdentity
    exit 0
}
if (Test-Path $key) {
    $properties = Get-ItemProperty -LiteralPath $key
    if ($properties.KWebFixtureOwner -ne $owner) {
        throw 'Notification fixture: the test AUMID is already owned by another application.'
    }
}
try {
    New-Item -Path $key -Force | Out-Null
    New-ItemProperty -LiteralPath $key -Name KWebFixtureOwner -Value $owner -PropertyType String -Force | Out-Null
    New-ItemProperty -LiteralPath $key -Name DisplayName -Value 'KWebShellMigrationFixture' -PropertyType String -Force | Out-Null
    New-ItemProperty -LiteralPath $key -Name ShowInSettings -Value 1 -PropertyType DWord -Force | Out-Null
    $registered = Get-ItemProperty -LiteralPath $key
    if ($registered.DisplayName -ne 'KWebShellMigrationFixture' -or $registered.KWebFixtureOwner -ne $owner) {
        throw 'Notification fixture: the installed AUMID registration did not match its owner.'
    }
    Write-Output 'Notification fixture: desktop AUMID registration verified.'
} catch {
    Remove-FixtureIdentity
    throw
}
