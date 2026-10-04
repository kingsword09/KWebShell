$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName UIAutomationClient
Add-Type -AssemblyName UIAutomationTypes
Add-Type @'
using System;
using System.Runtime.InteropServices;
public static class KWebShellInput {
    [DllImport("user32.dll")]
    private static extern void keybd_event(byte key, byte scan, uint flags, UIntPtr extra);
    public static void OpenActionCenter(int build) {
        // Server 2022 uses the Windows 10 shell (Win+A); Win+N is Windows 11.
        byte key = build >= 22000 ? (byte)0x4E : (byte)0x41;
        keybd_event(0x5B, 0, 0, UIntPtr.Zero);
        keybd_event(key, 0, 0, UIntPtr.Zero);
        keybd_event(key, 0, 2, UIntPtr.Zero);
        keybd_event(0x5B, 0, 2, UIntPtr.Zero);
    }
}
'@
$build = [int](Get-ItemProperty 'HKLM:\SOFTWARE\Microsoft\Windows NT\CurrentVersion').CurrentBuildNumber
[KWebShellInput]::OpenActionCenter($build)
$root = [System.Windows.Automation.AutomationElement]::RootElement
$actionCondition = New-Object System.Windows.Automation.PropertyCondition(
    [System.Windows.Automation.AutomationElement]::NameProperty, 'Open fixture'
)
$deadline = (Get-Date).AddSeconds(30)
$actionCount = 0
while ((Get-Date) -lt $deadline) {
    $actions = $root.FindAll([System.Windows.Automation.TreeScope]::Descendants, $actionCondition)
    $actionCount = $actions.Count
    foreach ($action in $actions) {
        try {
            $pattern = $null
            if (-not $action.TryGetCurrentPattern(
                [System.Windows.Automation.InvokePattern]::Pattern,
                [ref]$pattern
            )) { continue }
            $pattern.Invoke()
            Write-Output 'Notification fixture: declared OS action invoked'
            exit 0
        } catch [System.Windows.Automation.ElementNotAvailableException] {
            # Retry a card removed/replaced while walking the live shell tree.
        }
    }
    Start-Sleep -Milliseconds 200
}
Write-Error "Notification fixture: action surface unavailable; build=$build matchingActions=$actionCount."
exit 2
