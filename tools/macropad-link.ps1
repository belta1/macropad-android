<#
.SYNOPSIS
    Macropad PC link: keeps the tablet's Bluetooth serial port open, tells the tablet about Teams,
    the app in front, the next Outlook meeting, the clipboard, open windows, the volume, what's
    playing and the terminal's Python venv, mirrors a monitor to the tablet's Screen page (and
    clicks where it is touched), shows PowerPoint's and HTML slide decks' notes on its Presenter
    page, and runs the tablet's pc: steps.

.DESCRIPTION
    The macropad (Classic Bluetooth, e.g. Galaxy Tab S5e) offers a serial port next to the keyboard;
    Windows shows it as an outgoing Bluetooth COM port. This script opens it and talks to the tablet
    in newline-delimited JSON (see docs/pc-link.md). Windows PowerShell 5.1, no modules.

    Config and logs live in %APPDATA%\Macropad:
      commands.json     the commands pc:run may start (copied from macropad-commands.example.json)
      teams-token.txt   the token Teams hands out once you allow "Macropad"
      shell.txt         the folder and venv of the terminal last used (written by the shell hook)
      link.log          what happened, also when running hidden

.PARAMETER NoClipboard
    Don't send the clipboard to the tablet. (Texts a password manager marks as private are never sent.)

.PARAMETER AllowRecording
    Experimental. Lets the tablet record this computer's keyboard and mouse clicks while it records a
    macro (tablet settings > Connection > Record the computer's keyboard and mouse). The script then
    hooks the keyboard and mouse the way keyloggers do, only while the tablet records and for 5
    minutes at most; security software may report it.

.PARAMETER NoUpdate
    Don't let the tablet update this script. Otherwise, when the macropad app carries a newer copy,
    the tablet offers to send it (you confirm on the tablet); the script checks it arrived whole and
    is valid PowerShell, keeps the old one as macropad-link.ps1.bak, and restarts itself.

.PARAMETER InstallShellHook
    Add a few lines to your PowerShell profile so each prompt tells the tablet the terminal's
    folder and Python venv (the Python page's venv key), then exit.

.PARAMETER UninstallShellHook
    Remove those lines from your PowerShell profile, then exit.

.PARAMETER Port
    The COM port, e.g. COM5. Default: the outgoing Bluetooth COM port of the device named -Device.

.PARAMETER Device
    The tablet's Bluetooth name (default: Macropad).

.PARAMETER Install
    Start this script hidden at sign-in (a shortcut in the Startup folder), then exit.

.PARAMETER Uninstall
    Remove that shortcut, then exit.

.PARAMETER TeamsDump
    List the buttons of Teams' windows (automation id | name), then exit. For adjusting the UI
    Automation fallback when Teams' third-party API is off.

.EXAMPLE
    powershell -ExecutionPolicy Bypass -File tools\macropad-link.ps1 -Verbose
#>
[CmdletBinding()]
param(
    [string]$Port,
    [string]$Device = 'Macropad',
    [switch]$Install,
    [switch]$Uninstall,
    [switch]$NoTeams,
    [switch]$NoOutlook,
    [switch]$NoClipboard,
    [switch]$AllowRecording,
    [switch]$NoUpdate,
    [switch]$TeamsDump,
    [switch]$InstallShellHook,
    [switch]$UninstallShellHook
)

$ErrorActionPreference = 'Stop'
$ProtocolVersion = 1
# The tablet offers this script's update when the app carries a higher version, or the same one
# with other contents.
$ScriptVersion = '1.8.1'
$StartOptions = @{} + $PSBoundParameters # for starting again after an update
$ConfigDir = Join-Path $env:APPDATA 'Macropad'
$CommandsFile = Join-Path $ConfigDir 'commands.json'
$TokenFile = Join-Path $ConfigDir 'teams-token.txt'
$ShellFile = Join-Path $ConfigDir 'shell.txt'
$LogFile = Join-Path $ConfigDir 'link.log'
$ShortcutFile = Join-Path ([Environment]::GetFolderPath('Startup')) 'Macropad PC link.lnk'
New-Item -ItemType Directory -Force -Path $ConfigDir | Out-Null

# ------------------------------------------------------------------ setup switches

if ($Install) {
    $arguments = "-NoProfile -ExecutionPolicy Bypass -WindowStyle Hidden -File `"$PSCommandPath`""
    if ($Port) { $arguments += " -Port $Port" }
    if ($Device -ne 'Macropad') { $arguments += " -Device `"$Device`"" }
    if ($NoTeams) { $arguments += ' -NoTeams' }
    if ($NoOutlook) { $arguments += ' -NoOutlook' }
    if ($NoClipboard) { $arguments += ' -NoClipboard' }
    if ($AllowRecording) { $arguments += ' -AllowRecording' }
    if ($NoUpdate) { $arguments += ' -NoUpdate' }
    $shell = New-Object -ComObject WScript.Shell
    $shortcut = $shell.CreateShortcut($ShortcutFile)
    $shortcut.TargetPath = (Get-Command powershell.exe).Source
    $shortcut.Arguments = $arguments
    $shortcut.WorkingDirectory = Split-Path $PSCommandPath
    $shortcut.WindowStyle = 7 # minimized; -WindowStyle Hidden then hides it
    $shortcut.Save()
    Write-Host "Starts at sign-in: $ShortcutFile"
    Write-Host 'Start it now: sign out and in, or double-click the shortcut.'
    return
}
if ($Uninstall) {
    Remove-Item -LiteralPath $ShortcutFile -ErrorAction SilentlyContinue
    Write-Host 'No longer starts at sign-in.'
    return
}

# The shell hook: each PowerShell prompt writes the terminal's process id, folder and venv to
# shell.txt; the venv's activate script keeps calling it (it wraps the prompt it finds).
$HookStart = '# >>> Macropad shell hook'
$HookEnd = '# <<< Macropad shell hook'
$Hook = @'
# >>> Macropad shell hook (tools\macropad-link.ps1 -InstallShellHook): tells the macropad this
# terminal's folder and Python venv. Remove it with -UninstallShellHook.
if (-not $global:MacropadPrompt) {
    $global:MacropadPrompt = $function:prompt
    function global:prompt {
        try {
            $venv = if ($env:VIRTUAL_ENV) { $env:VIRTUAL_ENV } else { "$env:CONDA_PREFIX" }
            [IO.File]::WriteAllText("$env:APPDATA\Macropad\shell.txt", "$PID`n$((Get-Location).ProviderPath)`n$venv")
        } catch { }
        & $global:MacropadPrompt
    }
}
# <<< Macropad shell hook
'@

# Windows PowerShell's profile, and PowerShell 7's when it is installed.
function Get-ProfilePaths {
    $documents = [Environment]::GetFolderPath('MyDocuments')
    $paths = @(Join-Path $documents 'WindowsPowerShell\profile.ps1')
    if (Get-Command pwsh -ErrorAction SilentlyContinue) { $paths += Join-Path $documents 'PowerShell\profile.ps1' }
    return $paths
}

if ($InstallShellHook) {
    foreach ($path in Get-ProfilePaths) {
        $text = if (Test-Path $path) { Get-Content $path -Raw } else { '' }
        if ($text -and $text.Contains($HookStart)) { Write-Host "Already in $path"; continue }
        New-Item -ItemType Directory -Force -Path (Split-Path $path) | Out-Null
        Add-Content -Path $path -Value "`r`n$Hook" -Encoding UTF8
        Write-Host "Added to $path"
    }
    $policy = Get-ExecutionPolicy
    if ($policy -in 'Restricted', 'AllSigned', 'Undefined') {
        Write-Host "PowerShell doesn't run profiles with the execution policy '$policy'. If your organisation allows it:"
        Write-Host '  Set-ExecutionPolicy -Scope CurrentUser RemoteSigned'
    }
    Write-Host 'Open a new terminal (also in VS Code) for it to take effect.'
    return
}
if ($UninstallShellHook) {
    foreach ($path in Get-ProfilePaths) {
        if (-not (Test-Path $path)) { continue }
        $text = Get-Content $path -Raw
        $kept = [regex]::Replace($text, "(?s)\r?\n?$([regex]::Escape($HookStart)).*?$([regex]::Escape($HookEnd))\r?\n?", '')
        if ($kept -ne $text) { Set-Content -Path $path -Value $kept -Encoding UTF8 -NoNewline; Write-Host "Removed from $path" }
    }
    Remove-Item -LiteralPath $ShellFile -ErrorAction SilentlyContinue
    return
}

# One copy at a time (the Startup shortcut and a manual run would fight over the port).
$mutex = New-Object System.Threading.Mutex($false, 'Local\MacropadPcLink')
if (-not $TeamsDump -and -not $mutex.WaitOne(0)) {
    Write-Host 'The PC link is already running (see the Startup shortcut or Task Manager).'
    return
}

# ------------------------------------------------------------------ logging

function Write-Log([string]$Message) {
    $line = '{0:yyyy-MM-dd HH:mm:ss} {1}' -f (Get-Date), $Message
    Write-Host $line
    try {
        if ((Test-Path $LogFile) -and (Get-Item $LogFile).Length -gt 1MB) {
            Move-Item -Force $LogFile "$LogFile.1"
        }
        Add-Content -Path $LogFile -Value $line -Encoding UTF8
    } catch { }
}

function Write-Traffic([string]$Message) {
    if ($VerbosePreference -ne 'SilentlyContinue') { Write-Log $Message }
}

# ------------------------------------------------------------------ the serial port

$Link = $null
$LinkBuffer = ''
$LastHeard = Get-Date

# The outgoing Bluetooth COM port of the paired device named $Device. Its PnP id ends in the
# device's address (incoming ports have 000000000000 there).
function Find-Port {
    $addresses = @(Get-PnpDevice -Class Bluetooth -ErrorAction SilentlyContinue |
        Where-Object { $_.FriendlyName -like "*$Device*" -and $_.InstanceId -match 'DEV_([0-9A-F]{12})' } |
        ForEach-Object { $null = $_.InstanceId -match 'DEV_([0-9A-F]{12})'; $Matches[1] })
    if ($addresses.Count -eq 0) { return $null }
    $ports = Get-CimInstance Win32_PnPEntity |
        Where-Object { $_.PNPDeviceID -like 'BTHENUM\{00001101-0000-1000-8000-00805F9B34FB}*' }
    foreach ($p in $ports) {
        if ($p.PNPDeviceID -match '&([0-9A-F]{12})_' -and $addresses -contains $Matches[1] -and $p.Name -match '\((COM\d+)\)') {
            return $Matches[1]
        }
    }
    return $null
}

$PortHintShown = $false

function Open-Link {
    $name = $Port
    if (-not $name) {
        $name = Find-Port
        if (-not $name) {
            if (-not $script:PortHintShown) {
                Write-Log "No outgoing Bluetooth COM port for '$Device'. Pair the tablet (Classic Bluetooth), then check Bluetooth > More Bluetooth settings > COM Ports, or pass -Port COMx."
                $script:PortHintShown = $true
            }
            return $null
        }
    }
    $sp = New-Object System.IO.Ports.SerialPort $name, 115200
    $sp.NewLine = "`n"
    $sp.Encoding = [System.Text.Encoding]::UTF8
    $sp.ReadTimeout = 100
    $sp.WriteTimeout = 3000
    try {
        $sp.Open() # Windows connects to the tablet now; fails after a while if it's out of reach
    } catch {
        Write-Traffic "Can't open ${name}: $($_.Exception.Message)"
        $sp.Dispose()
        return $null
    }
    Write-Log "Connected to the tablet on $name"
    $script:PortHintShown = $false
    return $sp
}

function Close-Link {
    if ($script:Link) {
        try { $script:Link.Close() } catch { }
        $script:Link.Dispose()
        $script:Link = $null
        Write-Log 'Tablet disconnected; trying again every 10 s'
    }
    $script:LinkBuffer = ''
    # No tablet to record for: stop watching the keyboard.
    if ($script:Capture) {
        [MacropadLink.KeyCapture]::Stop()
        $script:Capture = $null
        Write-Log 'Stopped recording the keyboard and mouse (tablet disconnected)'
    }
}

function Send-Tablet($Message) {
    Send-TabletJson ($Message | ConvertTo-Json -Compress -Depth 5)
}

function Send-TabletJson([string]$Json) {
    if (-not $script:Link) { return }
    $script:Link.Write($Json + "`n")
    # Icons are long and say nothing useful.
    Write-Traffic "-> $(if ($Json.Length -gt 300) { $Json.Substring(0, 300) + '...' } else { $Json })"
}

$LastSent = @{}   # message type -> the JSON last sent

# Sends $Message unless it is the same as the last one of its type ($Always: anyway).
function Send-Changed($Message, [switch]$Always) {
    $json = $Message | ConvertTo-Json -Compress -Depth 5
    if (-not $Always -and $script:LastSent[$Message.t] -eq $json) { return }
    $script:LastSent[$Message.t] = $json
    Send-TabletJson $json
}

# ------------------------------------------------------------------ the app in front

Add-Type -Namespace MacropadLink -Name Win32 -MemberDefinition @'
[DllImport("user32.dll")] public static extern IntPtr GetForegroundWindow();
[DllImport("user32.dll")] public static extern int GetWindowThreadProcessId(IntPtr hWnd, out int processId);
[DllImport("user32.dll", CharSet = CharSet.Unicode)] public static extern int GetWindowText(IntPtr hWnd, System.Text.StringBuilder text, int count);
[DllImport("user32.dll")] public static extern uint GetClipboardSequenceNumber();
'@

$FrontApp = $null

function Get-FrontApp {
    $window = [MacropadLink.Win32]::GetForegroundWindow()
    if ($window -eq [IntPtr]::Zero) { return $null }
    $processId = 0
    [void][MacropadLink.Win32]::GetWindowThreadProcessId($window, [ref]$processId)
    $process = Get-Process -Id $processId -ErrorAction SilentlyContinue
    if (-not $process) { return $null }
    $title = New-Object System.Text.StringBuilder 256
    [void][MacropadLink.Win32]::GetWindowText($window, $title, 256)
    return @{ exe = $process.ProcessName.ToLower(); title = $title.ToString() }
}

function Send-FrontApp([switch]$Always) {
    $app = Get-FrontApp
    if (-not $app) { return }
    if ($Always -or $app.exe -ne $script:FrontApp) {
        $script:FrontApp = $app.exe
        Send-Tablet ([ordered]@{ t = 'app'; exe = $app.exe; title = $app.title })
    }
}

# ------------------------------------------------------------------ windows, icons and volume (Win32 / Core Audio)

Add-Type -ReferencedAssemblies System.Drawing, UIAutomationClient, UIAutomationTypes, WindowsBase -TypeDefinition @'
using System;
using System.Collections.Generic;
using System.Drawing;
using System.Drawing.Drawing2D;
using System.Drawing.Imaging;
using System.IO;
using System.Linq;
using System.Runtime.InteropServices;
using System.Text;
using UIA = System.Windows.Automation;

namespace MacropadLink {
    public class WindowInfo { public long Id; public int Pid; public string Title; }

    // Top-level windows as Alt+Tab lists them, bringing one to the front, and app icons.
    public static class Windows {
        delegate bool EnumProc(IntPtr h, IntPtr l);
        [DllImport("user32.dll")] static extern bool EnumWindows(EnumProc callback, IntPtr l);
        [DllImport("user32.dll")] static extern bool IsWindow(IntPtr h);
        [DllImport("user32.dll")] static extern bool IsWindowVisible(IntPtr h);
        [DllImport("user32.dll")] static extern bool IsIconic(IntPtr h);
        [DllImport("user32.dll")] static extern IntPtr GetWindow(IntPtr h, uint command);
        [DllImport("user32.dll")] static extern int GetWindowLong(IntPtr h, int index);
        [DllImport("user32.dll")] static extern int GetWindowTextLength(IntPtr h);
        [DllImport("user32.dll", CharSet = CharSet.Unicode)] static extern int GetWindowText(IntPtr h, StringBuilder text, int count);
        [DllImport("user32.dll")] static extern uint GetWindowThreadProcessId(IntPtr h, out int processId);
        [DllImport("dwmapi.dll")] static extern int DwmGetWindowAttribute(IntPtr h, int attribute, out int value, int size);
        [DllImport("user32.dll")] static extern IntPtr GetForegroundWindow();
        [DllImport("user32.dll")] static extern bool SetForegroundWindow(IntPtr h);
        [DllImport("user32.dll")] static extern bool BringWindowToTop(IntPtr h);
        [DllImport("user32.dll")] static extern bool ShowWindow(IntPtr h, int command);
        [DllImport("user32.dll")] static extern bool AttachThreadInput(uint from, uint to, bool attach);
        [DllImport("kernel32.dll")] static extern uint GetCurrentThreadId();
        [DllImport("user32.dll")] static extern void SwitchToThisWindow(IntPtr h, bool altTab);
        [DllImport("user32.dll")] static extern IntPtr SendMessageTimeout(IntPtr h, uint message, IntPtr w, IntPtr l, uint flags, uint timeout, out IntPtr result);
        [DllImport("user32.dll", EntryPoint = "GetClassLongPtrW")] static extern IntPtr GetClassLongPtr64(IntPtr h, int index);
        [DllImport("user32.dll", EntryPoint = "GetClassLongW")] static extern uint GetClassLong32(IntPtr h, int index);

        public static List<WindowInfo> List() {
            var list = new List<WindowInfo>();
            EnumWindows(delegate (IntPtr h, IntPtr l) {
                if (!IsWindowVisible(h) || GetWindow(h, 4) != IntPtr.Zero) return true;   // GW_OWNER: dialogs, popups
                if ((GetWindowLong(h, -20) & 0x80) != 0) return true;                    // WS_EX_TOOLWINDOW
                int cloaked;
                if (DwmGetWindowAttribute(h, 14, out cloaked, 4) == 0 && cloaked != 0) return true; // other desktops, suspended apps
                int length = GetWindowTextLength(h);
                if (length == 0) return true;
                var title = new StringBuilder(length + 1);
                GetWindowText(h, title, title.Capacity);
                if (title.ToString() == "Program Manager") return true;
                int pid;
                GetWindowThreadProcessId(h, out pid);
                list.Add(new WindowInfo { Id = h.ToInt64(), Pid = pid, Title = title.ToString() });
                return true;
            }, IntPtr.Zero);
            return list;
        }

        public static long Front() { return GetForegroundWindow().ToInt64(); }

        public static bool Exists(long id) { return IsWindow(new IntPtr(id)); }

        [DllImport("user32.dll")] static extern bool IsZoomed(IntPtr h);
        public static bool Maximized(long id) { return IsZoomed(new IntPtr(id)); }

        [DllImport("user32.dll")] static extern IntPtr GetKeyboardLayout(uint thread);
        [DllImport("user32.dll")] static extern IntPtr OpenInputDesktop(uint flags, bool inherit, uint access);
        [DllImport("user32.dll")] static extern bool CloseDesktop(IntPtr desktop);
        [DllImport("wtsapi32.dll")] static extern bool WTSQuerySessionInformationW(IntPtr server, int session, int infoClass, out IntPtr buffer, out int bytes);
        [DllImport("wtsapi32.dll")] static extern void WTSFreeMemory(IntPtr memory);

        // This session's lock state as Windows keeps it: 1 locked, 0 unlocked, -1 unknown.
        public static int SessionLocked() {
            IntPtr info;
            int bytes;
            if (!WTSQuerySessionInformationW(IntPtr.Zero, -1, 25, out info, out bytes)) return -1; // WTSSessionInfoEx
            try {
                // WTSINFOEXW: Level, then (8-byte aligned) SessionId, SessionState, SessionFlags.
                if (bytes < 20 || Marshal.ReadInt32(info, 0) != 1) return -1;
                int flags = Marshal.ReadInt32(info, 16);
                return flags == 0 ? 1 : flags == 1 ? 0 : -1; // WTS_SESSIONSTATE_LOCK / _UNLOCK
            } finally {
                WTSFreeMemory(info);
            }
        }

        // While the computer is locked (or a UAC prompt shows), the desktop taking input is
        // Windows' own, which other programs can't open.
        public static bool InputDesktopHidden() {
            var desktop = OpenInputDesktop(0, false, 0x0100); // DESKTOP_SWITCHDESKTOP
            if (desktop == IntPtr.Zero) return true;
            CloseDesktop(desktop);
            return false;
        }

        // The keyboard layout (HKL) of the window in front; 0 when there is none.
        public static long FrontKeyboard() {
            int ignored;
            uint thread = GetWindowThreadProcessId(GetForegroundWindow(), out ignored);
            return thread == 0 ? 0 : GetKeyboardLayout(thread).ToInt64();
        }

        // Windows only lets the app in front (or the one that got the last input) change the
        // foreground; joining the front window's input queue for a moment counts as that.
        public static bool Focus(long id) {
            var h = new IntPtr(id);
            if (!IsWindow(h)) return false;
            if (IsIconic(h)) ShowWindow(h, 9); // SW_RESTORE
            int ignored;
            uint front = GetWindowThreadProcessId(GetForegroundWindow(), out ignored);
            uint me = GetCurrentThreadId();
            bool attached = front != 0 && front != me && AttachThreadInput(me, front, true);
            BringWindowToTop(h);
            SetForegroundWindow(h);
            if (attached) AttachThreadInput(me, front, false);
            if (GetForegroundWindow() != h) SwitchToThisWindow(h, true);
            return GetForegroundWindow() == h;
        }

        // The app's icon as a PNG: the program file's, else the window's; null if neither.
        public static byte[] IconPng(long id, string exePath) {
            Bitmap bitmap = null;
            try {
                if (!string.IsNullOrEmpty(exePath) && File.Exists(exePath)) {
                    var icon = Icon.ExtractAssociatedIcon(exePath);
                    if (icon != null) bitmap = icon.ToBitmap();
                }
                if (bitmap == null && id != 0) {
                    var h = new IntPtr(id);
                    IntPtr handle = IntPtr.Zero;
                    foreach (int size in new[] { 1, 2, 0 }) { // ICON_BIG, ICON_SMALL2, ICON_SMALL
                        IntPtr result;
                        if (SendMessageTimeout(h, 0x7F, new IntPtr(size), IntPtr.Zero, 2, 200, out result) != IntPtr.Zero && result != IntPtr.Zero) {
                            handle = result;
                            break;
                        }
                    }
                    if (handle == IntPtr.Zero) handle = IntPtr.Size == 8 ? GetClassLongPtr64(h, -14) : new IntPtr(GetClassLong32(h, -14));
                    if (handle != IntPtr.Zero) bitmap = Icon.FromHandle(handle).ToBitmap();
                }
                if (bitmap == null) return null;
                using (var png = new MemoryStream()) {
                    bitmap.Save(png, ImageFormat.Png);
                    return png.ToArray();
                }
            } catch {
                return null;
            } finally {
                if (bitmap != null) bitmap.Dispose();
            }
        }
    }

    [ComImport, Guid("BCDE0395-E52F-467C-8E3D-C4579291692E")] class MMDeviceEnumeratorCom { }

    [ComImport, Guid("A95664D2-9614-4F35-A746-DE8DB63617E6"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
    interface IMMDeviceEnumerator {
        void EnumAudioEndpoints(int dataFlow, int stateMask, out IntPtr devices);
        void GetDefaultAudioEndpoint(int dataFlow, int role, out IMMDevice device);
    }

    [ComImport, Guid("D666063F-1587-4E43-81F1-B948E807363F"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
    interface IMMDevice {
        void Activate(ref Guid iid, int context, IntPtr parameters, [MarshalAs(UnmanagedType.IUnknown)] out object result);
    }

    [ComImport, Guid("5CDF2C82-841E-4546-9722-0CF74078229A"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
    interface IAudioEndpointVolume {
        void RegisterControlChangeNotify(IntPtr notify);
        void UnregisterControlChangeNotify(IntPtr notify);
        void GetChannelCount(out int count);
        void SetMasterVolumeLevel(float db, ref Guid context);
        void SetMasterVolumeLevelScalar(float level, ref Guid context);
        void GetMasterVolumeLevel(out float db);
        void GetMasterVolumeLevelScalar(out float level);
        void SetChannelVolumeLevel(int channel, float db, ref Guid context);
        void SetChannelVolumeLevelScalar(int channel, float level, ref Guid context);
        void GetChannelVolumeLevel(int channel, out float db);
        void GetChannelVolumeLevelScalar(int channel, out float level);
        void SetMute([MarshalAs(UnmanagedType.Bool)] bool mute, ref Guid context);
        void GetMute([MarshalAs(UnmanagedType.Bool)] out bool mute);
    }

    [ComImport, Guid("77AA99A0-1BD6-484F-8BC7-2C654C9A9B6F"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
    interface IAudioSessionManager2 {
        void GetAudioSessionControl(IntPtr session, int flags, out IntPtr control);
        void GetSimpleAudioVolume(IntPtr session, int flags, out IntPtr volume);
        void GetSessionEnumerator(out IAudioSessionEnumerator sessions);
    }

    [ComImport, Guid("E2F5BB11-0570-40CA-ACDD-3AA01277DEE8"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
    interface IAudioSessionEnumerator {
        void GetCount(out int count);
        void GetSession(int index, out IAudioSessionControl2 session);
    }

    [ComImport, Guid("BFB7FF88-7239-4FC9-8FA2-07C950BE9C6D"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
    interface IAudioSessionControl2 {
        void GetState(out int state);
        void GetDisplayName([MarshalAs(UnmanagedType.LPWStr)] out string name);
        void SetDisplayName([MarshalAs(UnmanagedType.LPWStr)] string name, ref Guid context);
        void GetIconPath([MarshalAs(UnmanagedType.LPWStr)] out string path);
        void SetIconPath([MarshalAs(UnmanagedType.LPWStr)] string path, ref Guid context);
        void GetGroupingParam(out Guid group);
        void SetGroupingParam(ref Guid group, ref Guid context);
        void RegisterAudioSessionNotification(IntPtr notify);
        void UnregisterAudioSessionNotification(IntPtr notify);
        void GetSessionIdentifier([MarshalAs(UnmanagedType.LPWStr)] out string id);
        void GetSessionInstanceIdentifier([MarshalAs(UnmanagedType.LPWStr)] out string id);
        void GetProcessId(out int pid);
        [PreserveSig] int IsSystemSoundsSession();
    }

    [ComImport, Guid("87CE5498-68D6-44E5-9215-6DA47EF883D8"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
    interface ISimpleAudioVolume {
        void SetMasterVolume(float level, ref Guid context);
        void GetMasterVolume(out float level);
        void SetMute([MarshalAs(UnmanagedType.Bool)] bool mute, ref Guid context);
        void GetMute([MarshalAs(UnmanagedType.Bool)] out bool mute);
    }

    public class AudioSession { public int Pid; public float Level; public bool Muted; }

    // The default speakers' volume and the apps playing through them (the volume mixer).
    public static class Audio {
        static IMMDevice Speakers() {
            var devices = (IMMDeviceEnumerator)new MMDeviceEnumeratorCom();
            IMMDevice device;
            devices.GetDefaultAudioEndpoint(0, 1, out device); // eRender, eMultimedia
            return device;
        }

        static object Activate(Guid iid) {
            object result;
            Speakers().Activate(ref iid, 23, IntPtr.Zero, out result); // CLSCTX_ALL
            return result;
        }

        static IAudioEndpointVolume Endpoint() { return (IAudioEndpointVolume)Activate(typeof(IAudioEndpointVolume).GUID); }

        public static float Level() { float level; Endpoint().GetMasterVolumeLevelScalar(out level); return level; }
        public static bool Muted() { bool muted; Endpoint().GetMute(out muted); return muted; }
        public static void SetLevel(float level) { var context = Guid.Empty; Endpoint().SetMasterVolumeLevelScalar(Math.Max(0f, Math.Min(1f, level)), ref context); }
        public static void SetMuted(bool muted) { var context = Guid.Empty; Endpoint().SetMute(muted, ref context); }

        // Apps' sessions (not expired ones, not Windows' system sounds).
        public static List<AudioSession> Sessions() {
            var list = new List<AudioSession>();
            foreach (var pair in Controls()) {
                var volume = (ISimpleAudioVolume)pair.Value;
                float level; bool muted;
                volume.GetMasterVolume(out level);
                volume.GetMute(out muted);
                list.Add(new AudioSession { Pid = pair.Key, Level = level, Muted = muted });
            }
            return list;
        }

        // Sets every session of process [pid]; a negative level leaves the level, null leaves mute.
        public static void SetApp(int pid, float level, object muted) {
            var context = Guid.Empty;
            foreach (var pair in Controls()) {
                if (pair.Key != pid) continue;
                var volume = (ISimpleAudioVolume)pair.Value;
                if (level >= 0) volume.SetMasterVolume(Math.Max(0f, Math.Min(1f, level)), ref context);
                if (muted != null) volume.SetMute((bool)muted, ref context);
            }
        }

        static List<KeyValuePair<int, IAudioSessionControl2>> Controls() {
            var list = new List<KeyValuePair<int, IAudioSessionControl2>>();
            var manager = (IAudioSessionManager2)Activate(typeof(IAudioSessionManager2).GUID);
            IAudioSessionEnumerator sessions;
            manager.GetSessionEnumerator(out sessions);
            int count;
            sessions.GetCount(out count);
            for (int i = 0; i < count; i++) {
                IAudioSessionControl2 control;
                sessions.GetSession(i, out control);
                int state, pid;
                control.GetState(out state);
                if (state == 2 || control.IsSystemSoundsSession() == 0) continue; // expired; system sounds
                control.GetProcessId(out pid);
                if (pid != 0) list.Add(new KeyValuePair<int, IAudioSessionControl2>(pid, control));
            }
            return list;
        }
    }

    // One monitor, captured and sent as the JPEG tiles that changed since the last capture, as
    // lines of JSON ready to send: {"t":"frame","seq":3,"w":1600,"h":900,"tiles":[[x,y,"<jpeg>"],...]}
    // The last line has "end":true and the cursor ("cx","cy": 0-1 within the monitor, or -1).
    public static class Mirror {
        const int Tile = 64;
        const int LineLimit = 24000; // characters of tiles per line
        static Bitmap last;
        static byte[] lastPixels;
        static ImageCodecInfo jpeg = ImageCodecInfo.GetImageEncoders().First(e => e.FormatID == ImageFormat.Jpeg.Guid);

        [StructLayout(LayoutKind.Sequential)] struct POINT { public int X, Y; }
        [DllImport("user32.dll")] static extern bool GetCursorPos(out POINT p);
        [DllImport("user32.dll")] static extern IntPtr SetProcessDpiAwarenessContext(IntPtr context);
        [DllImport("user32.dll")] static extern bool SetProcessDPIAware();

        // Screen coordinates in real pixels (else Windows scales them at 125 %, 150 %, ...).
        public static void UsePhysicalPixels() {
            try { if (SetProcessDpiAwarenessContext(new IntPtr(-4)) != IntPtr.Zero) return; } catch { } // per monitor v2
            try { SetProcessDPIAware(); } catch { }
        }

        public static void Reset() {
            if (last != null) last.Dispose();
            last = null;
            lastPixels = null;
        }

        // The monitor at (x, y, w, h), scaled to fit maxW x maxH; null when it can't be captured
        // (e.g. the PC is locked). [all] sends every tile (a new view on the tablet).
        public static List<string> Frame(int x, int y, int w, int h, int maxW, int maxH, int quality, bool all, int seq, int view) {
            Bitmap scaled;
            try {
                using (var full = new Bitmap(w, h, PixelFormat.Format32bppRgb)) {
                    using (var g = Graphics.FromImage(full)) g.CopyFromScreen(x, y, 0, 0, new Size(w, h), CopyPixelOperation.SourceCopy);
                    double scale = Math.Min(1.0, Math.Min((double)maxW / w, (double)maxH / h));
                    int sw = Math.Max(1, (int)(w * scale)), sh = Math.Max(1, (int)(h * scale));
                    scaled = new Bitmap(sw, sh, PixelFormat.Format32bppRgb);
                    using (var g = Graphics.FromImage(scaled)) {
                        g.InterpolationMode = scale < 1 ? InterpolationMode.Bilinear : InterpolationMode.NearestNeighbor;
                        g.PixelOffsetMode = PixelOffsetMode.HighQuality;
                        g.DrawImage(full, 0, 0, sw, sh);
                    }
                }
            } catch {
                return null;
            }
            int W = scaled.Width, H = scaled.Height;
            var data = scaled.LockBits(new Rectangle(0, 0, W, H), ImageLockMode.ReadOnly, PixelFormat.Format32bppRgb);
            var pixels = new byte[data.Stride * H];
            Marshal.Copy(data.Scan0, pixels, 0, pixels.Length);
            int stride = data.Stride;
            scaled.UnlockBits(data);
            bool fresh = all || last == null || last.Width != W || last.Height != H;

            var parameters = new EncoderParameters(1);
            parameters.Param[0] = new EncoderParameter(System.Drawing.Imaging.Encoder.Quality, (long)quality);
            var lines = new List<string>();
            var line = new StringBuilder();
            // Changed tiles side by side in a row go as one JPEG (each JPEG carries ~600 bytes of tables).
            for (int ty = 0; ty < H; ty += Tile) {
                int th = Math.Min(Tile, H - ty);
                int runStart = -1;
                for (int tx = 0; tx <= W; tx += Tile) {
                    bool changed = tx < W && (fresh || !Same(pixels, lastPixels, stride, tx, ty, Math.Min(Tile, W - tx), th));
                    if (changed && runStart < 0) runStart = tx;
                    if (changed || runStart < 0) continue;
                    var area = new Rectangle(runStart, ty, Math.Min(tx, W) - runStart, th);
                    runStart = -1;
                    string jpg;
                    using (var tile = scaled.Clone(area, PixelFormat.Format24bppRgb))
                    using (var stream = new MemoryStream()) {
                        tile.Save(stream, jpeg, parameters);
                        jpg = Convert.ToBase64String(stream.ToArray());
                    }
                    if (line.Length > 0 && line.Length + jpg.Length > LineLimit) {
                        lines.Add(Line(seq, view, W, H, line, null));
                        line.Clear();
                    }
                    if (line.Length > 0) line.Append(',');
                    line.Append('[').Append(area.X).Append(',').Append(area.Y).Append(",\"").Append(jpg).Append("\"]");
                }
            }
            POINT p;
            string cursor = "\"cx\":-1,\"cy\":-1";
            if (GetCursorPos(out p) && p.X >= x && p.X < x + w && p.Y >= y && p.Y < y + h) {
                cursor = string.Format(System.Globalization.CultureInfo.InvariantCulture, "\"cx\":{0:0.####},\"cy\":{1:0.####}",
                    (p.X - x) / (double)w, (p.Y - y) / (double)h);
            }
            lines.Add(Line(seq, view, W, H, line, cursor));
            if (last != null) last.Dispose();
            last = scaled;
            lastPixels = pixels;
            return lines;
        }

        // One picture of the screen at (x, y, w, h), at most maxW wide, as a JPEG; null if it can't be captured.
        public static byte[] Picture(int x, int y, int w, int h, int maxW, int quality) {
            try {
                using (var full = new Bitmap(w, h, PixelFormat.Format32bppRgb)) {
                    using (var g = Graphics.FromImage(full)) g.CopyFromScreen(x, y, 0, 0, new Size(w, h), CopyPixelOperation.SourceCopy);
                    double scale = Math.Min(1.0, (double)maxW / w);
                    using (var scaled = new Bitmap(Math.Max(1, (int)(w * scale)), Math.Max(1, (int)(h * scale)), PixelFormat.Format24bppRgb))
                    using (var stream = new MemoryStream()) {
                        using (var g = Graphics.FromImage(scaled)) {
                            g.InterpolationMode = InterpolationMode.HighQualityBilinear;
                            g.PixelOffsetMode = PixelOffsetMode.HighQuality;
                            g.DrawImage(full, 0, 0, scaled.Width, scaled.Height);
                        }
                        var parameters = new EncoderParameters(1);
                        parameters.Param[0] = new EncoderParameter(System.Drawing.Imaging.Encoder.Quality, (long)quality);
                        scaled.Save(stream, jpeg, parameters);
                        return stream.ToArray();
                    }
                }
            } catch {
                return null;
            }
        }

        static string Line(int seq, int view, int w, int h, StringBuilder tiles, string end) {
            var s = new StringBuilder("{\"t\":\"frame\",\"seq\":").Append(seq).Append(",\"view\":").Append(view)
                .Append(",\"w\":").Append(w).Append(",\"h\":").Append(h)
                .Append(",\"tiles\":[").Append(tiles).Append(']');
            if (end != null) s.Append(",\"end\":true,").Append(end);
            return s.Append('}').ToString();
        }

        static bool Same(byte[] a, byte[] b, int stride, int x, int y, int w, int h) {
            for (int row = y; row < y + h; row++) {
                int start = row * stride + x * 4, end = start + w * 4;
                for (int i = start; i < end; i++) if (a[i] != b[i]) return false;
            }
            return true;
        }
    }

    // A key pressed on this computer's keyboard while the tablet records (-AllowRecording): typed
    // text, a key (Esc, arrows, F-keys...), a shortcut, or a Backspace. Time: Environment.TickCount.
    // Or the mouse ('m'): Value "left", "right", "drag" (to X2, Y2) or "wheel" (Amount, 120 a
    // notch) at X, Y (screen pixels) over top-level window Window; Name is the button there, if
    // UI Automation names one, and Ready once it has looked.
    public class KeyToken {
        public int Time; public char Kind; public string Value; // 't' text, 's' step, 'b' backspace, 'm' mouse
        public int X, Y, X2, Y2, Amount; public long Window; public string Name; public volatile bool Ready;
    }

    // Records the keyboard and the mouse's clicks through low-level hooks on a thread of their own,
    // only between Start and Stop. Input other programs inject is left out.
    public static class KeyCapture {
        delegate IntPtr HookProc(int code, IntPtr message, IntPtr data);
        [StructLayout(LayoutKind.Sequential)] struct KBDLLHOOKSTRUCT { public uint Vk, Scan, Flags, Time; public IntPtr Extra; }
        [StructLayout(LayoutKind.Sequential)] struct MSLLHOOKSTRUCT { public int X, Y; public uint Data, Flags, Time; public IntPtr Extra; }
        [StructLayout(LayoutKind.Sequential)] struct POINT { public int X, Y; }
        [DllImport("user32.dll")] static extern IntPtr WindowFromPoint(POINT p);
        [DllImport("user32.dll")] static extern IntPtr GetAncestor(IntPtr h, uint flags);
        [StructLayout(LayoutKind.Sequential)] struct MSG { public IntPtr Hwnd; public uint Message; public IntPtr W, L; public uint Time; public int X, Y; }
        [DllImport("user32.dll")] static extern IntPtr SetWindowsHookEx(int id, HookProc proc, IntPtr module, uint thread);
        [DllImport("user32.dll")] static extern bool UnhookWindowsHookEx(IntPtr hook);
        [DllImport("user32.dll")] static extern IntPtr CallNextHookEx(IntPtr hook, int code, IntPtr message, IntPtr data);
        [DllImport("user32.dll")] static extern int GetMessage(out MSG msg, IntPtr hwnd, uint min, uint max);
        [DllImport("user32.dll")] static extern bool PostThreadMessage(uint thread, uint message, IntPtr w, IntPtr l);
        [DllImport("kernel32.dll")] static extern uint GetCurrentThreadId();
        [DllImport("kernel32.dll")] static extern IntPtr GetModuleHandle(string name);
        [DllImport("user32.dll")] static extern IntPtr GetForegroundWindow();
        [DllImport("user32.dll")] static extern uint GetWindowThreadProcessId(IntPtr h, out int processId);
        [DllImport("user32.dll")] static extern IntPtr GetKeyboardLayout(uint thread);
        [DllImport("user32.dll")] static extern short GetKeyState(int vk);
        [DllImport("user32.dll", CharSet = CharSet.Unicode)]
        static extern int ToUnicodeEx(uint vk, uint scan, byte[] state, StringBuilder text, int size, uint flags, IntPtr layout);

        static readonly System.Collections.Concurrent.ConcurrentQueue<KeyToken> tokens = new System.Collections.Concurrent.ConcurrentQueue<KeyToken>();
        static HookProc proc = Hook, mouseProc = MouseHook; // kept, so the collector leaves them
        static uint thread;
        static bool shift, ctrl, alt, altGr, win;
        static char dead; // a dead key (acute, grave, circumflex, diaeresis, tilde) waiting for its letter
        static KeyToken press; // the mouse button held down

        public static bool Running { get { return thread != 0; } }

        public static void Start() {
            if (Running) return;
            shift = ctrl = alt = altGr = win = false;
            dead = '\0';
            press = null;
            KeyToken ignored;
            while (tokens.TryDequeue(out ignored)) { }
            var started = new System.Threading.ManualResetEvent(false);
            var t = new System.Threading.Thread(delegate () {
                var hook = SetWindowsHookEx(13, proc, GetModuleHandle(null), 0); // WH_KEYBOARD_LL
                var mouseHook = SetWindowsHookEx(14, mouseProc, GetModuleHandle(null), 0); // WH_MOUSE_LL
                thread = GetCurrentThreadId();
                started.Set();
                MSG msg;
                while (GetMessage(out msg, IntPtr.Zero, 0, 0) > 0) { } // the hooks run from this loop
                UnhookWindowsHookEx(hook);
                UnhookWindowsHookEx(mouseHook);
            });
            t.IsBackground = true;
            t.Start();
            started.WaitOne(2000);
        }

        public static void Stop() {
            if (!Running) return;
            PostThreadMessage(thread, 0x12, IntPtr.Zero, IntPtr.Zero); // WM_QUIT
            thread = 0;
        }

        public static List<KeyToken> Drain() {
            var list = new List<KeyToken>();
            KeyToken token;
            while (tokens.TryDequeue(out token)) list.Add(token);
            return list;
        }

        static void Add(char kind, string value) { tokens.Enqueue(new KeyToken { Time = Environment.TickCount, Kind = kind, Value = value }); }

        static IntPtr Hook(int code, IntPtr message, IntPtr data) {
            try {
                if (code >= 0) {
                    var key = (KBDLLHOOKSTRUCT)Marshal.PtrToStructure(data, typeof(KBDLLHOOKSTRUCT));
                    int m = message.ToInt32();
                    bool down = m == 0x100 || m == 0x104, up = m == 0x101 || m == 0x105;
                    if ((key.Flags & 0x10) == 0 && (down || up)) Key(key.Vk, key.Scan, down); // not LLKHF_INJECTED
                }
            } catch { }
            return CallNextHookEx(IntPtr.Zero, code, message, data);
        }

        // Kept short: Windows drops a hook that keeps the mouse waiting. Moves are skipped at once.
        static IntPtr MouseHook(int code, IntPtr message, IntPtr data) {
            try {
                int m = message.ToInt32();
                if (code >= 0 && m != 0x200) {
                    var e = (MSLLHOOKSTRUCT)Marshal.PtrToStructure(data, typeof(MSLLHOOKSTRUCT));
                    if ((e.Flags & 1) == 0) Mouse(m, e.X, e.Y, (short)(e.Data >> 16)); // not LLMHF_INJECTED
                }
            } catch { }
            return CallNextHookEx(IntPtr.Zero, code, message, data);
        }

        static void Mouse(int m, int x, int y, int wheel) {
            switch (m) {
                case 0x201: case 0x204: // left / right button down: what's there is looked up now, before the click changes it
                    press = new KeyToken { Kind = 'm', Value = m == 0x201 ? "left" : "right", X = x, Y = y, Window = RootAt(x, y) };
                    var token = press;
                    System.Threading.ThreadPool.QueueUserWorkItem(delegate {
                        try { token.Name = Uia.NameAt(x, y); } catch { }
                        token.Ready = true;
                    });
                    return;
                case 0x202: case 0x205: // up: a click, or a drag when it moved
                    var p = press;
                    press = null;
                    if (p == null || (p.Value == "left") != (m == 0x202)) return;
                    if (p.Value == "left" && (Math.Abs(x - p.X) > 8 || Math.Abs(y - p.Y) > 8)) { p.Value = "drag"; p.X2 = x; p.Y2 = y; }
                    p.Time = Environment.TickCount;
                    tokens.Enqueue(p);
                    return;
                case 0x20A:
                    tokens.Enqueue(new KeyToken { Time = Environment.TickCount, Kind = 'm', Value = "wheel", X = x, Y = y, Amount = wheel, Window = RootAt(x, y), Ready = true });
                    return;
            }
        }

        static long RootAt(int x, int y) {
            var h = WindowFromPoint(new POINT { X = x, Y = y });
            return h == IntPtr.Zero ? 0 : GetAncestor(h, 2).ToInt64(); // GA_ROOT
        }

        static void Key(uint vk, uint scan, bool down) {
            switch (vk) {
                case 0xA0: case 0xA1: case 0x10: shift = down; return;
                case 0xA2: case 0xA3: case 0x11: ctrl = down; return;
                case 0xA4: case 0x12: alt = down; return;
                case 0xA5: altGr = down; return; // right Alt: AltGr on Spanish layouts (it also sends Ctrl)
                case 0x5B: case 0x5C: win = down; return;
            }
            if (!down) return;
            string name = Name(vk, scan);
            // A shortcut: Win, Ctrl or (left) Alt held, not AltGr typing @ # ...
            if (win || alt || (ctrl && !altGr)) {
                if (name == null) return;
                var combo = new StringBuilder();
                if (win) combo.Append("win+");
                if (ctrl && !altGr) combo.Append("ctrl+");
                if (alt) combo.Append("alt+");
                if (shift) combo.Append("shift+");
                Add('s', combo.Append(name).ToString());
                return;
            }
            switch (vk) {
                case 0x08: Add('b', null); return;
                case 0x0D: Add('t', "\n"); return;
                case 0x09: if (shift) Add('s', "shift+tab"); else Add('t', "\t"); return;
            }
            if (name != null && IsNamedKey(vk)) { Add('s', (shift ? "shift+" : "") + name); return; }
            // Typed text, with the window in front's keyboard layout. Flag 4: don't touch the
            // keyboard's dead-key state (else accents typed on this keyboard would break).
            var state = new byte[256];
            if (shift) state[0x10] = 0x80;
            if (altGr) { state[0x11] = 0x80; state[0x12] = 0x80; }
            if ((GetKeyState(0x14) & 1) != 0) state[0x14] = 1;
            int ignored;
            var layout = GetKeyboardLayout(GetWindowThreadProcessId(GetForegroundWindow(), out ignored));
            var text = new StringBuilder(8);
            int n = ToUnicodeEx(vk, scan, state, text, text.Capacity, 4, layout);
            if (n < 0) { dead = text.Length > 0 ? text[0] : '\0'; return; }
            if (n == 0) return;
            string typed = text.ToString(0, n);
            if (dead != '\0') { typed = Compose(dead, typed); dead = '\0'; }
            Add('t', typed);
        }

        // An accented letter from a dead key and the letter after it (acute + a, tilde + n, ...);
        // a dead key before something else stays itself. Character codes keep this file ASCII.
        static string Compose(char deadKey, string letter) {
            int mark = deadKey == (char)0xB4 || deadKey == '\'' ? 0x301 : deadKey == '`' ? 0x300 : deadKey == '^' ? 0x302
                : deadKey == (char)0xA8 || deadKey == '"' ? 0x308 : deadKey == '~' ? 0x303 : 0;
            if (mark == 0 || letter == " ") return deadKey.ToString() + (letter == " " ? "" : letter);
            string composed = (letter + (char)mark).Normalize(NormalizationForm.FormC);
            return composed.Length == letter.Length ? composed : deadKey.ToString() + letter;
        }

        static bool IsNamedKey(uint vk) {
            return vk == 0x1B || (vk >= 0x21 && vk <= 0x28) || vk == 0x2C || vk == 0x2D || vk == 0x2E || vk == 0x5D || (vk >= 0x70 && vk <= 0x87);
        }

        // The macro language's name for a key; punctuation by its place on a US keyboard (scan code).
        static string Name(uint vk, uint scan) {
            if (vk >= 0x41 && vk <= 0x5A) return ((char)('a' + vk - 0x41)).ToString();
            if (vk >= 0x30 && vk <= 0x39) return ((char)('0' + vk - 0x30)).ToString();
            if (vk >= 0x70 && vk <= 0x87) return "f" + (vk - 0x6F);
            if (vk >= 0x60 && vk <= 0x69) return "kp" + (vk - 0x60);
            switch (vk) {
                case 0x0D: return "enter"; case 0x1B: return "esc"; case 0x09: return "tab"; case 0x20: return "space";
                case 0x08: return "backspace"; case 0x2E: return "delete"; case 0x2D: return "insert";
                case 0x24: return "home"; case 0x23: return "end"; case 0x21: return "pageup"; case 0x22: return "pagedown";
                case 0x25: return "left"; case 0x26: return "up"; case 0x27: return "right"; case 0x28: return "down";
                case 0x2C: return "printscreen"; case 0x5D: return "menu";
                case 0x6A: return "kpasterisk"; case 0x6B: return "kpplus"; case 0x6D: return "kpminus";
                case 0x6E: return "kpdot"; case 0x6F: return "kpslash";
            }
            switch (scan) {
                case 0x0C: return "minus"; case 0x0D: return "equal"; case 0x1A: return "lbracket"; case 0x1B: return "rbracket";
                case 0x2B: return "backslash"; case 0x27: return "semicolon"; case 0x28: return "quote"; case 0x29: return "grave";
                case 0x33: return "comma"; case 0x34: return "period"; case 0x35: return "slash"; case 0x56: return "iso";
            }
            return null;
        }
    }

    // Clicks, drags and scrolls at a point of the screen (physical pixels).
    public static class Pointer {
        [DllImport("user32.dll")] static extern bool SetCursorPos(int x, int y);
        [DllImport("user32.dll")] static extern void mouse_event(uint flags, int dx, int dy, int data, UIntPtr extra);
        const uint LeftDown = 0x02, LeftUp = 0x04, RightDown = 0x08, RightUp = 0x10, Wheel = 0x800;

        public static void Do(int x, int y, string action, int amount) {
            SetCursorPos(x, y);
            switch (action) {
                case "tap": mouse_event(LeftDown, 0, 0, 0, UIntPtr.Zero); mouse_event(LeftUp, 0, 0, 0, UIntPtr.Zero); break;
                case "right": mouse_event(RightDown, 0, 0, 0, UIntPtr.Zero); mouse_event(RightUp, 0, 0, 0, UIntPtr.Zero); break;
                case "down": mouse_event(LeftDown, 0, 0, 0, UIntPtr.Zero); break;
                case "up": mouse_event(LeftUp, 0, 0, 0, UIntPtr.Zero); break;
                case "scroll": mouse_event(Wheel, 0, 0, amount * 120, UIntPtr.Zero); break;
                default: break; // "move"
            }
        }

        // A recorded pc:click / pc:drag / pc:scroll: action "left", "right", "double", "drag" (to
        // x2, y2) or "scroll" (amount notches).
        public static void Act(string action, int x, int y, int x2, int y2, int amount) {
            switch (action) {
                case "left": Do(x, y, "tap", 0); break;
                case "double": Do(x, y, "tap", 0); Do(x, y, "tap", 0); break;
                case "scroll": Do(x, y, "scroll", amount); break;
                case "drag":
                    Do(x, y, "down", 0);
                    for (int i = 1; i <= 8; i++) { System.Threading.Thread.Sleep(15); SetCursorPos(x + (x2 - x) * i / 8, y + (y2 - y) * i / 8); }
                    Do(x2, y2, "up", 0);
                    break;
                default: Do(x, y, action, 0); break;
            }
        }

        [StructLayout(LayoutKind.Sequential)] struct RECT { public int Left, Top, Right, Bottom; }
        [DllImport("user32.dll")] static extern bool GetWindowRect(IntPtr h, out RECT r);
        [DllImport("user32.dll", CharSet = CharSet.Unicode)] static extern int GetClassName(IntPtr h, StringBuilder name, int count);
        [DllImport("user32.dll")] static extern uint GetWindowThreadProcessId(IntPtr h, out int processId);

        // Left, top, width, height; null when the window is gone.
        public static int[] Rect(long window) {
            RECT r;
            if (!GetWindowRect(new IntPtr(window), out r)) return null;
            return new int[] { r.Left, r.Top, r.Right - r.Left, r.Bottom - r.Top };
        }

        public static string ClassName(long window) {
            var name = new StringBuilder(256);
            GetClassName(new IntPtr(window), name, name.Capacity);
            return name.ToString();
        }

        public static int ProcessId(long window) {
            int pid;
            GetWindowThreadProcessId(new IntPtr(window), out pid);
            return pid;
        }
    }

    // Keys pressed on this computer, as if typed: for slide decks in a browser, which only listen
    // to their keys.
    public static class Keys {
        [DllImport("user32.dll")] static extern void keybd_event(byte vk, byte scan, uint flags, UIntPtr extra);
        [DllImport("user32.dll")] static extern uint MapVirtualKey(uint code, uint mapType);
        const uint Extended = 0x1, Up = 0x2;

        // Virtual key [vk], [times] times; arrows, Home and End are the extended (not numpad) ones.
        public static void Tap(int vk, int times) {
            bool extended = vk >= 0x21 && vk <= 0x28;
            byte scan = (byte)MapVirtualKey((uint)vk, 0);
            uint flags = extended ? Extended : 0;
            for (int i = 0; i < times; i++) {
                keybd_event((byte)vk, scan, flags, UIntPtr.Zero);
                keybd_event((byte)vk, scan, flags | Up, UIntPtr.Zero);
            }
        }
    }

    // Buttons by name (UI Automation, as screen readers see them), so a recorded click finds its
    // button again where it is now. Only short names of things one clicks: a cell or a document
    // has no name worth keeping.
    public static class Uia {
        static readonly UIA.ControlType[] Clickable = {
            UIA.ControlType.Button, UIA.ControlType.SplitButton, UIA.ControlType.MenuItem, UIA.ControlType.TabItem,
            UIA.ControlType.ListItem, UIA.ControlType.TreeItem, UIA.ControlType.Hyperlink, UIA.ControlType.CheckBox,
            UIA.ControlType.RadioButton,
        };

        static string UsableName(UIA.AutomationElement e) {
            var current = e.Current;
            if (Array.IndexOf(Clickable, current.ControlType) < 0) return null;
            string name = (current.Name ?? "").Trim();
            return name.Length == 0 || name.Length > 60 || name.IndexOfAny(new[] { '\n', '\r', '|' }) >= 0 ? null : name;
        }

        // The name of the button (menu item, tab...) at (x, y), or null.
        public static string NameAt(int x, int y) {
            var e = UIA.AutomationElement.FromPoint(new System.Windows.Point(x, y));
            for (int i = 0; i < 3 && e != null; i++) { // a button's label or icon is a part of it
                string name = UsableName(e);
                if (name != null) return name;
                e = UIA.TreeWalker.ControlViewWalker.GetParent(e);
            }
            return null;
        }

        // Where to click the button named [name]: the one at (x, y) if it's there, else the one
        // nearest it in window [window] or another window of its app (a menu...); null if none.
        public static int[] Find(long window, string name, int x, int y) {
            try {
                var e = UIA.AutomationElement.FromPoint(new System.Windows.Point(x, y));
                for (int i = 0; i < 3 && e != null; i++) {
                    if (string.Equals(UsableName(e), name, StringComparison.OrdinalIgnoreCase)) return ClickPoint(e);
                    e = UIA.TreeWalker.ControlViewWalker.GetParent(e);
                }
            } catch { }
            if (window == 0) return null;
            var root = UIA.AutomationElement.FromHandle(new IntPtr(window));
            var roots = new List<UIA.AutomationElement> { root };
            var app = new UIA.PropertyCondition(UIA.AutomationElement.ProcessIdProperty, root.Current.ProcessId);
            foreach (UIA.AutomationElement w in UIA.AutomationElement.RootElement.FindAll(UIA.TreeScope.Children, app)) {
                if (w.Current.NativeWindowHandle != window) roots.Add(w);
            }
            var named = new UIA.AndCondition(
                new UIA.PropertyCondition(UIA.AutomationElement.NameProperty, name, UIA.PropertyConditionFlags.IgnoreCase),
                new UIA.PropertyCondition(UIA.AutomationElement.IsOffscreenProperty, false));
            int[] best = null;
            double nearest = double.MaxValue;
            foreach (var r in roots) {
                foreach (UIA.AutomationElement e in r.FindAll(UIA.TreeScope.Descendants, named)) {
                    if (UsableName(e) == null) continue;
                    var p = ClickPoint(e);
                    if (p == null) continue;
                    double d = Math.Pow(p[0] - x, 2) + Math.Pow(p[1] - y, 2);
                    if (d < nearest) { nearest = d; best = p; }
                }
            }
            return best;
        }

        static readonly Dictionary<long, UIA.AutomationElement> addressBars = new Dictionary<long, UIA.AutomationElement>();

        // What browser window [window]'s address bar shows (its first edit box: Chrome's and Edge's
        // in any language); null when it has none or hides it (full screen). The box is looked up
        // once per window.
        public static string AddressBar(long window) {
            UIA.AutomationElement bar;
            if (!addressBars.TryGetValue(window, out bar)) {
                if (addressBars.Count > 50) addressBars.Clear();
                var edit = new UIA.PropertyCondition(UIA.AutomationElement.ControlTypeProperty, UIA.ControlType.Edit);
                bar = UIA.AutomationElement.FromHandle(new IntPtr(window)).FindFirst(UIA.TreeScope.Descendants, edit);
                if (bar == null) return null; // not yet (a window opening), or none: look again next time
                addressBars[window] = bar;
            }
            try {
                if (bar.Current.IsOffscreen) return null;
                object pattern;
                if (!bar.TryGetCurrentPattern(UIA.ValuePattern.Pattern, out pattern)) return null;
                return ((UIA.ValuePattern)pattern).Current.Value;
            } catch (UIA.ElementNotAvailableException) {
                addressBars.Remove(window);
                return null;
            }
        }

        static int[] ClickPoint(UIA.AutomationElement e) {
            System.Windows.Point p;
            if (e.TryGetClickablePoint(out p)) return new int[] { (int)p.X, (int)p.Y };
            var r = e.Current.BoundingRectangle;
            if (r.IsEmpty || r.Width <= 0 || r.Height <= 0) return null;
            return new int[] { (int)(r.X + r.Width / 2), (int)(r.Y + r.Height / 2) };
        }
    }
}
'@
[MacropadLink.Mirror]::UsePhysicalPixels()

$ProcessInfo = @{}   # process id -> @{ exe; app; path }

# Process name ("chrome"), the program's name ("Google Chrome") and its file; $null if it's gone.
function Get-ProcessInfo([int]$Id) {
    $info = $script:ProcessInfo[$Id]
    if ($info) { return $info }
    $process = Get-Process -Id $Id -ErrorAction SilentlyContinue
    if (-not $process) { return $null }
    # Both are empty for processes of other users or running as administrator.
    $path = try { $process.Path } catch { $null }
    $description = try { $process.Description } catch { $null }
    if ($script:ProcessInfo.Count -gt 500) { $script:ProcessInfo.Clear() } # process ids get reused
    $info = @{ exe = $process.ProcessName.ToLower(); app = $(if ($description) { $description } else { $process.ProcessName }); path = $path }
    $script:ProcessInfo[$Id] = $info
    return $info
}

$SentIcons = @{}   # exe -> $true, per connection

# The app's icon, once per connection, before the first message that lists the app.
function Send-Icon($Info, [long]$Window) {
    if ($script:SentIcons.ContainsKey($Info.exe)) { return }
    $script:SentIcons[$Info.exe] = $true
    $png = [MacropadLink.Windows]::IconPng($Window, $Info.path)
    if ($png) { Send-Tablet ([ordered]@{ t = 'icon'; exe = $Info.exe; png = [Convert]::ToBase64String($png) }) }
}

# ------------------------------------------------------------------ open windows (the Switcher page)

# Errors reading the computer are caught; errors writing to the tablet are not (they end the link).
function Send-Windows([switch]$Always) {
    $list = New-Object System.Collections.ArrayList
    $icons = @{}
    try {
        foreach ($w in [MacropadLink.Windows]::List()) {
            $info = Get-ProcessInfo $w.Pid
            if (-not $info) { continue }
            if (-not $icons.ContainsKey($info.exe)) { $icons[$info.exe] = @($info, $w.Id) }
            [void]$list.Add([ordered]@{ id = $w.Id; exe = $info.exe; app = $info.app; title = $w.Title })
        }
        $front = [MacropadLink.Windows]::Front()
    } catch {
        Write-Traffic "Windows: $($_.Exception.Message)"
        return
    }
    foreach ($icon in $icons.Values) { Send-Icon $icon[0] $icon[1] }
    Send-Changed ([ordered]@{ t = 'windows'; front = $front; list = $list }) -Always:$Always
}

function Invoke-Focus($Request) {
    $ok = $false
    try { $ok = [MacropadLink.Windows]::Focus([long]$Request.window) } catch { }
    if ($ok) { Send-Result $Request.id $true $null }
    else { Send-Result $Request.id $false "Couldn't bring it to the front (closed, or Windows refused)" }
    # Show the new window in front at once.
    $script:Next.windows = Get-Date
    $script:Next.app = Get-Date
}

# ------------------------------------------------------------------ waiting for windows (pc:focus, pc:wait)

# A macro on the tablet holds at pc:focus / pc:wait until the result comes: when its window is in
# front, or after 10 s (which stops the macro). Checked on every turn of the main loop.
$Waits = New-Object System.Collections.ArrayList   # @{ id; kind; target; until }

function Add-Wait($Request, [string]$Kind, [string]$Target) {
    if (-not $Target) { return Send-Result $Request.id $false "pc:$Kind needs an app or a title" }
    [void]$script:Waits.Add(@{ id = $Request.id; kind = $Kind; target = $Target.ToLower(); until = (Get-Date).AddSeconds(10) })
    Test-Waits
}

# App $Exe's window used last (Alt+Tab order), or $null.
function Find-AppWindow([string]$Exe) {
    foreach ($w in [MacropadLink.Windows]::List()) {
        $info = Get-ProcessInfo $w.Pid
        if ($info -and $info.exe -eq $Exe) { return $w }
    }
    return $null
}

function Test-Waits {
    if ($script:Waits.Count -eq 0) { return }
    $now = Get-Date
    $front = Get-FrontApp
    foreach ($wait in @($script:Waits)) {
        $done = $false
        try {
            if ($wait.kind -eq 'focus') {
                # Its window may still be opening: keep trying until it's there.
                if ($front -and $front.exe -eq $wait.target) { $done = $true }
                else { $window = Find-AppWindow $wait.target; if ($window) { $done = [MacropadLink.Windows]::Focus($window.Id) } }
            } elseif ($wait.kind -eq 'click') {
                $done = Invoke-Click $wait
            } else {
                $done = $front -and ($front.exe -eq $wait.target -or $front.title.ToLower().Contains($wait.target))
            }
        } catch { }
        if ($done) {
            Send-Result $wait.id $true $null
        } elseif ($now -ge $wait.until) {
            Send-Result $wait.id $false $(if ($wait.kind -eq 'wait') { "waited 10 s for $($wait.target)" } else { "no $($wait.target) window to bring to the front" })
        } else {
            continue
        }
        $script:Waits.Remove($wait)
    }
}

# ------------------------------------------------------------------ recording this computer's keyboard and mouse (experimental, -AllowRecording)

# While the tablet records a macro it can ask for this keyboard's keys too. They are held a moment
# before going to the tablet, so the ones the tablet itself sent (it says so: "pause") can be
# dropped; typed letters go as one text step, sent when typing stops or a shortcut comes.
$Capture = $null   # @{ until; paused; ignoreTo; pending; text; textAt } while capturing
$CaptureHold = 400 # ms a key waits before it goes to the tablet

function Set-Capture($Request) {
    if ($null -ne $Request.on) {
        if (-not $Request.on) { return Stop-Capture 'the tablet stopped recording' }
        if (-not $AllowRecording) { return Write-Log 'The tablet asked to record the keyboard and mouse; start with -AllowRecording to allow it' }
        [MacropadLink.KeyCapture]::Start()
        $script:Capture = @{ until = (Get-Date).AddMinutes(5); paused = $false; ignoreTo = 0; pending = New-Object System.Collections.ArrayList; text = ''; textAt = 0 }
        Write-Log 'Recording the keyboard and mouse for the tablet (5 minutes at most)'
        return
    }
    $c = $script:Capture
    if (-not $c -or $null -eq $Request.pause) { return }
    if ($Request.pause) {
        # The tablet's own keys are on their way (or here already): leave them out.
        Read-Capture
        $from = [Environment]::TickCount - $CaptureHold - 100
        foreach ($token in @($c.pending)) { if ($token.Time -ge $from) { $c.pending.Remove($token) } }
        $c.paused = $true
    } else {
        $c.paused = $false
        $c.ignoreTo = [Environment]::TickCount + 300
    }
}

function Stop-Capture([string]$Why) {
    if (-not $script:Capture) { return }
    [MacropadLink.KeyCapture]::Stop()
    Send-CaptureText
    Send-CaptureWheel
    $script:Capture = $null
    Write-Log "Stopped recording the keyboard and mouse ($Why)"
}

function Send-CaptureText {
    $c = $script:Capture
    if (-not $c -or -not $c.text) { return }
    Send-Tablet ([ordered]@{ t = 'recorded'; step = 'text:' + $c.text.Replace("`n", '\n').Replace("`t", '\t') })
    $c.text = ''
}

# New keys from the hook into the pending list (unless the tablet's own are going out).
function Read-Capture {
    $c = $script:Capture
    foreach ($token in [MacropadLink.KeyCapture]::Drain()) {
        if (-not $c.paused -and $token.Time -gt $c.ignoreTo) { [void]$c.pending.Add($token) }
    }
}

function Send-Capture {
    $c = $script:Capture
    if (-not $c) { return }
    if ((Get-Date) -gt $c.until) { return Stop-Capture '5 minutes' }
    if ($script:WasLocked) { return Stop-Capture 'the computer locked' }
    Read-Capture
    $now = [Environment]::TickCount
    $double = [Windows.Forms.SystemInformation]::DoubleClickTime
    while ($c.pending.Count -gt 0) {
        $token = $c.pending[0]
        $age = $now - $token.Time
        if ($age -lt $CaptureHold) { break }
        if ($token.Kind -eq 'm') {
            # Wait for the button's name (UI Automation can be slow), and a left click for a second one.
            if (-not $token.Ready -and $age -lt 1500) { break }
            if ($token.Value -eq 'left') {
                $next = $c.pending | Select-Object -Skip 1 -First 1
                if ($next -and $next.Kind -eq 'm' -and $next.Value -eq 'left' -and $next.Time - $token.Time -le $double -and
                    [Math]::Abs($next.X - $token.X) -le 4 -and [Math]::Abs($next.Y - $token.Y) -le 4) {
                    $token.Value = 'double'
                    $c.pending.Remove($next)
                } elseif (-not $next -and $age -lt $double + 50) { break }
            }
        }
        $c.pending.RemoveAt(0)
        if ($token.Kind -ne 'm' -or $token.Value -ne 'wheel') { Send-CaptureWheel }
        switch ($token.Kind) {
            't' { $c.text += $token.Value; $c.textAt = $token.Time }
            'b' { if ($c.text) { $c.text = $c.text.Substring(0, $c.text.Length - 1) } else { Send-Tablet ([ordered]@{ t = 'recorded'; step = 'backspace' }) } }
            'm' { Send-CaptureText; Send-CaptureMouse $token }
            default { Send-CaptureText; Send-Tablet ([ordered]@{ t = 'recorded'; step = $token.Value }) }
        }
    }
    # Typing paused: the text so far is a step (pauses then show between the steps on the tablet).
    if ($c.text -and $now - $c.textAt -ge 1500) { Send-CaptureText }
    if ($c.wheel -and $now - $c.wheel.Time -ge 1000) { Send-CaptureWheel }
}

# The mouse as a pc:click / pc:drag step; wheel turns add up (Send-CaptureWheel sends them).
function Send-CaptureMouse($Token) {
    $c = $script:Capture
    if ($Token.Value -eq 'wheel') {
        if ($c.wheel -and $c.wheel.Window -eq $Token.Window -and [Math]::Sign($c.wheel.Amount) -eq [Math]::Sign($Token.Amount)) {
            $c.wheel.Amount += $Token.Amount
            $c.wheel.Time = $Token.Time
        } else {
            Send-CaptureWheel
            $c.wheel = $Token
        }
        return
    }
    $place = Get-ClickPlace $Token.Window
    $at = "$($Token.X - $place.x),$($Token.Y - $place.y)"
    $step = if ($Token.Value -eq 'drag') { "pc:drag $($place.app) $at $($Token.X2 - $place.x),$($Token.Y2 - $place.y)" }
        else { "pc:click $($place.app) $at" + $(if ($Token.Value -ne 'left') { " $($Token.Value)" }) + $(if ($Token.Name) { " | $($Token.Name)" }) }
    Send-Tablet ([ordered]@{ t = 'recorded'; step = $step })
}

function Send-CaptureWheel {
    $c = $script:Capture
    $wheel = $c.wheel
    if (-not $wheel) { return }
    $c.wheel = $null
    $notches = [int][Math]::Round($wheel.Amount / 120)
    if ($notches -eq 0) { return }
    $place = Get-ClickPlace $wheel.Window
    Send-Tablet ([ordered]@{ t = 'recorded'; step = "pc:scroll $($place.app) $($wheel.X - $place.x),$($wheel.Y - $place.y) $notches" })
}

# Where a click on top-level window $Window is measured from: its app's window (a menu or a
# dialog counts as its app's: the app's window used last), so the step works wherever that window
# is; or the screen, for the taskbar, the desktop and the Start menu.
function Get-ClickPlace([long]$Window) {
    $screen = @{ app = 'screen'; x = 0; y = 0 }
    if (-not $Window) { return $screen }
    $class = [MacropadLink.Pointer]::ClassName($Window)
    if ($class -in 'Shell_TrayWnd', 'Shell_SecondaryTrayWnd', 'Progman', 'WorkerW', 'Windows.UI.Core.CoreWindow', 'NotifyIconOverflowWindow') { return $screen }
    $info = Get-ProcessInfo ([MacropadLink.Pointer]::ProcessId($Window))
    if (-not $info -or $info.exe -match '\s') { return $screen }
    $listed = @([MacropadLink.Windows]::List() | Where-Object { $_.Id -eq $Window }).Count -gt 0
    $reference = if ($listed) { $Window } else { $w = Find-AppWindow $info.exe; if ($w) { $w.Id } }
    $rect = if ($reference) { [MacropadLink.Pointer]::Rect($reference) }
    if (-not $rect) { return $screen }
    return @{ app = $info.exe; x = $rect[0]; y = $rect[1] }
}

# ------------------------------------------------------------------ replaying recorded clicks (pc:click, pc:drag, pc:scroll)

# Held in $Waits like pc:focus: its app's window comes to the front first, then a named button
# gets 3 s to show up before the click goes where it was recorded.
function Add-Click($Request) {
    $app = ([string]$Request.app).ToLower()
    if (-not $app) { return Send-Result $Request.id $false 'pc:click needs an app' }
    [void]$script:Waits.Add(@{ id = $Request.id; kind = 'click'; target = $app; request = $Request
        until = (Get-Date).AddSeconds(10); nameUntil = (Get-Date).AddSeconds(3) })
    Test-Waits
}

# One try at a click; $true once it's done.
function Invoke-Click($Wait) {
    $r = $Wait.request
    $x = [int]$r.x; $y = [int]$r.y; $x2 = [int]$r.x2; $y2 = [int]$r.y2
    $window = 0
    if ($Wait.target -ne 'screen') {
        $found = Find-AppWindow $Wait.target
        if (-not $found) { return $false }
        $front = Get-FrontApp
        if (-not $front -or $front.exe -ne $Wait.target) { [void][MacropadLink.Windows]::Focus($found.Id); return $false }
        $window = $found.Id
        $rect = [MacropadLink.Pointer]::Rect($window)
        if (-not $rect) { return $false }
        $x += $rect[0]; $y += $rect[1]; $x2 += $rect[0]; $y2 += $rect[1]
    }
    if ($r.name) {
        $at = $null
        try { $at = [MacropadLink.Uia]::Find($window, [string]$r.name, $x, $y) } catch { }
        if ($at) { $x = $at[0]; $y = $at[1] }
        elseif ((Get-Date) -lt $Wait.nameUntil) { return $false }
        else { Write-Log "pc:click: no '$($r.name)' in $($Wait.target); clicked where it was recorded" }
    }
    [MacropadLink.Pointer]::Act([string]$r.do, $x, $y, $x2, $y2, [int]$r.amount)
    return $true
}

# ------------------------------------------------------------------ the clipboard

Add-Type -AssemblyName System.Windows.Forms
$ClipSequence = $null
$ClipLimit = 20000   # characters; longer texts aren't sent

# Password managers mark what they copy so clipboard tools leave it alone; so does this.
function Test-PrivateClip($Data) {
    if ($Data.GetDataPresent('ExcludeClipboardContentFromMonitorProcessing')) { return $true }
    if (-not $Data.GetDataPresent('CanIncludeInClipboardHistory')) { return $false }
    $stream = $Data.GetData('CanIncludeInClipboardHistory') -as [IO.MemoryStream]
    return $stream -and $stream.Length -ge 4 -and [BitConverter]::ToInt32($stream.ToArray(), 0) -eq 0
}

# Sends the clipboard's text when it changes ($Always: the current one anyway). The tablet keeps
# the history.
function Send-Clipboard([switch]$Always) {
    if ($NoClipboard) { return }
    $sequence = [MacropadLink.Win32]::GetClipboardSequenceNumber()
    if (-not $Always -and $sequence -eq $script:ClipSequence) { return }
    try {
        $data = [Windows.Forms.Clipboard]::GetDataObject()
        if (-not $data -or -not $data.GetDataPresent([Windows.Forms.DataFormats]::UnicodeText)) { $text = $null }
        elseif (Test-PrivateClip $data) { $text = $null; Write-Traffic 'Clipboard: marked private, not sent' }
        else { $text = [string]$data.GetData([Windows.Forms.DataFormats]::UnicodeText) }
    } catch {
        return # another app has the clipboard open; try again next time
    }
    $script:ClipSequence = $sequence
    if ($text -and $text.Length -le $ClipLimit) { Send-Tablet ([ordered]@{ t = 'clip'; text = $text }) }
}

# ------------------------------------------------------------------ volume (the mixer and the volume slider)

$AudioHintShown = $false

function Send-Volume([switch]$Always) {
    $apps = New-Object System.Collections.ArrayList
    $infos = @{}
    try {
        foreach ($s in [MacropadLink.Audio]::Sessions()) {
            $info = Get-ProcessInfo $s.Pid
            # One channel per app (a browser has several sessions; they move together).
            if (-not $info -or $infos.ContainsKey($info.exe)) { continue }
            $infos[$info.exe] = $info
            [void]$apps.Add([ordered]@{ exe = $info.exe; app = $info.app; level = [int][Math]::Round($s.Level * 100); muted = $s.Muted })
        }
        $message = [ordered]@{
            t = 'volume'; level = [int][Math]::Round([MacropadLink.Audio]::Level() * 100)
            muted = [MacropadLink.Audio]::Muted(); apps = $apps
        }
        $script:AudioHintShown = $false
    } catch {
        if (-not $script:AudioHintShown) { Write-Log "Volume: not available ($($_.Exception.Message))"; $script:AudioHintShown = $true }
        return
    }
    foreach ($info in $infos.Values) { Send-Icon $info 0 }
    Send-Changed $message -Always:$Always
}

# {"t":"volume","app":"spotify","level":40,"mute":true}: the speakers without "app".
function Set-Volume($Request) {
    try {
        $level = if ($null -ne $Request.level) { [float]$Request.level / 100 } else { -1 }
        $mute = if ($null -ne $Request.mute) { [bool]$Request.mute } else { $null }
        if ($Request.app) {
            foreach ($s in [MacropadLink.Audio]::Sessions()) {
                $info = Get-ProcessInfo $s.Pid
                if ($info -and $info.exe -eq [string]$Request.app) { [MacropadLink.Audio]::SetApp($s.Pid, $level, $mute) }
            }
        } else {
            if ($level -ge 0) { [MacropadLink.Audio]::SetLevel($level) }
            if ($null -ne $mute) { [MacropadLink.Audio]::SetMuted($mute) }
        }
    } catch {
        Write-Log "Couldn't change the volume: $($_.Exception.Message)"
    }
    $script:Next.volume = Get-Date
}

# ------------------------------------------------------------------ what's playing (Windows' media controls)

$Media = $null   # the session manager; $false when Windows doesn't offer it

function Wait-WinRt($Operation, [Type]$Type) {
    $task = $script:AsTask.MakeGenericMethod($Type).Invoke($null, @($Operation))
    if (-not $task.Wait(2000)) { throw 'timed out' }
    return $task.Result
}

function Initialize-Media {
    try {
        Add-Type -AssemblyName System.Runtime.WindowsRuntime
        $script:AsTask = [System.WindowsRuntimeSystemExtensions].GetMethods() | Where-Object {
            $_.Name -eq 'AsTask' -and $_.GetParameters().Count -eq 1 -and $_.GetParameters()[0].ParameterType.Name -eq 'IAsyncOperation`1'
        } | Select-Object -First 1
        $type = [Windows.Media.Control.GlobalSystemMediaTransportControlsSessionManager, Windows.Media.Control, ContentType = WindowsRuntime]
        $script:Media = Wait-WinRt ($type::RequestAsync()) $type
    } catch {
        Write-Log "What's playing: not available ($($_.Exception.Message))"
        $script:Media = $false
    }
}

function Send-Media([switch]$Always) {
    if ($null -eq $script:Media) { Initialize-Media }
    if (-not $script:Media) { return }
    $message = [ordered]@{ t = 'media' }
    try {
        $session = $script:Media.GetCurrentSession()
        if ($session) {
            $properties = Wait-WinRt ($session.TryGetMediaPropertiesAsync()) ([Windows.Media.Control.GlobalSystemMediaTransportControlsSessionMediaProperties, Windows.Media.Control, ContentType = WindowsRuntime])
            if ($properties.Title) {
                $message.title = $properties.Title
                if ($properties.Artist) { $message.artist = $properties.Artist }
                $message.playing = [string]$session.GetPlaybackInfo().PlaybackStatus -eq 'Playing'
            }
        }
    } catch {
        return # the app went away while asking; next time
    }
    Send-Changed $message -Always:$Always
}

# ------------------------------------------------------------------ the terminal (shell hook)

# The folder and venv of the terminal last used, from shell.txt (-InstallShellHook); nothing
# once that terminal is closed. A venv's Python version comes from its pyvenv.cfg.
function Send-Shell([switch]$Always) {
    if (-not (Test-Path $ShellFile)) { return }
    $message = [ordered]@{ t = 'shell' }
    try {
        $lines = [IO.File]::ReadAllText($ShellFile) -split "`n"
        if ($lines.Count -ge 2 -and (Get-Process -Id ([int]$lines[0]) -ErrorAction SilentlyContinue)) {
            $message.cwd = $lines[1].Trim()
            $venv = if ($lines.Count -ge 3) { $lines[2].Trim() } else { '' }
            if ($venv) {
                $message.venv = $venv
                $config = Join-Path $venv 'pyvenv.cfg'
                if (Test-Path $config) {
                    $found = Select-String -Path $config -Pattern '^\s*version(_info)?\s*=\s*(\d+\.\d+(\.\d+)?)' | Select-Object -First 1
                    if ($found) { $message.python = $found.Matches[0].Groups[2].Value }
                }
            }
        }
    } catch {
        return # being written; next time
    }
    Send-Changed $message -Always:$Always
}

# ------------------------------------------------------------------ the Screen page (a monitor, mirrored and touchable)

$Mirror = $null       # @{ monitor; w; h; seq; acked; full; next; sentAt } while the tablet mirrors a monitor
$MirrorQuality = 50   # JPEG quality: a whole 1600 x 900 picture is ~200 KB

# Monitors numbered from the left (then from the top), as the tablet shows them.
function Get-Monitors {
    $number = 0
    foreach ($s in [Windows.Forms.Screen]::AllScreens | Sort-Object { $_.Bounds.X }, { $_.Bounds.Y }) {
        $number++
        @{ n = $number; bounds = $s.Bounds; primary = $s.Primary }
    }
}

function Send-Monitors([switch]$Always) {
    try {
        $list = New-Object System.Collections.ArrayList
        foreach ($m in Get-Monitors) { [void]$list.Add([ordered]@{ n = $m.n; w = $m.bounds.Width; h = $m.bounds.Height; primary = $m.primary }) }
    } catch {
        return
    }
    Send-Changed ([ordered]@{ t = 'monitors'; list = $list }) -Always:$Always
}

# {"t":"screen","monitor":1,"w":1600,"h":1000,"view":3} starts (a whole picture first); "vx","vy","vw"
# (0-1) mirror just that part of the monitor (zoom). {"t":"screen","stop":true} stops.
function Set-Mirror($Request) {
    [MacropadLink.Mirror]::Reset()
    if ($Request.stop) {
        if ($script:Mirror) { Write-Log 'Screen: stopped' }
        $script:Mirror = $null
        return
    }
    $size = if ($Request.vw) { [Math]::Min(1.0, [Math]::Max(0.05, [double]$Request.vw)) } else { 1.0 }
    $script:Mirror = @{
        monitor = [int]$Request.monitor; w = [int]$Request.w; h = [int]$Request.h; view = [int]$Request.view
        vx = [double]$Request.vx; vy = [double]$Request.vy; vw = $size
        # Numbers go on from the last picture, so a late acknowledgement can't pass for a new one.
        seq = $(if ($script:Mirror) { $script:Mirror.seq } else { 0 }); acked = $true; full = $true; next = Get-Date; sentAt = Get-Date
    }
    Write-Traffic "Screen: monitor $($Request.monitor), from $($Request.vx),$($Request.vy) size $size"
}

# Sends the next picture once the tablet has the last one (so Bluetooth is never flooded) and the
# screen changed; a picture lost for 10 s is sent again whole.
function Send-Screen {
    $m = $script:Mirror
    if (-not $m) { return }
    $now = Get-Date
    if (-not $m.acked) {
        if (($now - $m.sentAt).TotalSeconds -lt 10) { return }
        $m.acked = $true
        $m.full = $true
    }
    if ($now -lt $m.next) { return }
    $monitor = Get-Monitors | Where-Object { $_.n -eq $m.monitor } | Select-Object -First 1
    if (-not $monitor) { return }
    $b = $monitor.bounds
    # The part of the monitor asked for (all of it unless zoomed).
    $w = [int]($b.Width * $m.vw)
    $h = [int]($b.Height * $m.vw)
    $x = $b.X + [int][Math]::Min($b.Width - $w, [Math]::Max(0, $m.vx * $b.Width))
    $y = $b.Y + [int][Math]::Min($b.Height - $h, [Math]::Max(0, $m.vy * $b.Height))
    $lines = [MacropadLink.Mirror]::Frame($x, $y, $w, $h, $m.w, $m.h, $MirrorQuality, $m.full, $m.seq + 1, $m.view)
    $m.next = $now.AddMilliseconds(100)
    if (-not $lines) { return } # locked, or the secure desktop
    # Nothing changed and the pointer didn't move: nothing to send.
    $last = $lines[$lines.Count - 1]
    if (-not $m.full -and $lines.Count -eq 1 -and $last.Contains('"tiles":[]') -and $last -eq $m.idle) { return }
    $m.full = $false
    $m.seq++
    $m.idle = $last -replace '"seq":\d+', "`"seq`":$($m.seq + 1)"
    foreach ($line in $lines) { $script:Link.Write($line + "`n") }
    Write-Traffic "-> frame $($m.seq): $($lines.Count) lines, $([int](($lines | Measure-Object -Property Length -Sum).Sum / 1024)) KB"
    $m.acked = $false
    $m.sentAt = $now
}

function Use-ScreenAck($Request) {
    $m = $script:Mirror
    if ($m -and [int]$Request.seq -eq $m.seq) { $m.acked = $true }
}

# {"t":"pointer","monitor":1,"x":0.5,"y":0.25,"do":"tap"}: tap, right, down, move, up, scroll ("amount").
function Invoke-Pointer($Request) {
    $monitor = Get-Monitors | Where-Object { $_.n -eq [int]$Request.monitor } | Select-Object -First 1
    if (-not $monitor) { return }
    $b = $monitor.bounds
    $x = $b.X + [int][Math]::Min($b.Width - 1, [double]$Request.x * $b.Width)
    $y = $b.Y + [int][Math]::Min($b.Height - 1, [double]$Request.y * $b.Height)
    try { [MacropadLink.Pointer]::Do($x, $y, [string]$Request.do, [int]$Request.amount) } catch { }
    if ($script:Mirror) { $script:Mirror.next = Get-Date } # show the result soon
}

# ------------------------------------------------------------------ locked, keyboard layout

$LockedFor = 0      # checks in a row that found Windows' input desktop hidden
$WasLocked = $null

# Locked (Win+L, or after a while): the tablet dims, and wakes when it's unlocked. Windows' own
# session state says; if it can't, the input desktop being hidden two checks in a row (seconds
# apart, since a UAC prompt hides it for a moment).
function Send-Lock([switch]$Always) {
    try {
        $state = [MacropadLink.Windows]::SessionLocked()
        if ($state -ge 0) {
            $locked = $state -eq 1
        } else {
            $script:LockedFor = if ([MacropadLink.Windows]::InputDesktopHidden()) { $script:LockedFor + 1 } else { 0 }
            $locked = $script:LockedFor -ge 2
        }
    } catch {
        return
    }
    if ($locked -ne $script:WasLocked) { Write-Log "Computer $(if ($locked) { 'locked' } else { 'unlocked' })"; $script:WasLocked = $locked }
    Send-Changed ([ordered]@{ t = 'lock'; locked = $locked }) -Always:$Always
}

# Layout ids by "Layout Id" (layouts other than a language's first one, e.g. US International).
$LayoutIds = @{}
try {
    foreach ($key in Get-ChildItem 'HKLM:\SYSTEM\CurrentControlSet\Control\Keyboard Layouts' -ErrorAction Stop) {
        $id = (Get-ItemProperty $key.PSPath -ErrorAction SilentlyContinue).'Layout Id'
        if ($id) { $LayoutIds[$id.ToUpper()] = $key.PSChildName.ToUpper() }
    }
} catch { }

# The window in front's keyboard layout as Windows' layout id (KLID, e.g. 0000080A); the tablet
# types texts with it.
function Send-Keyboard([switch]$Always) {
    try { $hkl = [MacropadLink.Windows]::FrontKeyboard() } catch { return }
    if ($hkl -eq 0) { return } # no window in front (or a console): keep the last one
    $layout = ($hkl -shr 16) -band 0xFFFF
    $klid = if (($layout -band 0xF000) -eq 0xF000) { $LayoutIds['{0:X4}' -f ($layout -band 0x0FFF)] } else { '0000{0:X4}' -f $layout }
    if ($klid) { Send-Changed ([ordered]@{ t = 'keyboard'; klid = $klid }) -Always:$Always }
}

# ------------------------------------------------------------------ Outlook (classic, via COM)

$OutlookReadable = $false
$LastMeetingJson = $null

# The meeting under way or the next one today / tomorrow; $null if none. Throws if Outlook isn't
# running (it is never started just for this).
function Get-NextMeeting {
    $outlook = [Runtime.InteropServices.Marshal]::GetActiveObject('Outlook.Application')
    $items = $outlook.GetNamespace('MAPI').GetDefaultFolder(9).Items # 9 = calendar
    $items.IncludeRecurrences = $true
    $items.Sort('[Start]')
    $now = Get-Date
    $until = $now.Date.AddDays(2)
    $found = $items.Restrict("[End] > '$($now.ToString('g'))' AND [Start] < '$($until.ToString('g'))'")
    $item = $found.GetFirst()
    $checked = 0
    while ($item -and $checked -lt 50) {
        $checked++
        # Skip all-day events, declined meetings (4) and free time (0).
        if (-not $item.AllDayEvent -and $item.ResponseStatus -ne 4 -and $item.BusyStatus -ne 0) {
            $script:MeetingLink = Get-MeetingLink $item
            $meeting = [ordered]@{
                t = 'meeting'
                title = $item.Subject
                start = $item.Start.ToString("yyyy-MM-dd'T'HH:mm:sszzz")
                end = $item.End.ToString("yyyy-MM-dd'T'HH:mm:sszzz")
            }
            if ($script:MeetingLink) { $meeting.join = $true } # the link itself stays here
            return $meeting
        }
        $item = $found.GetNext()
    }
    $script:MeetingLink = $null
    return $null
}

$MeetingLink = $null   # the next meeting's online meeting link (pc:join opens it)
$TeamsUrlProperty = 'http://schemas.microsoft.com/mapi/string/{00020329-0000-0000-C000-000000000046}/SkypeTeamsMeetingUrl'

# A meeting's Teams link (Outlook keeps it in a property), else a Teams / Zoom / Meet / Webex link
# in its location or invitation text.
function Get-MeetingLink($Item) {
    try {
        $url = [string]$Item.PropertyAccessor.GetProperty($TeamsUrlProperty)
        if ($url -match '^https://') { return $url }
    } catch { }
    foreach ($text in @([string]$Item.Location, $(try { [string]$Item.Body } catch { '' }))) {
        if ($text -match 'https://teams\.microsoft\.com/l/meetup-join/[^\s<>"]+') { return $Matches[0] }
        if ($text -match 'https://[\w.-]*(zoom\.us|meet\.google\.com|webex\.com)/[^\s<>"]+') { return $Matches[0] }
    }
    return $null
}

# {"t":"join"}: opens the next meeting's link (Teams links in the Teams app). The tablet never
# sends a link: it can only open the one Outlook has.
function Invoke-Join($Request) {
    $link = $script:MeetingLink
    if (-not $link) { return Send-Result $Request.id $false 'Your next meeting has no online meeting link' }
    try {
        $target = if ($link -match '^https://teams\.microsoft\.com(/l/meetup-join/.*)$') { "msteams:$($Matches[1])" } else { $link }
        Start-Process $target
        Write-Log 'Opened the next meeting'
        Send-Result $Request.id $true $null
    } catch {
        Send-Result $Request.id $false "Couldn't open the meeting: $($_.Exception.Message)"
    }
}

$MailSeen = $null   # received time of the newest mail already told; $null until Outlook is first read

# New mail in classic Outlook's inbox since the last look: the newest one's sender and subject,
# and how many came. Mail already there when the script starts is never reported.
function Send-NewMail {
    if ($NoOutlook) { return }
    try {
        $outlook = [Runtime.InteropServices.Marshal]::GetActiveObject('Outlook.Application')
        $inbox = $outlook.GetNamespace('MAPI').GetDefaultFolder(6)
    } catch {
        return # Outlook closed
    }
    if (-not $script:MailSeen) { $script:MailSeen = Get-Date; return }
    $new = @()
    try {
        # Restrict works to the minute; the times are compared exactly below.
        $items = $inbox.Items.Restrict("[ReceivedTime] >= '$($script:MailSeen.ToString('g'))'")
        $items.Sort('[ReceivedTime]', $true)
        foreach ($item in $items) {
            if ($item.Class -eq 43 -and $item.ReceivedTime -gt $script:MailSeen) { $new += $item } # olMail
            if ($new.Count -ge 20) { break }
        }
    } catch {
        return
    }
    if ($new.Count -eq 0) { return }
    $script:MailSeen = $new[0].ReceivedTime
    Send-Tablet ([ordered]@{ t = 'mail'; from = [string]$new[0].SenderName; subject = [string]$new[0].Subject; count = $new.Count })
}

# Unread mail in classic Outlook's inbox, for the Outlook tab.
function Send-Badges([switch]$Always) {
    if ($NoOutlook) { return }
    $counts = [ordered]@{}
    try {
        $outlook = [Runtime.InteropServices.Marshal]::GetActiveObject('Outlook.Application')
        $counts.outlook = [int]$outlook.GetNamespace('MAPI').GetDefaultFolder(6).UnReadItemCount
    } catch { } # Outlook closed: no count
    Send-Changed ([ordered]@{ t = 'badges'; counts = $counts }) -Always:$Always
}

function Send-Meeting([switch]$Always) {
    if ($NoOutlook) { return }
    $wasReadable = $script:OutlookReadable
    try {
        $meeting = Get-NextMeeting
        $script:OutlookReadable = $true
    } catch {
        $script:OutlookReadable = $false
        $script:MeetingLink = $null
        $meeting = $null
    }
    if ($wasReadable -ne $script:OutlookReadable) {
        Write-Log "Outlook calendar: $(if ($script:OutlookReadable) { 'reading' } else { 'not available (classic Outlook not running)' })"
        Send-Hello
    }
    if (-not $script:OutlookReadable) { return }
    if (-not $meeting) { $meeting = [ordered]@{ t = 'meeting' } }
    $json = $meeting | ConvertTo-Json -Compress
    if ($Always -or $json -ne $script:LastMeetingJson) {
        $script:LastMeetingJson = $json
        Send-Tablet $meeting
    }
}

# ------------------------------------------------------------------ Outlook commands (pc:outlook)

# Classic Outlook's own commands (its object model) rather than shortcuts: they work whatever
# Outlook's language and from any window (a Citrix session too). Commands on mail act on the
# message open in front, else on the messages selected in Outlook's main window.

$OutlookFolders = @{ mail = 6; calendar = 9 }   # olFolderInbox, olFolderCalendar

# Brings an Outlook window to the front: the one titled $Caption, else the main window.
function Show-OutlookWindow([string]$Caption) {
    $ids = @(Get-Process -Name outlook -ErrorAction SilentlyContinue | ForEach-Object { $_.Id })
    $windows = @([MacropadLink.Windows]::List() | Where-Object { $ids -contains $_.Pid })
    $window = if ($Caption) { $windows | Where-Object { $_.Title -eq $Caption } | Select-Object -First 1 } else { $null }
    if (-not $window) { $window = $windows | Select-Object -First 1 }
    if ($window) { [void][MacropadLink.Windows]::Focus($window.Id) }
}

# The main window (opened on the inbox if there is none), on $Folder if given, in front.
function Show-OutlookMain($Outlook, [int]$Folder) {
    $explorer = $Outlook.ActiveExplorer()
    $namespace = $Outlook.GetNamespace('MAPI')
    if (-not $explorer) {
        $namespace.GetDefaultFolder($(if ($Folder) { $Folder } else { 6 })).Display()
        $explorer = $Outlook.ActiveExplorer()
    } else {
        if ($Folder) { $explorer.CurrentFolder = $namespace.GetDefaultFolder($Folder) }
        if ($explorer.WindowState -eq 1) { $explorer.WindowState = 2 } # minimized -> normal
    }
    Show-OutlookWindow $(if ($explorer) { $explorer.Caption } else { $null })
}

# The messages a command acts on: the one open in front, else the selection.
function Get-OutlookItems($Outlook) {
    $window = $Outlook.ActiveWindow()
    if ($window -and $window.Class -eq 35) { return @($window.CurrentItem) } # olInspector
    $explorer = $Outlook.ActiveExplorer()
    $items = @()
    if ($explorer) { foreach ($item in $explorer.Selection) { $items += $item } }
    return $items
}

# Opens a new item / reply in its own window, in front.
function Show-OutlookItem($Item) {
    $Item.Display()
    Show-OutlookWindow $Item.GetInspector.Caption
}

function Invoke-OutlookCommand($Request) {
    try {
        $outlook = [Runtime.InteropServices.Marshal]::GetActiveObject('Outlook.Application')
    } catch {
        return Send-Result $Request.id $false 'Classic Outlook is not open'
    }
    $action = [string]$Request.do
    try {
        switch ($action) {
            'open' { Show-OutlookMain $outlook 0 }
            'mail' { Show-OutlookMain $outlook $OutlookFolders.mail }
            'calendar' { Show-OutlookMain $outlook $OutlookFolders.calendar }
            'new-mail' { Show-OutlookItem $outlook.CreateItem(0) }
            'new-meeting' {
                $meeting = $outlook.CreateItem(1) # olAppointmentItem
                $meeting.MeetingStatus = 1        # olMeeting: with attendees
                Show-OutlookItem $meeting
            }
            'send' {
                $explorer = $outlook.ActiveExplorer()
                $inline = if ($explorer) { $explorer.ActiveInlineResponse } else { $null }
                $window = $outlook.ActiveWindow()
                if ($inline) { $inline.Send() }
                elseif ($window -and $window.Class -eq 35 -and $window.CurrentItem.Sent -eq $false) { $window.CurrentItem.Send() }
                else { throw 'open a message you are writing first' }
            }
            default {
                $items = @(Get-OutlookItems $outlook)
                if ($items.Count -eq 0) { throw 'select a message first' }
                switch ($action) {
                    'reply' { Show-OutlookItem $items[0].Reply() }
                    'reply-all' { Show-OutlookItem $items[0].ReplyAll() }
                    'forward' { Show-OutlookItem $items[0].Forward() }
                    'mark-read' { foreach ($i in $items) { $i.UnRead = $false; $i.Save() } }
                    'mark-unread' { foreach ($i in $items) { $i.UnRead = $true; $i.Save() } }
                    'flag' {
                        $flagged = $items[0].IsMarkedAsTask
                        foreach ($i in $items) { if ($flagged) { $i.ClearTaskFlag() } else { $i.MarkAsTask(0) }; $i.Save() } # olMarkToday
                    }
                    'archive' {
                        $root = $items[0].Parent.Store.GetRootFolder()
                        $archive = $root.Folders | Where-Object { $_.Name -in 'Archive', 'Archivo', 'Archivar', 'Archiv', 'Archives' } | Select-Object -First 1
                        if (-not $archive) { throw "no Archive folder in $($root.Name)" }
                        foreach ($i in $items) { [void]$i.Move($archive) }
                    }
                    'delete' { foreach ($i in $items) { $i.Delete() } }
                    default { throw "unknown command '$action'" }
                }
            }
        }
    } catch {
        $why = $_.Exception.Message -replace '^Exception calling .*?: ', ''
        Write-Log "Outlook $action failed (line $($_.InvocationInfo.ScriptLineNumber)): $($_.Exception.Message)"
        return Send-Result $Request.id $false "Outlook: $why"
    }
    Write-Traffic "Outlook: $action"
    Send-Result $Request.id $true $null
}

# ------------------------------------------------------------------ Teams (third-party app API)

$Teams = $null            # ClientWebSocket while connected
$TeamsReceive = $null     # pending ReceiveAsync
$TeamsBuffer = New-Object byte[] 16384
$TeamsMessage = New-Object System.IO.MemoryStream
$TeamsNextTry = Get-Date
$TeamsState = $null       # last state sent to the tablet (ordered hashtable), $null = no Teams
$TeamsRequest = 0
$TeamsPending = @{}       # Teams requestId -> tablet id
$TeamsHintShown = $false

$TeamsActions = @{
    mute = 'toggle-mute'; camera = 'toggle-video'; hand = 'toggle-hand'
    blur = 'toggle-background-blur'; leave = 'leave-call'; react = 'send-reaction'
}

function Start-TeamsReceive {
    $segment = New-Object 'System.ArraySegment[byte]' (, $script:TeamsBuffer)
    $script:TeamsReceive = $script:Teams.ReceiveAsync($segment, [Threading.CancellationToken]::None)
}

function Connect-Teams {
    $script:TeamsNextTry = (Get-Date).AddSeconds(15)
    $token = if (Test-Path $TokenFile) { (Get-Content $TokenFile -Raw).Trim() } else { '' }
    $uri = "ws://127.0.0.1:8124?token=$token&protocol-version=2.0.0&manufacturer=belta&device=PC&app=Macropad&app-version=1.7"
    $ws = New-Object System.Net.WebSockets.ClientWebSocket
    try {
        if (-not $ws.ConnectAsync([Uri]$uri, [Threading.CancellationToken]::None).Wait(3000) -or $ws.State -ne 'Open') {
            throw 'timeout'
        }
    } catch {
        $ws.Dispose()
        if (-not $script:TeamsHintShown) {
            $why = if ($_.Exception.InnerException) { $_.Exception.InnerException.Message } else { $_.Exception.Message }
            Write-Log "Teams API not reachable on 127.0.0.1:8124 ($why); reading Teams' window instead. (New Teams: Settings > Privacy > Third-party app API > Manage API, if your organisation allows it.)"
            $script:TeamsHintShown = $true
        }
        return
    }
    $script:TeamsHintShown = $false
    $script:Teams = $ws
    $script:TeamsMessage.SetLength(0)
    Write-Log 'Connected to Teams'
    Start-TeamsReceive
    # Teams says nothing until something changes; until then assume no meeting.
    Set-TeamsState ([ordered]@{ t = 'teams'; meeting = $false; muted = $false; camera = $false; hand = $false; blur = $false })
}

function Disconnect-Teams([string]$Why) {
    if ($script:Teams) {
        try { $script:Teams.Dispose() } catch { }
        $script:Teams = $null
        $script:TeamsReceive = $null
        Write-Log "Teams disconnected: $Why"
    }
    if ($script:TeamsState) { Set-TeamsState $null }
}

function Set-TeamsState($State) {
    $old = if ($script:TeamsState) { $script:TeamsState | ConvertTo-Json -Compress } else { '' }
    $new = if ($State) { $State | ConvertTo-Json -Compress } else { '' }
    $script:TeamsState = $State
    if ($old -ne $new) { Send-TeamsState }
}

function Send-TeamsState {
    if ($script:TeamsState) { Send-Tablet $script:TeamsState } else { Send-Tablet ([ordered]@{ t = 'teams' }) }
}

function Send-Teams($Message) {
    $json = $Message | ConvertTo-Json -Compress -Depth 4
    $bytes = [System.Text.Encoding]::UTF8.GetBytes($json)
    $segment = New-Object 'System.ArraySegment[byte]' (, $bytes)
    [void]$script:Teams.SendAsync($segment, [System.Net.WebSockets.WebSocketMessageType]::Text, $true, [Threading.CancellationToken]::None).Wait(3000)
    Write-Traffic "Teams <- $json"
}

function Read-Teams {
    if ($NoTeams) { return }
    if (-not $script:Teams) {
        if ((Get-Date) -ge $script:TeamsNextTry) { Connect-Teams }
        if (-not $script:Teams) { Read-TeamsUi }
        return
    }
    while ($script:TeamsReceive -and $script:TeamsReceive.IsCompleted) {
        if ($script:TeamsReceive.IsFaulted -or $script:TeamsReceive.IsCanceled) { return Disconnect-Teams 'connection lost' }
        $result = $script:TeamsReceive.Result
        if ($result.MessageType -eq [System.Net.WebSockets.WebSocketMessageType]::Close) { return Disconnect-Teams 'Teams closed the connection' }
        $script:TeamsMessage.Write($script:TeamsBuffer, 0, $result.Count)
        if ($result.EndOfMessage) {
            $json = [System.Text.Encoding]::UTF8.GetString($script:TeamsMessage.ToArray())
            $script:TeamsMessage.SetLength(0)
            Write-Traffic "Teams -> $json"
            try { Use-TeamsMessage ($json | ConvertFrom-Json) } catch { Write-Log "Bad message from Teams: $_" }
        }
        Start-TeamsReceive
    }
}

function Use-TeamsMessage($Message) {
    if ($Message.tokenRefresh) {
        Set-Content -Path $TokenFile -Value $Message.tokenRefresh -Encoding ASCII
        Write-Log 'Teams allowed Macropad (token saved)'
    }
    $meetingState = if ($Message.meetingUpdate) { $Message.meetingUpdate.meetingState } else { $null }
    if ($meetingState) {
        Set-TeamsState ([ordered]@{
            t = 'teams'
            meeting = [bool]$meetingState.isInMeeting
            muted = [bool]$meetingState.isMuted
            camera = [bool]$meetingState.isVideoOn
            hand = [bool]$meetingState.isHandRaised
            blur = [bool]$meetingState.isBackgroundBlurred
        })
    }
    if ($null -ne $Message.requestId -and $script:TeamsPending.ContainsKey([int]$Message.requestId)) {
        $id = $script:TeamsPending[[int]$Message.requestId]
        $script:TeamsPending.Remove([int]$Message.requestId)
        if ($Message.errorMsg) {
            Send-Tablet ([ordered]@{ t = 'result'; id = $id; ok = $false; error = "Teams: $($Message.errorMsg)" })
        } else {
            Send-Tablet ([ordered]@{ t = 'result'; id = $id; ok = $true })
        }
    }
}

function Invoke-TeamsCommand($Request) {
    $action = $script:TeamsActions[[string]$Request.do]
    if (-not $action) { return Send-Result $Request.id $false "Unknown Teams action '$($Request.do)'" }
    if (-not $script:Teams) { return Invoke-TeamsUi $Request }
    $script:TeamsRequest++
    $parameters = @{}
    if ($Request.do -eq 'react') { $parameters = @{ type = [string]$Request.param } }
    $script:TeamsPending[$script:TeamsRequest] = $Request.id
    try {
        Send-Teams ([ordered]@{ action = $action; parameters = $parameters; requestId = $script:TeamsRequest })
    } catch {
        $script:TeamsPending.Remove($script:TeamsRequest)
        Send-Result $Request.id $false 'Lost the connection to Teams'
        Disconnect-Teams 'send failed'
    }
}

# ------------------------------------------------------------------ Teams without its API (UI Automation)

# When Teams' third-party API is off (e.g. blocked by the organisation), read and press the
# buttons of Teams' meeting window instead, the way screen readers do. Mute, camera, hand and
# leave work this way; blur and reactions need the API.

Add-Type -AssemblyName UIAutomationClient, UIAutomationTypes

$TeamsUiIds = @{ mic = 'microphone-button'; camera = 'video-button'; hand = 'raisehands-button'; hangup = 'hangup-button' }
# Button names (English and Spanish) for builds without those automation ids.
$TeamsUiNames = @{
    mic = '^(mute|unmute|silenciar|reactivar|activar (el )?(audio|sonido|micr))'
    camera = '(turn )?camera (on|off)|c.mara'
    hand = '(raise|lower|levantar|bajar).*(hand|mano)'
    hangup = '^(leave|hang up|salir|abandonar|colgar)'
}
$TeamsUiActions = @{ mute = 'mic'; camera = 'camera'; hand = 'hand'; leave = 'hangup' }
$TeamsUiControls = $null          # the meeting window's buttons while in a meeting
$TeamsUiNext = Get-Date
$TeamsUiSearchNext = Get-Date
$TeamsUiDeepSearchNext = Get-Date
$TeamsUiMode = ''                 # last mode logged

$UiaButton = New-Object System.Windows.Automation.PropertyCondition (
    [System.Windows.Automation.AutomationElement]::ControlTypeProperty, [System.Windows.Automation.ControlType]::Button)
$UiaDescendants = [System.Windows.Automation.TreeScope]::Descendants

# Teams' top-level windows; $null when Teams isn't running.
function Get-TeamsWindows {
    $ids = @(Get-Process -Name 'ms-teams', 'Teams' -ErrorAction SilentlyContinue | ForEach-Object { $_.Id })
    if ($ids.Count -eq 0) { return $null }
    $windows = New-Object System.Collections.ArrayList
    foreach ($id in $ids) {
        $condition = New-Object System.Windows.Automation.PropertyCondition (
            [System.Windows.Automation.AutomationElement]::ProcessIdProperty, $id)
        foreach ($w in [System.Windows.Automation.AutomationElement]::RootElement.FindAll([System.Windows.Automation.TreeScope]::Children, $condition)) {
            [void]$windows.Add($w)
        }
    }
    return @{ windows = $windows }
}

# The meeting controls (mic, camera, hand, hangup) of the first window that has them, or $null.
# [ByName] also scans every button's name, which is slower.
function Find-TeamsControls($Windows, [bool]$ByName) {
    foreach ($w in $Windows) {
        $controls = @{}
        foreach ($key in $TeamsUiIds.Keys) {
            $condition = New-Object System.Windows.Automation.PropertyCondition (
                [System.Windows.Automation.AutomationElement]::AutomationIdProperty, $TeamsUiIds[$key])
            $found = $w.FindFirst($UiaDescendants, $condition)
            if ($found) { $controls[$key] = $found }
        }
        if ($ByName -and -not ($controls.ContainsKey('hangup') -and $controls.ContainsKey('mic'))) {
            foreach ($button in $w.FindAll($UiaDescendants, $UiaButton)) {
                $name = $button.Current.Name
                if (-not $name) { continue }
                foreach ($key in $TeamsUiNames.Keys) {
                    if (-not $controls.ContainsKey($key) -and $name -match $TeamsUiNames[$key]) { $controls[$key] = $button }
                }
            }
        }
        if ($controls.ContainsKey('hangup') -and $controls.ContainsKey('mic')) { return $controls }
    }
    return $null
}

# Reads the state from the buttons' names ("Unmute" while muted, "Turn camera off" while on, ...).
# Throws when the buttons are gone (the meeting ended).
function Get-TeamsUiState($Controls) {
    $mic = $Controls.mic.Current.Name
    [void]$Controls.hangup.Current.Name
    $camera = if ($Controls.camera) { $Controls.camera.Current.Name } else { '' }
    $hand = if ($Controls.hand) { $Controls.hand.Current.Name } else { '' }
    return [ordered]@{
        t = 'teams'
        meeting = $true
        muted = [bool]($mic -match 'unmute|reactivar|(?<!des)activar')
        camera = [bool]($camera -match '\boff\b|desactivar|apagar')
        hand = [bool]($hand -match 'lower|bajar')
        blur = $false
    }
}

$NoMeeting = [ordered]@{ t = 'teams'; meeting = $false; muted = $false; camera = $false; hand = $false; blur = $false }

function Set-TeamsUiMode([string]$Mode, [string]$Message) {
    if ($script:TeamsUiMode -ne $Mode) {
        $script:TeamsUiMode = $Mode
        Write-Log $Message
    }
}

function Read-TeamsUi {
    $now = Get-Date
    if ($now -lt $script:TeamsUiNext) { return }
    $script:TeamsUiNext = $now.AddSeconds(1)
    if (-not $script:TeamsUiControls) {
        if ($now -lt $script:TeamsUiSearchNext) { return }
        $script:TeamsUiSearchNext = $now.AddSeconds(5)
        $teams = Get-TeamsWindows
        if (-not $teams) {
            Set-TeamsUiMode 'off' 'Teams is not running'
            return Set-TeamsState $null
        }
        $deep = $now -ge $script:TeamsUiDeepSearchNext
        if ($deep) { $script:TeamsUiDeepSearchNext = $now.AddSeconds(30) }
        try {
            $script:TeamsUiControls = Find-TeamsControls $teams.windows $deep
        } catch {
            $script:TeamsUiControls = $null
        }
        if (-not $script:TeamsUiControls) {
            Set-TeamsUiMode 'idle' 'Teams: reading its window (UI Automation); no meeting open'
            return Set-TeamsState $NoMeeting
        }
        $c = $script:TeamsUiControls
        Set-TeamsUiMode 'meeting' ("Teams meeting found: mic '{0}', camera '{1}', hand '{2}'" -f
            $c.mic.Current.Name, $(if ($c.camera) { $c.camera.Current.Name } else { '-' }), $(if ($c.hand) { $c.hand.Current.Name } else { '-' }))
    }
    try {
        Set-TeamsState (Get-TeamsUiState $script:TeamsUiControls)
    } catch {
        $script:TeamsUiControls = $null
        $script:TeamsUiSearchNext = $now
        Set-TeamsState $NoMeeting
    }
}

# Presses a meeting button for a tablet request; replies with a result.
function Invoke-TeamsUi($Request) {
    $key = $TeamsUiActions[[string]$Request.do]
    if (-not $key) { return Send-Result $Request.id $false "Teams $($Request.do) needs Teams' third-party API" }
    $button = if ($script:TeamsUiControls) { $script:TeamsUiControls[$key] } else { $null }
    if (-not $button) { return Send-Result $Request.id $false 'No Teams meeting open' }
    try {
        $button.GetCurrentPattern([System.Windows.Automation.InvokePattern]::Pattern).Invoke()
    } catch {
        $script:TeamsUiControls = $null
        return Send-Result $Request.id $false "Couldn't press Teams' button: $($_.Exception.Message)"
    }
    Send-Result $Request.id $true $null
    # Show the new state at once rather than on the next poll.
    Start-Sleep -Milliseconds 300
    $script:TeamsUiNext = Get-Date
    Read-TeamsUi
}

# ------------------------------------------------------------------ named commands (pc:run)

function Get-Commands {
    if (-not (Test-Path $CommandsFile)) {
        $example = Join-Path (Split-Path $PSCommandPath) 'macropad-commands.example.json'
        if (Test-Path $example) {
            Copy-Item $example $CommandsFile
            Write-Log "Created $CommandsFile from the example; edit it to add commands"
        } else {
            return $null
        }
    }
    return Get-Content $CommandsFile -Raw -Encoding UTF8 | ConvertFrom-Json
}

function Invoke-NamedCommand($Request) {
    try {
        $commands = Get-Commands
    } catch {
        return Send-Result $Request.id $false "commands.json is not valid JSON: $($_.Exception.Message)"
    }
    $name = [string]$Request.name
    $entry = if ($commands) { $commands.PSObject.Properties[$name] } else { $null }
    if (-not $entry -or $name.StartsWith('_')) {
        return Send-Result $Request.id $false "No command '$name' in $CommandsFile"
    }
    try {
        Start-Process -FilePath 'cmd.exe' -ArgumentList '/c', ([string]$entry.Value) -WindowStyle Hidden
        Write-Log "Ran '$name'"
        Send-Result $Request.id $true $null
    } catch {
        Send-Result $Request.id $false "Could not run '$name': $($_.Exception.Message)"
    }
}

function Send-Result($Id, [bool]$Ok, [string]$Reason) {
    $message = [ordered]@{ t = 'result'; id = $Id; ok = $Ok }
    if ($Reason) { $message.error = $Reason }
    Send-Tablet $message
}

# ------------------------------------------------------------------ typing a file (pc:typefile)

# The file selected in File Explorer, for the tablet to type somewhere a file can't be copied to
# (a Citrix session without a shared clipboard or drives). The Explorer window used last counts.
$TypeFileLimit = 200KB

function Get-SelectedFile {
    $byWindow = @{}
    foreach ($w in @((New-Object -ComObject Shell.Application).Windows())) {
        try {
            $selected = @($w.Document.SelectedItems())
            if ($selected.Count -gt 0) { $byWindow[[long]$w.HWND] = $selected[0].Path } # with tabs, one that has a selection
        } catch { } # Internet Explorer windows, closing ones
    }
    foreach ($window in [MacropadLink.Windows]::List()) { # Alt+Tab order: the one used last first
        if ($byWindow.ContainsKey([long]$window.Id)) { return $byWindow[[long]$window.Id] }
    }
    return $null
}

function Invoke-TypeFile($Request) {
    $path = $null
    try { $path = Get-SelectedFile } catch { }
    if (-not $path) { return Send-Result $Request.id $false 'Select a file in File Explorer first' }
    if (-not (Test-Path -LiteralPath $path -PathType Leaf)) { return Send-Result $Request.id $false "$(Split-Path $path -Leaf) is a folder; select a file" }
    $name = Split-Path $path -Leaf
    $length = (Get-Item -LiteralPath $path).Length
    if ($length -gt $TypeFileLimit) { return Send-Result $Request.id $false "$name is too long to type ($([int]($length / 1KB)) KB; up to $([int]($TypeFileLimit / 1KB)) KB)" }
    try {
        $bytes = [IO.File]::ReadAllBytes($path)
    } catch {
        return Send-Result $Request.id $false "Can't read ${name}: $($_.Exception.Message)"
    }
    if ([Array]::IndexOf($bytes, [byte]0) -ge 0) { return Send-Result $Request.id $false "$name isn't a text file" }
    # UTF-8 (with or without BOM), else Windows' own code page (files saved by older programs).
    try {
        $text = (New-Object Text.UTF8Encoding($false, $true)).GetString($bytes)
    } catch {
        $text = [Text.Encoding]::Default.GetString($bytes)
    }
    $text = $text.TrimStart([char]0xFEFF).Replace("`r`n", "`n").Replace("`r", "`n")
    Write-Log "Sending $name to be typed ($($text.Length) characters)"
    Send-Tablet ([ordered]@{ t = 'file'; id = $Request.id; name = $name; text = $text })
}

# ------------------------------------------------------------------ PowerPoint (the tablet's Presenter page)

# The slide show running in PowerPoint (or, while editing, the slide selected): which slide, its
# title and speaker notes, every half second when they change. Pictures of the slides go one per
# turn of the main loop, the slide on screen and the next one first; each once per version (a new
# show or another presentation starts a new one, so edits show up).
$Slides = @{ version = 0; file = $null; show = $false; presentation = $null; deck = $false; sent = @{}; queue = New-Object System.Collections.ArrayList }
$SlideImageWidth = 960

function Get-PowerPoint {
    try { return [Runtime.InteropServices.Marshal]::GetActiveObject('PowerPoint.Application') } catch { return $null }
}

function Get-SlideNotes($Slide) {
    foreach ($shape in $Slide.NotesPage.Shapes) {
        try {
            if ($shape.PlaceholderFormat.Type -eq 2 -and $shape.HasTextFrame) { return [string]$shape.TextFrame.TextRange.Text } # ppPlaceholderBody
        } catch { } # not a placeholder
    }
    return ''
}

function Get-SlideTitle($Slide) {
    try { if ($Slide.Shapes.HasTitle) { return [string]$Slide.Shapes.Title.TextFrame.TextRange.Text } } catch { }
    return $null
}

function Send-Slides([switch]$Always) {
    $ppt = Get-PowerPoint
    $presentation = $null; $slide = $null; $show = $false; $state = 'running'
    if ($ppt) {
        try {
            if ($ppt.SlideShowWindows.Count -gt 0) {
                $window = $ppt.SlideShowWindows.Item(1)
                $presentation = $window.Presentation
                $show = $true
                $state = switch ([int]$window.View.State) { 2 { 'paused' } 3 { 'black' } 4 { 'white' } 5 { 'done' } default { 'running' } }
                if ($state -ne 'done') { $slide = $window.View.Slide }
            } elseif ($ppt.Presentations.Count -gt 0) {
                $presentation = $ppt.ActivePresentation
                try { $slide = $ppt.ActiveWindow.View.Slide } catch { } # not in every view
            }
        } catch { Write-Traffic "PowerPoint: $($_.Exception.Message)" }
    }
    # A slide show running in PowerPoint is what's on screen; else an HTML deck in the browser.
    try { Update-Deck } catch { Write-Traffic "Deck: $($_.Exception.Message)"; $script:Deck = $null }
    if ($script:Deck -and -not $show) { return Send-Deck -Always:$Always }
    $script:Slides.deck = $false
    $file = if ($presentation) { [string]$presentation.Name } else { $null }
    # A new show or another presentation: the tablet's pictures may be old.
    if ($file -ne $script:Slides.file -or ($show -and -not $script:Slides.show)) { Reset-SlideImages }
    $script:Slides.file = $file
    $script:Slides.show = $show
    $script:Slides.presentation = $presentation
    if (-not $presentation) { return Send-Changed ([ordered]@{ t = 'slides' }) -Always:$Always }
    $message = [ordered]@{ t = 'slides'; file = $file; show = $show; state = $state; count = [int]$presentation.Slides.Count; v = $script:Slides.version }
    if ($slide) {
        $index = [int]$slide.SlideIndex
        $message.slide = $index
        $message.title = Get-SlideTitle $slide
        $message.notes = Get-SlideNotes $slide
        Add-SlideImage ($index + 1) -First
        Add-SlideImage $index -First
    }
    Send-Changed $message -Always:$Always
}

function Reset-SlideImages {
    $script:Slides.version++
    $script:Slides.sent.Clear()
    $script:Slides.queue.Clear()
}

# Slide $Index's picture is wanted ($First: before the others waiting).
function Add-SlideImage([int]$Index, [switch]$First) {
    if ($Index -lt 1 -or $script:Slides.sent.ContainsKey($Index)) { return }
    $script:Slides.queue.Remove($Index)
    if ($First) { $script:Slides.queue.Insert(0, $Index) } else { [void]$script:Slides.queue.Add($Index) }
}

function Send-SlideImage {
    $queue = $script:Slides.queue
    $presentation = $script:Slides.presentation
    if ($queue.Count -eq 0 -or -not $presentation) { return }
    $index = [int]$queue[0]
    $queue.RemoveAt(0)
    $script:Slides.sent[$index] = $true
    $path = Join-Path ([IO.Path]::GetTempPath()) 'macropad-slide.jpg'
    try {
        if ($index -gt $presentation.Slides.Count) { return }
        $height = [int]($SlideImageWidth * $presentation.PageSetup.SlideHeight / $presentation.PageSetup.SlideWidth)
        $presentation.Slides.Item($index).Export($path, 'JPG', $SlideImageWidth, $height)
        $jpg = [IO.File]::ReadAllBytes($path)
    } catch {
        Write-Traffic "PowerPoint: slide $index picture: $($_.Exception.Message)"
        return
    } finally {
        Remove-Item $path -ErrorAction SilentlyContinue
    }
    Send-Tablet ([ordered]@{ t = 'slide-image'; file = $script:Slides.file; n = $index; v = $script:Slides.version; jpg = [Convert]::ToBase64String($jpg) })
}

function Invoke-Slides($Request) {
    if ($script:Slides.deck) { return Invoke-DeckCommand $Request }
    $do = [string]$Request.do
    $ppt = Get-PowerPoint
    if (-not $ppt) { if ($Request.id) { Send-Result $Request.id $false 'PowerPoint is not open on this computer' }; return }
    try {
        if ($do -eq 'thumbs') {
            $count = if ($script:Slides.presentation) { [int]$script:Slides.presentation.Slides.Count } else { 0 }
            for ($n = 1; $n -le $count; $n++) { Add-SlideImage $n }
        } elseif ($do -eq 'start') {
            if ($ppt.SlideShowWindows.Count -eq 0) { [void]$ppt.ActivePresentation.SlideShowSettings.Run() }
        } elseif ($ppt.SlideShowWindows.Count -eq 0) {
            if ($do -in 'point', 'point-end') { return }
            if ($Request.id) { Send-Result $Request.id $false 'No slide show is running (Start show)' }
            return
        } else {
            $window = $ppt.SlideShowWindows.Item(1)
            $view = $window.View
            switch ($do) {
                'next' { $view.Next() }
                'prev' { $view.Previous() }
                'goto' { $view.GotoSlide([int]$Request.n) }
                'black' { $view.State = $(if ($view.State -eq 3) { 1 } else { 3 }) }
                'white' { $view.State = $(if ($view.State -eq 4) { 1 } else { 4 }) }
                'end' { $view.Exit() }
                'point' { Move-Laser $window ([double]$Request.x) ([double]$Request.y) }
                'point-end' { $view.LaserPointerEnabled = $false }
            }
        }
        if ($Request.id) { Send-Result $Request.id $true $null }
    } catch {
        if ($Request.id) { Send-Result $Request.id $false "PowerPoint: $($_.Exception.Message)" }
    }
    $script:Next.slides = Get-Date # show the new slide at once
}

# PowerPoint's laser pointer at (x, y), 0-1 across the slide, which sits centred in the show's window.
function Move-Laser($Window, [double]$X, [double]$Y) {
    $view = $Window.View
    if (-not $view.LaserPointerEnabled) { $view.LaserPointerEnabled = $true }
    $rect = [MacropadLink.Pointer]::Rect([long]$Window.HWND)
    if (-not $rect) { return }
    $setup = $Window.Presentation.PageSetup
    $ratio = $setup.SlideWidth / $setup.SlideHeight
    $w = [Math]::Min($rect[2], $rect[3] * $ratio)
    $h = $w / $ratio
    $left = $rect[0] + ($rect[2] - $w) / 2
    $top = $rect[1] + ($rect[3] - $h) / 2
    [MacropadLink.Pointer]::Do([int]($left + $X * $w), [int]($top + $Y * $h), 'move', 0)
}

# ------------------------------------------------------------------ HTML slide decks (the Presenter page too)

# Single-file HTML presentations open in a browser: `const S=o=>SLIDES.push(o);` then one
# S({sect, cls, html, notes}) per slide, a 1280 x 720 slide centred in the window, ← → Home End
# and F (full screen) to drive it, and "#n" in the address for slide n. The address bar says which
# file and slide; the file has the titles and notes. In full screen the address bar is hidden, so
# the slide is the last one read, moved by the tablet's own ◀ ▶ (pressed here, on the deck).
# Reported like PowerPoint (kind "html"), after a running PowerPoint show and before PowerPoint
# being edited.
$Deck = $null         # @{ window; app; path; stamp; slides; slide; heldUntil; shownAt } while one is open
$DeckFiles = @{}      # path -> @{ stamp; slides } as last read ($null slides: not a deck)
$DeckBrowsers = 'chrome', 'msedge', 'brave', 'vivaldi', 'opera', 'firefox'

# "E:/talks/deck.html#7" or "file:///E:/talks/my%20deck.html#7" -> @{ path; slide }; $null for
# anything else (a web page, the address being typed…).
function Get-DeckAddress([string]$Text) {
    if (-not $Text) { return $null }
    $address = $Text.Trim() -replace '^file:///', ''
    if ($address -notmatch '^(?<path>[A-Za-z]:/[^#?]+\.html?)(\?[^#]*)?(#(?<n>\d+))?$') { return $null }
    $slide = if ($Matches.n) { [int]$Matches.n } else { 1 }
    return @{ path = [Uri]::UnescapeDataString($Matches.path).Replace('/', '\'); slide = $slide }
}

# A JavaScript string after "<Field>:" in $Block (backquoted or quoted), or the backquoted value of
# the constant it names (html:COVER); $null if none.
function Get-DeckField([string]$Block, [string]$Field, [string]$Text) {
    $m = [regex]::Match($Block, "\b$Field\s*:\s*(?:``(?<s>[^``]*)``|`"(?<s>(?:[^`"\\]|\\.)*)`"|(?<name>[A-Za-z_]\w*))")
    if (-not $m.Success) { return $null }
    if ($m.Groups['name'].Success) {
        $m = [regex]::Match($Text, "(?:const|let|var)\s+$($m.Groups['name'].Value)\s*=\s*``(?<s>[^``]*)``")
        if (-not $m.Success) { return $null }
    }
    return $m.Groups['s'].Value -replace '\\(.)', '$1'
}

# HTML as plain text: tags out (a <br> or a paragraph is a space), entities decoded.
function ConvertFrom-DeckHtml([string]$Html) {
    $text = [Net.WebUtility]::HtmlDecode(($Html -replace '<br\s*/?>|</(p|div|li|h\d)>', ' ' -replace '<[^>]+>', ''))
    return ($text -replace '\s+', ' ').Trim()
}

# The deck's slides, @{ title; notes } each, or $null if $Path isn't such a deck. Read again when
# the file changes.
function Read-DeckFile([string]$Path) {
    $stamp = [IO.File]::GetLastWriteTimeUtc($Path)
    $known = $script:DeckFiles[$Path]
    if (-not $known -or $known.stamp -ne $stamp) {
        $known = @{ stamp = $stamp; slides = (Get-DeckSlides ([IO.File]::ReadAllText($Path, [Text.Encoding]::UTF8))) }
        if ($script:DeckFiles.Count -gt 20) { $script:DeckFiles.Clear() }
        $script:DeckFiles[$Path] = $known
    }
    if ($null -eq $known.slides) { return $null }
    return , $known.slides # the list itself, the same one until the file changes
}

function Get-DeckSlides([string]$Text) {
    if ($Text -notmatch '\bS\s*=\s*\w+\s*=>\s*SLIDES\.push') { return $null }
    $slides = New-Object System.Collections.ArrayList
    $starts = [regex]::Matches($Text, '(?<![\w.$])S\(\{')
    for ($i = 0; $i -lt $starts.Count; $i++) {
        $start = $starts[$i].Index
        $end = if ($i + 1 -lt $starts.Count) { $starts[$i + 1].Index } else { $Text.IndexOf('</script>', $start) }
        if ($end -lt 0) { $end = $Text.Length }
        $block = $Text.Substring($start, $end - $start)
        # The slide's HTML first and out of the way: its words could look like the other fields.
        $html = $null
        $literal = [regex]::Match($block, '\bhtml\s*:\s*`[^`]*`')
        if ($literal.Success) {
            $html = Get-DeckField $literal.Value 'html' $Text
            $block = $block.Remove($literal.Index, $literal.Length)
        } else {
            $html = Get-DeckField $block 'html' $Text
        }
        $heading = if ($html) { [regex]::Match($html, '(?s)<h[12][^>]*>(.*?)</h[12]>') } else { $null }
        $title = if ($heading -and $heading.Success) { ConvertFrom-DeckHtml $heading.Groups[1].Value } else { Get-DeckField $block 'sect' $Text }
        # The deck shows each line of its notes as a paragraph (HTML allowed).
        $notes = @([string](Get-DeckField $block 'notes' $Text) -split '\r?\n' | ForEach-Object { ConvertFrom-DeckHtml $_ } | Where-Object { $_ }) -join "`n`n"
        [void]$slides.Add(@{ title = $title; notes = $notes })
    }
    if ($slides.Count -eq 0) { return $null }
    return , $slides
}

# Browser window $Window's deck: @{ path; slide; slides }, $false when it shows something else,
# $null when its address can't be read (full screen).
function Read-DeckWindow([long]$Window) {
    $text = $null
    try { $text = [MacropadLink.Uia]::AddressBar($Window) } catch { }
    if ($null -eq $text) { return $null }
    $address = Get-DeckAddress $text
    if (-not $address -or -not (Test-Path -LiteralPath $address.path -PathType Leaf)) { return $false }
    try { $slides = Read-DeckFile $address.path } catch { Write-Traffic "Deck $($address.path): $($_.Exception.Message)"; return $false }
    if (-not $slides) { return $false }
    $address.slides = $slides
    return $address
}

# Which deck is open, and on which slide: a browser coming to the front with a deck takes over;
# the deck's window follows its address while it can be read.
function Update-Deck {
    $front = [MacropadLink.Windows]::Front()
    if ($front -and (-not $script:Deck -or $front -ne $script:Deck.window)) {
        $info = Get-ProcessInfo ([MacropadLink.Pointer]::ProcessId($front))
        if ($info -and $info.exe -in $DeckBrowsers) {
            $found = Read-DeckWindow $front
            if ($found) { $script:Deck = @{ window = $front; app = $info.exe; path = $null; heldUntil = [datetime]::MinValue; shownAt = Get-Date } }
        }
    }
    $deck = $script:Deck
    if (-not $deck) { return }
    if (-not [MacropadLink.Windows]::Exists($deck.window)) { $script:Deck = $null; return }
    $found = Read-DeckWindow $deck.window
    if ($found -is [bool]) { $script:Deck = $null; return } # another page now
    if ($null -eq $found) { return } # full screen: the slide as last known
    if ($found.path -ne $deck.path -or -not [object]::ReferenceEquals($found.slides, $deck.slides)) {
        if ($deck.path) { Reset-SlideImages } # edited, or another deck in that tab: new pictures
        $deck.path = $found.path
        $deck.slides = $found.slides
        $deck.slide = 0
    }
    # Right after the tablet changed slides the browser may not have caught up yet.
    $slide = [Math]::Min($found.slide, $deck.slides.Count)
    if ((Get-Date) -ge $deck.heldUntil -and $slide -ne $deck.slide) {
        $deck.slide = $slide
        $deck.shownAt = Get-Date
    }
}

# The deck's window fills its monitor (the deck's F, or the browser's F11): its slide show. Chrome
# and Edge un-maximize a window going full screen, so a maximized one never counts.
function Test-DeckFullScreen([long]$Window) {
    if ([MacropadLink.Windows]::Maximized($Window)) { return $false }
    $rect = [MacropadLink.Pointer]::Rect($Window)
    if (-not $rect) { return $false }
    $screen = [Windows.Forms.Screen]::FromHandle([IntPtr]$Window).Bounds
    return $rect[0] -le $screen.X -and $rect[1] -le $screen.Y -and
        $rect[0] + $rect[2] -ge $screen.Right -and $rect[1] + $rect[3] -ge $screen.Bottom
}

# Where the 1280 x 720 slide sits in the full-screen deck window: left, top, width, height.
function Get-DeckSlideBox([long]$Window) {
    $rect = [MacropadLink.Pointer]::Rect($Window)
    if (-not $rect) { return $null }
    $w = [Math]::Min($rect[2], $rect[3] * 16 / 9)
    $h = $w * 9 / 16
    return @([int]($rect[0] + ($rect[2] - $w) / 2), [int]($rect[1] + ($rect[3] - $h) / 2), [int]$w, [int]$h)
}

function Send-Deck([switch]$Always) {
    $deck = $script:Deck
    $file = [IO.Path]::GetFileName($deck.path)
    $show = Test-DeckFullScreen $deck.window
    if ($file -ne $script:Slides.file -or ($show -and -not $script:Slides.show)) { Reset-SlideImages }
    $script:Slides.file = $file
    $script:Slides.show = $show
    $script:Slides.presentation = $null
    $script:Slides.deck = $true
    $message = [ordered]@{ t = 'slides'; kind = 'html'; app = $deck.app; file = $file; show = $show; state = 'running'; count = $deck.slides.Count; v = $script:Slides.version }
    if ($deck.slide -gt 0) {
        $message.slide = $deck.slide
        $message.title = $deck.slides[$deck.slide - 1].title
        $message.notes = $deck.slides[$deck.slide - 1].notes
    }
    Send-Changed $message -Always:$Always
    if ($show) { Send-DeckPicture }
}

# A picture of the slide on screen, once per slide: in full screen, with the deck in front, once
# the slide has been up a moment (the browser has drawn it).
function Send-DeckPicture {
    $deck = $script:Deck
    $n = $deck.slide
    if ($n -lt 1 -or $script:Slides.sent.ContainsKey($n)) { return }
    if ([MacropadLink.Windows]::Front() -ne $deck.window -or ((Get-Date) - $deck.shownAt).TotalMilliseconds -lt 600) { return }
    $box = Get-DeckSlideBox $deck.window
    if (-not $box) { return }
    $jpg = [MacropadLink.Mirror]::Picture($box[0], $box[1], $box[2], $box[3], $SlideImageWidth, 80)
    if (-not $jpg) { return }
    $script:Slides.sent[$n] = $true
    Send-Tablet ([ordered]@{ t = 'slide-image'; file = $script:Slides.file; n = $n; v = $script:Slides.version; jpg = [Convert]::ToBase64String($jpg) })
}

# The Presenter page's buttons on a deck: its own keys, pressed with its window in front.
function Invoke-DeckCommand($Request) {
    $deck = $script:Deck
    $do = [string]$Request.do
    $count = if ($deck) { $deck.slides.Count } else { 0 }
    try {
        if (-not $deck) { throw 'The presentation was closed' }
        if ($do -eq 'thumbs') {
            # Pictures come as each slide is shown.
        } elseif ($do -in 'point', 'point-end') {
            # The mouse pointer is the deck's laser pointer.
            if ($do -eq 'point-end' -or -not (Test-DeckFullScreen $deck.window)) { return }
            $box = Get-DeckSlideBox $deck.window
            if ($box) { [MacropadLink.Pointer]::Do([int]($box[0] + [double]$Request.x * $box[2]), [int]($box[1] + [double]$Request.y * $box[3]), 'move', 0) }
            return
        } elseif ($do -in 'black', 'white') {
            throw 'An HTML presentation has no black or white screen'
        } else {
            if (-not [MacropadLink.Windows]::Focus($deck.window)) { throw "Couldn't bring the presentation to the front" }
            $full = Test-DeckFullScreen $deck.window
            $slide = [Math]::Max(1, $deck.slide)
            switch ($do) {
                'next' { [MacropadLink.Keys]::Tap(0x27, 1); $slide = [Math]::Min($count, $slide + 1) }    # →
                'prev' { [MacropadLink.Keys]::Tap(0x25, 1); $slide = [Math]::Max(1, $slide - 1) }         # ←
                'goto' {
                    $slide = [Math]::Max(1, [Math]::Min($count, [int]$Request.n))
                    # From the nearer end: Home then →, or End then ←.
                    if ($slide - 1 -le $count - $slide) { [MacropadLink.Keys]::Tap(0x24, 1); [MacropadLink.Keys]::Tap(0x27, $slide - 1) }
                    else { [MacropadLink.Keys]::Tap(0x23, 1); [MacropadLink.Keys]::Tap(0x25, $count - $slide) }
                }
                'start' { if (-not $full) { [MacropadLink.Keys]::Tap(0x46, 1) } } # F
                'end' { if ($full) { [MacropadLink.Keys]::Tap(0x1B, 1) } }        # Esc
            }
            if ($slide -ne $deck.slide) {
                $deck.slide = $slide
                $deck.shownAt = Get-Date
                $deck.heldUntil = (Get-Date).AddSeconds(1)
            }
        }
        if ($Request.id) { Send-Result $Request.id $true $null }
    } catch {
        if ($Request.id) { Send-Result $Request.id $false $_.Exception.Message }
    }
    $script:Next.slides = Get-Date # show the new slide at once
}

# ------------------------------------------------------------------ talking to the tablet

function Send-Hello {
    Send-Tablet ([ordered]@{
        t = 'hello'; v = $ProtocolVersion; host = $env:COMPUTERNAME
        teams = (-not $NoTeams); outlook = $script:OutlookReadable; capture = [bool]$AllowRecording
        script = $ScriptVersion; sha256 = $ScriptHash; updates = (-not $NoUpdate)
    })
}

# ------------------------------------------------------------------ updates from the tablet

# This file as it was started, to tell the tablet which copy runs.
function Get-Sha256([byte[]]$Bytes) {
    $sha = [Security.Cryptography.SHA256]::Create()
    try { return (($sha.ComputeHash($Bytes) | ForEach-Object { $_.ToString('x2') }) -join '') } finally { $sha.Dispose() }
}
$ScriptHash = Get-Sha256 ([IO.File]::ReadAllBytes($PSCommandPath))
$Restart = $false

# The app's copy of this script, sent after you confirmed on the tablet: checked, swapped in (the
# old one kept as .bak), then the main loop ends and the new one starts with the same options.
function Invoke-Update($Request) {
    if ($NoUpdate) { return Send-Result $Request.id $false 'The script was started with -NoUpdate' }
    try {
        $bytes = [Convert]::FromBase64String([string]$Request.ps1)
        if ((Get-Sha256 $bytes) -ne [string]$Request.sha256) { return Send-Result $Request.id $false 'The new script arrived damaged; try again' }
        $errors = $null
        $tokens = $null
        [void][Management.Automation.Language.Parser]::ParseInput([Text.Encoding]::UTF8.GetString($bytes), [ref]$tokens, [ref]$errors)
        if ($errors -and $errors.Count) { return Send-Result $Request.id $false "The new script isn't valid PowerShell: $($errors[0].Message)" }
        $new = "$PSCommandPath.new"
        [IO.File]::WriteAllBytes($new, $bytes)
        Copy-Item -LiteralPath $PSCommandPath -Destination "$PSCommandPath.bak" -Force
        Move-Item -LiteralPath $new -Destination $PSCommandPath -Force
    } catch {
        return Send-Result $Request.id $false "Couldn't replace the script: $($_.Exception.Message)"
    }
    Send-Result $Request.id $true $null
    Write-Log "Updated by the tablet to $([string]$Request.version) (the old copy is $PSCommandPath.bak); restarting"
    $script:Restart = $true
}

# Starts this script again the way it was started (hidden from the Startup shortcut stays hidden).
function Start-Again {
    $original = [Environment]::GetCommandLineArgs()
    $file = [Array]::IndexOf(($original | ForEach-Object { $_.ToLower() }), '-file')
    $arguments = if ($file -gt 0) {
        # powershell.exe's own options, -File, then this script's options; this file's path may differ in case.
        $parts = @($original[1..$file]) + @("`"$PSCommandPath`"") + @($(if ($original.Count -gt $file + 2) { $original[($file + 2)..($original.Count - 1)] }))
        ($parts | ForEach-Object { if ($_ -match '\s' -and $_ -notmatch '^".*"$') { "`"$_`"" } else { $_ } }) -join ' '
    } else {
        # Started from a console (.\macropad-link.ps1): a new window with the same options.
        $options = foreach ($p in $StartOptions.GetEnumerator()) {
            if ($p.Value -is [switch]) { if ($p.Value) { "-$($p.Key)" } } else { "-$($p.Key) `"$($p.Value)`"" }
        }
        "-NoProfile -ExecutionPolicy Bypass -File `"$PSCommandPath`" $($options -join ' ')"
    }
    Start-Process -FilePath (Get-Command powershell.exe).Source -ArgumentList $arguments
}

# Everything the tablet should know, e.g. after it (re)connects.
function Send-All {
    $script:SentIcons.Clear()
    $script:Mirror = $null # the tablet asks again when its Screen page shows
    $script:Waits.Clear()  # and its macros stopped waiting
    Stop-Capture 'the tablet connected again'
    Send-Hello
    Send-FrontApp -Always
    Send-TeamsState
    Send-Meeting -Always
    Send-Clipboard -Always
    Send-Windows -Always
    Send-Volume -Always
    Send-Media -Always
    Send-Shell -Always
    Send-Monitors -Always
    Send-Lock -Always
    Send-Keyboard -Always
    Send-Badges -Always
    Reset-SlideImages # the tablet has none of them now
    Send-Slides -Always
}

function Use-TabletLine([string]$Line) {
    Write-Traffic "<- $Line"
    try { $request = $Line | ConvertFrom-Json } catch { return }
    switch ([string]$request.t) {
        'hello' { Send-All }
        'teams' { Invoke-TeamsCommand $request }
        'outlook' { Invoke-OutlookCommand $request }
        'join' { Invoke-Join $request }
        'run' { Invoke-NamedCommand $request }
        'focus' { Invoke-Focus $request }
        'capture' { Set-Capture $request }
        'activate' { Add-Wait $request 'focus' ([string]$request.app) }
        'wait' { Add-Wait $request 'wait' ([string]$request.for) }
        'click' { Add-Click $request }
        'volume' { Set-Volume $request }
        'screen' { Set-Mirror $request }
        'screen-ack' { Use-ScreenAck $request }
        'pointer' { Invoke-Pointer $request }
        'slides' { Invoke-Slides $request }
        'update' { Invoke-Update $request }
        'typefile' { Invoke-TypeFile $request }
        default { } # ping, or something newer than this script
    }
}

function Read-Tablet {
    $chunk = $script:Link.ReadExisting()
    if ($chunk) {
        $script:LastHeard = Get-Date
        $script:LinkBuffer += $chunk
        while (($at = $script:LinkBuffer.IndexOf("`n")) -ge 0) {
            $line = $script:LinkBuffer.Substring(0, $at).Trim()
            $script:LinkBuffer = $script:LinkBuffer.Substring($at + 1)
            if ($line) { Use-TabletLine $line }
        }
    }
    # The tablet pings every 30 s; silence means the link is gone even if Windows hasn't noticed.
    if (((Get-Date) - $script:LastHeard).TotalSeconds -gt 90) { throw 'no word from the tablet for 90 s' }
}

# ------------------------------------------------------------------ -TeamsDump

if ($TeamsDump) {
    $teams = Get-TeamsWindows
    if (-not $teams) { Write-Host 'Teams is not running.'; return }
    foreach ($w in $teams.windows) {
        Write-Host "== $($w.Current.Name)"
        foreach ($button in $w.FindAll($UiaDescendants, $UiaButton)) {
            Write-Host ('  {0} | {1}' -f $button.Current.AutomationId, $button.Current.Name)
        }
    }
    $found = Find-TeamsControls $teams.windows $true
    if ($found) {
        Write-Host ''
        foreach ($key in $found.Keys) { Write-Host ('{0,-7} -> {1} | {2}' -f $key, $found[$key].Current.AutomationId, $found[$key].Current.Name) }
    } else {
        Write-Host ''
        Write-Host 'No meeting controls found (join a meeting and run this again).'
    }
    return
}

# ------------------------------------------------------------------ main loop

Write-Log "Macropad PC link started (device '$Device'$(if ($Port) { ", port $Port" }))"
# When each report is next due; requests from the tablet bring some forward.
$Next = @{}
foreach ($key in 'app', 'meeting', 'clip', 'windows', 'volume', 'media', 'shell', 'monitors', 'badges', 'slides') { $Next[$key] = Get-Date }
try {
    while (-not $Restart) {
        Read-Teams
        if (-not $Link) {
            $Link = Open-Link
            if (-not $Link) { Start-Sleep -Seconds 10; continue }
            $LastHeard = Get-Date
            try {
                Send-All
            } catch {
                # Usually an Incoming port (it opens at once but nobody is on the other end), or a
                # tablet without the PC link (before 1.7, or on Bluetooth LE).
                Write-Log "Can't talk to the tablet on $($Link.PortName): $($_.Exception.Message). Use the Outgoing port in More Bluetooth settings > COM Ports, and check the tablet runs 1.7+ on Classic Bluetooth (Diagnostics)."
                Close-Link
                Start-Sleep -Seconds 10
                continue
            }
        }
        try {
            Read-Tablet
            if ($Restart) { break } # updated: the result went out; start the new copy
            Test-Waits
            Send-Capture
            $now = Get-Date
            if ($now -ge $Next.app) { Send-FrontApp; Send-Keyboard; Send-Lock; $Next.app = $now.AddSeconds(1) }
            if ($now -ge $Next.badges) { Send-Badges; Send-NewMail; $Next.badges = $now.AddSeconds(15) }
            if ($now -ge $Next.meeting) { Send-Meeting; $Next.meeting = $now.AddSeconds(60) }
            if ($now -ge $Next.clip) { Send-Clipboard; $Next.clip = $now.AddMilliseconds(500) }
            if ($now -ge $Next.windows) { Send-Windows; $Next.windows = $now.AddSeconds(2) }
            if ($now -ge $Next.volume) { Send-Volume; $Next.volume = $now.AddMilliseconds(500) }
            if ($now -ge $Next.media) { Send-Media; $Next.media = $now.AddSeconds(2) }
            if ($now -ge $Next.shell) { Send-Shell; $Next.shell = $now.AddSeconds(1) }
            if ($now -ge $Next.monitors) { Send-Monitors; $Next.monitors = $now.AddSeconds(5) }
            if ($now -ge $Next.slides) { Send-Slides; $Next.slides = $now.AddMilliseconds(500) }
            Send-SlideImage
            Send-Screen
        } catch {
            Write-Log "Link error: $($_.Exception.Message)"
            Close-Link
            continue
        }
        # Quicker while mirroring, so taps and acknowledgements aren't kept waiting.
        Start-Sleep -Milliseconds $(if ($Mirror) { 40 } else { 200 })
    }
} finally {
    Close-Link
    Disconnect-Teams 'stopped'
    $mutex.ReleaseMutex()
}
if ($Restart) { Start-Again }
