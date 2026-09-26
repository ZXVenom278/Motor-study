# Runs the Demon Dodo <-> OpenCode bridge in a restart loop. Started hidden by the "OpenCode Discord" logon task.
$here = Split-Path -Parent $MyInvocation.MyCommand.Path
Set-Location $here
$log = Join-Path $here 'bridge.log'

while ($true) {
    if ((Test-Path $log) -and (Get-Item $log).Length -gt 5MB) { Move-Item $log "$log.old" -Force }
    "$(Get-Date -Format s) starting bridge" | Out-File -Append -Encoding utf8 $log
    node "$here\bridge.js" 2>&1 | Out-File -Append -Encoding utf8 $log  # full path so install/uninstall can find it
    "$(Get-Date -Format s) bridge exited ($LASTEXITCODE), restarting in 5s" | Out-File -Append -Encoding utf8 $log
    Start-Sleep -Seconds 5
}
