#Requires -Version 5.1
param([string]$DownloadProxy = '')
$ErrorActionPreference = 'Stop'
$taskProjectRoot = Split-Path -Parent $PSScriptRoot
$taskModelDir = Join-Path $taskProjectRoot '.cache\models'
$taskDownloads = Join-Path $taskProjectRoot '.cache\downloads'
$taskRuntime = Join-Path $taskProjectRoot '.cache\llama-b11433-vulkan'
New-Item -ItemType Directory -Path $taskModelDir,$taskDownloads,$taskRuntime -Force | Out-Null

function Receive-ProjectFile([string]$Url, [string]$Destination, [string]$ExpectedHash, [switch]$Direct) {
    if (Test-Path -LiteralPath $Destination) {
        if (-not $ExpectedHash -or (Get-FileHash -LiteralPath $Destination -Algorithm SHA256).Hash -eq $ExpectedHash) { return }
        throw ('Existing file checksum differs; inspect it before retrying: ' + $Destination)
    }
    $taskPartial = $Destination + '.partial'
    $taskCurl = @('--fail','--location','--show-error','--connect-timeout','20','--speed-limit','1024',
        '--speed-time','60','--max-time','1800','--continue-at','-','--output',$taskPartial)
    if ($Direct) { $taskCurl += @('--noproxy','*') }
    elseif ($DownloadProxy) { $taskCurl += @('--proxy',$DownloadProxy) }
    else {
        $taskSystemProxy = [Net.WebRequest]::GetSystemWebProxy().GetProxy([uri]$Url)
        if ($taskSystemProxy.Host -ne ([uri]$Url).Host) { $taskCurl += @('--proxy',$taskSystemProxy.GetLeftPart([UriPartial]::Authority)) }
    }
    & curl.exe @taskCurl $Url
    if ($LASTEXITCODE -ne 0) { throw ('Download failed. Partial file retained for resume: ' + $Destination) }
    if ($ExpectedHash -and (Get-FileHash -LiteralPath $taskPartial -Algorithm SHA256).Hash -ne $ExpectedHash) {
        throw ('SHA256 verification failed: ' + $taskPartial)
    }
    Move-Item -LiteralPath $taskPartial -Destination $Destination
}

$taskModelHash = '7485fe6f11af29433bc51cab58009521f205840f5b4ae3a32fa7f92e8534fdf5'
$taskModelPath = Join-Path $taskModelDir 'Qwen3-4B-Q4_K_M.gguf'
Write-Host 'Downloading/verifying Qwen3 4B Q4_K_M (about 2.5 GB, official Qwen repository).'
Receive-ProjectFile -Url 'https://modelscope.cn/models/Qwen/Qwen3-4B-GGUF/resolve/master/Qwen3-4B-Q4_K_M.gguf' -Destination $taskModelPath -ExpectedHash $taskModelHash -Direct
$taskZip = Join-Path $taskDownloads 'llama-b11433-bin-win-vulkan-x64.zip'
Receive-ProjectFile -Url 'https://github.com/ggml-org/llama.cpp/releases/download/b11433/llama-b11433-bin-win-vulkan-x64.zip' -Destination $taskZip -ExpectedHash '021001e2b7a60aab1d23b301edb2f076e8a5136d8d29686d0d95341342088714'
Expand-Archive -LiteralPath $taskZip -DestinationPath $taskRuntime -Force
$taskServer = Get-ChildItem -LiteralPath $taskRuntime -Recurse -File -Filter 'llama-server.exe' | Select-Object -First 1
if (-not $taskServer) { throw 'Official runtime archive did not contain llama-server.exe.' }
Write-Host 'Local AI files are ready. Start with .\scripts\start.ps1 -Mode local-ai'
