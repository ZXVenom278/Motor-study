# Demon Dodo <-> OpenCode setup. Safe to re-run (keeps config.json).
# 1. installs packages   2. config.json (finds/validates the bot token)   3. disables the old Demon Dodo
# 4. installs the demon_dodo OpenCode tool   5. logon task "OpenCode Discord"   6. restarts OpenCode   7. health check
# Works in Windows PowerShell 5.1 and PowerShell 7.
$ErrorActionPreference = 'Stop'
$here = Split-Path -Parent $MyInvocation.MyCommand.Path
$remote = Join-Path $env:USERPROFILE 'OpenCodeRemote'
$ocDir = Join-Path $env:USERPROFILE '.config\opencode'
$toolDir = Join-Path $ocDir 'tool'
$task = 'OpenCode Discord'
[Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12

function Step($t) { Write-Host "`n== $t" -ForegroundColor Cyan }
function Ok($t) { Write-Host "  OK  $t" -ForegroundColor Green }
function Warn($t) { Write-Host "  !!  $t" -ForegroundColor Yellow }
function Ask($q, $default = 'y') {
    $a = Read-Host "  $q [$(if ($default -eq 'y') { 'Y/n' } else { 'y/N' })]"
    if (-not $a) { $a = $default }
    return $a -match '^(y|yes)$'
}
function Test-Token($t) {
    try { return Invoke-RestMethod 'https://discord.com/api/v10/users/@me' -Headers @{ Authorization = "Bot $t" } }
    catch { return $null }
}
$tokenRx = '[A-Za-z0-9_-]{24,28}\.[A-Za-z0-9_-]{6}\.[A-Za-z0-9_-]{27,40}'

# ---------------------------------------------------------------- 1. packages
Step 'Node.js and packages'
if (-not (Get-Command node -ErrorAction SilentlyContinue)) { throw 'Node.js not found. Install Node 18+ from https://nodejs.org and re-run.' }
$nodeMajor = [int]((node -v).TrimStart('v').Split('.')[0])
if ($nodeMajor -lt 18) { throw "Node $(node -v) is too old. Install Node 18+ from https://nodejs.org" }
Push-Location $here
try { npm install --omit=dev --no-audit --no-fund | Out-Host; if ($LASTEXITCODE) { throw 'npm install failed' } } finally { Pop-Location }
Ok "Node $(node -v), packages installed"

# ---------------------------------------------------------------- find the old Demon Dodo
$me = $PID
$oldProcs = @(Get-CimInstance Win32_Process | Where-Object {
        $_.ProcessId -ne $me -and $_.CommandLine -and $_.CommandLine -match 'dodo' -and
        $_.CommandLine -notmatch [regex]::Escape($here) -and $_.CommandLine -notmatch 'install\.ps1|get\.ps1'
    })
$oldTasks = @(Get-ScheduledTask -ErrorAction SilentlyContinue | Where-Object { $_.TaskName -match 'dodo' -and $_.TaskName -ne $task })
$oldTools = @()
if (Test-Path $toolDir) { $oldTools = @(Get-ChildItem $toolDir -File | Where-Object { $_.Name -match 'dodo|discord' -and $_.Name -ne 'demon_dodo.js' }) }

# ---------------------------------------------------------------- 2. config
Step 'Config'
$cfgPath = Join-Path $here 'config.json'
if (Test-Path $cfgPath) {
    $cfg = (Get-Content $cfgPath -Raw).TrimStart([char]0xFEFF) | ConvertFrom-Json
} else {
    $cfg = Get-Content (Join-Path $here 'config.example.json') -Raw | ConvertFrom-Json
}
foreach ($k in 'controlPort', 'membersIntent') {
    if (-not ($cfg.PSObject.Properties.Name -contains $k)) { $cfg | Add-Member -NotePropertyName $k -NotePropertyValue ((Get-Content (Join-Path $here 'config.example.json') -Raw | ConvertFrom-Json).$k) }
}

$bot = $null
if ($cfg.discordToken -and $cfg.discordToken -notmatch 'PASTE') { $bot = Test-Token $cfg.discordToken }
if (-not $bot) {
    # Look for the token the old Demon Dodo already uses.
    $places = @()
    $places += $oldTools | ForEach-Object { $_.FullName }
    foreach ($p in $oldProcs) {
        foreach ($m in [regex]::Matches($p.CommandLine, '"([^"]+)"|(\S+)')) {
            $arg = ($m.Groups[1].Value + $m.Groups[2].Value)
            if ((Test-Path $arg -PathType Leaf -ErrorAction SilentlyContinue) -and $arg -notmatch '\.exe$') { $places += (Split-Path $arg -Parent) }
        }
    }
    $places += Get-ChildItem $env:USERPROFILE, (Join-Path $env:USERPROFILE 'OneDrive\Desktop'), (Join-Path $env:USERPROFILE 'Desktop'), (Join-Path $env:USERPROFILE 'Documents') -Directory -ErrorAction SilentlyContinue |
        Where-Object { $_.Name -match 'dodo|discord' -and $_.FullName -ne $here } | ForEach-Object { $_.FullName }
    $files = foreach ($pl in ($places | Select-Object -Unique)) {
        if (Test-Path $pl -PathType Leaf) { Get-Item $pl }
        else { Get-ChildItem $pl -Recurse -Depth 2 -File -Force -Include *.env, .env, *.json, *.js, *.ts, *.py, *.txt, *.cfg, *.ini, *.toml -ErrorAction SilentlyContinue | Where-Object { $_.FullName -notmatch 'node_modules|\\\.git\\' -and $_.Length -lt 1MB } }
    }
    $seen = @{}
    foreach ($f in $files) {
        $txt = Get-Content $f.FullName -Raw -ErrorAction SilentlyContinue
        if (-not $txt) { continue }
        foreach ($m in [regex]::Matches($txt, $tokenRx)) {
            if ($seen[$m.Value]) { continue }; $seen[$m.Value] = 1
            $b = Test-Token $m.Value
            if ($b -and (Ask "Found bot '$($b.username)' token in $($f.FullName). Use it?")) { $cfg.discordToken = $m.Value; $bot = $b; break }
        }
        if ($bot) { break }
    }
}
while (-not $bot) {
    Write-Host '  Bot token: https://discord.com/developers/applications > Demon Dodo > Bot > Reset Token'
    $sec = Read-Host '  Paste Demon Dodo bot token' -AsSecureString
    $t = [Runtime.InteropServices.Marshal]::PtrToStringAuto([Runtime.InteropServices.Marshal]::SecureStringToBSTR($sec)).Trim()
    $bot = Test-Token $t
    if ($bot) { $cfg.discordToken = $t } else { Warn 'Discord rejected that token. Try again.' }
}
Ok "Bot: $($bot.username) ($($bot.id))"

while ("$($cfg.ownerId)" -notmatch '^\d{17,20}$') {
    Write-Host '  Your user ID: Discord > Settings > Advanced > Developer Mode ON, then right-click your name > Copy User ID'
    $cfg.ownerId = (Read-Host '  Paste your Discord user ID').Trim()
}
Ok "Owner: $($cfg.ownerId) (only this account can DM it)"

if (-not (Test-Path $cfg.passwordFile)) { throw "Password file not found: $($cfg.passwordFile)" }
$cfg | ConvertTo-Json | Set-Content $cfgPath -Encoding utf8
Ok "Saved $cfgPath"

# ---------------------------------------------------------------- 3. old Demon Dodo
Step 'Old Demon Dodo'
if (-not ($oldProcs.Count + $oldTasks.Count + $oldTools.Count)) { Ok 'Nothing old found' }
foreach ($p in $oldProcs) { Warn "Running: [$($p.ProcessId)] $($p.CommandLine)" }
foreach ($t in $oldTasks) { Warn "Task: $($t.TaskName) ($($t.State))" }
if (($oldProcs.Count + $oldTasks.Count) -and (Ask 'Stop these and turn off their auto-start? (two Demon Dodos would double-reply)')) {
    foreach ($t in $oldTasks) { Stop-ScheduledTask -TaskName $t.TaskName -TaskPath $t.TaskPath -ErrorAction SilentlyContinue; Disable-ScheduledTask -TaskName $t.TaskName -TaskPath $t.TaskPath | Out-Null; Ok "Disabled task $($t.TaskName)" }
    foreach ($p in $oldProcs) { Stop-Process -Id $p.ProcessId -Force -ErrorAction SilentlyContinue; Ok "Stopped $($p.ProcessId)" }
}
foreach ($s in @(Get-ChildItem ([Environment]::GetFolderPath('Startup')) -ErrorAction SilentlyContinue | Where-Object Name -match 'dodo')) {
    if (Ask "Startup item '$($s.Name)' launches the old bot. Remove it?") { Move-Item $s.FullName (Join-Path $remote "$($s.Name).OLD") -Force; Ok "Moved to $remote\$($s.Name).OLD" }
}
if ($oldTools.Count) {
    $off = Join-Path $ocDir 'tool-disabled'
    foreach ($t in $oldTools) { Warn "Old OpenCode tool: $($t.FullName)" }
    if (Ask "Move old tool(s) to $off? (demon_dodo replaces them)") {
        New-Item -ItemType Directory -Force $off | Out-Null
        foreach ($t in $oldTools) { Move-Item $t.FullName $off -Force; Ok "Moved $($t.Name)" }
    }
}
foreach ($f in 'opencode.json', 'opencode.jsonc') {
    $p = Join-Path $ocDir $f
    if ((Test-Path $p) -and ((Get-Content $p -Raw) -match '"[^"]*(dodo|discord)[^"]*"\s*:\s*\{')) { Warn "$p has a Discord/Dodo MCP entry. If it runs the old bot, set its `"enabled`": false." }
}

# ---------------------------------------------------------------- 4. tool
Step 'demon_dodo tool'
New-Item -ItemType Directory -Force $toolDir | Out-Null
Copy-Item (Join-Path $here 'tool\demon_dodo.js') $toolDir -Force
Ok "Installed $toolDir\demon_dodo.js"

# ---------------------------------------------------------------- 5. logon task
Step "Task '$task'"
$action = New-ScheduledTaskAction -Execute 'powershell.exe' -Argument "-NoProfile -ExecutionPolicy Bypass -WindowStyle Hidden -File `"$here\start-discord.ps1`""
$trigger = New-ScheduledTaskTrigger -AtLogOn -User $env:USERNAME
$settings = New-ScheduledTaskSettingsSet -AllowStartIfOnBatteries -DontStopIfGoingOnBatteries -ExecutionTimeLimit (New-TimeSpan -Seconds 0)
Register-ScheduledTask -TaskName $task -Action $action -Trigger $trigger -Settings $settings -Force | Out-Null
Stop-ScheduledTask -TaskName $task -ErrorAction SilentlyContinue
Get-CimInstance Win32_Process | Where-Object { $_.CommandLine -match [regex]::Escape($here) -and $_.ProcessId -ne $me -and $_.CommandLine -notmatch 'install\.ps1' } |
    ForEach-Object { Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue }
Start-ScheduledTask -TaskName $task
Ok 'Registered and started'

# ---------------------------------------------------------------- 6. restart OpenCode so it loads the tool
Step 'OpenCode server'
$serve = @(Get-CimInstance Win32_Process | Where-Object { $_.CommandLine -match 'opencode' -and $_.CommandLine -match '\bserve\b' -and $_.Name -notmatch '^(powershell|pwsh)\.exe$' })
if (-not $serve.Count) { Warn "OpenCode server isn't running. Start the 'OpenCode Remote' task." }
elseif (Ask 'Restart the OpenCode server now to load demon_dodo? (interrupts a running task)') {
    $serve | ForEach-Object { Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue }
    Ok 'Stopped; start-server.ps1 brings it back in a few seconds'
} else { Warn 'Restart it later (or log out/in) so the agent gets the demon_dodo tool.' }

# ---------------------------------------------------------------- 7. health check
Step 'Health check'
$pw = (Get-Content $cfg.passwordFile -Raw).Trim()
$auth = @{ Authorization = 'Basic ' + [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes("opencode:$pw")) }
$port = if ($cfg.controlPort) { $cfg.controlPort } else { 4099 }
$servers = $null
for ($i = 0; $i -lt 30 -and -not $servers; $i++) {
    Start-Sleep -Seconds 2
    try {
        $r = Invoke-RestMethod "http://127.0.0.1:$port/discord" -Method Post -Headers $auth -ContentType 'application/json' -Body '{"action":"servers"}'
        if ($r.ok) { $servers = $r.result; if (-not $servers) { $servers = @() }; break }
    } catch {}
}
if ($null -eq $servers) {
    Warn "Bridge didn't come up. Last log lines:"
    Get-Content (Join-Path $here 'bridge.log') -Tail 15 -ErrorAction SilentlyContinue | ForEach-Object { Write-Host "    $_" }
    exit 1
}
Ok "Demon Dodo is online in $(@($servers).Count) server(s): $((@($servers) | ForEach-Object { $_.name }) -join ', ')"
$ocUp = $false
for ($i = 0; $i -lt 20 -and -not $ocUp; $i++) {
    try { Invoke-RestMethod 'http://127.0.0.1:4096/session' -Headers $auth | Out-Null; $ocUp = $true } catch { Start-Sleep -Seconds 2 }
}
if ($ocUp) { Ok 'OpenCode server is up' } else { Warn "OpenCode server isn't answering on 127.0.0.1:4096" }

Write-Host "`nDone. DM Demon Dodo from your account to test. Commands: !help  !new  !stop  !session" -ForegroundColor Green
Write-Host "Invite link (to add the bot to another server): https://discord.com/oauth2/authorize?client_id=$($bot.id)&scope=bot&permissions=274878032960"
