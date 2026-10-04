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
$buttonCondition = New-Object System.Windows.Automation.AndCondition(
    (New-Object System.Windows.Automation.PropertyCondition(
        [System.Windows.Automation.AutomationElement]::ControlTypeProperty,
        [System.Windows.Automation.ControlType]::Button)),
    (New-Object System.Windows.Automation.PropertyCondition(
        [System.Windows.Automation.AutomationElement]::NameProperty, 'Open fixture'))
)
$deadline = (Get-Date).AddSeconds(30)
$buttonCount = 0
while ((Get-Date) -lt $deadline) {
    $buttons = $root.FindAll([System.Windows.Automation.TreeScope]::Descendants, $buttonCondition)
    $buttonCount = $buttons.Count
    foreach ($button in $buttons) {
        try {
            $pattern = $button.GetCurrentPattern([System.Windows.Automation.InvokePattern]::Pattern)
            $pattern.Invoke()
            Write-Output 'Notification fixture: declared OS action invoked'
            exit 0
        } catch [System.Windows.Automation.ElementNotAvailableException] {
            # Retry a card removed/replaced while walking the live shell tree.
        }
    }
    Start-Sleep -Milliseconds 200
}
throw "Notification fixture: action unavailable; build=$build matchingButtons=$buttonCount."
