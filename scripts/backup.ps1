#Requires -Version 5.1
$ErrorActionPreference = 'Stop'
$taskProjectRoot = Split-Path -Parent $PSScriptRoot
function Get-BackupSha256 {
    param([string]$Path)
    $taskInput = [IO.File]::OpenRead($Path)
    $taskHasher = [Security.Cryptography.SHA256]::Create()
    try { return [BitConverter]::ToString($taskHasher.ComputeHash($taskInput)).Replace('-','').ToLowerInvariant() }
    finally { $taskHasher.Dispose(); $taskInput.Dispose() }
}
$taskSelectionPath = Join-Path $taskProjectRoot '.runtime\database.json'
if (-not (Test-Path -LiteralPath $taskSelectionPath) -or
    (Get-Content -LiteralPath $taskSelectionPath -Raw -Encoding UTF8 | ConvertFrom-Json).type -ne 'mysql') {
    throw 'This backup entry requires the project MySQL selected by scripts/use-mysql.ps1.'
}
$taskAppRecordPath = Join-Path $taskProjectRoot '.runtime\app-process.json'
if (Test-Path -LiteralPath $taskAppRecordPath) {
    $taskAppRecord = Get-Content -LiteralPath $taskAppRecordPath -Raw -Encoding UTF8 | ConvertFrom-Json
    if ($taskAppRecord.database -ne 'mysql' -and (Get-Process -Id ([int]$taskAppRecord.pid) -ErrorAction SilentlyContinue)) {
        throw 'The current application uses another database. This entry only backs up the project MySQL.'
    }
}
& (Join-Path $PSScriptRoot 'start-mysql.ps1')
. (Join-Path $PSScriptRoot 'mysql-path.ps1')
$taskLaunchRoot = Get-ProjectMysqlPath $taskProjectRoot
$taskConfig = Get-Content -LiteralPath (Join-Path $taskProjectRoot '.runtime\mysql.json') -Raw -Encoding UTF8 | ConvertFrom-Json
$taskBin = Split-Path -Parent $taskConfig.executable
$taskClient = Join-Path $taskBin 'mysql.exe'
$taskDump = Join-Path $taskBin 'mysqldump.exe'
if (-not (Test-Path -LiteralPath $taskDump)) { throw 'mysqldump.exe is missing from the recorded MySQL installation.' }
$taskAdminConfig = Join-Path $taskLaunchRoot '.runtime\mysql-admin.ini'
$taskClientConfig = Join-Path $taskLaunchRoot '.runtime\mysql-client.ini'
$taskBackupName = 'mysql-'+(Get-Date -Format 'yyyyMMdd-HHmmss')+'-'+[Guid]::NewGuid().ToString('N').Substring(0,6)
$taskBackupDir = Join-Path $taskProjectRoot ('backups\'+$taskBackupName)
$taskDumpPath = Join-Path $taskLaunchRoot ('backups\'+$taskBackupName+'\knowledge.sql')
$taskCheckPath = Join-Path $taskLaunchRoot ('backups\'+$taskBackupName+'\restore-check.sql')
$taskManifestPath = Join-Path $taskBackupDir 'manifest.json'
$taskRestoreDatabase = 'ai_knowledge_restore_'+[Guid]::NewGuid().ToString('N').Substring(0,12)
$taskTables = @('kb_user','kb_session','kb_document','kb_chunk','kb_answer','kb_embedding')
# Older installations remain backup-compatible before the additive practice migration is applied.
$taskPracticeTable = Invoke-ProjectMysql -Executable $taskClient -ClientConfiguration $taskClientConfig -SqlArguments @(
    '--batch','--skip-column-names','--database=ai_knowledge',"--execute=SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='ai_knowledge' AND table_name='kb_exercise'")
if ([int]$taskPracticeTable -eq 1) { $taskTables += 'kb_exercise' }
# --result-file avoids PowerShell 5.1's UTF-16 output redirection; the snapshot includes InnoDB data only.
$taskDumpOptions = @('--single-transaction','--quick','--no-tablespaces',
    '--set-gtid-purged=OFF','--skip-comments','--order-by-primary','--hex-blob','--skip-triggers','--skip-lock-tables')
New-Item -ItemType Directory -Path $taskBackupDir | Out-Null
$taskManifest = [ordered]@{formatVersion=1;createdAt=[DateTimeOffset]::Now.ToString('o');database='ai_knowledge';
    mysqlVersion=$taskConfig.version;file='knowledge.sql';restoreVerified=$false}
$taskManifest | ConvertTo-Json | Set-Content -LiteralPath $taskManifestPath -Encoding UTF8
$taskCreated = $false
$taskVerified = $false
try {
    Invoke-ProjectMysql -Executable $taskDump -ClientConfiguration $taskClientConfig -SqlArguments (
        $taskDumpOptions+@(('--result-file='+$taskDumpPath),'ai_knowledge')+$taskTables)
    if ((Get-Item -LiteralPath $taskDumpPath).Length -eq 0) { throw 'The SQL backup is empty.' }
    Invoke-ProjectMysql -Executable $taskClient -ClientConfiguration $taskAdminConfig -SqlArguments @(
        '--connect-timeout=5',("--execute=CREATE DATABASE $taskRestoreDatabase CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;"))
    $taskCreated = $true
    Invoke-ProjectMysql -Executable $taskClient -ClientConfiguration $taskAdminConfig -SqlArguments @(
        '--connect-timeout=5',('--database='+$taskRestoreDatabase),('--execute=source '+$taskDumpPath.Replace('\','/')))
    Invoke-ProjectMysql -Executable $taskDump -ClientConfiguration $taskAdminConfig -SqlArguments (
        $taskDumpOptions+@(('--result-file='+$taskCheckPath),$taskRestoreDatabase)+$taskTables)
    $taskBackupHash = Get-BackupSha256 $taskDumpPath
    if ($taskBackupHash -ne (Get-BackupSha256 $taskCheckPath)) {
        throw 'Restored content does not match the backup. The files were retained for diagnosis.'
    }
    $taskCounts = [ordered]@{}
    foreach ($taskTable in $taskTables) {
        $taskCount = Invoke-ProjectMysql -Executable $taskClient -ClientConfiguration $taskAdminConfig -SqlArguments @(
            '--connect-timeout=5','--batch','--skip-column-names',('--database='+$taskRestoreDatabase),('--execute=SELECT COUNT(*) FROM '+$taskTable))
        $taskCounts[$taskTable] = [long]$taskCount
    }
    $taskManifest['sha256'] = $taskBackupHash.ToLowerInvariant()
    $taskManifest['bytes'] = (Get-Item -LiteralPath $taskDumpPath).Length
    $taskManifest['rows'] = $taskCounts
    $taskVerified = $true
} finally {
    if ($taskCreated -and $taskRestoreDatabase -match '^ai_knowledge_restore_[a-f0-9]{12}$') {
        # Revalidate the owned server, then remove only the temporary DB made by this invocation.
        & (Join-Path $PSScriptRoot 'start-mysql.ps1')
        Invoke-ProjectMysql -Executable $taskClient -ClientConfiguration $taskAdminConfig -SqlArguments @(
            '--connect-timeout=5',("--execute=DROP DATABASE $taskRestoreDatabase;"))
    }
}
if ($taskVerified) {
    Remove-Item -LiteralPath $taskCheckPath -Force
    $taskManifest['restoreVerified'] = $true
    $taskManifest['verifiedAt'] = [DateTimeOffset]::Now.ToString('o')
    $taskManifest | ConvertTo-Json -Depth 4 | Set-Content -LiteralPath $taskManifestPath -Encoding UTF8
    Write-Host ('Backup restored and SHA-256 verified: '+$taskBackupDir)
}
