#Requires -Version 5.1
param([switch]$KeepModel, [switch]$KeepDatabase)
$ErrorActionPreference = 'Stop'
$taskProjectRoot = Split-Path -Parent $PSScriptRoot
$taskProcessRecord = Join-Path $taskProjectRoot '.runtime\app-process.json'
$taskExpectedJar = Join-Path $taskProjectRoot 'backend\target\knowledge-api-0.1.0.jar'
if (-not (Test-Path -LiteralPath $taskProcessRecord)) {
    Write-Host 'No background application recorded. For foreground runs, use Ctrl+C in the start terminal.'
} else {
    $taskSavedProcess = Get-Content -LiteralPath $taskProcessRecord -Raw -Encoding UTF8 | ConvertFrom-Json
    if (-not [string]::Equals($taskSavedProcess.jar, $taskExpectedJar, [StringComparison]::OrdinalIgnoreCase)) {
        throw 'The recorded JAR does not belong to this workspace. No process was stopped.'
    }
    $taskRunningProcess = Get-CimInstance Win32_Process -Filter ('ProcessId = ' + [int]$taskSavedProcess.pid)
    if ($taskRunningProcess) {
        if (-not $taskRunningProcess.CommandLine -or
            $taskRunningProcess.CommandLine.IndexOf($taskExpectedJar, [StringComparison]::OrdinalIgnoreCase) -lt 0) {
            throw 'The process identity no longer matches this project. No process was stopped.'
        }
        Stop-Process -Id ([int]$taskSavedProcess.pid)
        Wait-Process -Id ([int]$taskSavedProcess.pid) -Timeout 8 -ErrorAction SilentlyContinue
        Write-Host 'Stopped this project background application.'
    } else { Write-Host 'The recorded background application is already stopped.' }
}
if (-not $KeepModel) {
    $taskModelRecordPath = Join-Path $taskProjectRoot '.runtime\model-process.json'
    if (Test-Path -LiteralPath $taskModelRecordPath) {
        $taskModelRecord = Get-Content -LiteralPath $taskModelRecordPath -Raw -Encoding UTF8 | ConvertFrom-Json
        $taskToolsRoot = [IO.Path]::GetFullPath((Join-Path $taskProjectRoot '.cache\llama-b11433-vulkan')) + [IO.Path]::DirectorySeparatorChar
        $taskModelExe = [IO.Path]::GetFullPath($taskModelRecord.executable)
        if (-not $taskModelExe.StartsWith($taskToolsRoot,[StringComparison]::OrdinalIgnoreCase) -or
            [IO.Path]::GetFileName($taskModelExe) -ne 'llama-server.exe') { throw 'Unrecognized model executable. No model was stopped.' }
        $taskModelProcess = Get-CimInstance Win32_Process -Filter ('ProcessId = ' + [int]$taskModelRecord.pid)
        if ($taskModelProcess) {
            if (-not $taskModelProcess.CommandLine -or $taskModelProcess.CommandLine.IndexOf($taskModelExe,[StringComparison]::OrdinalIgnoreCase) -lt 0) {
                throw 'Model process identity does not match. No model was stopped.'
            }
            Stop-Process -Id ([int]$taskModelRecord.pid)
            Wait-Process -Id ([int]$taskModelRecord.pid) -Timeout 8 -ErrorAction SilentlyContinue
            Write-Host 'Stopped this project local model and released its GPU memory.'
        }
    }
}
if (-not $KeepDatabase) { & (Join-Path $PSScriptRoot 'stop-mysql.ps1') }
