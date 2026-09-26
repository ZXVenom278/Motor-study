# Removes the "OpenCode Discord" task and the demon_dodo tool. Leaves this folder and config.json.
$here = Split-Path -Parent $MyInvocation.MyCommand.Path
Stop-ScheduledTask -TaskName 'OpenCode Discord' -ErrorAction SilentlyContinue
Unregister-ScheduledTask -TaskName 'OpenCode Discord' -Confirm:$false -ErrorAction SilentlyContinue
Get-CimInstance Win32_Process | Where-Object { $_.CommandLine -match [regex]::Escape($here) -and $_.ProcessId -ne $PID } |
    ForEach-Object { Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue }
Remove-Item (Join-Path $env:USERPROFILE '.config\opencode\tool\demon_dodo.js') -ErrorAction SilentlyContinue
Write-Host 'Demon Dodo bridge removed. Restart the OpenCode server to drop the tool.'
