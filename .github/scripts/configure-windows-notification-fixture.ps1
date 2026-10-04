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
    [Windows.UI.Notifications.ToastNotificationManager, Windows.UI.Notifications, ContentType = WindowsRuntime] | Out-Null
    $notifier = [Windows.UI.Notifications.ToastNotificationManager]::CreateToastNotifier($applicationId)
    $setting = $notifier.Setting
    if ($setting.ToString() -ne 'Enabled') {
        throw "Notification fixture: registered WinRT provider is not enabled (setting=$setting)."
    }
    Write-Output "Notification fixture: desktop AUMID registered; WinRT setting=$setting."
} catch {
    Remove-FixtureIdentity
    throw
}
