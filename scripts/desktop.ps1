#Requires -Version 5.1
param([Parameter(Mandatory=$true)][ValidateSet('start','stop','status','backup')][string]$Action,
    [switch]$NoBrowser, [switch]$NoPause)
$ErrorActionPreference = 'Stop'
[Console]::OutputEncoding = [Text.UTF8Encoding]::new($false)
$taskProjectRoot = Split-Path -Parent $PSScriptRoot
. (Join-Path $PSScriptRoot 'runtime-state.ps1')
$taskExitCode = 0
$taskLock = $null
try {
    if ($Action -ne 'status') {
        $taskRuntimeDir = Join-Path $taskProjectRoot '.runtime'
        New-Item -ItemType Directory -Path $taskRuntimeDir -Force | Out-Null
        try { $taskLock = [IO.File]::Open((Join-Path $taskRuntimeDir 'desktop.lock'),[IO.FileMode]::OpenOrCreate,[IO.FileAccess]::ReadWrite,[IO.FileShare]::None) }
        catch [IO.IOException] { throw '已有启动、停止或备份操作正在进行，请等该窗口完成后再试。' }
    }
    switch ($Action) {
        'start' {
            Write-Host '正在启动知页，请稍候……'
            $taskState = Get-ProjectRuntimeState $taskProjectRoot
            if ($taskState.appRunning) {
                if ($taskState.databaseMode -eq 'mysql' -and $taskState.databaseReady -ne $true) {
                    & (Join-Path $PSScriptRoot 'start-mysql.ps1')
                }
                if ($taskState.localModelRequired -and -not $taskState.modelReady) {
                    & (Join-Path $PSScriptRoot 'start-model.ps1')
                }
            } else { & (Join-Path $PSScriptRoot 'start.ps1') -Background }
            $taskState = Get-ProjectRuntimeState $taskProjectRoot
            if (-not $taskState.ready) { throw '服务尚未就绪。请运行“检查知页”，并查看 .runtime 中的日志。' }
            Write-Host ('知页已就绪：'+$taskState.url)
            if (-not $NoBrowser) { Start-Process $taskState.url }
        }
        'stop' {
            & (Join-Path $PSScriptRoot 'stop.ps1')
            Write-Host '知页后台服务已停止。下次双击“启动知页”即可继续使用。'
        }
        'status' {
            $taskState = Get-ProjectRuntimeState $taskProjectRoot
            Write-Host ('页面地址：'+$taskState.url)
            Write-Host ('应用：'+$(if($taskState.appReady){'正常'}elseif($taskState.appRunning){'进程运行中，服务未就绪'}else{'未启动'}))
            Write-Host ('数据库：'+$(if($taskState.databaseReady -eq $true){$taskState.database+' 连接正常'}elseif($taskState.databaseReady -eq $false){'连接失败'}else{'未能验证，启动应用后再检查'}))
            Write-Host ('本地模型：'+$(if($taskState.modelReady){'Qwen 已就绪'}elseif($taskState.modelRunning){'进程运行中，服务未就绪'}else{'未启动'}))
            if ($taskState.aiEnabled -and -not $taskState.localModelRequired) { Write-Host '当前使用另外配置的模型服务，此处未验证其生成能力。' }
            if ($taskState.ready) { Write-Host '可以使用。' } else { Write-Host '需要启动或恢复服务。'; $taskExitCode = 1 }
        }
        'backup' {
            & (Join-Path $PSScriptRoot 'backup.ps1')
            Write-Host '备份已完成，恢复校验通过。文件位于项目 backups 文件夹。'
        }
    }
} catch {
    $taskExitCode = 1
    $taskMessage = if ($_.FullyQualifiedErrorId -match 'ConvertFromJson|GetContent') {
        '项目配置无法读取，请检查 .runtime 中的对应文件。为保护配置内容，此处不显示原文。'
    } else { $_.Exception.Message }
    Write-Host ('操作未完成：'+$taskMessage) -ForegroundColor Red
    Write-Host '日志位置：项目目录下的 .runtime；请保留 data 和 .runtime 中的数据与配置。'
} finally { if ($taskLock) { $taskLock.Dispose() } }
if (-not $NoPause) { [void](Read-Host '按 Enter 关闭此窗口') }
exit $taskExitCode
