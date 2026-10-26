param([string]$SdkPath = "$env:LOCALAPPDATA\Android\Sdk", [string]$BuildTools = '35.0.0', [string]$Platform = 'android-35')
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
$projectRoot = $PSScriptRoot
$buildRoot = Join-Path $projectRoot 'build'
$distRoot = Join-Path $projectRoot 'dist'
$toolsRoot = Join-Path $SdkPath ('build-tools\' + $BuildTools)
$sdkAndroidJar = Join-Path $SdkPath ('platforms\' + $Platform + '\android.jar')
$androidJar = Join-Path $buildRoot 'android.jar'
$apiJar = Join-Path $projectRoot 'app\libs\xposed-api-82.jar'
if (-not (Test-Path -LiteralPath $apiJar)) {
    throw 'Missing Xposed API. Run scripts/prepare-dependencies.ps1 first.'
}
if ((Get-FileHash -LiteralPath $apiJar -Algorithm SHA256).Hash -ne 'F48C635F1C7469FDEC0E00AD2EA0B7A6B2F5B55065784A35B7CA3A84615E8E25') {
    throw 'Xposed API SHA-256 mismatch. Verify the compile-only dependency.'
}
foreach ($requiredFile in @($sdkAndroidJar, $apiJar, (Join-Path $toolsRoot 'aapt2.exe'))) {
    if (-not (Test-Path -LiteralPath $requiredFile)) { throw "Missing build dependency: $requiredFile" }
}
function Invoke-Checked([string]$Program, [string[]]$Arguments) {
    & $Program @Arguments
    if ($LASTEXITCODE -ne 0) { throw "$Program failed: $LASTEXITCODE" }
}
$classesRoot = Join-Path $buildRoot 'classes'
$dexRoot = Join-Path $buildRoot 'dex'
$testsRoot = Join-Path $buildRoot 'tests'
foreach ($generatedPath in @($classesRoot, $dexRoot, $testsRoot)) {
    $absoluteGenerated = [IO.Path]::GetFullPath($generatedPath)
    $absoluteBuild = [IO.Path]::GetFullPath($buildRoot).TrimEnd('\') + '\'
    if (-not $absoluteGenerated.StartsWith($absoluteBuild, [StringComparison]::OrdinalIgnoreCase)) { throw 'Generated path escaped build directory' }
    if (Test-Path -LiteralPath $absoluteGenerated) { Remove-Item -LiteralPath $absoluteGenerated -Recurse -Force }
}
New-Item -ItemType Directory -Path $buildRoot,$distRoot,$classesRoot,$dexRoot,$testsRoot -Force | Out-Null
Copy-Item -LiteralPath $sdkAndroidJar -Destination $androidJar -Force
$sourceRoot = Join-Path $projectRoot 'app\src\main'
$sources = @(Get-ChildItem -LiteralPath (Join-Path $sourceRoot 'java') -Filter '*.java' -Recurse | ForEach-Object FullName)
Invoke-Checked 'javac' (@('-encoding','UTF-8','-source','8','-target','8','-classpath',"$androidJar;$apiJar",'-d',$classesRoot) + $sources)
$testSources = @(Get-ChildItem -LiteralPath (Join-Path $projectRoot 'tests') -Filter '*.java' -Recurse | ForEach-Object FullName)
$testClassPath = "$testsRoot;$classesRoot;$androidJar;$apiJar"
Invoke-Checked 'javac' (@('-encoding','UTF-8','-source','8','-target','8','-classpath',$testClassPath,'-d',$testsRoot) + $testSources)
Invoke-Checked 'java' @('-cp',$testClassPath,'StepPlanTest')
Invoke-Checked 'java' @('-cp',$testClassPath,'dev.local.stepcontroller.WechatFeatureTest')
$classJar = Join-Path $buildRoot 'module-classes.jar'
Invoke-Checked 'jar' @('cf',$classJar,'-C',$classesRoot,'.')
Invoke-Checked (Join-Path $toolsRoot 'd8.bat') @('--min-api','26','--lib',$androidJar,'--classpath',$apiJar,'--output',$dexRoot,$classJar)
$compiledResources = Join-Path $buildRoot 'resources.zip'
Invoke-Checked (Join-Path $toolsRoot 'aapt2.exe') @('compile','--dir',(Join-Path $sourceRoot 'res'),'-o',$compiledResources)
$unsignedApk = Join-Path $buildRoot 'unsigned.apk'
Invoke-Checked (Join-Path $toolsRoot 'aapt2.exe') @('link','-I',$androidJar,'--manifest',(Join-Path $sourceRoot 'AndroidManifest.xml'),'--min-sdk-version','26','--target-sdk-version','35','-A',(Join-Path $sourceRoot 'assets'),'-o',$unsignedApk,$compiledResources)
Add-Type -AssemblyName System.IO.Compression.FileSystem
$archive = [System.IO.Compression.ZipFile]::Open($unsignedApk,[System.IO.Compression.ZipArchiveMode]::Update)
try {
    foreach ($dex in Get-ChildItem -LiteralPath $dexRoot -Filter '*.dex') {
        [System.IO.Compression.ZipFileExtensions]::CreateEntryFromFile($archive,$dex.FullName,$dex.Name,[System.IO.Compression.CompressionLevel]::Optimal) | Out-Null
    }
} finally { $archive.Dispose() }
$alignedApk = Join-Path $buildRoot 'aligned.apk'
Invoke-Checked (Join-Path $toolsRoot 'zipalign.exe') @('-p','-f','4',$unsignedApk,$alignedApk)
$keystore = Join-Path $buildRoot 'debug.keystore'
if (-not (Test-Path -LiteralPath $keystore)) {
    Invoke-Checked 'keytool' @('-genkeypair','-keystore',$keystore,'-storepass','android','-keypass','android','-alias','step-controller','-dname','CN=Local Step Controller','-keyalg','RSA','-keysize','2048','-validity','10000','-noprompt')
}
$manifestXml = [xml](Get-Content -LiteralPath (Join-Path $sourceRoot 'AndroidManifest.xml') -Raw)
$versionName = $manifestXml.manifest.GetAttribute('versionName','http://schemas.android.com/apk/res/android')
if ($versionName -notmatch '^\d+\.\d+\.\d+$') { throw 'Unexpected module versionName' }
$finalApk = Join-Path $distRoot ("step-controller-$versionName.apk")
Invoke-Checked (Join-Path $toolsRoot 'apksigner.bat') @('sign','--ks',$keystore,'--ks-pass','pass:android','--key-pass','pass:android','--out',$finalApk,$alignedApk)
Invoke-Checked (Join-Path $toolsRoot 'apksigner.bat') @('verify','--verbose','--print-certs',$finalApk)
Invoke-Checked (Join-Path $toolsRoot 'zipalign.exe') @('-c','-v','4',$finalApk)
Get-FileHash -LiteralPath $finalApk -Algorithm SHA256
Write-Output "Built: $finalApk"
