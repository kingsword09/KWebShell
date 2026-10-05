$ErrorActionPreference = 'Stop'
Add-Type @'
using System;
using System.Runtime.InteropServices;
public static class KWebShellScreen {
    [DllImport("user32.dll")]
    public static extern int GetSystemMetrics(int index);
}
'@
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
    public static void CloseActionCenter() {
        keybd_event(0x1B, 0, 0, UIntPtr.Zero);
        keybd_event(0x1B, 0, 2, UIntPtr.Zero);
    }
}
'@
Add-Type @'
using System;
using System.Runtime.InteropServices;
public static class KWebShellMouse {
    [DllImport("user32.dll")] private static extern bool SetCursorPos(int x, int y);
    [DllImport("user32.dll")] private static extern void mouse_event(uint flags, uint x, uint y, uint data, UIntPtr extra);
    public static void Click(int x, int y) {
        SetCursorPos(x, y);
        mouse_event(0x0002, 0, 0, 0, UIntPtr.Zero);
        mouse_event(0x0004, 0, 0, 0, UIntPtr.Zero);
    }
}
'@
$script:diagnosticsEnabled = $env:KWEB_NOTIFICATION_CAPTURE_DIAGNOSTICS -eq '1'
if ($script:diagnosticsEnabled) {
    Add-Type -AssemblyName System.Drawing
    Add-Type -AssemblyName System.Windows.Forms
    Add-Type @'
using System.Drawing;
using System.Drawing.Imaging;
using System.Windows.Forms;
public static class KWebShellDiagnostics {
    public static void Capture(string path) {
        var bounds = SystemInformation.VirtualScreen;
        using (var bitmap = new Bitmap(bounds.Width, bounds.Height))
        using (var graphics = Graphics.FromImage(bitmap)) {
            graphics.CopyFromScreen(bounds.Left, bounds.Top, 0, 0, bitmap.Size);
            bitmap.Save(path, ImageFormat.Png);
        }
    }
}
'@
}

function Save-DiagnosticsScreenshot([string] $name) {
    if (-not $script:diagnosticsEnabled) {
        return
    }
    $path = Join-Path (Get-Location) $name
    [KWebShellDiagnostics]::Capture($path)
    Write-Output "Notification fixture: captured $path."
}

$build = [int](Get-ItemProperty 'HKLM:\SOFTWARE\Microsoft\Windows NT\CurrentVersion').CurrentBuildNumber
$screenWidth = [KWebShellScreen]::GetSystemMetrics(0)
if ($script:diagnosticsEnabled) {
    [pscustomobject]@{
        build = $build
        screenWidth = $screenWidth
        screenHeight = [KWebShellScreen]::GetSystemMetrics(1)
        virtualScreenLeft = [KWebShellScreen]::GetSystemMetrics(76)
        virtualScreenTop = [KWebShellScreen]::GetSystemMetrics(77)
        virtualScreenWidth = [KWebShellScreen]::GetSystemMetrics(78)
        virtualScreenHeight = [KWebShellScreen]::GetSystemMetrics(79)
        actionCenterShortcut = if ($build -ge 22000) { 'Win+N' } else { 'Win+A' }
    } | ConvertTo-Json -Compress | Set-Content -Encoding UTF8 (Join-Path (Get-Location) 'windows-notification-diagnostics.json')
}
[KWebShellInput]::CloseActionCenter()
Start-Sleep -Milliseconds 500
[KWebShellInput]::OpenActionCenter($build)
Start-Sleep -Milliseconds 750
Save-DiagnosticsScreenshot 'windows-notification-before-click.png'
[KWebShellMouse]::Click($screenWidth - 900 + 304, 234)
Start-Sleep -Milliseconds 750
Save-DiagnosticsScreenshot 'windows-notification-after-card-click.png'
[KWebShellMouse]::Click($screenWidth - 900 + 540, 435)
Save-DiagnosticsScreenshot 'windows-notification-after-action-click.png'
Write-Output 'Notification fixture: declared OS action clicked.'
Start-Sleep -Milliseconds 1000
exit 0
