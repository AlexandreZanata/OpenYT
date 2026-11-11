param(
    [string]$Msi = (Get-ChildItem desktopApp/build/compose/binaries/main/msi/*.msi | Select-Object -First 1).FullName,
    [string]$Exe = (Get-ChildItem desktopApp/build/compose/binaries/main/exe/*.exe | Select-Object -First 1).FullName,
    [string]$Harness = "$PWD/desktopApp/build/libs/windows-contract-harness.jar"
)
$ErrorActionPreference = 'Stop'
$work = Join-Path $PWD 'build/windows-validation'
New-Item -ItemType Directory -Force $work | Out-Null
$shell = New-Object -ComObject WScript.Shell
$explorer = New-Object -ComObject Shell.Application
$extension = '.openyt-contract-' + [Guid]::NewGuid().ToString('N')
$class = 'OpenYT.Contract.' + [Guid]::NewGuid().ToString('N')
$classes = 'HKCU:\Software\Classes'
$file = Join-Path $work ('Vídeo 100% & ação #1 (final)' + $extension)
[IO.File]::WriteAllText($file, 'Shell association fixture')
$handler = Join-Path $work 'record-open.ps1'
@'
param([string]$SelectedFile)
[IO.File]::WriteAllText((Join-Path $PSScriptRoot 'opened.txt'), $SelectedFile, [Text.Encoding]::UTF8)
'@ | Set-Content -Encoding utf8 $handler
$powershell = Join-Path $env:SystemRoot 'System32/WindowsPowerShell/v1.0/powershell.exe'

function Run-Launcher([string]$Path, [string[]]$Arguments, [string]$Name) {
    $process = Start-Process -FilePath $Path -ArgumentList $Arguments -PassThru `
        -RedirectStandardOutput (Join-Path $work "$Name.log") -RedirectStandardError (Join-Path $work "$Name.err")
    if (!$process.WaitForExit(90000)) { $process.Kill(); throw "$Name timed out" }
    if ($process.ExitCode -ne 0) { Get-Content (Join-Path $work "$Name.err"); throw "$Name failed: $($process.ExitCode)" }
}

function Find-Shortcuts {
    $desktop = @([Environment]::GetFolderPath('Desktop'), [Environment]::GetFolderPath('CommonDesktopDirectory'))
    $menu = @([Environment]::GetFolderPath('Programs'), [Environment]::GetFolderPath('CommonPrograms'))
    $desktopLinks = @($desktop | Where-Object { $_ } | ForEach-Object { Get-ChildItem $_ -Filter OpenYT.lnk -ErrorAction SilentlyContinue })
    $menuLinks = @($menu | Where-Object { $_ } | ForEach-Object { Get-ChildItem $_ -Filter OpenYT.lnk -Recurse -ErrorAction SilentlyContinue })
    if (!$desktopLinks.Count -or !$menuLinks.Count) { throw 'Desktop or Start-menu shortcut missing' }
    return @($desktopLinks + $menuLinks | Sort-Object FullName -Unique)
}

function Validate-Installed([string]$Format) {
    $targetFile = Join-Path $work ($Format + '-' + [IO.Path]::GetFileName($file))
    [IO.File]::WriteAllText($targetFile, 'Unicode shell fixture')
    $app = Join-Path $env:ProgramFiles 'OpenYT/OpenYT.exe'
    if (!(Test-Path $app)) { throw 'Installed launcher missing' }
    $links = @(Find-Shortcuts)
    $linkEvidence = @()
    foreach ($link in $links) {
        $shortcut = $shell.CreateShortcut($link.FullName)
        $linkEvidence += [ordered]@{ link = $link.FullName; target = $shortcut.TargetPath; icon = $shortcut.IconLocation }
        $linkEvidence | ConvertTo-Json | Set-Content (Join-Path $work "$Format-shortcuts.json")
        if ($shortcut.TargetPath -ne $app) { throw "Invalid shortcut target: $($link.Name)" }
        $icon = [Environment]::ExpandEnvironmentVariables(($shortcut.IconLocation -replace ',\s*-?\d+$', '').Trim('"'))
        # An empty IconLocation is valid: Explorer uses the target's embedded icon.
        if (!$icon) { $icon = $shortcut.TargetPath }
        if (![IO.Path]::IsPathRooted($icon)) { $icon = Join-Path (Split-Path $shortcut.TargetPath) $icon }
        if (!(Test-Path $icon)) { throw "Shortcut icon resource missing: $icon" }
        $actualIcon = [Drawing.Icon]::ExtractAssociatedIcon($icon)
        $expectedIcon = [Drawing.Icon]::new((Resolve-Path 'desktopApp/packaging/icons/openyt.ico').Path, [Drawing.Size]::new(32,32))
        try {
            $actual = [Drawing.Bitmap]::new($actualIcon.ToBitmap(),32,32)
            $expected = $expectedIcon.ToBitmap()
            try {
                $matched = 0; $visible = 0
                for ($y=0; $y -lt 32; $y++) { for ($x=0; $x -lt 32; $x++) {
                    $e = $expected.GetPixel($x,$y); $a = $actual.GetPixel($x,$y)
                    if ($e.A -gt 128) {
                        $visible++
                        if ([Math]::Abs([int]$e.R-$a.R) -le 8 -and [Math]::Abs([int]$e.G-$a.G) -le 8 -and [Math]::Abs([int]$e.B-$a.B) -le 8) { $matched++ }
                    }
                } }
                $actual.Save((Join-Path $work "$Format-icon-$($linkEvidence.Count).png"))
                if ($visible -lt 100 -or $matched / $visible -lt 0.9) { throw 'Installed shortcut does not use the OpenYT brand icon' }
            } finally { $actual.Dispose(); $expected.Dispose() }
        } finally { $actualIcon.Dispose(); $expectedIcon.Dispose() }
    }
    Run-Launcher $app @('--cli', 'version') "$Format-version"
    $expected = (Select-String '^openyt.version=' gradle.properties).Line.Split('=')[1]
    if ((Get-Content (Join-Path $work "$Format-version.log") -Raw).Trim() -ne $expected) { throw 'Installed version mismatch' }
    Run-Launcher $app @('--cli', 'sources', '--json') "$Format-sources"
    if ((Get-Content (Join-Path $work "$Format-sources.log") -Raw | ConvertFrom-Json).Count -lt 100) { throw 'Installed source catalog missing' }

    # Launch the actual .lnk, rather than merely inspecting the installer settings.
    $desktopFolders = @([Environment]::GetFolderPath('Desktop'), [Environment]::GetFolderPath('CommonDesktopDirectory'))
    $desktopLink = $links | Where-Object { $_.DirectoryName -in $desktopFolders } | Select-Object -First 1
    if (!$desktopLink) { throw 'Desktop shortcut missing' }
    Start-Process -FilePath $desktopLink.FullName
    $deadline = [DateTime]::UtcNow.AddSeconds(30)
    do {
        Start-Sleep -Milliseconds 250
        $gui = @(Get-Process OpenYT -ErrorAction SilentlyContinue | Where-Object { $_.MainWindowHandle -ne 0 })
    } until ($gui.Count -or [DateTime]::UtcNow -gt $deadline)
    if (!$gui.Count) { throw 'Installed shortcut did not open the application window' }
    $gui | Stop-Process
    Start-Sleep -Seconds 1

    # Only test files are added: the shipping launcher/config and jars stay intact.
    $root = Split-Path $app
    $testExe = Join-Path $root 'OpenYT-contract.exe'
    $testCfg = Join-Path $root 'app/OpenYT-contract.cfg'
    Copy-Item $app $testExe -Force
    $config = Get-Content (Join-Path $root 'app/OpenYT.cfg') -Raw
    $config = $config -replace '(?m)^app.mainclass=.*$', 'app.mainclass=com.openyt.desktop.contract.WindowsContractHarness'
    $config = $config.Replace('[JavaOptions]', ('app.classpath=' + (Resolve-Path $Harness).Path + "`n`n[JavaOptions]"))
    [IO.File]::WriteAllText($testCfg, $config, [Text.UTF8Encoding]::new($false))
    try {
        Remove-Item (Join-Path $work 'opened.txt') -ErrorAction SilentlyContinue
        Run-Launcher $testExe @('open', ('"' + $targetFile + '"')) "$Format-open"
        $deadline = [DateTime]::UtcNow.AddSeconds(15)
        while (!(Test-Path (Join-Path $work 'opened.txt')) -and [DateTime]::UtcNow -lt $deadline) { Start-Sleep -Milliseconds 100 }
        if (!(Test-Path (Join-Path $work 'opened.txt')) -or (Get-Content (Join-Path $work 'opened.txt') -Raw) -ne $targetFile) { throw 'Open did not reach the associated handler with the exact Unicode path' }
        Run-Launcher $testExe @('reveal', ('"' + $targetFile + '"')) "$Format-reveal"
        $found = $false
        $deadline = [DateTime]::UtcNow.AddSeconds(20)
        do {
            foreach ($window in @($explorer.Windows())) {
                try {
                    foreach ($item in @($window.Document.SelectedItems())) {
                        if ($item.Path -eq $targetFile) { $found = $true }
                    }
                } catch { }
            }
            if (!$found) { Start-Sleep -Milliseconds 200 }
        } until ($found -or [DateTime]::UtcNow -gt $deadline)
        if (!$found) { throw 'Explorer did not select the requested Unicode file' }
        $previousPlugins = $env:VLC_PLUGIN_PATH
        $env:VLC_PLUGIN_PATH = Join-Path $work 'incompatible-vlc-plugins'
        try {
            Run-Launcher $testExe @('play', ('"' + (Join-Path $work "$Format-vídeo & ação.mp4") + '"')) "$Format-play"
        } finally { $env:VLC_PLUGIN_PATH = $previousPlugins }
        Copy-Item (Join-Path $work 'windows-playback.png') (Join-Path $work "$Format-playback.png") -Force
    } finally {
        Remove-Item $testExe, $testCfg -ErrorAction SilentlyContinue
    }
    Write-Output "PASS ${Format}: installed shortcut target/icon/launch, exact external-open handler, Explorer selection, bundled VLC decoded playback"
}

try {
    New-Item "$classes\$extension" -Force | Out-Null
    Set-Item "$classes\$extension" $class
    New-Item "$classes\$class\shell\open\command" -Force | Out-Null
    Set-Item "$classes\$class\shell\open\command" ('"' + $powershell + '" -NoProfile -NonInteractive -File "' + $handler + '" "%1"')
    $install = Start-Process msiexec.exe -ArgumentList @('/i', ('"' + $Msi + '"'), '/qn', '/norestart') -Wait -PassThru
    if ($install.ExitCode -notin @(0,3010)) { throw "MSI installation failed: $($install.ExitCode)" }
    Validate-Installed 'msi'
    $uninstall = Start-Process msiexec.exe -ArgumentList @('/x', ('"' + $Msi + '"'), '/qn', '/norestart') -Wait -PassThru
    if ($uninstall.ExitCode -notin @(0,3010)) { throw 'MSI uninstall failed' }
    if (!(Test-Path $file)) { throw 'Uninstall deleted user media' }
    $install = Start-Process $Exe -ArgumentList @('/quiet', '/norestart') -Wait -PassThru
    if ($install.ExitCode -notin @(0,3010)) { throw "EXE installation failed: $($install.ExitCode)" }
    Validate-Installed 'exe'
} finally {
    Remove-Item "$classes\$extension", "$classes\$class" -Recurse -Force -ErrorAction SilentlyContinue
}
