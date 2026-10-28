# The self-test: the app built in build\Chataltul, wired up on a scratch folder, with nobody at the keyboard.
# The Windows workflow runs it after every build; so can you.
#
#   windows\selftest.ps1 [-App build\Chataltul\Chataltul.exe] [-OnScreen] [-Browser] [-Launch]
#
# By itself every window stays far off the screen, so nothing shows on the desk of whoever runs it, and no
# browser is touched. The workflow, on a machine of its own, adds:
#   -OnScreen  real pixels and a real mouse: the windows are see-through where they don't paint, a click goes
#              through the cat's empty corner, and Leave anyway is held for real
#   -Browser   Edge, Chrome and Firefox (those installed) open a page, and their address is read
#   -Launch    the app as it ships starts, sits in the tray for a few seconds and quits cleanly (not while
#              your own Chataltul runs: a second one only wakes the first)
# It prints each check and leaves with 0 when every one held. The pictures of every screen go to build\selftest.
param([string]$App = "build\Chataltul\Chataltul.exe", [switch]$OnScreen, [switch]$Browser, [switch]$Launch)
$ErrorActionPreference = 'Stop'

$work = Join-Path ([IO.Path]::GetTempPath()) ("chataltul-selftest-" + [guid]::NewGuid().ToString('N'))
$data = Join-Path $work 'Data'
$shots = Join-Path $work 'Shots'
$results = Join-Path $work 'results.json'
New-Item -ItemType Directory -Force $data, $shots | Out-Null

$arguments = @('--selftest', '--data', "`"$data`"", '--out', "`"$results`"", '--shots', "`"$shots`"")
if ($OnScreen) { $arguments += '--onscreen' }
if ($Browser) { $arguments += '--browser' }
$process = Start-Process -FilePath (Resolve-Path $App) -ArgumentList $arguments -PassThru
$null = $process.Handle          # or the exit code is lost once it ends
if (-not $process.WaitForExit(240000)) {
    $process.Kill()
    Write-Host "the self-test didn't finish in four minutes"
    $code = 2
} else {
    $code = $process.ExitCode
}

if (Test-Path $results) {
    $report = Get-Content $results -Raw -Encoding utf8 | ConvertFrom-Json
    foreach ($check in $report.checks) {
        $mark = if ($check.ok) { 'ok  ' } else { 'FAIL' }
        if ($check.detail) { "$mark  $($check.name)  ($($check.detail))" } else { "$mark  $($check.name)" }
    }
    $failed = @($report.checks | Where-Object { -not $_.ok }).Count
    "$(@($report.checks).Count - $failed) passed, $failed failed"
} else {
    Write-Host "no results were written"
    if ($code -eq 0) { $code = 3 }
}
$log = Join-Path $data 'chataltul.log'
if (Test-Path $log) { "--- chataltul.log"; Get-Content $log -Encoding utf8 | Select-Object -Last 80 }
# the workflow keeps the pictures with the build, to look at
New-Item -ItemType Directory -Force 'build\selftest' | Out-Null
if (Test-Path $shots) { Copy-Item (Join-Path $shots '*') 'build\selftest' -ErrorAction SilentlyContinue }
if (Test-Path $log) { Copy-Item $log 'build\selftest\selftest.log' }

if ($Launch) {
    # the app as it ships: no self-test, a scratch folder, started as Windows starts it at sign-in, gone after 4 seconds
    $launchData = Join-Path $work 'Launch'
    New-Item -ItemType Directory -Force $launchData | Out-Null
    $shipped = Start-Process -FilePath (Resolve-Path $App) -ArgumentList @('--login', '--data', "`"$launchData`"", '--quit-after', '4') -PassThru
    $null = $shipped.Handle
    $launchLog = Join-Path $launchData 'chataltul.log'
    if (-not $shipped.WaitForExit(60000)) {
        $shipped.Kill()
        "FAIL  the app as it ships starts and quits (it was still running after a minute)"
        $code = if ($code) { $code } else { 4 }
    } elseif ($shipped.ExitCode -ne 0 -or -not (Test-Path $launchLog) -or -not (Select-String -Path $launchLog -Pattern 'LAUNCH ok' -Quiet)) {
        "FAIL  the app as it ships starts and quits (exit code $($shipped.ExitCode))"
        if (Test-Path $launchLog) { Get-Content $launchLog -Encoding utf8 | Select-Object -Last 30 }
        $code = if ($code) { $code } else { 4 }
    } else {
        "ok    the app as it ships starts, sits in the tray and quits"
    }
    if (Test-Path $launchLog) { Copy-Item $launchLog 'build\selftest\launch.log' }
}
Remove-Item -Recurse -Force $work -ErrorAction SilentlyContinue
exit $code
