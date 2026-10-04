param([switch]$Cleanup)
$ErrorActionPreference = 'Stop'

# Register only the hosted fixture's desktop AUMID. This is the warm WinRT
# notification test identity, not evidence of MSIX/cold activation support.
$applicationId = 'io.github.kwebshell.migration.fixture'
$key = "HKCU:\Software\Classes\AppUserModelId\$applicationId"
$owner = 'KWebShell.NotificationIntegrationFixture'
$shortcut = Join-Path ([Environment]::GetFolderPath('StartMenu')) 'Programs\KWebShellMigrationFixture.lnk'

Add-Type -TypeDefinition @'
using System;
using System.Runtime.InteropServices;

internal static class KWebShellShortcut {
    [ComImport, Guid("00021401-0000-0000-C000-000000000046")] private class ShellLink {}
    [ComImport, Guid("000214F9-0000-0000-C000-000000000046"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
    private interface IShellLinkW {
        void GetPath([Out, MarshalAs(UnmanagedType.LPWStr)] string pszFile, int cch, IntPtr pfd, uint fFlags);
        void GetIDList(out IntPtr ppidl); void SetIDList(IntPtr pidl);
        void GetDescription([Out, MarshalAs(UnmanagedType.LPWStr)] string pszName, int cch); void SetDescription([MarshalAs(UnmanagedType.LPWStr)] string pszName);
        void GetWorkingDirectory([Out, MarshalAs(UnmanagedType.LPWStr)] string pszDir, int cch); void SetWorkingDirectory([MarshalAs(UnmanagedType.LPWStr)] string pszDir);
        void GetArguments([Out, MarshalAs(UnmanagedType.LPWStr)] string pszArgs, int cch); void SetArguments([MarshalAs(UnmanagedType.LPWStr)] string pszArgs);
        void GetHotkey(out ushort pwHotkey); void SetHotkey(ushort wHotkey); void GetShowCmd(out int piShowCmd); void SetShowCmd(int iShowCmd);
        void GetIconLocation([Out, MarshalAs(UnmanagedType.LPWStr)] string pszIconPath, int cch, out int iIcon); void SetIconLocation([MarshalAs(UnmanagedType.LPWStr)] string pszIconPath, int iIcon);
        void SetRelativePath([MarshalAs(UnmanagedType.LPWStr)] string pszPathRel, uint dwReserved); void Resolve(IntPtr hwnd, uint fFlags); void SetPath([MarshalAs(UnmanagedType.LPWStr)] string pszFile);
    }
    [ComImport, Guid("0000010B-0000-0000-C000-000000000046"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
    private interface IPersistFile { void GetClassID(out Guid pClassID); void IsDirty(); void Load([MarshalAs(UnmanagedType.LPWStr)] string pszFileName, uint dwMode); void Save([MarshalAs(UnmanagedType.LPWStr)] string pszFileName, bool fRemember); void SaveCompleted([MarshalAs(UnmanagedType.LPWStr)] string pszFileName); void GetCurFile([MarshalAs(UnmanagedType.LPWStr)] out string ppszFileName); }
    [ComImport, Guid("886D8EEB-8CF2-4446-8D02-CDBA1DBDCF99"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
    private interface IPropertyStore { uint GetCount(); void GetAt(uint iProp, out PROPERTYKEY pkey); void GetValue(ref PROPERTYKEY key, out PROPVARIANT pv); void SetValue(ref PROPERTYKEY key, ref PROPVARIANT pv); void Commit(); }
    [StructLayout(LayoutKind.Sequential)] private struct PROPERTYKEY { public Guid fmtid; public uint pid; }
    [StructLayout(LayoutKind.Explicit)] private struct PROPVARIANT { [FieldOffset(0)] public ushort vt; [FieldOffset(8)] public IntPtr pointer; }

    [DllImport("ole32.dll")] private static extern int PropVariantClear(ref PROPVARIANT pvar);
    public static void Create(string path, string target, string appId) {
        var link = (IShellLinkW)new ShellLink(); link.SetPath(target); link.SetDescription("KWebShell notification fixture");
        var store = (IPropertyStore)link; var key = new PROPERTYKEY { fmtid = new Guid("9F4C2855-9F79-4B39-A8D0-E1D42DE1D5F3"), pid = 5 };
        var value = new PROPVARIANT { vt = 31, pointer = Marshal.StringToCoTaskMemUni(appId) };
        try { store.SetValue(ref key, ref value); store.Commit(); ((IPersistFile)link).Save(path, true); } finally { PropVariantClear(ref value); }
    }
}
'@

function Remove-FixtureIdentity {
    if (Test-Path $key) {
        $properties = Get-ItemProperty -LiteralPath $key
        if ($properties.KWebFixtureOwner -ne $owner) {
            throw 'Notification fixture: refusing to remove an identity owned by another application.'
        }
        Remove-Item -LiteralPath $key -Recurse -Force
    }
    if (Test-Path $shortcut) { Remove-Item -LiteralPath $shortcut -Force }
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
    [KWebShellShortcut]::Create($shortcut, "$env:SystemRoot\System32\notepad.exe", $applicationId)
    $registered = Get-ItemProperty -LiteralPath $key
    if ($registered.DisplayName -ne 'KWebShellMigrationFixture' -or $registered.KWebFixtureOwner -ne $owner) {
        throw 'Notification fixture: the installed AUMID registration did not match its owner.'
    }
    Write-Output 'Notification fixture: desktop AUMID registration verified.'
} catch {
    Remove-FixtureIdentity
    throw
}
