param()
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

$moduleRoot = Split-Path -Parent $PSScriptRoot
$libraryRoot = Join-Path $moduleRoot 'app\libs'
$apiJar = Join-Path $libraryRoot 'xposed-api-82.jar'
$expectedSha256 = 'F48C635F1C7469FDEC0E00AD2EA0B7A6B2F5B55065784A35B7CA3A84615E8E25'
$downloadUri = 'https://api.xposed.info/de/robv/android/xposed/api/82/api-82.jar'

if (Test-Path -LiteralPath $apiJar) {
    $actual = (Get-FileHash -LiteralPath $apiJar -Algorithm SHA256).Hash
    if ($actual -ne $expectedSha256) { throw 'Existing Xposed API JAR failed SHA-256 verification.' }
    Write-Output 'Xposed API 82 is ready; SHA-256 verified.'
    return
}

New-Item -ItemType Directory -Path $libraryRoot -Force | Out-Null
$temporary = Join-Path $libraryRoot ('xposed-api-' + [Guid]::NewGuid().ToString('N') + '.tmp')
try {
    Invoke-WebRequest -Uri $downloadUri -OutFile $temporary -TimeoutSec 60
    $actual = (Get-FileHash -LiteralPath $temporary -Algorithm SHA256).Hash
    if ($actual -ne $expectedSha256) { throw 'Downloaded Xposed API JAR failed SHA-256 verification.' }
    Move-Item -LiteralPath $temporary -Destination $apiJar
    Write-Output 'Downloaded official Xposed API 82; SHA-256 verified.'
} finally {
    if (Test-Path -LiteralPath $temporary) { Remove-Item -LiteralPath $temporary -Force }
}
