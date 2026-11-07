# 纯 Android SDK 离线构建脚本：aapt2 -> javac -> d8 -> zipalign -> apksigner
# 不依赖 Gradle / 网络。用法： pwsh -File scripts/build.ps1
$ErrorActionPreference = "Continue"

$proj = Split-Path -Parent $PSScriptRoot
if (-not $proj) { $proj = (Get-Location).Path }
Set-Location $proj

$sdk = $env:ANDROID_HOME
if (-not $sdk) { $sdk = $env:ANDROID_SDK_ROOT }
if (-not $sdk) { $sdk = "$env:LOCALAPPDATA\Android\Sdk" }
$buildTools = Join-Path $sdk "build-tools\34.0.0"
$androidJar = Join-Path $sdk "platforms\android-34\android.jar"
if (-not (Test-Path $androidJar)) {
    Write-Error "找不到 android.jar：$androidJar"
    exit 1
}

$aapt2 = Join-Path $buildTools "aapt2.exe"
$d8 = Join-Path $buildTools "d8.bat"
$zipalign = Join-Path $buildTools "zipalign.exe"
$apksigner = Join-Path $buildTools "apksigner.bat"

$src = "app\src\main"
$build = "build\android"
$resCompiled = "$build\res.zip"
$baseApk = "$build\base.apk"
$unsigned = "$build\unsigned.apk"
$aligned = "$build\aligned.apk"
$outApk = "dist\DanmuCompanion-1.35.apk"

$versionName = "1.35"
$versionCode = 12

if (Test-Path $build) { Remove-Item $build -Recurse -Force }
New-Item -ItemType Directory -Force -Path "$build\gen", "$build\classes", "$build\dex", "dist" | Out-Null
if (Test-Path $outApk) { Remove-Item $outApk -Force }

Write-Host "==> 1/6 编译资源 (aapt2)" -ForegroundColor Cyan
& $aapt2 compile --dir "$src\res" -o $resCompiled
if ($LASTEXITCODE -ne 0) { Write-Error "aapt2 compile 失败"; exit 1 }

Write-Host "==> 2/6 链接资源与清单 (aapt2 link)" -ForegroundColor Cyan
& $aapt2 link -o $baseApk -I $androidJar --manifest "$src\AndroidManifest.xml" $resCompiled `
    --java "$build\gen" --min-sdk-version 23 --target-sdk-version 34 `
    --version-code $versionCode --version-name $versionName --auto-add-overlay
if ($LASTEXITCODE -ne 0) { Write-Error "aapt2 link 失败"; exit 1 }

Write-Host "==> 3/6 编译 Java (javac)" -ForegroundColor Cyan
$javaFiles = @()
$javaFiles += (Get-ChildItem "$src\java" -Recurse -Filter *.java | ForEach-Object { $_.FullName })
$javaFiles += (Get-ChildItem "$build\gen" -Recurse -Filter *.java | ForEach-Object { $_.FullName })
& javac -encoding UTF-8 -source 8 -target 8 -Xlint:-options -nowarn `
    -bootclasspath $androidJar -d "$build\classes" $javaFiles
if ($LASTEXITCODE -ne 0) { Write-Error "javac 失败"; exit 1 }

Write-Host "==> 3.5 清理 MethodParameters（规避 d8 的匿名类 bug）" -ForegroundColor Cyan
New-Item -ItemType Directory -Force -Path "$build\tools" | Out-Null
& javac -encoding UTF-8 -d "$build\tools" "tools\StripMethodParameters.java"
if ($LASTEXITCODE -ne 0) { Write-Error "编译 StripMethodParameters 失败"; exit 1 }
& java -cp "$build\tools" StripMethodParameters "$build\classes"
if ($LASTEXITCODE -ne 0) { Write-Error "StripMethodParameters 失败"; exit 1 }

Write-Host "==> 4/6 转 dex (d8)" -ForegroundColor Cyan
# 先把 class 打成 jar，避免命令行过长
& jar cf "$build\classes.jar" -C "$build\classes" .
if ($LASTEXITCODE -ne 0) { Write-Error "打包 classes.jar 失败"; exit 1 }
& $d8 --release --min-api 23 --lib $androidJar --output "$build\dex" "$build\classes.jar"
if ($LASTEXITCODE -ne 0) { Write-Error "d8 失败"; exit 1 }

Write-Host "==> 5/6 打包与对齐" -ForegroundColor Cyan
Copy-Item $baseApk $unsigned -Force
& jar uf $unsigned -C "$build\dex" classes.dex
if ($LASTEXITCODE -ne 0) { Write-Error "写入 classes.dex 失败"; exit 1 }
& $zipalign -p -f 4 $unsigned $aligned
if ($LASTEXITCODE -ne 0) { Write-Error "zipalign 失败"; exit 1 }

Write-Host "==> 6/6 签名 (apksigner)" -ForegroundColor Cyan
$ks = "keystore\danmaku.keystore"
if (-not (Test-Path $ks)) {
    New-Item -ItemType Directory -Force -Path "keystore" | Out-Null
    & keytool -genkeypair -keystore $ks -storepass danmaku123 -keypass danmaku123 `
        -alias danmaku -keyalg RSA -keysize 2048 -validity 36500 `
        -dname "CN=Danmu Companion, O=Local Build, C=CN" | Out-Null
    Write-Host "    已生成本地签名密钥 $ks（口令 danmaku123）" -ForegroundColor Yellow
}
& $apksigner sign --ks $ks --ks-pass pass:danmaku123 --key-pass pass:danmaku123 `
    --ks-key-alias danmaku --v4-signing-enabled false --out $outApk $aligned
if ($LASTEXITCODE -ne 0) { Write-Error "apksigner 失败"; exit 1 }

$certs = & $apksigner verify --print-certs $outApk 2>&1
$certs | ForEach-Object { Write-Host "    $_" }
$size = [math]::Round((Get-Item $outApk).Length / 1KB, 1)
Write-Host "==> 完成：$outApk ($size KB)" -ForegroundColor Green
exit 0
