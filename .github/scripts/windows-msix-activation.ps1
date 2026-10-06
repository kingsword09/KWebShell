function Initialize-KWebMsixActivation {
    if ('KWebShellMsixActivation' -as [type]) { return }
    Add-Type -TypeDefinition @'
using System;
using System.ComponentModel;
using System.Runtime.InteropServices;
using System.Security.Principal;

public static class KWebShellMsixActivation {
    [ComImport, Guid("2e941141-7f97-4756-ba1d-9decde894a3d"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
    private interface IApplicationActivationManager {
        [PreserveSig] int ActivateApplication([MarshalAs(UnmanagedType.LPWStr)] string appId, [MarshalAs(UnmanagedType.LPWStr)] string arguments, uint options, out uint processId);
        [PreserveSig] int ActivateForFile([MarshalAs(UnmanagedType.LPWStr)] string appId, IntPtr items, [MarshalAs(UnmanagedType.LPWStr)] string verb, out uint processId);
        [PreserveSig] int ActivateForProtocol([MarshalAs(UnmanagedType.LPWStr)] string appId, IntPtr items, out uint processId);
    }
    [DllImport("ole32.dll", PreserveSig = true)]
    private static extern int CoCreateInstance(ref Guid classId, IntPtr outer, uint context, ref Guid interfaceId, [MarshalAs(UnmanagedType.Interface)] out IApplicationActivationManager manager);
    [DllImport("advapi32.dll", SetLastError = true)]
    private static extern bool GetTokenInformation(IntPtr token, int informationClass, IntPtr information, int length, out int required);
    [DllImport("advapi32.dll")]
    private static extern IntPtr GetSidSubAuthorityCount(IntPtr sid);
    [DllImport("advapi32.dll")]
    private static extern IntPtr GetSidSubAuthority(IntPtr sid, uint index);

    public static int Activate(string appId, out uint processId) {
        var classId = new Guid("45ba127d-10a8-46ea-8ab7-56ea9078943c");
        var interfaceId = typeof(IApplicationActivationManager).GUID;
        IApplicationActivationManager manager;
        processId = 0;
        int result = CoCreateInstance(ref classId, IntPtr.Zero, 4, ref interfaceId, out manager);
        if (result < 0) return result;
        try { return manager.ActivateApplication(appId, null, 2, out processId); }
        finally { Marshal.ReleaseComObject(manager); }
    }

    private static IntPtr ReadTokenInformation(IntPtr token, int informationClass) {
        int required;
        GetTokenInformation(token, informationClass, IntPtr.Zero, 0, out required);
        if (required <= 0) throw new Win32Exception(Marshal.GetLastWin32Error());
        IntPtr information = Marshal.AllocHGlobal(required);
        if (GetTokenInformation(token, informationClass, information, required, out required)) return information;
        int error = Marshal.GetLastWin32Error();
        Marshal.FreeHGlobal(information);
        throw new Win32Exception(error);
    }

    public static int[] DescribeToken() {
        using (var identity = WindowsIdentity.GetCurrent()) {
            IntPtr elevation = ReadTokenInformation(identity.Token, 20);
            try {
                IntPtr integrity = ReadTokenInformation(identity.Token, 25);
                try {
                    IntPtr sid = Marshal.ReadIntPtr(integrity);
                    uint index = (uint)(Marshal.ReadByte(GetSidSubAuthorityCount(sid)) - 1);
                    return new int[] { Marshal.ReadInt32(elevation), Marshal.ReadInt32(GetSidSubAuthority(sid, index)) };
                } finally { Marshal.FreeHGlobal(integrity); }
            } finally { Marshal.FreeHGlobal(elevation); }
        }
    }
}
'@
}

function Invoke-KWebMsixActivation {
    param([Parameter(Mandatory = $true)][ValidateNotNullOrEmpty()][string] $ApplicationUserModelId)
    Initialize-KWebMsixActivation
    [uint32] $processId = 0
    $result = [KWebShellMsixActivation]::Activate($ApplicationUserModelId, [ref] $processId)
    return [pscustomobject]@{
        hresult = ('0x{0:X8}' -f $result)
        signedHresult = $result
        processId = $processId
    }
}

function Limit-KWebMsixDiagnosticText {
    param([AllowNull()][string] $Text, [int] $Limit = 2048)
    if ($null -eq $Text) { return $null }
    return $Text.Substring(0, [Math]::Min($Text.Length, $Limit))
}

function Add-KWebMsixProcessSnapshot {
    param(
        [Parameter(Mandatory = $true)][AllowEmptyCollection()][Collections.Generic.List[object]] $Snapshots,
        [AllowEmptyCollection()][Diagnostics.Process[]] $Processes = @()
    )
    $entries = @($Processes | Select-Object -First 16 | ForEach-Object {
        $candidate = $_
        try {
            $candidate.Refresh()
            [ordered]@{
                processId = $candidate.Id
                name = Limit-KWebMsixDiagnosticText $candidate.ProcessName 128
                path = Limit-KWebMsixDiagnosticText $candidate.Path 512
                sessionId = $candidate.SessionId
                hasExited = $candidate.HasExited
                windowHandle = $candidate.MainWindowHandle.ToInt64()
                windowTitle = Limit-KWebMsixDiagnosticText $candidate.MainWindowTitle 128
            }
        }
        catch { [ordered]@{ processId = $candidate.Id; error = Limit-KWebMsixDiagnosticText $_.Exception.Message } }
    })
    if ($Snapshots.Count -eq 64) { $Snapshots.RemoveAt(0) }
    $Snapshots.Add([ordered]@{ utc = [DateTime]::UtcNow.ToString('o'); processCount = $Processes.Count; processes = $entries })
}

function Assert-KWebMsixOwnedDiagnosticPath {
    param([string] $Root, [string] $Path)
    $rootPath = [IO.Path]::GetFullPath($Root).TrimEnd([IO.Path]::DirectorySeparatorChar)
    $fullPath = [IO.Path]::GetFullPath($Path)
    if (-not $fullPath.StartsWith($rootPath + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase)) {
        throw 'Diagnostic log is outside the owned application-data root.'
    }
    $cursor = $fullPath
    while ($true) {
        $item = Get-Item -LiteralPath $cursor -Force -ErrorAction Stop
        if (($item.Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0) { throw 'Refusing to read a reparse-point diagnostic log.' }
        if ($cursor -eq $rootPath) { break }
        $cursor = [IO.Path]::GetDirectoryName($cursor)
    }
}

function Read-KWebMsixOwnedLog {
    param([string] $Root, [string] $Path)
    Assert-KWebMsixOwnedDiagnosticPath -Root $Root -Path $Path
    $rootPath = [IO.Path]::GetFullPath($Root).TrimEnd([IO.Path]::DirectorySeparatorChar)
    $fullPath = [IO.Path]::GetFullPath($Path)
    $stream = [IO.File]::Open($fullPath, [IO.FileMode]::Open, [IO.FileAccess]::Read, [IO.FileShare]::ReadWrite -bor [IO.FileShare]::Delete)
    try {
        $length = $stream.Length
        $tailLength = [int][Math]::Min($length, 65536)
        [void]$stream.Seek(-$tailLength, [IO.SeekOrigin]::End)
        $buffer = New-Object byte[] $tailLength
        $read = 0
        while ($read -lt $tailLength) {
            $count = $stream.Read($buffer, $read, $tailLength - $read)
            if ($count -eq 0) { break }
            $read += $count
        }
        return [ordered]@{
            name = $fullPath.Substring($rootPath.Length + 1)
            sizeBytes = $length
            truncated = $length -gt $read
            tail = [Text.Encoding]::UTF8.GetString($buffer, 0, $read)
        }
    }
    finally { $stream.Dispose() }
}

function Save-KWebMsixActivationDiagnostics {
    param(
        [Parameter(Mandatory = $true)][string] $Path,
        [Parameter(Mandatory = $true)][Collections.IDictionary] $Evidence,
        [Parameter(Mandatory = $true)][DateTime] $StartedAt,
        [Parameter(Mandatory = $true)][AllowEmptyCollection()][Collections.Generic.List[object]] $Snapshots,
        [string] $ApplicationDataRoot,
        [bool] $ApplicationDataOwned = $false,
        [Diagnostics.Process] $ActivationProcess
    )
    $errors = [Collections.Generic.List[string]]::new()
    $caller = [ordered]@{
        processId = $PID
        sessionId = [Diagnostics.Process]::GetCurrentProcess().SessionId
        userInteractive = [Environment]::UserInteractive
        apartmentState = [Threading.Thread]::CurrentThread.GetApartmentState().ToString()
        elevated = $null
        integrityRid = $null
    }
    try {
        Initialize-KWebMsixActivation
        $token = [KWebShellMsixActivation]::DescribeToken()
        $caller.elevated = $token[0] -ne 0
        $caller.integrityRid = $token[1]
    }
    catch { $errors.Add("Caller token: $(Limit-KWebMsixDiagnosticText $_.Exception.Message)") }
    $bootstrap = $null
    if ($null -ne $ActivationProcess) {
        try {
            $ActivationProcess.Refresh()
            $bootstrap = [ordered]@{ processId = $ActivationProcess.Id; hasExited = $ActivationProcess.HasExited; exitCode = $null }
            if ($bootstrap.hasExited) { $bootstrap.exitCode = $ActivationProcess.ExitCode }
        }
        catch { $errors.Add("Activation process: $(Limit-KWebMsixDiagnosticText $_.Exception.Message)") }
    }
    $logs = [Collections.Generic.List[object]]::new()
    if ($ApplicationDataOwned -and $ApplicationDataRoot -and (Test-Path -LiteralPath $ApplicationDataRoot)) {
        try {
            $logRoot = Join-Path $ApplicationDataRoot 'profiles'
            if (Test-Path -LiteralPath $logRoot) {
                Assert-KWebMsixOwnedDiagnosticPath -Root $ApplicationDataRoot -Path $logRoot
                foreach ($candidate in @(Get-ChildItem -LiteralPath $logRoot -File -ErrorAction Stop |
                    Where-Object { $_.Name -eq 'kweb-cef.log' -or $_.Name -like 'hs_err_pid*.log' } |
                    Sort-Object @{ Expression = { $_.Name -ne 'kweb-cef.log' } }, Name | Select-Object -First 4)) {
                    $logs.Add((Read-KWebMsixOwnedLog -Root $ApplicationDataRoot -Path $candidate.FullName))
                }
            }
        }
        catch { $errors.Add("Owned runtime logs: $(Limit-KWebMsixDiagnosticText $_.Exception.Message)") }
    }
    if ($logs.Count -eq 0) { $errors.Add('No owned runtime log files were available; AUMID activation does not capture launcher stdout/stderr.') }
    $channels = [ordered]@{}
    foreach ($channel in @('Microsoft-Windows-AppModel-Runtime/Admin', 'Microsoft-Windows-AppModel-Runtime/Operational', 'Microsoft-Windows-TWinUI/Operational')) {
        try {
            $channels[$channel] = @(Get-WinEvent -FilterHashtable @{ LogName = $channel; StartTime = $StartedAt.ToLocalTime(); EndTime = [DateTime]::Now } -MaxEvents 64 -ErrorAction Stop | ForEach-Object {
                [ordered]@{ utc = $_.TimeCreated.ToUniversalTime().ToString('o'); id = $_.Id; level = $_.Level; message = Limit-KWebMsixDiagnosticText $_.Message }
            })
        }
        catch { $errors.Add("${channel}: $(Limit-KWebMsixDiagnosticText $_.Exception.Message)"); $channels[$channel] = @() }
    }
    $diagnostics = [ordered]@{
        schemaVersion = 1
        utc = [DateTime]::UtcNow.ToString('o')
        sourceRevision = $Evidence.sourceRevision
        applicationUserModelId = $Evidence.applicationUserModelId
        activationHresult = $Evidence.activationHresult
        activationProcessId = $Evidence.activationProcessId
        failure = $Evidence.failure
        processInspectionFailure = $Evidence.activationProcessInspectionFailure
        snapshotFailure = $Evidence.activationSnapshotFailure
        caller = $caller
        bootstrap = $bootstrap
        snapshots = @($Snapshots.ToArray())
        logs = @($logs.ToArray())
        events = $channels
        errors = @($errors.ToArray())
    }
    $json = ConvertTo-Json -InputObject $diagnostics -Depth 8
    $bytes = [Text.UTF8Encoding]::new($false).GetBytes($json + "`n")
    if ($bytes.Length -gt 2MB) { throw 'Activation diagnostics exceed the 2-MiB limit.' }
    $destination = [IO.Path]::GetFullPath($Path)
    if (Test-Path -LiteralPath $destination) { throw 'Activation diagnostics destination already exists; refusing to overwrite it.' }
    $stream = [IO.File]::Open($destination, [IO.FileMode]::CreateNew, [IO.FileAccess]::Write, [IO.FileShare]::None)
    try { $stream.Write($bytes, 0, $bytes.Length) }
    finally { $stream.Dispose() }
}
