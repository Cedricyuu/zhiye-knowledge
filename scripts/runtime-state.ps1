#Requires -Version 5.1
function Get-ProjectHttpState {
    param([string]$Url)
    Add-Type -AssemblyName System.Net.Http
    $taskHandler = [Net.Http.HttpClientHandler]::new()
    $taskHandler.UseProxy = $false
    $taskClient = [Net.Http.HttpClient]::new($taskHandler)
    $taskClient.Timeout = [TimeSpan]::FromSeconds(6)
    try {
        $taskResponse = $taskClient.GetAsync($Url).GetAwaiter().GetResult()
        try {
            $taskBody = $taskResponse.Content.ReadAsStringAsync().GetAwaiter().GetResult() | ConvertFrom-Json
            return [pscustomobject]@{code=[int]$taskResponse.StatusCode;body=$taskBody}
        } finally { $taskResponse.Dispose() }
    } catch { return $null }
    finally { $taskClient.Dispose() }
}

function Get-ProjectRuntimeState {
    param([string]$ProjectRoot)
    $taskRecordPath = Join-Path $ProjectRoot '.runtime\app-process.json'
    $taskExpectedJar = Join-Path $ProjectRoot 'backend\target\knowledge-api-0.1.0.jar'
    $taskAppRunning = $false
    $taskRecord = $null
    if (Test-Path -LiteralPath $taskRecordPath) {
        $taskRecord = Get-Content -LiteralPath $taskRecordPath -Raw -Encoding UTF8 | ConvertFrom-Json
        $taskProcess = Get-CimInstance Win32_Process -Filter ('ProcessId = '+[int]$taskRecord.pid)
        $taskAppRunning = $null -ne $taskProcess -and $taskProcess.CommandLine -and
            [string]::Equals($taskRecord.jar,$taskExpectedJar,[StringComparison]::OrdinalIgnoreCase) -and
            $taskProcess.CommandLine.IndexOf($taskExpectedJar,[StringComparison]::OrdinalIgnoreCase) -ge 0
    }
    $taskPort = if ($taskAppRunning) { [int]$taskRecord.port } elseif ($env:KB_PORT) { [int]$env:KB_PORT } else { 8088 }
    $taskUrl = 'http://127.0.0.1:'+$taskPort
    $taskHealth = if ($taskAppRunning) { Get-ProjectHttpState ($taskUrl+'/api/health') } else { $null }
    $taskAppReady = $null -ne $taskHealth -and $taskHealth.code -eq 200 -and $taskHealth.body.status -eq 'UP'
    $taskDatabaseReady = if ($null -ne $taskHealth) { $taskHealth.body.databaseReady } else { $null }
    $taskLocalRequired = $null -ne $taskRecord -and $taskRecord.mode -eq 'local-ai'
    $taskModelReady = $false
    $taskModelRunning = $false
    $taskModelRecordPath = Join-Path $ProjectRoot '.runtime\model-process.json'
    if (Test-Path -LiteralPath $taskModelRecordPath) {
        $taskModelRecord = Get-Content -LiteralPath $taskModelRecordPath -Raw -Encoding UTF8 | ConvertFrom-Json
        $taskToolsRoot = [IO.Path]::GetFullPath((Join-Path $ProjectRoot '.cache\llama-b11433-vulkan'))+[IO.Path]::DirectorySeparatorChar
        $taskModelExe = [IO.Path]::GetFullPath($taskModelRecord.executable)
        $taskModelProcess = Get-CimInstance Win32_Process -Filter ('ProcessId = '+[int]$taskModelRecord.pid)
        $taskModelRunning = $null -ne $taskModelProcess -and
            $taskModelExe.StartsWith($taskToolsRoot,[StringComparison]::OrdinalIgnoreCase) -and
            [IO.Path]::GetFileName($taskModelExe) -eq 'llama-server.exe' -and
            [string]::Equals($taskModelProcess.ExecutablePath,$taskModelExe,[StringComparison]::OrdinalIgnoreCase)
        if ($taskModelRunning) {
            $taskModelHealth = Get-ProjectHttpState 'http://127.0.0.1:18081/health'
            $taskModelReady = $null -ne $taskModelHealth -and $taskModelHealth.code -eq 200 -and $taskModelHealth.body.status -eq 'ok'
        }
    }
    [pscustomobject]@{
        url=$taskUrl; appRunning=[bool]$taskAppRunning; appReady=[bool]$taskAppReady
        database=$(if($taskHealth){$taskHealth.body.database}else{'unconfirmed'})
        databaseMode=$(if($taskRecord){$taskRecord.database}else{'auto'})
        databaseReady=$taskDatabaseReady; localModelRequired=[bool]$taskLocalRequired
        modelRunning=[bool]$taskModelRunning; modelReady=[bool]$taskModelReady
        aiEnabled=$(if($taskHealth){$taskHealth.body.aiEnabled}else{$null})
        ready=($taskAppReady -and $taskDatabaseReady -eq $true -and (-not $taskLocalRequired -or $taskModelReady))
    }
}
