# One-line install/update from GitHub:
#   irm https://raw.githubusercontent.com/ZXVenom278/Motor-study/claude/opencode-pc-phone-handoff-soouje/opencode-discord/get.ps1 | iex
# Downloads opencode-discord into %USERPROFILE%\OpenCodeRemote\discord-bridge (keeps config.json) and runs install.ps1.
$ErrorActionPreference = 'Stop'
[Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12
$branch = if ($env:DODO_BRANCH) { $env:DODO_BRANCH } else { 'claude/opencode-pc-phone-handoff-soouje' }
$dest = Join-Path $env:USERPROFILE 'OpenCodeRemote\discord-bridge'
$tmp = Join-Path $env:TEMP "dodo-$([guid]::NewGuid().ToString('N'))"
New-Item -ItemType Directory -Force $tmp, $dest | Out-Null
try {
    Write-Host "Downloading $branch ..."
    Invoke-WebRequest "https://github.com/ZXVenom278/Motor-study/archive/refs/heads/$branch.zip" -OutFile "$tmp\src.zip" -UseBasicParsing
    Expand-Archive "$tmp\src.zip" $tmp -Force
    $src = Get-ChildItem $tmp -Directory | ForEach-Object { Join-Path $_.FullName 'opencode-discord' } | Where-Object { Test-Path $_ } | Select-Object -First 1
    if (-not $src) { throw 'opencode-discord folder not found in download' }
    Copy-Item "$src\*" $dest -Recurse -Force
    Write-Host "Files in $dest"
} finally { Remove-Item $tmp -Recurse -Force -ErrorAction SilentlyContinue }
& powershell -NoProfile -ExecutionPolicy Bypass -File (Join-Path $dest 'install.ps1')
