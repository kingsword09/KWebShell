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
$build = [int](Get-ItemProperty 'HKLM:\SOFTWARE\Microsoft\Windows NT\CurrentVersion').CurrentBuildNumber
$screenWidth = [KWebShellScreen]::GetSystemMetrics(0)
[KWebShellInput]::CloseActionCenter()
Start-Sleep -Milliseconds 500
[KWebShellInput]::OpenActionCenter($build)
Start-Sleep -Milliseconds 750
[KWebShellMouse]::Click($screenWidth - 900 + 304, 234)
Start-Sleep -Milliseconds 750
[KWebShellMouse]::Click($screenWidth - 900 + 540, 435)
Write-Output 'Notification fixture: declared OS action clicked.'
Start-Sleep -Milliseconds 1000
exit 0
