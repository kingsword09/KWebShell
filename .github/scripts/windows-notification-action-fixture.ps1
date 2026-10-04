$ErrorActionPreference = 'Stop'

Add-Type -AssemblyName UIAutomationClient
Add-Type -AssemblyName UIAutomationTypes

Add-Type @'
using System;
using System.Runtime.InteropServices;
public static class KWebShellInput {
    [DllImport("user32.dll", SetLastError = true)]
    private static extern void keybd_event(byte key, byte scan, uint flags, UIntPtr extra);
    private const uint KEYEVENTF_KEYUP = 0x0002;
    private const byte VK_LWIN = 0x5B;
    private const byte VK_N = 0x4E;
    private static void Press(byte key) {
        keybd_event(VK_LWIN, 0, 0, UIntPtr.Zero);
        keybd_event(key, 0, 0, UIntPtr.Zero);
        keybd_event(key, 0, KEYEVENTF_KEYUP, UIntPtr.Zero);
        keybd_event(VK_LWIN, 0, KEYEVENTF_KEYUP, UIntPtr.Zero);
    }
    public static void OpenActionCenter() {
        // Win+N opens Notification Center on current Windows runners. A
        // second shortcut would toggle the panel and hide the toast again.
        Press(VK_N);
    }
}
'@

[KWebShellInput]::OpenActionCenter()
Start-Sleep -Milliseconds 750

$root = [System.Windows.Automation.AutomationElement]::RootElement
$trueCondition = [System.Windows.Automation.Condition]::TrueCondition
$buttonCondition = New-Object System.Windows.Automation.PropertyCondition(
    [System.Windows.Automation.AutomationElement]::ControlTypeProperty,
    [System.Windows.Automation.ControlType]::Button
)
$deadline = (Get-Date).AddSeconds(30)

while ((Get-Date) -lt $deadline) {
    $elements = $root.FindAll(
        [System.Windows.Automation.TreeScope]::Descendants,
        $trueCondition
    )
    foreach ($element in $elements) {
        try {
            if ($element.Current.Name -notlike '*KWebShell notification fixture*') {
                continue
            }
            $buttons = $element.FindAll(
                [System.Windows.Automation.TreeScope]::Subtree,
                $buttonCondition
            )
            foreach ($button in $buttons) {
                if ($button.Current.Name -notmatch '^(Open|打开)$') {
                    continue
                }
                $pattern = $button.GetCurrentPattern(
                    [System.Windows.Automation.InvokePattern]::Pattern
                )
                $pattern.Invoke()
                Write-Output 'Windows notification OS action activated'
                exit 0
            }
        } catch {
            # Notification Center changes its UI tree while cards appear and close.
            # Retry until the bounded deadline instead of treating a transient tree
            # mutation as a successful activation.
        }
    }
    Start-Sleep -Milliseconds 250
}

throw 'The real Windows notification action was not exposed by UI Automation within 30 seconds.'
