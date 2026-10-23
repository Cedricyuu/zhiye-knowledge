#Requires -Version 5.1
$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Net.Http
$taskProjectRoot = Split-Path -Parent $PSScriptRoot
$taskRuntimeDir = Join-Path $taskProjectRoot '.runtime'
$taskToolsDir = Join-Path $taskProjectRoot '.cache\llama-b11433-vulkan'
$taskModelPath = Join-Path $taskProjectRoot '.cache\models\Qwen3-4B-Q4_K_M.gguf'
$taskServer = Get-ChildItem -LiteralPath $taskToolsDir -Recurse -File -Filter 'llama-server.exe' -ErrorAction SilentlyContinue | Select-Object -First 1
if (-not $taskServer -or -not (Test-Path -LiteralPath $taskModelPath)) {
    throw 'Local AI files are missing. Run scripts/setup-local-ai.ps1 first.'
}
New-Item -ItemType Directory -Path $taskRuntimeDir -Force | Out-Null
$taskRecordPath = Join-Path $taskRuntimeDir 'model-process.json'
if (Test-Path -LiteralPath $taskRecordPath) {
    $taskRecorded = Get-Content -LiteralPath $taskRecordPath -Raw -Encoding UTF8 | ConvertFrom-Json
    $taskExisting = Get-CimInstance Win32_Process -Filter ('ProcessId = ' + [int]$taskRecorded.pid)
    if ($taskExisting -and $taskExisting.CommandLine -and
        $taskExisting.CommandLine.IndexOf($taskServer.FullName,[StringComparison]::OrdinalIgnoreCase) -ge 0) {
        Write-Host 'The project local model is already running.'
        return
    }
}
$taskKeyPath = Join-Path $taskRuntimeDir 'model-api-key'
if (-not (Test-Path -LiteralPath $taskKeyPath)) {
    $taskBytes = New-Object byte[] 32
    $taskRandom = [Security.Cryptography.RandomNumberGenerator]::Create()
    try { $taskRandom.GetBytes($taskBytes) } finally { $taskRandom.Dispose() }
    [IO.File]::WriteAllText($taskKeyPath,[Convert]::ToBase64String($taskBytes),[Text.UTF8Encoding]::new($false))
}
# Narrow-character C++ file APIs on Windows need ASCII paths; the process working directory is the project root.
$taskArguments = @('-m','.cache/models/Qwen3-4B-Q4_K_M.gguf','--host','127.0.0.1','--port','18081',
    '--ctx-size','8192','--n-gpu-layers','99','--threads','4','--threads-batch','4',
    '--parallel','1','--jinja','--reasoning','off','--no-webui',
    '--alias','qwen3-4b-local','--api-key-file','.runtime/model-api-key')
$taskProcess = Start-Process -FilePath $taskServer.FullName -ArgumentList $taskArguments -WorkingDirectory $taskProjectRoot -WindowStyle Hidden -PassThru `
    -RedirectStandardOutput (Join-Path $taskRuntimeDir 'model.out.log') -RedirectStandardError (Join-Path $taskRuntimeDir 'model.err.log')
[pscustomobject]@{pid=$taskProcess.Id;executable=$taskServer.FullName;model=$taskModelPath;port=18081;started=$taskProcess.StartTime.ToString('o')} |
    ConvertTo-Json | Set-Content -LiteralPath $taskRecordPath -Encoding UTF8
$taskDeadline = [DateTime]::UtcNow.AddSeconds(90)
$taskReady = $false
while ([DateTime]::UtcNow -lt $taskDeadline) {
    if ($taskProcess.HasExited) { throw 'Local model exited. Inspect .runtime/model.err.log.' }
    try {
        $taskHandler = [Net.Http.HttpClientHandler]::new()
        $taskHandler.UseProxy = $false
        $taskClient = [Net.Http.HttpClient]::new($taskHandler)
        $taskClient.Timeout = [TimeSpan]::FromSeconds(2)
        try {
            $taskResponse = $taskClient.GetAsync('http://127.0.0.1:18081/health').GetAwaiter().GetResult()
            if ($taskResponse.IsSuccessStatusCode) { $taskReady = $true; break }
        } finally { $taskClient.Dispose() }
    } catch { }
    Start-Sleep -Milliseconds 500
}
if (-not $taskReady) { throw 'Local model readiness timed out. Inspect .runtime/model.err.log.' }
Write-Host 'Qwen3 4B is ready at 127.0.0.1:18081. Model inference stays on this computer.'
