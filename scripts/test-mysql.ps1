#Requires -Version 5.1
param([switch]$WithLocalModel, [switch]$ModelOnly, [switch]$PracticeOnly)
$ErrorActionPreference = 'Stop'
if ($ModelOnly -and -not $WithLocalModel) { throw '-ModelOnly requires -WithLocalModel.' }
$taskProjectRoot = Split-Path -Parent $PSScriptRoot
& (Join-Path $PSScriptRoot 'start-mysql.ps1')
if ($WithLocalModel) { & (Join-Path $PSScriptRoot 'start-model.ps1') }
. (Join-Path $PSScriptRoot 'mysql-path.ps1')
$taskLaunchRoot = Get-ProjectMysqlPath $taskProjectRoot
$taskConfig = Get-Content -LiteralPath (Join-Path $taskProjectRoot '.runtime\mysql.json') -Raw -Encoding UTF8 | ConvertFrom-Json
$taskClient = Join-Path (Split-Path -Parent $taskConfig.executable) 'mysql.exe'
$taskAdminConfig = Join-Path $taskLaunchRoot '.runtime\mysql-admin.ini'
$taskDatabase = 'ai_knowledge_verify_'+[Guid]::NewGuid().ToString('N').Substring(0,12)
$taskPreviousEnv = @{}
foreach ($taskName in @('KB_RUN_MYSQL_TESTS','KB_RUN_LOCAL_AI_TESTS','KB_MYSQL_TEST_URL','KB_MYSQL_TEST_PASSWORD')) {
    $taskPreviousEnv[$taskName] = [Environment]::GetEnvironmentVariable($taskName,'Process')
}
$taskCreated = $false
Push-Location -LiteralPath $taskProjectRoot
try {
    Invoke-ProjectMysql -Executable $taskClient -ClientConfiguration $taskAdminConfig -SqlArguments @('--execute='+("CREATE DATABASE $taskDatabase CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci; GRANT SELECT, INSERT, UPDATE, DELETE, CREATE, ALTER, INDEX, REFERENCES ON $taskDatabase.* TO 'knowledge'@'localhost';"))
    $taskCreated = $true
    $env:KB_RUN_MYSQL_TESTS = 'true'
    $env:KB_RUN_LOCAL_AI_TESTS = $(if ($WithLocalModel) { 'true' } else { 'false' })
    $env:KB_MYSQL_TEST_URL = 'jdbc:mysql://127.0.0.1:13307/'+$taskDatabase+'?useUnicode=true&characterEncoding=UTF-8&connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true'
    $env:KB_MYSQL_TEST_PASSWORD = $taskConfig.password
    $taskTests = if ($PracticeOnly) {
        if ($ModelOnly) { 'MysqlLocalPracticeAcceptanceTest' } elseif ($WithLocalModel) { 'MysqlPracticeFlowTest,MysqlLocalPracticeAcceptanceTest' } else { 'MysqlPracticeFlowTest' }
    } elseif ($ModelOnly) { 'MysqlLocalRagAcceptanceTest,MysqlLocalPracticeAcceptanceTest' }
    elseif ($WithLocalModel) { 'MysqlKnowledgeFlowTest,MysqlDocumentWriteTest,MysqlPracticeFlowTest,MysqlLocalRagAcceptanceTest,MysqlLocalPracticeAcceptanceTest' }
    else { 'MysqlKnowledgeFlowTest,MysqlDocumentWriteTest,MysqlPracticeFlowTest' }
    & mvn.cmd -B -ntp -pl backend test ('-Dtest='+$taskTests)
    if ($LASTEXITCODE -ne 0) { throw 'MySQL acceptance failed. Inspect backend/target/surefire-reports.' }
    Write-Host 'MySQL acceptance passed using a disposable DB; your knowledge-base data was not used.'
} finally {
    Pop-Location
    foreach ($taskName in $taskPreviousEnv.Keys) { [Environment]::SetEnvironmentVariable($taskName,$taskPreviousEnv[$taskName],'Process') }
    if ($taskCreated -and $taskDatabase -match '^ai_knowledge_verify_[a-f0-9]{12}$') {
        # Revalidate the owned server before deleting only the DB created by this invocation.
        & (Join-Path $PSScriptRoot 'start-mysql.ps1')
        Invoke-ProjectMysql -Executable $taskClient -ClientConfiguration $taskAdminConfig -SqlArguments @('--execute='+("REVOKE ALL PRIVILEGES ON $taskDatabase.* FROM 'knowledge'@'localhost'; DROP DATABASE $taskDatabase;"))
    }
}
