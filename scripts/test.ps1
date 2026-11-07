# 跑核心层单元测试、网络端到端测试，并渲染弹幕预览图
$ErrorActionPreference = "Continue"
$proj = Split-Path -Parent $PSScriptRoot
Set-Location $proj

$sdk = $env:ANDROID_HOME
if (-not $sdk) { $sdk = $env:ANDROID_SDK_ROOT }
if (-not $sdk) { $sdk = "$env:LOCALAPPDATA\Android\Sdk" }
$apiXml = Join-Path $sdk "platforms\android-34\data\api-versions.xml"

$core = "build\jvm-core"
$tests = "build\jvm-tests"
New-Item -ItemType Directory -Force -Path $core, $tests | Out-Null

Write-Host "==> 编译核心层" -ForegroundColor Cyan
$coreFiles = (Get-ChildItem "app\src\main\java\com\baomihua\danmaku\core" -Filter *.java |
    ForEach-Object { $_.FullName })
& javac -encoding UTF-8 -source 8 -target 8 -Xlint:-options -nowarn -d $core $coreFiles
if ($LASTEXITCODE -ne 0) { Write-Error "核心层编译失败"; exit 1 }

Write-Host "==> 编译测试" -ForegroundColor Cyan
& javac -encoding UTF-8 -source 8 -target 8 -Xlint:-options -nowarn -cp $core -d $tests `
    "tools\CoreTests.java" "tools\HttpEndToEndTest.java" "tools\PreviewRender.java"
if ($LASTEXITCODE -ne 0) { Write-Error "测试编译失败"; exit 1 }

Write-Host "==> 单元测试" -ForegroundColor Cyan
& java "-Dfile.encoding=UTF-8" "-Dstdout.encoding=UTF-8" -cp "$core;$tests" CoreTests
if ($LASTEXITCODE -ne 0) { Write-Error "单元测试失败"; exit 1 }

Write-Host "==> 网络端到端测试" -ForegroundColor Cyan
& java "-Dfile.encoding=UTF-8" "-Dstdout.encoding=UTF-8" -cp "$core;$tests" HttpEndToEndTest
if ($LASTEXITCODE -ne 0) { Write-Error "端到端测试失败"; exit 1 }

Write-Host "==> 渲染弹幕预览图" -ForegroundColor Cyan
& java "-Dfile.encoding=UTF-8" "-Dstdout.encoding=UTF-8" "-Djava.awt.headless=true" `
    -cp "$core;$tests" PreviewRender "docs\preview-danmaku.png"
if ($LASTEXITCODE -ne 0) { Write-Error "渲染预览失败"; exit 1 }

if (Test-Path "dist\DanmuCompanion-1.3.5.apk") {
    Write-Host "==> 检查 APK 的 API 版本兼容性" -ForegroundColor Cyan
    New-Item -ItemType Directory -Force -Path "build\tools" | Out-Null
    & javac -encoding UTF-8 -d "build\tools" "tools\ApiLevelCheck.java"
    if ($LASTEXITCODE -eq 0 -and (Test-Path $apiXml)) {
        & java "-Dfile.encoding=UTF-8" "-Dstdout.encoding=UTF-8" -Xmx1g -cp "build\tools" `
            ApiLevelCheck "dist\DanmuCompanion-1.3.5.apk" $apiXml 23
    }
}

Write-Host ""
Write-Host "==> 全部完成" -ForegroundColor Green
exit 0
