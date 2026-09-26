# One-time setup: installs packages, writes config.json, registers the "OpenCode Discord" logon task, starts it.
$ErrorActionPreference = 'Stop'
$here = Split-Path -Parent $MyInvocation.MyCommand.Path

if (-not (Get-Command node -ErrorAction SilentlyContinue)) { throw 'Node.js not found. Install Node 18+ from https://nodejs.org and re-run.' }

Push-Location $here
npm install --omit=dev
Pop-Location

$cfg = Join-Path $here 'config.json'
if (-not (Test-Path $cfg)) {
    $c = Get-Content (Join-Path $here 'config.example.json') -Raw | ConvertFrom-Json
    $c.discordToken = Read-Host 'Demon Dodo bot token'
    $c.ownerId = Read-Host 'Your Discord user ID'
    $c | ConvertTo-Json | Set-Content $cfg -Encoding utf8
    Write-Host "Wrote $cfg"
}

$toolDir = Join-Path $env:USERPROFILE '.config\opencode\tool'
New-Item -ItemType Directory -Force $toolDir | Out-Null
Copy-Item (Join-Path $here 'tool\demon_dodo.js') $toolDir -Force
Write-Host "Installed demon_dodo tool to $toolDir (restart the OpenCode server to load it)"

$task = 'OpenCode Discord'
$action = New-ScheduledTaskAction -Execute 'powershell.exe' -Argument "-NoProfile -ExecutionPolicy Bypass -WindowStyle Hidden -File `"$here\start-discord.ps1`""
$trigger = New-ScheduledTaskTrigger -AtLogOn -User $env:USERNAME
$settings = New-ScheduledTaskSettingsSet -AllowStartIfOnBatteries -DontStopIfGoingOnBatteries -ExecutionTimeLimit (New-TimeSpan -Seconds 0)
Register-ScheduledTask -TaskName $task -Action $action -Trigger $trigger -Settings $settings -Force | Out-Null
Stop-ScheduledTask -TaskName $task -ErrorAction SilentlyContinue
Start-ScheduledTask -TaskName $task
Write-Host "Task '$task' registered and started. Log: $here\bridge.log"
