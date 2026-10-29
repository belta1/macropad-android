<#
.SYNOPSIS
    Macropad PC link: keeps the tablet's Bluetooth serial port open, tells the tablet about Teams,
    the app in front and the next Outlook meeting, and runs the tablet's pc: steps.

.DESCRIPTION
    The macropad (Classic Bluetooth, e.g. Galaxy Tab S5e) offers a serial port next to the keyboard;
    Windows shows it as an outgoing Bluetooth COM port. This script opens it and talks to the tablet
    in newline-delimited JSON (see docs/pc-link.md). Windows PowerShell 5.1, no modules.

    Config and logs live in %APPDATA%\Macropad:
      commands.json     the commands pc:run may start (copied from macropad-commands.example.json)
      teams-token.txt   the token Teams hands out once you allow "Macropad"
      link.log          what happened, also when running hidden

.PARAMETER Port
    The COM port, e.g. COM5. Default: the outgoing Bluetooth COM port of the device named -Device.

.PARAMETER Device
    The tablet's Bluetooth name (default: Macropad).

.PARAMETER Install
    Start this script hidden at sign-in (a shortcut in the Startup folder), then exit.

.PARAMETER Uninstall
    Remove that shortcut, then exit.

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
    [switch]$NoOutlook
)

$ErrorActionPreference = 'Stop'
$ProtocolVersion = 1
$ConfigDir = Join-Path $env:APPDATA 'Macropad'
$CommandsFile = Join-Path $ConfigDir 'commands.json'
$TokenFile = Join-Path $ConfigDir 'teams-token.txt'
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

# One copy at a time (the Startup shortcut and a manual run would fight over the port).
$mutex = New-Object System.Threading.Mutex($false, 'Local\MacropadPcLink')
if (-not $mutex.WaitOne(0)) {
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
}

function Send-Tablet($Message) {
    if (-not $script:Link) { return }
    $json = $Message | ConvertTo-Json -Compress -Depth 4
    $script:Link.Write($json + "`n")
    Write-Traffic "-> $json"
}

# ------------------------------------------------------------------ the app in front

Add-Type -Namespace MacropadLink -Name Win32 -MemberDefinition @'
[DllImport("user32.dll")] public static extern IntPtr GetForegroundWindow();
[DllImport("user32.dll")] public static extern int GetWindowThreadProcessId(IntPtr hWnd, out int processId);
[DllImport("user32.dll", CharSet = CharSet.Unicode)] public static extern int GetWindowText(IntPtr hWnd, System.Text.StringBuilder text, int count);
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
            return [ordered]@{
                t = 'meeting'
                title = $item.Subject
                start = $item.Start.ToString("yyyy-MM-dd'T'HH:mm:sszzz")
                end = $item.End.ToString("yyyy-MM-dd'T'HH:mm:sszzz")
            }
        }
        $item = $found.GetNext()
    }
    return $null
}

function Send-Meeting([switch]$Always) {
    if ($NoOutlook) { return }
    $wasReadable = $script:OutlookReadable
    try {
        $meeting = Get-NextMeeting
        $script:OutlookReadable = $true
    } catch {
        $script:OutlookReadable = $false
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
            Write-Log "Teams API not reachable on 127.0.0.1:8124 ($why). In new Teams: Settings > Privacy > Third-party app API > Manage API. Retrying every 15 s."
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
    if (-not $script:Teams) {
        return Send-Result $Request.id $false 'Teams is not running, or its API is off (Teams > Settings > Privacy > Third-party app API)'
    }
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

# ------------------------------------------------------------------ talking to the tablet

function Send-Hello {
    Send-Tablet ([ordered]@{
        t = 'hello'; v = $ProtocolVersion; host = $env:COMPUTERNAME
        teams = (-not $NoTeams); outlook = $script:OutlookReadable
    })
}

# Everything the tablet should know, e.g. after it (re)connects.
function Send-All {
    Send-Hello
    Send-FrontApp -Always
    Send-TeamsState
    Send-Meeting -Always
}

function Use-TabletLine([string]$Line) {
    Write-Traffic "<- $Line"
    try { $request = $Line | ConvertFrom-Json } catch { return }
    switch ([string]$request.t) {
        'hello' { Send-All }
        'teams' { Invoke-TeamsCommand $request }
        'run' { Invoke-NamedCommand $request }
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

# ------------------------------------------------------------------ main loop

Write-Log "Macropad PC link started (device '$Device'$(if ($Port) { ", port $Port" }))"
$nextApp = Get-Date
$nextMeeting = Get-Date
try {
    while ($true) {
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
            $now = Get-Date
            if ($now -ge $nextApp) { Send-FrontApp; $nextApp = $now.AddSeconds(1) }
            if ($now -ge $nextMeeting) { Send-Meeting; $nextMeeting = $now.AddSeconds(60) }
        } catch {
            Write-Log "Link error: $($_.Exception.Message)"
            Close-Link
            continue
        }
        Start-Sleep -Milliseconds 200
    }
} finally {
    Close-Link
    Disconnect-Teams 'stopped'
    $mutex.ReleaseMutex()
}
