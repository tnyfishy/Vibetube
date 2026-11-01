[CmdletBinding()]
param(
    [string]$Serial,
    [switch]$Watch,
    [switch]$Release,
    [switch]$Logs,
    [switch]$Doctor,
    [switch]$BuildOnly,
    [switch]$StartWatch,
    [switch]$StopWatch
)

$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path $PSScriptRoot -Parent
Set-Location -LiteralPath $projectRoot

$watchStateDir = Join-Path $projectRoot '.local-debug'
$watchPidFile = Join-Path $watchStateDir 'watch.pid'
if ($StartWatch -or $StopWatch) {
    $watchProcess = $null
    if (Test-Path -LiteralPath $watchPidFile) {
        $savedProcessId = (Get-Content -LiteralPath $watchPidFile -Raw).Trim()
        if ($savedProcessId -match '^\d+$') {
            $candidate = Get-CimInstance Win32_Process -Filter "ProcessId = $savedProcessId" -ErrorAction SilentlyContinue
            if ($candidate -and $candidate.CommandLine -and
                $candidate.CommandLine.Contains($PSCommandPath) -and
                $candidate.CommandLine.Contains('-Watch')) { $watchProcess = $candidate }
        }
    }
    if ($StopWatch) {
        if ($watchProcess) { Stop-Process -Id $watchProcess.ProcessId -ErrorAction Stop }
        if (Test-Path -LiteralPath $watchPidFile) { Remove-Item -LiteralPath $watchPidFile }
        Write-Host 'Background watcher stopped.'
        exit 0
    }
    if ($watchProcess) { Write-Host "Watcher already running (PID $($watchProcess.ProcessId))."; exit 0 }
    New-Item -ItemType Directory -Path $watchStateDir -Force | Out-Null
    $watchArguments = "-NoProfile -ExecutionPolicy Bypass -File `"$PSCommandPath`" -Watch"
    if ($Release) { $watchArguments += " -Release" }
    if ($Serial) {
        if ($Serial -notmatch '^[a-zA-Z0-9._:-]+$') { throw 'Invalid device serial.' }
        $watchArguments += " -Serial $Serial"
    }
    $watchChild = Start-Process powershell.exe -ArgumentList $watchArguments -WorkingDirectory $projectRoot -WindowStyle Hidden -PassThru -RedirectStandardOutput "$watchStateDir\watch.log" -RedirectStandardError "$watchStateDir\watch-error.log"
    Set-Content -LiteralPath $watchPidFile -Value $watchChild.Id
    Write-Host "Background watcher started (PID $($watchChild.Id)). Logs: $watchStateDir"
    exit 0
}

# Keep tool configuration local to this process and its children.
$javaCandidates = @($env:JAVA_HOME)
$jdkRoots = @("$env:USERPROFILE\.gradle\jdks", "$env:USERPROFILE\.jdks")
if ($env:GRADLE_USER_HOME) { $jdkRoots += "$env:GRADLE_USER_HOME\jdks" }
foreach ($jdkRoot in $jdkRoots) {
    if (Test-Path -LiteralPath $jdkRoot) {
        $javaCandidates += @(Get-ChildItem -LiteralPath $jdkRoot -Directory | Select-Object -ExpandProperty FullName)
    }
}
$javaCandidates += "$env:ProgramFiles\Android\Android Studio\jbr"
$javaHome = $javaCandidates | Where-Object {
    $_ -and (Test-Path -LiteralPath (Join-Path $_ 'bin\java.exe')) -and
    (Test-Path -LiteralPath (Join-Path $_ 'release')) -and
    (Select-String -LiteralPath (Join-Path $_ 'release') -Pattern '^JAVA_VERSION="21[\.\"]' -Quiet)
} | Select-Object -First 1
if (!$javaHome) { throw 'JDK 21 not found. Install JDK 21 or set JAVA_HOME to its directory.' }
$env:JAVA_HOME = $javaHome

$sdkRoot = @($env:ANDROID_HOME, $env:ANDROID_SDK_ROOT, "$env:LOCALAPPDATA\Android\Sdk") |
    Where-Object { $_ -and (Test-Path -LiteralPath (Join-Path $_ 'platform-tools\adb.exe')) } |
    Select-Object -First 1
if (!$sdkRoot) { throw 'Android SDK not found. Set ANDROID_HOME to the SDK directory.' }
$env:ANDROID_HOME = $sdkRoot
$env:ANDROID_SDK_ROOT = $sdkRoot
$env:Path = "$javaHome\bin;$sdkRoot\platform-tools;$env:Path"
$adb = Join-Path $sdkRoot 'platform-tools\adb.exe'
$packageName = 'vibe.tube.test'

function Invoke-Adb {
    & $adb -s $script:Serial @args
    if ($LASTEXITCODE -ne 0) { throw "ADB failed (exit $LASTEXITCODE)." }
}

Write-Host "Java: $javaHome"
Write-Host "SDK:  $sdkRoot"
if ($Doctor) {
    & "$javaHome\bin\java.exe" -version
    & $adb devices -l
    if ($LASTEXITCODE -ne 0) { throw 'Could not list Android devices.' }
    exit 0
}

if (!$BuildOnly) {
    $deviceOutput = & $adb devices
    if ($LASTEXITCODE -ne 0) { throw 'Could not list Android devices.' }
    $devices = @($deviceOutput | ForEach-Object {
        if ($_ -match '^(\S+)\s+device$') { $Matches[1] }
    })
    if ($Serial) {
        if ($Serial -notin $devices) { throw "Device $Serial is not ready. Check USB debugging authorization." }
    } elseif ($devices.Count -eq 1) {
        $Serial = $devices[0]
    } elseif ($devices.Count -eq 0) {
        throw 'No authorized device. Connect a phone with USB debugging, or start an emulator.'
    } else {
        throw "Multiple devices: $($devices -join ', '). Pass -Serial DEVICE_ID."
    }
    Write-Host "Device: $Serial"
}

function Deploy-App {
    $buildTask = if ($Release) { ':app:assembleRelease' } else { ':app:assembleDebug' }
    & "$projectRoot\gradlew.bat" $buildTask -PvibetubeLocalTest=true --configuration-cache --console=plain
    if ($LASTEXITCODE -ne 0) { throw "Build failed (exit $LASTEXITCODE)." }
    if ($BuildOnly) { return }
    $apkRelative = if ($Release) { 'app\build\outputs\apk\release\app-release.apk' } else { 'app\build\outputs\apk\debug\app-debug.apk' }
    $apkPath = Join-Path $projectRoot $apkRelative
    $previousErrorPreference = $ErrorActionPreference
    try {
        $ErrorActionPreference = 'Continue'
        # Push installation also works on phones that reject streamed ADB installs.
        $installOutput = & $adb -s $Serial install --no-streaming -r $apkPath 2>&1
        $installExitCode = $LASTEXITCODE
    } finally {
        $ErrorActionPreference = $previousErrorPreference
    }
    $installText = ($installOutput | ForEach-Object { $_.ToString() }) -join "`n"
    Write-Host $installText
    if ($installExitCode -ne 0) {
        if ($installText -match 'INSTALL_FAILED_USER_RESTRICTED') {
            throw 'Phone blocked USB installation. Unlock it, enable Install via USB in Developer options, and accept its installation prompt. Then run debug.cmd again.'
        }
        throw "APK installation failed (exit $installExitCode). Existing app data was kept."
    }
    Invoke-Adb shell am force-stop $packageName
    Invoke-Adb shell am start -W -n "$packageName/vibe.tube.MainActivity"
    Write-Host 'Vibetube Test is running.' -ForegroundColor Green
}

if ($Logs) {
    $appProcessId = (Invoke-Adb shell pidof -s $packageName | Out-String).Trim()
    if (!$appProcessId) { throw 'App is not running. Run debug.cmd first.' }
    Invoke-Adb logcat "--pid=$appProcessId" -v threadtime
    exit 0
}

function Get-SourceStamp {
    $sourceFiles = @(Get-ChildItem -LiteralPath "$projectRoot\app\src", "$projectRoot\gradle" -Recurse -File)
    $sourceFiles += @(Get-Item -LiteralPath "$projectRoot\app\build.gradle.kts", "$projectRoot\build.gradle.kts", "$projectRoot\settings.gradle.kts", "$projectRoot\gradle.properties")
    return (($sourceFiles | Sort-Object FullName | ForEach-Object {
        '{0}|{1}|{2}' -f $_.FullName, $_.LastWriteTimeUtc.Ticks, $_.Length
    }) -join "`n")
}

$sourceStamp = if ($Watch) { Get-SourceStamp } else { '' }
Deploy-App
if ($Watch) {
    Write-Host 'Watching app/src and Gradle configuration. Save changes to rebuild and launch. Ctrl+C stops.'
    while ($true) {
        Start-Sleep -Milliseconds 1000
        $nextStamp = Get-SourceStamp
        if ($nextStamp -ne $sourceStamp) {
            # Wait until a burst of editor saves has settled.
            do {
                $sourceStamp = $nextStamp
                Start-Sleep -Milliseconds 800
                $nextStamp = Get-SourceStamp
            } while ($nextStamp -ne $sourceStamp)
            try { Deploy-App } catch { Write-Warning $_.Exception.Message }
        }
    }
}
