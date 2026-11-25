param(
    [string]$ProductCode,
    [int]$ParentPid = 0,
    [string]$InstallDir = ""
)
$ErrorActionPreference = 'SilentlyContinue'
$log = Join-Path $env:TEMP 'nexus-uninstall.log'
function Say($m) { Add-Content -LiteralPath $log -Value ("[{0}] {1}" -f (Get-Date -Format 'HH:mm:ss'), $m) }
Set-Content -LiteralPath $log -Value '' 
Say "start, product=$ProductCode, dir=$InstallDir"

if ($ParentPid -gt 0) { try { Wait-Process -Id $ParentPid -Timeout 30 } catch {} }
Start-Sleep -Milliseconds 700

function Test-LooksLikeInstallDir {
    return ($InstallDir -and (Test-Path -LiteralPath (Join-Path $InstallDir 'app')) `
        -and (Test-Path -LiteralPath (Join-Path $InstallDir 'runtime')) `
        -and (Get-ChildItem -LiteralPath $InstallDir -Filter 'NEXUS*.exe' -File))
}

function Test-InstallerBusy {
    try { $m = [Threading.Mutex]::OpenExisting('Global\_MSIExecute'); $m.Dispose(); return $true } catch { return $false }
}
function Reset-ConfigMsi([string]$drive) {
    if (-not $drive) { return }
    $cm = Join-Path $drive 'Config.Msi'
    if (-not (Test-Path -LiteralPath $cm)) { return }
    if (Test-InstallerBusy) { Say "installer busy, leaving $cm"; return }
    Say "resetting $cm"
    & takeown.exe /f $cm /r /d y | Out-Null
    & icacls.exe $cm /grant '*S-1-5-32-544:(OI)(CI)F' /t /c /q | Out-Null
    Remove-Item -LiteralPath $cm -Recurse -Force
}
$drives = @()
if ($InstallDir) { $drives += [IO.Path]::GetPathRoot($InstallDir) }
$drives += ($env:SystemDrive + '\')
$drives = $drives | Where-Object { $_ } | Select-Object -Unique
foreach ($d in $drives) { Reset-ConfigMsi $d }

$ntfs = $true
if ($InstallDir) {
    try { $ntfs = ([IO.DriveInfo]([IO.Path]::GetPathRoot($InstallDir))).DriveFormat -eq 'NTFS' } catch {}
}
$custom = $false
try { $custom = [bool](Get-ItemProperty -LiteralPath "HKCU:\Software\Microsoft\Windows\CurrentVersion\Uninstall\$ProductCode").NexusInstaller } catch {}
if ($custom) { Say 'installed by the NEXUS installer, skipping msiexec'; $ntfs = $false }
$ok = $false
if ($ntfs) {
    for ($try = 1; $try -le 2 -and -not $ok; $try++) {
        $msiLog = Join-Path $env:TEMP 'nexus-msiexec.log'
        $p = Start-Process -FilePath 'msiexec.exe' -ArgumentList "/x $ProductCode /qb /norestart /l*v `"$msiLog`"" -Wait -PassThru
        $code = $p.ExitCode
        Say "msiexec try $try exit=$code"
        if (@(0, 3010, 1641, 1605) -contains $code) { $ok = $true } else { foreach ($d in $drives) { Reset-ConfigMsi $d } }
    }
} else {
    Say 'install drive is not NTFS, skipping msiexec'
}

if (-not $ok) {
    $looksRight = Test-LooksLikeInstallDir
    if ($looksRight) {
        Say "manual removal of the program files in $InstallDir"
        foreach ($name in 'app', 'runtime', '.jpackage.xml') {
            $item = Join-Path $InstallDir $name
            if (Test-Path -LiteralPath $item) { Remove-Item -LiteralPath $item -Recurse -Force }
        }
        Get-ChildItem -LiteralPath $InstallDir -File -Force | Where-Object { $_.Name -like 'NEXUS*.exe' -or $_.Name -like '*.ico' -or $_.Name -like 'unins*' } | Remove-Item -Force
        if (-not (Get-ChildItem -LiteralPath $InstallDir -Force)) { Remove-Item -LiteralPath $InstallDir -Force; Say 'install folder was empty and removed' }
        else { Say 'install folder contains other files, left as is' }
        foreach ($root in 'HKLM:', 'HKCU:') {
            foreach ($sub in 'Software\Microsoft\Windows\CurrentVersion\Uninstall', 'Software\WOW6432Node\Microsoft\Windows\CurrentVersion\Uninstall') {
                Remove-Item -LiteralPath "$root\$sub\$ProductCode" -Recurse -Force
            }
        }
        $shortcutDirs = @(
            [Environment]::GetFolderPath('Desktop'), [Environment]::GetFolderPath('CommonDesktopDirectory'),
            [Environment]::GetFolderPath('Programs'), [Environment]::GetFolderPath('CommonPrograms'))
        foreach ($dir in $shortcutDirs) {
            if (-not $dir) { continue }
            Get-ChildItem -LiteralPath $dir -Filter 'NEXUS*.lnk' -File | Remove-Item -Force
            $sub = Join-Path $dir 'NEXUS'
            if (Test-Path -LiteralPath $sub) { Remove-Item -LiteralPath $sub -Recurse -Force }
        }
    } else {
        Say 'install folder not recognised, nothing removed'
    }
}
Say 'done'
Remove-Item -LiteralPath $PSCommandPath -Force
