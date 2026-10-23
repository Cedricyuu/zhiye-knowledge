#Requires -Version 5.1
param([ValidateSet('auto','local-ai','retrieval','external')][string]$Mode='auto',
    [ValidateSet('auto','h2','mysql','external')][string]$Database='auto', [switch]$Background)
$ErrorActionPreference = 'Stop'
$taskProjectRoot = Split-Path -Parent $PSScriptRoot
$taskApplicationJar = Join-Path $taskProjectRoot 'backend\target\knowledge-api-0.1.0.jar'
$taskRuntimeDir = Join-Path $taskProjectRoot '.runtime'
New-Item -ItemType Directory -Path $taskRuntimeDir -Force | Out-Null
$taskPort = if ($env:KB_PORT) { [int]$env:KB_PORT } else { 8088 }
if (@([Net.NetworkInformation.IPGlobalProperties]::GetIPGlobalProperties().GetActiveTcpListeners() | Where-Object { $_.Port -eq $taskPort }).Count) {
    throw ('Port '+$taskPort+' is already in use. Stop this project with scripts/stop.ps1 before starting it again.')
}
if (-not (Test-Path -LiteralPath $taskApplicationJar)) {
    & (Join-Path $PSScriptRoot 'build.ps1')
    if (-not (Test-Path -LiteralPath $taskApplicationJar)) { throw 'Build did not produce the application JAR.' }
}
$taskDatabaseMode = $Database
if ($taskDatabaseMode -eq 'auto') {
    $taskSelectionPath = Join-Path $taskRuntimeDir 'database.json'
    if ($env:SPRING_PROFILES_ACTIVE -match '(^|,)\s*mysql\s*(,|$)') { $taskDatabaseMode = 'external' }
    elseif ((Test-Path -LiteralPath $taskSelectionPath) -and (Get-Content -LiteralPath $taskSelectionPath -Raw -Encoding UTF8 | ConvertFrom-Json).type -eq 'mysql') { $taskDatabaseMode = 'mysql' }
    else { $taskDatabaseMode = 'h2' }
}
if ($taskDatabaseMode -eq 'mysql') {
    & (Join-Path $PSScriptRoot 'start-mysql.ps1')
    $taskDatabaseConfig = Get-Content -LiteralPath (Join-Path $taskRuntimeDir 'mysql.json') -Raw -Encoding UTF8 | ConvertFrom-Json
}
$taskUseLocalModel = $Mode -eq 'local-ai' -or ($Mode -eq 'auto' -and
    (Test-Path -LiteralPath (Join-Path $taskProjectRoot '.cache\models\Qwen3-4B-Q4_K_M.gguf')))
if ($taskUseLocalModel) { & (Join-Path $PSScriptRoot 'start-model.ps1') }
$taskPreviousEnv = @{}
foreach ($taskName in @('KB_AI_ENABLED','KB_AI_BASE_URL','KB_AI_MODEL','KB_AI_API_KEY','DJL_CACHE_DIR',
    'SPRING_PROFILES_ACTIVE','KB_DB_URL','KB_DB_USERNAME','KB_DB_PASSWORD')) {
    $taskPreviousEnv[$taskName] = [Environment]::GetEnvironmentVariable($taskName,'Process')
}
$env:DJL_CACHE_DIR = Join-Path $taskProjectRoot '.cache\djl'
if ($taskUseLocalModel) {
    $env:KB_AI_ENABLED = 'true'
    $env:KB_AI_BASE_URL = 'http://127.0.0.1:18081/v1'
    $env:KB_AI_MODEL = 'qwen3-4b-local'
    $env:KB_AI_API_KEY = [IO.File]::ReadAllText((Join-Path $taskRuntimeDir 'model-api-key')).Trim()
} elseif ($Mode -eq 'retrieval') { $env:KB_AI_ENABLED = 'false' }
if ($taskDatabaseMode -eq 'mysql') {
    $env:SPRING_PROFILES_ACTIVE = 'mysql'
    $env:KB_DB_URL = 'jdbc:mysql://127.0.0.1:13307/ai_knowledge?useUnicode=true&characterEncoding=UTF-8&connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true'
    $env:KB_DB_USERNAME = $taskDatabaseConfig.username
    $env:KB_DB_PASSWORD = $taskDatabaseConfig.password
} elseif ($taskDatabaseMode -eq 'h2') { $env:SPRING_PROFILES_ACTIVE = 'h2' }
Push-Location -LiteralPath $taskProjectRoot
try {
    if ($Background) {
        $taskJavaArguments = @('-Dfile.encoding=UTF-8','-Xmx512m','-jar',('"'+$taskApplicationJar+'"'))
        $taskJavaProcess = Start-Process -FilePath (Get-Command java.exe).Source -ArgumentList $taskJavaArguments -WorkingDirectory $taskProjectRoot -WindowStyle Hidden -PassThru `
            -RedirectStandardOutput (Join-Path $taskRuntimeDir 'app.out.log') -RedirectStandardError (Join-Path $taskRuntimeDir 'app.err.log')
        [pscustomobject]@{pid=$taskJavaProcess.Id;jar=$taskApplicationJar;mode=$(if($taskUseLocalModel){'local-ai'}elseif($Mode -eq 'external'){'external'}else{'retrieval'});database=$taskDatabaseMode;port=$taskPort;started=$taskJavaProcess.StartTime.ToString('o')} |
            ConvertTo-Json | Set-Content -LiteralPath (Join-Path $taskRuntimeDir 'app-process.json') -Encoding UTF8
        Add-Type -AssemblyName System.Net.Http
        $taskClientHandler = [Net.Http.HttpClientHandler]::new()
        $taskClientHandler.UseProxy = $false
        $taskClient = [Net.Http.HttpClient]::new($taskClientHandler)
        $taskClient.Timeout = [TimeSpan]::FromSeconds(2)
        $taskReady = $false
        try {
            $taskDeadline = [DateTime]::UtcNow.AddSeconds(50)
            while ([DateTime]::UtcNow -lt $taskDeadline) {
                if ($taskJavaProcess.HasExited) { throw 'Application exited. Inspect .runtime/app.err.log and app.out.log.' }
                try {
                    $taskResponse = $taskClient.GetAsync(('http://127.0.0.1:'+ $taskPort +'/api/health')).GetAwaiter().GetResult()
                    if ($taskResponse.IsSuccessStatusCode) { $taskReady=$true; break }
                } catch { }
                Start-Sleep -Milliseconds 500
            }
        } finally { $taskClient.Dispose() }
        if (-not $taskReady) { throw 'Application readiness timed out. Inspect .runtime/app.out.log.' }
        Write-Host ('Application is ready at http://127.0.0.1:'+$taskPort+'. Use scripts/stop.ps1 to stop it.')
        return
    }
    Write-Host 'Open http://127.0.0.1:8088 (or your configured KB_PORT). Press Ctrl+C to stop.'
    & java '-Dfile.encoding=UTF-8' '-Xmx512m' -jar $taskApplicationJar
    if ($LASTEXITCODE -ne 0) { throw 'Application exited with an error; see the output above.' }
} finally {
    Pop-Location
    foreach ($taskName in $taskPreviousEnv.Keys) { [Environment]::SetEnvironmentVariable($taskName,$taskPreviousEnv[$taskName],'Process') }
}
