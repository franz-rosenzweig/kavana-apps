# Builds Chataltul for Windows on this PC, as the Windows workflow does on GitHub's machines: the core tests, the
# paintings as PNG, the icon, the app, its self-test, and with Inno Setup installed the installer.
# Needs the .NET 10 SDK and Python with Pillow (pip install pillow).
#
#   windows\build.ps1 [-Version 0.1.0] [-SkipSelfTest]
#
# The app ends up in build\Chataltul (it runs from there as it is); the installer in build\out.
param([string]$Version = "", [switch]$SkipSelfTest)
$ErrorActionPreference = 'Stop'
Set-Location (Split-Path $PSScriptRoot)

if (-not $Version) { $Version = ([xml](Get-Content windows\Chataltul\Chataltul.csproj)).Project.PropertyGroup.Version | Where-Object { $_ } | Select-Object -First 1 }

dotnet run --project windows\Tests -c Release -- --repo "$PWD"
if ($LASTEXITCODE) { throw "the core tests failed" }

python windows\tools\convert-art.py
if ($LASTEXITCODE) { throw "the paintings weren't made into PNG (pip install pillow)" }
powershell -NoProfile -File windows\tools\make-icon.ps1
if ($LASTEXITCODE) { throw "the icon wasn't made" }

Remove-Item -Recurse -Force build\Chataltul -ErrorAction SilentlyContinue
dotnet publish windows\Chataltul\Chataltul.csproj -c Release -r win-x64 -p:Platform=x64 -p:Version=$Version -o build\Chataltul
if ($LASTEXITCODE) { throw "the app didn't build" }

if (-not $SkipSelfTest) {
    & windows\selftest.ps1 -App build\Chataltul\Chataltul.exe
    if ($LASTEXITCODE) { throw "the self-test failed" }
}

$iscc = @("${env:ProgramFiles(x86)}\Inno Setup 6\ISCC.exe", "$env:LOCALAPPDATA\Programs\Inno Setup 6\ISCC.exe") | Where-Object { Test-Path $_ } | Select-Object -First 1
if ($iscc) {
    & $iscc /Q "/DVersion=$Version" "/DSource=$PWD\build\Chataltul" windows\installer.iss
    if ($LASTEXITCODE) { throw "the installer didn't build" }
    "made build\out\Chataltul-$Version-Setup.exe"
} else {
    "no Inno Setup: build\Chataltul is the app, without an installer"
}
